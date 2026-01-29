package com.ras.data.webrtc

/**
 * Represents who owns a WebRTC connection at any given time.
 *
 * This pattern prevents race conditions during connection handoff:
 * - Only the current owner can close a connection
 * - Ownership must be explicitly transferred
 * - Once disposed, a connection cannot be transferred
 */
sealed class ConnectionOwnership {
    /** Connection is owned by PairingManager during initial setup and authentication */
    data object PairingManager : ConnectionOwnership()

    /** Connection is owned by ReconnectionManager during auto-reconnection */
    data object ReconnectionManager : ConnectionOwnership()

    /** Connection is owned by ConnectionManager for active session */
    data object ConnectionManager : ConnectionOwnership()

    /** Connection has been closed and cannot be used */
    data object Disposed : ConnectionOwnership()

    override fun toString(): String = this::class.simpleName ?: "Unknown"
}
