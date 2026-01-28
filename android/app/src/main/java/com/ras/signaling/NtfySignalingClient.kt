package com.ras.signaling

import android.util.Log
import com.ras.crypto.KeyDerivation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Data class for ntfy WebSocket messages.
 */
data class NtfyMessage(
    val event: String,
    val message: String = ""
)

/**
 * Interface for ntfy client operations.
 *
 * CONTRACT: All suspend functions MUST be main-safe (callable from any dispatcher).
 * Implementations must handle their own dispatcher switching for blocking I/O.
 */
interface NtfyClientInterface {
    /**
     * Subscribe to a topic and receive an NtfySubscription.
     *
     * The subscription provides:
     * - awaitReady() to wait for WebSocket connection
     * - messages flow to receive messages
     * - close() to clean up
     *
     * Main-safe: can be called from any dispatcher.
     */
    fun subscribe(topic: String): NtfySubscription

    /**
     * Unsubscribe from the current topic.
     */
    fun unsubscribe()

    /**
     * Publish a message to a topic.
     * Main-safe: can be called from any dispatcher.
     */
    suspend fun publish(topic: String, message: String)

    /**
     * Publish a message with retry on failure.
     *
     * @param topic The topic to publish to
     * @param message The message content
     * @param maxRetries Maximum number of retry attempts
     * @param baseDelayMs Base delay for exponential backoff
     * @throws PublishFailedException if all retries fail
     */
    suspend fun publishWithRetry(
        topic: String,
        message: String,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        baseDelayMs: Long = DEFAULT_RETRY_DELAY_MS
    )

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_RETRY_DELAY_MS = 1000L
    }
}

/**
 * Exception thrown when publish fails after all retries.
 */
class PublishFailedException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Client for ntfy signaling relay.
 *
 * All suspend functions are main-safe - they can be called from any dispatcher.
 * Blocking I/O is internally dispatched to the IO dispatcher.
 *
 * Provides:
 * - Topic computation from master secret
 * - WebSocket subscription for receiving messages
 * - HTTP POST for publishing messages
 *
 * Usage:
 * ```kotlin
 * val client = NtfySignalingClient()
 * val topic = NtfySignalingClient.computeTopic(masterSecret)
 *
 * // Subscribe to receive messages
 * client.subscribe(topic).collect { message ->
 *     // Process message
 * }
 *
 * // Publish a message (safe to call from main thread)
 * client.publish(topic, encryptedMessage)
 * ```
 */
class NtfySignalingClient(
    private val server: String = DEFAULT_SERVER,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS
) : NtfyClientInterface {

    companion object {
        private const val TAG = "NtfySignalingClient"
        const val DEFAULT_SERVER = "https://ntfy.sh"
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 10000L

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)  // No read timeout for WebSocket
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)  // Keep WebSocket alive through cellular NAT
            .build()

        /**
         * Compute ntfy topic from master secret.
         *
         * @param masterSecret 32-byte master secret from QR code
         * @return Topic string: "ras-" + first 12 hex chars of SHA256(masterSecret)
         */
        fun computeTopic(masterSecret: ByteArray): String {
            return KeyDerivation.deriveNtfyTopic(masterSecret)
        }

        /**
         * Build WebSocket URL for a topic.
         *
         * Includes ?since=30s to retrieve recent messages. This is needed because
         * the WebSocket may not connect before messages are published (cold Flow).
         * The daemon's answer could arrive before we're subscribed, so we need to
         * fetch recent messages to catch it.
         */
        fun buildWsUrl(topic: String, server: String = DEFAULT_SERVER): String {
            val wsServer = server
                .replace("https://", "wss://")
                .replace("http://", "ws://")
            return "$wsServer/$topic/ws?since=30s"
        }

        /**
         * Build HTTP publish URL for a topic.
         */
        fun buildPublishUrl(topic: String, server: String = DEFAULT_SERVER): String {
            return "$server/$topic"
        }

        /**
         * Parse an ntfy WebSocket message.
         *
         * @param text JSON string from WebSocket
         * @return NtfyMessage if valid, null otherwise
         */
        fun parseNtfyMessage(text: String): NtfyMessage? {
            if (text.isEmpty()) return null

            return try {
                val json = JSONObject(text)
                val event = json.optString("event", "")
                val message = json.optString("message", "")
                NtfyMessage(event = event, message = message)
            } catch (e: Exception) {
                null
            }
        }
    }

    private var currentWebSocket: WebSocket? = null
    private var currentSubscription: NtfySubscription? = null

    /**
     * Subscribe to a topic and receive an NtfySubscription.
     *
     * The subscription provides:
     * - awaitReady() to wait for WebSocket connection
     * - messages flow to receive messages
     * - close() to clean up
     *
     * Features:
     * - Automatic reconnection on failure (up to maxReconnectAttempts)
     * - Exponential backoff between retries
     * - Connection state logging
     */
    override fun subscribe(topic: String): NtfySubscription {
        val readyDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

        val messagesFlow = createWebSocketFlow(topic, readyDeferred)
            .retryWhen { cause, attempt ->
                if (attempt < maxReconnectAttempts && cause is IOException) {
                    val backoffMs = minOf(INITIAL_BACKOFF_MS * (1 shl attempt.toInt()), MAX_BACKOFF_MS)
                    Log.w(TAG, "WebSocket connection failed, retrying in ${backoffMs}ms (attempt ${attempt + 1}/$maxReconnectAttempts)")
                    delay(backoffMs)
                    true
                } else {
                    Log.e(TAG, "WebSocket connection failed after $attempt attempts, giving up", cause)
                    false
                }
            }

        val subscription = NtfySubscription(
            messages = messagesFlow,
            readyDeferred = readyDeferred,
            onClose = { unsubscribe() }
        )

        currentSubscription = subscription
        return subscription
    }

    /**
     * Create the underlying WebSocket flow.
     *
     * @param topic The topic to subscribe to
     * @param readyDeferred Deferred to complete when WebSocket connects
     */
    private fun createWebSocketFlow(
        topic: String,
        readyDeferred: kotlinx.coroutines.CompletableDeferred<Unit>
    ): Flow<NtfyMessage> = callbackFlow {
        val wsUrl = buildWsUrl(topic, server)
        val connectionStartTime = System.currentTimeMillis()

        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to topic: $topic")
                // Mark subscription as ready - WebSocket is now connected
                readyDeferred.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = parseNtfyMessage(text)
                if (message != null) {
                    // Also treat "open" event as a ready signal (in case we didn't get onOpen)
                    if (message.event == "open" && !readyDeferred.isCompleted) {
                        readyDeferred.complete(Unit)
                    }
                    if (message.event == "message") {
                        Log.d(TAG, "Received message on topic: $topic")
                    }
                    trySend(message)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val duration = System.currentTimeMillis() - connectionStartTime
                Log.e(TAG, "WebSocket failed after ${duration}ms: ${t.message}", t)
                // Mark subscription as failed if not yet ready
                if (!readyDeferred.isCompleted) {
                    readyDeferred.completeExceptionally(IOException("WebSocket failed: ${t.message}", t))
                }
                close(IOException("WebSocket failed: ${t.message}", t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val duration = System.currentTimeMillis() - connectionStartTime
                if (code != 1000) {
                    Log.w(TAG, "WebSocket closed unexpectedly after ${duration}ms: code=$code, reason=$reason")
                    if (!readyDeferred.isCompleted) {
                        readyDeferred.completeExceptionally(IOException("WebSocket closed: $reason"))
                    }
                    close(IOException("WebSocket closed: $reason"))
                } else {
                    Log.i(TAG, "WebSocket closed normally after ${duration}ms")
                    close()
                }
            }
        }

        currentWebSocket = httpClient.newWebSocket(request, listener)

        awaitClose {
            Log.d(TAG, "Closing WebSocket connection")
            currentWebSocket?.close(1000, "Client disconnect")
            currentWebSocket = null
        }
    }

    /**
     * Unsubscribe from the current topic.
     */
    override fun unsubscribe() {
        currentWebSocket?.close(1000, "Client unsubscribe")
        currentWebSocket = null
        currentSubscription = null
    }

    /**
     * Publish a message to a topic via HTTP POST.
     *
     * Main-safe: switches to IO dispatcher for blocking network call.
     *
     * @param topic The ntfy topic to publish to
     * @param message The message content (should be encrypted)
     * @throws IOException if the request fails
     */
    override suspend fun publish(topic: String, message: String) = withContext(ioDispatcher) {
        val url = buildPublishUrl(topic, server)

        val request = Request.Builder()
            .url(url)
            .post(message.toRequestBody())
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to publish: ${response.code}")
            }
        }
    }

    /**
     * Publish a message with retry on failure.
     *
     * Uses exponential backoff between retries.
     *
     * @param topic The topic to publish to
     * @param message The message content
     * @param maxRetries Maximum number of retry attempts
     * @param baseDelayMs Base delay for exponential backoff
     * @throws PublishFailedException if all retries fail
     */
    override suspend fun publishWithRetry(
        topic: String,
        message: String,
        maxRetries: Int,
        baseDelayMs: Long
    ) {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                publish(topic, message)
                Log.d(TAG, "Published successfully on attempt ${attempt + 1}")
                return  // Success
            } catch (e: CancellationException) {
                throw e  // Don't retry on cancellation
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val delayMs = minOf(baseDelayMs * (1 shl attempt), MAX_BACKOFF_MS)
                    Log.w(TAG, "Publish failed on attempt ${attempt + 1}, retrying in ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                }
            }
        }

        throw PublishFailedException(
            "Failed to publish after $maxRetries attempts",
            lastException
        )
    }
}

/**
 * Mock ntfy client for testing.
 *
 * Allows controlled delivery of messages and tracking of published messages.
 */
class MockNtfyClient : NtfyClientInterface {
    @Volatile
    private var _isSubscribed = false
    private var messageChannel = Channel<NtfyMessage>(Channel.UNLIMITED)
    private val publishedMessages = mutableListOf<String>()
    private var currentSubscription: NtfySubscription? = null

    /**
     * Configurable delay before marking subscription as ready.
     * Set this before calling subscribe() to test timing behavior.
     */
    var subscribeReadyDelayMs: Long = 0L

    /**
     * Number of times to fail publish before succeeding.
     * Set to > 0 to test retry behavior.
     */
    var failPublishTimes: Int = 0
    private var publishAttempts = 0

    val isSubscribed: Boolean get() = _isSubscribed

    override fun subscribe(topic: String): NtfySubscription {
        val readyDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

        // Create fresh channel for this subscription
        messageChannel = Channel(Channel.UNLIMITED)

        val messagesFlow = callbackFlow {
            _isSubscribed = true

            // Simulate connection delay if configured
            if (subscribeReadyDelayMs > 0) {
                delay(subscribeReadyDelayMs)
            }

            // Mark as ready after delay
            readyDeferred.complete(Unit)

            // Send "open" event to signal WebSocket is connected (mimics real behavior)
            send(NtfyMessage(event = "open", message = ""))

            try {
                for (message in messageChannel) {
                    send(message)
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                // Channel closed or other error
            } finally {
                _isSubscribed = false
            }

            awaitClose {
                _isSubscribed = false
            }
        }

        val subscription = NtfySubscription(
            messages = messagesFlow,
            readyDeferred = readyDeferred,
            onClose = { unsubscribe() }
        )

        currentSubscription = subscription
        return subscription
    }

    override fun unsubscribe() {
        _isSubscribed = false
        messageChannel.close()
        currentSubscription = null
    }

    override suspend fun publish(topic: String, message: String) {
        publishAttempts++
        if (publishAttempts <= failPublishTimes) {
            throw IOException("Simulated publish failure (attempt $publishAttempts)")
        }
        publishedMessages.add(message)
    }

    override suspend fun publishWithRetry(
        topic: String,
        message: String,
        maxRetries: Int,
        baseDelayMs: Long
    ) {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                publish(topic, message)
                return  // Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(baseDelayMs * (1 shl attempt))
                }
            }
        }

        throw PublishFailedException("Failed after $maxRetries attempts", lastException)
    }

    /**
     * Deliver a message to subscribers.
     */
    fun deliverMessage(message: String) {
        if (_isSubscribed) {
            messageChannel.trySend(NtfyMessage(event = "message", message = message))
        }
    }

    /**
     * Deliver a raw NtfyMessage to subscribers.
     */
    fun deliverNtfyMessage(message: NtfyMessage) {
        if (_isSubscribed) {
            messageChannel.trySend(message)
        }
    }

    /**
     * Get all published messages.
     */
    fun getPublishedMessages(): List<String> = publishedMessages.toList()

    /**
     * Get total number of publish attempts (including failures).
     */
    fun getPublishAttempts(): Int = publishAttempts

    /**
     * Clear published messages.
     */
    fun clearPublished() {
        publishedMessages.clear()
        publishAttempts = 0
    }

    /**
     * Simulate a WebSocket disconnect.
     */
    fun simulateDisconnect() {
        messageChannel.close()
    }

    /**
     * Reset for reuse.
     */
    fun reset() {
        _isSubscribed = false
        publishedMessages.clear()
        messageChannel = Channel(Channel.UNLIMITED)
        subscribeReadyDelayMs = 0L
        failPublishTimes = 0
        publishAttempts = 0
        currentSubscription = null
    }
}
