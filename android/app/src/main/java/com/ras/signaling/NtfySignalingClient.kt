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
     * Subscribe to a topic and receive messages as a Flow.
     * Main-safe: can be called from any dispatcher.
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
}

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
     * The Flow emits NtfyMessage objects for each received message.
     * Only "message" events contain actual data; "keepalive" and "open" are control events.
     *
     * Features:
     * - Automatic reconnection on failure (up to maxReconnectAttempts)
     * - Exponential backoff between retries
     * - Connection state logging
     */
    override suspend fun subscribe(topic: String): Flow<NtfyMessage> = createWebSocketFlow(topic)
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

    /**
     * Create the underlying WebSocket flow.
     */
    private fun createWebSocketFlow(topic: String): Flow<NtfyMessage> = callbackFlow {
        val wsUrl = buildWsUrl(topic, server)
        val connectionStartTime = System.currentTimeMillis()

        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to topic: $topic")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = parseNtfyMessage(text)
                if (message != null) {
                    if (message.event == "message") {
                        Log.d(TAG, "Received message on topic: $topic")
                    }
                    trySend(message)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val duration = System.currentTimeMillis() - connectionStartTime
                Log.e(TAG, "WebSocket failed after ${duration}ms: ${t.message}", t)
                close(IOException("WebSocket failed: ${t.message}", t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val duration = System.currentTimeMillis() - connectionStartTime
                if (code != 1000) {
                    Log.w(TAG, "WebSocket closed unexpectedly after ${duration}ms: code=$code, reason=$reason")
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

    val isSubscribed: Boolean get() = _isSubscribed

    override suspend fun subscribe(topic: String): Flow<NtfyMessage> = callbackFlow {
        // Create fresh channel for this subscription
        messageChannel = Channel(Channel.UNLIMITED)
        _isSubscribed = true

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

    override fun unsubscribe() {
        _isSubscribed = false
        messageChannel.close()
    }

    override suspend fun publish(topic: String, message: String) {
        publishedMessages.add(message)
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
     * Clear published messages.
     */
    fun clearPublished() {
        publishedMessages.clear()
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
    }
}
