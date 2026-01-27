package com.ras.ntfy

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Subscribes to ntfy topic via WebSocket and emits decrypted IP changes.
 *
 * Features:
 * - Filters ntfy events (only processes "message" events)
 * - Thread-safe nonce cache for replay protection
 * - Automatic reconnection with backoff (5s, 10s, 20s)
 * - Timestamp validation (5 minute window)
 */
class NtfySubscriber(
    private val crypto: NtfyCrypto,
    private val topic: String,
    private val server: String = "https://ntfy.sh",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "NtfySubscriber"
        private const val MAX_NONCES = 100
        private const val TIMESTAMP_WINDOW_SECONDS = 300L  // 5 minutes
        private const val NONCE_SIZE = 16
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // No timeout for WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null

    private val _ipChanges = MutableSharedFlow<IpChangeData>()
    val ipChanges: SharedFlow<IpChangeData> = _ipChanges

    // Thread-safe nonce cache for replay protection
    private val seenNonces: MutableSet<String> = Collections.synchronizedSet(
        LinkedHashSet<String>()
    )

    // Reconnection state
    private var reconnectAttempt = 0
    private val backoffDelays = listOf(5000L, 10000L, 20000L)
    private var reconnectJob: Job? = null

    private fun ensureScope(): CoroutineScope {
        if (scope == null) {
            scope = CoroutineScope(dispatcher + SupervisorJob())
        }
        return scope!!
    }

    /**
     * Connect to the ntfy WebSocket.
     */
    fun connect() {
        ensureScope()
        val wsUrl = buildWsUrl()
        Log.d(TAG, "Connecting to ntfy topic: $topic")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                // Reset reconnect counter on successful connection
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                if (code != 1000) {  // Not user-initiated close
                    scheduleReconnect()
                }
            }
        })
    }

    /**
     * Disconnect from the ntfy WebSocket.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        scope?.cancel()
        scope = null
    }

    internal fun buildWsUrl(): String {
        val wsServer = server
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        return "$wsServer/$topic/ws"
    }

    internal fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // IMPORTANT: Only process "message" events
            // Ignore "open" and "keepalive" events
            val event = json.optString("event", "")
            if (event != "message") {
                Log.d(TAG, "Ignoring event type: $event")
                return
            }

            val message = json.optString("message", "")
            if (message.isEmpty()) {
                Log.w(TAG, "Empty message field")
                return
            }

            val data = try {
                crypto.decryptIpNotification(message)
            } catch (e: Exception) {
                Log.w(TAG, "Decryption failed: ${e.message}")
                return
            }

            // Validate nonce size
            if (data.nonce.size != NONCE_SIZE) {
                Log.w(TAG, "Invalid nonce size: ${data.nonce.size}")
                return
            }

            // Replay protection: check nonce
            val nonceHex = data.nonce.toHexString()
            synchronized(seenNonces) {
                if (nonceHex in seenNonces) {
                    Log.w(TAG, "Replay detected, ignoring")
                    return
                }

                // Timestamp validation (within 5 minutes)
                val now = System.currentTimeMillis() / 1000
                if (abs(now - data.timestamp) > TIMESTAMP_WINDOW_SECONDS) {
                    Log.w(TAG, "Timestamp outside window, ignoring")
                    return
                }

                // Add to seen nonces (thread-safe)
                seenNonces.add(nonceHex)
                if (seenNonces.size > MAX_NONCES) {
                    // Remove oldest (first) entry - FIFO eviction
                    val iterator = seenNonces.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }

            // Log at INFO level without full IP
            Log.i(TAG, "Valid IP change received")
            // Log full details only at DEBUG level
            Log.d(TAG, "New address: ${data.ip}:${data.port}")

            ensureScope().launch {
                _ipChanges.emit(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempt >= backoffDelays.size) {
            Log.e(TAG, "Max reconnect attempts reached, giving up")
            return
        }

        val delay = backoffDelays[reconnectAttempt]
        reconnectAttempt++

        Log.d(TAG, "Scheduling reconnect in ${delay}ms (attempt $reconnectAttempt)")

        reconnectJob = ensureScope().launch {
            delay(delay)
            if (isActive) {
                Log.d(TAG, "Attempting reconnect")
                connect()
            }
        }
    }

    /**
     * Reset reconnect counter. Call after successful IP change handling.
     */
    fun resetReconnectCounter() {
        reconnectAttempt = 0
    }

    /**
     * Clear the nonce cache. Primarily for testing.
     */
    internal fun clearNonceCache() {
        seenNonces.clear()
    }

    /**
     * Add a nonce to the cache. Primarily for testing.
     */
    internal fun addNonce(nonce: String) {
        synchronized(seenNonces) {
            seenNonces.add(nonce)
            if (seenNonces.size > MAX_NONCES) {
                val iterator = seenNonces.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Check if a nonce is in the cache. Primarily for testing.
     */
    internal fun hasNonce(nonce: String): Boolean {
        return nonce in seenNonces
    }
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
