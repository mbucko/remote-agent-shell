package com.ras.data.connection

import android.util.Log
import com.ras.crypto.HmacUtils
import com.ras.crypto.KeyDerivation
import com.ras.proto.LanDirectAuthRequest
import com.ras.proto.LanDirectAuthResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Transport implementation using WebSocket over LAN.
 *
 * This provides the fastest connection when the app and daemon are on
 * the same local network. Uses HTTP WebSocket upgrade on the existing
 * daemon HTTP port.
 *
 * Protocol:
 * 1. WebSocket connect to ws://{host}:{port}/ws/{device_id}
 * 2. Send LanDirectAuthRequest (protobuf binary)
 * 3. Receive LanDirectAuthResponse with status "authenticated"
 * 4. Exchange messages as binary WebSocket frames (no length prefix needed)
 */
class LanDirectTransport private constructor(
    private val webSocket: WebSocket,
    private val messageChannel: Channel<ByteArray>,
    private val host: String,
    private val port: Int
) : Transport {

    companion object {
        private const val TAG = "LanDirectTransport"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5000L
        private const val AUTH_TIMEOUT_MS = 5000L

        /**
         * Create a LanDirectTransport by connecting via WebSocket.
         *
         * @param host Daemon's IP address (e.g., "192.168.1.100")
         * @param port Daemon's HTTP port
         * @param deviceId Device ID for authentication
         * @param masterSecret 32-byte master secret for auth key derivation
         * @param client OkHttpClient instance (for DI/testing)
         * @return Connected and authenticated transport
         * @throws LanDirectAuthException if authentication fails
         * @throws IOException if connection fails
         */
        suspend fun connect(
            host: String,
            port: Int,
            deviceId: String,
            masterSecret: ByteArray,
            client: OkHttpClient = defaultClient()
        ): LanDirectTransport = withContext(Dispatchers.IO) {
            Log.i(TAG, "Connecting to ws://$host:$port/ws/$deviceId")

            // Create WebSocket connection
            val request = Request.Builder()
                .url("ws://$host:$port/ws/$deviceId")
                .build()

            // Channel to receive connection events
            val connectionResult = Channel<Result<Pair<WebSocket, Channel<ByteArray>>>>(1)
            val messageChannel = Channel<ByteArray>(Channel.UNLIMITED)

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket opened")
                    connectionResult.trySend(Result.success(Pair(webSocket, messageChannel)))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.v(TAG, "Received ${bytes.size} bytes")
                    messageChannel.trySend(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}")
                    connectionResult.trySend(Result.failure(IOException("WebSocket failed: ${t.message}", t)))
                    messageChannel.close(t)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    messageChannel.close()
                }
            }

            val webSocket = client.newWebSocket(request, listener)

            // Wait for connection
            val connectResult = withTimeoutOrNull(DEFAULT_CONNECT_TIMEOUT_MS) {
                connectionResult.receive()
            } ?: run {
                webSocket.cancel()
                throw IOException("Connection timeout")
            }

            val (ws, msgChannel) = connectResult.getOrThrow()

            // Authenticate
            try {
                val authKey = KeyDerivation.deriveKey(masterSecret, "auth")
                val timestamp = System.currentTimeMillis() / 1000
                val signature = HmacUtils.computeSignalingHmac(authKey, deviceId, timestamp, ByteArray(0))

                val authRequest = LanDirectAuthRequest.newBuilder()
                    .setDeviceId(deviceId)
                    .setTimestamp(timestamp)
                    .setSignature(signature.toHex())
                    .build()

                Log.d(TAG, "Sending auth request")
                ws.send(authRequest.toByteArray().toByteString())

                // Wait for auth response
                val responseBytes = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                    msgChannel.receive()
                } ?: throw LanDirectAuthException("Auth response timeout")

                val authResponse = LanDirectAuthResponse.parseFrom(responseBytes)
                if (authResponse.status != "authenticated") {
                    throw LanDirectAuthException("Server rejected authentication: ${authResponse.status}")
                }

                Log.i(TAG, "Authentication successful")
                return@withContext LanDirectTransport(ws, msgChannel, host, port)

            } catch (e: CancellationException) {
                ws.cancel()
                throw e
            } catch (e: LanDirectAuthException) {
                ws.cancel()
                throw e
            } catch (e: Exception) {
                ws.cancel()
                throw e
            }
        }

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }

    override val type: TransportType = TransportType.LAN_DIRECT

    private val closed = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = !closed.get()

    // Thread-safe stats counters
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val messagesSent = AtomicLong(0)
    private val messagesReceived = AtomicLong(0)
    private val connectedAt: Long = System.currentTimeMillis()
    @Volatile
    private var lastActivity: Long = connectedAt

    override suspend fun send(data: ByteArray) {
        if (closed.get()) throw TransportException("Transport is closed")

        val success = webSocket.send(data.toByteString())
        if (!success) {
            throw TransportException("Send failed - WebSocket buffer full or closed")
        }

        bytesSent.addAndGet(data.size.toLong())
        messagesSent.incrementAndGet()
        lastActivity = System.currentTimeMillis()

        Log.v(TAG, "Sent ${data.size} bytes")
    }

    override suspend fun receive(timeoutMs: Long): ByteArray {
        if (closed.get()) throw TransportException("Transport is closed")

        val data = try {
            if (timeoutMs > 0) {
                withTimeoutOrNull(timeoutMs) {
                    messageChannel.receive()
                } ?: throw TransportException("Receive timeout", isRecoverable = true)
            } else {
                messageChannel.receive()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            if (closed.get()) {
                throw TransportException("Transport closed", e)
            }
            throw TransportException("Receive error: ${e.message}", e, isRecoverable = true)
        }

        bytesReceived.addAndGet(data.size.toLong())
        messagesReceived.incrementAndGet()
        lastActivity = System.currentTimeMillis()

        Log.v(TAG, "Received ${data.size} bytes")
        return data
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        Log.d(TAG, "Closing LAN Direct transport")
        try {
            webSocket.close(1000, "Client closing")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket", e)
        }
        messageChannel.close()
    }

    override fun getStats(): TransportStats {
        return TransportStats(
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            messagesSent = messagesSent.get(),
            messagesReceived = messagesReceived.get(),
            connectedAtMs = connectedAt,
            lastActivityMs = lastActivity,
            estimatedLatencyMs = null
        )
    }
}
