package com.ras.signaling

import android.util.Log
import com.ras.crypto.KeyDerivation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
     * Subscribe to a topic and receive messages as a Flow.
     *
     * IMPORTANT: This function suspends until the WebSocket is connected.
     * This guarantees that any messages published after this function returns
     * will be received by the Flow.
     *
     * Main-safe: can be called from any dispatcher.
     *
     * @return Hot Flow of messages (WebSocket already connected)
     * @throws IOException if connection fails or times out
     */
    suspend fun subscribe(topic: String): Flow<NtfyMessage>

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
 * // Subscribe - blocks until connected
 * val messages = client.subscribe(topic)
 *
 * // Now safe to publish - we're connected
 * client.publish(topic, encryptedMessage)
 *
 * // Collect messages
 * messages.collect { message -> ... }
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
        private const val CONNECTION_TIMEOUT_MS = 10_000L

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
         * No longer needs ?since= because subscribe() now waits for connection
         * before returning, guaranteeing messages published after subscribe()
         * returns will be received.
         */
        fun buildWsUrl(topic: String, server: String = DEFAULT_SERVER): String {
            val wsServer = server
                .replace("https://", "wss://")
                .replace("http://", "ws://")
            return "$wsServer/$topic/ws"
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

    /**
     * Subscribe to a topic and receive messages as a Flow.
     *
     * IMPORTANT: This function suspends until the WebSocket is connected.
     * This guarantees that any messages published after this function returns
     * will be received by the Flow.
     *
     * Features:
     * - Blocks until WebSocket connection is established
     * - Returns a hot Flow (WebSocket already connected)
     * - Automatic retry with exponential backoff
     * - Connection state logging with duration tracking
     *
     * @param topic The ntfy topic to subscribe to
     * @return Hot Flow of messages (WebSocket already connected)
     * @throws IOException if connection fails or times out
     */
    override suspend fun subscribe(topic: String): Flow<NtfyMessage> {
        var lastException: IOException? = null

        repeat(maxReconnectAttempts) { attempt ->
            try {
                return connectAndSubscribe(topic)
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxReconnectAttempts - 1) {
                    val backoffMs = minOf(INITIAL_BACKOFF_MS * (1 shl attempt), MAX_BACKOFF_MS)
                    Log.w(TAG, "WebSocket connection failed, retrying in ${backoffMs}ms (attempt ${attempt + 1}/$maxReconnectAttempts)")
                    delay(backoffMs)
                }
            }
        }

        Log.e(TAG, "WebSocket connection failed after $maxReconnectAttempts attempts, giving up", lastException)
        throw lastException ?: IOException("Connection failed")
    }

    /**
     * Connect to WebSocket and return a hot Flow.
     * Suspends until connection is established.
     */
    private suspend fun connectAndSubscribe(topic: String): Flow<NtfyMessage> {
        val connectedSignal = CompletableDeferred<Unit>()
        val messageChannel = Channel<NtfyMessage>(Channel.UNLIMITED)

        val wsUrl = buildWsUrl(topic, server)
        val connectionStartTime = System.currentTimeMillis()

        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val duration = System.currentTimeMillis() - connectionStartTime
                Log.i(TAG, "WebSocket connected to topic: $topic (${duration}ms)")
                connectedSignal.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = parseNtfyMessage(text)
                if (message != null) {
                    if (message.event == "message") {
                        Log.d(TAG, "Received message on topic: $topic")
                    }
                    messageChannel.trySend(message)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val duration = System.currentTimeMillis() - connectionStartTime
                Log.e(TAG, "WebSocket failed after ${duration}ms: ${t.message}", t)
                val exception = IOException("WebSocket failed: ${t.message}", t)
                connectedSignal.completeExceptionally(exception)
                messageChannel.close(exception)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val duration = System.currentTimeMillis() - connectionStartTime
                if (code != 1000) {
                    Log.w(TAG, "WebSocket closed unexpectedly after ${duration}ms: code=$code, reason=$reason")
                    messageChannel.close(IOException("WebSocket closed: $reason"))
                } else {
                    Log.i(TAG, "WebSocket closed normally after ${duration}ms")
                    messageChannel.close()
                }
            }
        }

        currentWebSocket = httpClient.newWebSocket(request, listener)

        // CRITICAL: Wait for connection before returning
        try {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                connectedSignal.await()
            }
        } catch (e: Exception) {
            currentWebSocket?.close(1000, "Connection timeout")
            currentWebSocket = null
            throw IOException("WebSocket connection timeout", e)
        }

        // Return hot Flow - WebSocket is already connected
        return messageChannel.receiveAsFlow()
            .onCompletion {
                Log.d(TAG, "Closing WebSocket connection")
                currentWebSocket?.close(1000, "Flow completed")
                currentWebSocket = null
            }
    }

    /**
     * Unsubscribe from the current topic.
     */
    override fun unsubscribe() {
        currentWebSocket?.close(1000, "Client unsubscribe")
        currentWebSocket = null
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
 * Realistic mock ntfy client for testing.
 *
 * Simulates real ntfy behavior:
 * - subscribe() suspends until "connected" (simulating real WebSocket)
 * - Messages are only delivered to CONNECTED subscribers
 * - Messages published before subscription completes are lost (unless using message history)
 * - Connection has simulated latency
 *
 * @param connectionDelayMs Simulated connection delay (0 = instant)
 * @param simulateMessageHistory If true, delivers messages from last 30s on connect (like ?since=30s)
 */
class MockNtfyClient(
    private val connectionDelayMs: Long = 0L,
    private val simulateMessageHistory: Boolean = false
) : NtfyClientInterface {

    @Volatile
    private var _isConnected = false

    @Volatile
    private var _subscribeTime: Long = 0L

    private var messageChannel = Channel<NtfyMessage>(Channel.UNLIMITED)
    private val publishedMessages = mutableListOf<TimestampedMessage>()

    /**
     * Number of times to fail publish before succeeding.
     * Set to > 0 to test retry behavior.
     */
    var failPublishTimes: Int = 0
    private var publishAttempts = 0

    data class TimestampedMessage(
        val timestamp: Long,
        val message: String
    )

    val isConnected: Boolean get() = _isConnected

    // Backwards compatibility alias
    val isSubscribed: Boolean get() = _isConnected

    /**
     * Subscribe to a topic.
     *
     * IMPORTANT: Like the real implementation, this suspends until "connected".
     * This guarantees messages published after subscribe() returns will be received.
     */
    override suspend fun subscribe(topic: String): Flow<NtfyMessage> {
        // Simulate connection delay (like real WebSocket handshake)
        if (connectionDelayMs > 0) {
            delay(connectionDelayMs)
        }

        // Mark as connected
        _subscribeTime = System.currentTimeMillis()
        _isConnected = true
        messageChannel = Channel(Channel.UNLIMITED)

        // If simulating message history (?since=), deliver past messages
        if (simulateMessageHistory) {
            val cutoffTime = _subscribeTime - 30_000  // 30 second history
            publishedMessages
                .filter { it.timestamp >= cutoffTime }
                .forEach { messageChannel.trySend(NtfyMessage(event = "message", message = it.message)) }
        }

        return messageChannel.receiveAsFlow()
            .onCompletion { _isConnected = false }
    }

    override fun unsubscribe() {
        _isConnected = false
        messageChannel.close()
    }

    /**
     * Publish a message to a topic.
     *
     * CRITICAL: Only delivers if subscriber is ALREADY connected.
     * This matches real ntfy behavior - messages published before
     * WebSocket connects are "broadcast to nobody" and lost.
     */
    override suspend fun publish(topic: String, message: String) {
        publishAttempts++
        if (publishAttempts <= failPublishTimes) {
            throw IOException("Simulated publish failure (attempt $publishAttempts)")
        }

        val now = System.currentTimeMillis()
        publishedMessages.add(TimestampedMessage(now, message))

        // Only deliver if subscriber is ALREADY connected
        if (_isConnected && now >= _subscribeTime) {
            messageChannel.trySend(NtfyMessage(event = "message", message = message))
        }
        // If not connected, message is "broadcast to nobody" - lost!
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
     * Deliver a message to subscribers (simulates external message from daemon).
     * Only delivered if currently connected.
     */
    fun deliverMessage(message: String) {
        if (_isConnected) {
            messageChannel.trySend(NtfyMessage(event = "message", message = message))
        }
    }

    /**
     * Deliver a raw NtfyMessage to subscribers.
     * Only delivered if currently connected.
     */
    fun deliverNtfyMessage(message: NtfyMessage) {
        if (_isConnected) {
            messageChannel.trySend(message)
        }
    }

    /**
     * Simulate an external message arriving (e.g., from daemon).
     * Alias for deliverMessage for clarity in tests.
     */
    fun simulateIncomingMessage(message: String) {
        deliverMessage(message)
    }

    /**
     * Get all published messages (for test assertions).
     */
    fun getPublishedMessages(): List<String> = publishedMessages.map { it.message }

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
        _isConnected = false
        messageChannel.close()
    }

    /**
     * Reset for reuse between tests.
     */
    fun reset() {
        _isConnected = false
        _subscribeTime = 0L
        publishedMessages.clear()
        messageChannel = Channel(Channel.UNLIMITED)
        failPublishTimes = 0
        publishAttempts = 0
    }
}
