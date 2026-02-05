package com.ras.data.connection

/**
 * Abstract transport layer for daemon communication.
 *
 * All connection strategies (Tailscale, WebRTC, TURN) produce a Transport
 * that implements this interface. The rest of the app doesn't care HOW
 * we're connected, just that we can send and receive data.
 */
interface Transport {
    /**
     * The type of transport (for logging/UI).
     */
    val type: TransportType

    /**
     * Whether the transport is currently connected.
     */
    val isConnected: Boolean

    /**
     * Send data to the daemon.
     *
     * @param data The protobuf-encoded message
     * @throws TransportException if send fails
     */
    suspend fun send(data: ByteArray)

    /**
     * Receive data from the daemon.
     *
     * @param timeoutMs Timeout in milliseconds (0 = no timeout)
     * @return The received data
     * @throws TransportException if receive fails or times out
     */
    suspend fun receive(timeoutMs: Long = 30_000): ByteArray

    /**
     * Close the transport and release resources.
     * Safe to call multiple times.
     */
    fun close()

    /**
     * Get transport statistics for monitoring.
     */
    fun getStats(): TransportStats
}

/**
 * Types of transport connections.
 */
enum class TransportType(val displayName: String) {
    TAILSCALE("Tailscale Direct"),
    WEBRTC("WebRTC P2P"),
    TURN("TURN Relay"),
    UNKNOWN("Unknown")
}

/**
 * Transport statistics for monitoring and debugging.
 */
data class TransportStats(
    val bytesSent: Long,
    val bytesReceived: Long,
    val messagesSent: Long,
    val messagesReceived: Long,
    val connectedAtMs: Long,
    val lastActivityMs: Long,
    val estimatedLatencyMs: Long? = null
)

/**
 * Exception thrown by transport operations.
 */
open class TransportException(
    message: String,
    cause: Throwable? = null,
    val isRecoverable: Boolean = false
) : Exception(message, cause)

/**
 * Thrown when an operation is attempted on a closed transport.
 *
 * Typed subclass so callers can use `is TransportClosedException` instead of
 * matching on the exception message string.
 */
class TransportClosedException(
    cause: Throwable? = null
) : TransportException("Transport is closed", cause)
