package com.ras.data.connection

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Transport implementation using direct UDP socket over Tailscale.
 *
 * This provides a simple, fast connection when both devices are on
 * the same Tailscale network. No NAT traversal needed - Tailscale
 * handles the routing.
 *
 * Protocol:
 * - Each message is prefixed with 4-byte length (big-endian)
 * - Messages are sent as UDP datagrams
 */
class TailscaleTransport private constructor(
    private val socket: DatagramSocket,
    private val remoteAddress: InetSocketAddress,
    private val localTailscaleIp: String
) : Transport {

    companion object {
        private const val TAG = "TailscaleTransport"
        private const val MAX_PACKET_SIZE = 65507  // Max UDP payload
        private const val HEADER_SIZE = 4  // Length prefix
        private const val DEFAULT_PORT = 9876
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 2000L  // Total connection timeout for fast fallback
        private const val HANDSHAKE_ATTEMPT_TIMEOUT_MS = 500  // Per-attempt timeout (allows retries within total)
        private const val HANDSHAKE_MAX_RETRIES = 3
        private const val HANDSHAKE_MAGIC = 0x52415354  // "RAST" in hex
        private const val MAX_STALE_HANDSHAKE_SKIP = 3  // Max stale handshake packets to skip before failing

        /**
         * Create a TailscaleTransport by connecting to a remote Tailscale IP.
         *
         * Includes automatic retry for handshake failures (UDP packets can be lost).
         * Uses the SAME socket for all retries so daemon can respond to correct port.
         *
         * @param localIp Our Tailscale IP (to bind to)
         * @param remoteIp Remote device's Tailscale IP
         * @param remotePort Remote device's listening port
         * @param connectionTimeoutMs Total connection timeout in milliseconds (default: 2000ms for fast fallback)
         * @param socketFactory Factory for creating DatagramSocket instances (for DI/testing)
         * @return Connected transport
         * @throws IOException if connection fails after timeout or all retries
         */
        suspend fun connect(
            localIp: String,
            remoteIp: String,
            remotePort: Int = DEFAULT_PORT,
            connectionTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
            socketFactory: DatagramSocketFactory = DefaultDatagramSocketFactory()
        ): TailscaleTransport = withContext(Dispatchers.IO) {
            Log.i(TAG, "Connecting to $remoteIp:$remotePort from $localIp (timeout: ${connectionTimeoutMs}ms)")

            // Create socket and connect to remote (connected UDP socket)
            // This helps Android route packets correctly on VPN interfaces
            val remoteAddress = InetSocketAddress(InetAddress.getByName(remoteIp), remotePort)
            val socket = socketFactory.createConnected(remoteAddress)
            Log.d(TAG, "Socket connected: local=${socket.localAddress}:${socket.localPort} -> remote=$remoteAddress")
            socket.soTimeout = HANDSHAKE_ATTEMPT_TIMEOUT_MS

            try {
                // Wrap handshake attempts in overall timeout for fast fallback
                val result = withTimeoutOrNull(connectionTimeoutMs) {
                    var lastException: Exception? = null

                    // Handshake packet (same for all attempts)
                    val handshake = ByteBuffer.allocate(8)
                        .putInt(HANDSHAKE_MAGIC)
                        .putInt(0)  // Reserved
                        .array()
                    val handshakePacket = DatagramPacket(handshake, handshake.size, remoteAddress)

                    // Response buffer
                    val responseBuffer = ByteArray(8)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

                    repeat(HANDSHAKE_MAX_RETRIES) { attempt ->
                        try {
                            // Send handshake
                            socket.send(handshakePacket)
                            Log.d(TAG, "Sent handshake to $remoteIp:$remotePort (attempt ${attempt + 1})")

                            // Wait for response
                            socket.receive(responsePacket)
                            val response = ByteBuffer.wrap(responseBuffer)
                            val magic = response.int

                            if (magic != HANDSHAKE_MAGIC) {
                                throw IOException("Invalid handshake response: $magic")
                            }

                            Log.i(TAG, "Handshake successful with $remoteIp:$remotePort")
                            socket.soTimeout = 0  // Clear timeout for normal operation

                            return@withTimeoutOrNull TailscaleTransport(socket, remoteAddress, localIp)

                        } catch (e: SocketTimeoutException) {
                            lastException = e
                            if (attempt < HANDSHAKE_MAX_RETRIES - 1) {
                                Log.w(TAG, "Handshake timeout (attempt ${attempt + 1}/$HANDSHAKE_MAX_RETRIES), retrying...")
                            }
                        } catch (e: Exception) {
                            lastException = e
                            if (attempt < HANDSHAKE_MAX_RETRIES - 1) {
                                Log.w(TAG, "Handshake error (attempt ${attempt + 1}/$HANDSHAKE_MAX_RETRIES): ${e.message}, retrying...")
                            }
                        }
                    }

                    throw IOException(
                        "Handshake failed after $HANDSHAKE_MAX_RETRIES attempts - daemon may not be listening on Tailscale",
                        lastException
                    )
                }

                if (result == null) {
                    throw IOException("Connection timeout after ${connectionTimeoutMs}ms - daemon not reachable via Tailscale")
                }

                return@withContext result

            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
    }

    override val type: TransportType = TransportType.TAILSCALE

    /** Local Tailscale IP address (100.x.x.x) */
    val localIp: String = localTailscaleIp

    /** Remote Tailscale IP address */
    val remoteIp: String = remoteAddress.address.hostAddress ?: "unknown"

    /** Remote port */
    val remotePort: Int = remoteAddress.port

    @Volatile
    private var closed = false

    override val isConnected: Boolean
        get() = !closed && !socket.isClosed

    private var bytesSent: Long = 0
    private var bytesReceived: Long = 0
    private var messagesSent: Long = 0
    private var messagesReceived: Long = 0
    private val connectedAt: Long = System.currentTimeMillis()
    private var lastActivity: Long = connectedAt

    override suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            if (closed) throw TransportClosedException()
            if (data.size > MAX_PACKET_SIZE - HEADER_SIZE) {
                throw TransportException("Message too large: ${data.size} bytes")
            }

            // Prepend length header
            val packet = ByteBuffer.allocate(HEADER_SIZE + data.size)
                .putInt(data.size)
                .put(data)
                .array()

            val datagram = DatagramPacket(packet, packet.size, remoteAddress)
            socket.send(datagram)

            bytesSent += data.size
            messagesSent++
            lastActivity = System.currentTimeMillis()

            Log.v(TAG, "Sent ${data.size} bytes")
        }
    }

    override suspend fun receive(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        if (closed) throw TransportClosedException()

        if (timeoutMs > 0) {
            socket.soTimeout = timeoutMs.toInt()
        }

        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        var skipped = 0

        try {
            while (true) {
                socket.receive(packet)

                if (packet.length < HEADER_SIZE) {
                    throw TransportException("Packet too small: ${packet.length} bytes")
                }

                val wrapper = ByteBuffer.wrap(buffer, 0, packet.length)
                val length = wrapper.int

                // Skip stale handshake packets (exactly 8 bytes starting with RAST magic)
                if (packet.length == 8 && length == HANDSHAKE_MAGIC) {
                    skipped++
                    Log.w(TAG, "Skipping stale handshake packet ($skipped)")
                    if (skipped >= MAX_STALE_HANDSHAKE_SKIP) {
                        throw TransportException("Too many stale handshake packets")
                    }
                    continue
                }

                if (length < 0 || length > packet.length - HEADER_SIZE) {
                    throw TransportException("Invalid message length: $length")
                }

                val data = ByteArray(length)
                wrapper.get(data)

                bytesReceived += length
                messagesReceived++
                lastActivity = System.currentTimeMillis()

                Log.v(TAG, "Received $length bytes")
                return@withContext data
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("Unreachable")
        } catch (e: TransportException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw TransportException("Receive timeout", e, isRecoverable = true)
        } catch (e: IOException) {
            if (closed) {
                throw TransportException("Transport closed", e)
            }
            throw TransportException("Receive error: ${e.message}", e)
        } finally {
            socket.soTimeout = 0
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        Log.d(TAG, "Closing Tailscale transport")
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket", e)
        }
    }

    override fun getStats(): TransportStats {
        return TransportStats(
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            messagesSent = messagesSent,
            messagesReceived = messagesReceived,
            connectedAtMs = connectedAt,
            lastActivityMs = lastActivity,
            estimatedLatencyMs = null  // Could implement ping for this
        )
    }
}
