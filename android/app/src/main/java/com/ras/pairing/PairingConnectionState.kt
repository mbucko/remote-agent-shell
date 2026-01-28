package com.ras.pairing

/**
 * State machine for tracking the WebRTC connection during the pairing flow.
 *
 * Valid state transitions:
 * - Creating -> Signaling (offer created, exchange started)
 * - Signaling -> Connecting (SDP answer received)
 * - Connecting -> Authenticating (data channel opened)
 * - Authenticating -> HandedOff (auth success, ownership transferred)
 * - Any state -> Closed (explicit close or error)
 *
 * This state machine helps ensure:
 * 1. The connection is not closed after handoff
 * 2. State transitions follow valid paths
 * 3. The current lifecycle phase is always known
 */
sealed class PairingConnectionState {
    /** Connection is being created (WebRTC client instantiation) */
    data object Creating : PairingConnectionState()

    /** Signaling in progress (SDP offer/answer exchange) */
    data object Signaling : PairingConnectionState()

    /** ICE connectivity being established */
    data object Connecting : PairingConnectionState()

    /** Authentication handshake in progress */
    data object Authenticating : PairingConnectionState()

    /** Connection successfully handed off to ConnectionManager */
    data class HandedOff(val to: String) : PairingConnectionState()

    /** Connection has been closed */
    data object Closed : PairingConnectionState()

    /**
     * Check if this state transition is valid.
     */
    fun canTransitionTo(newState: PairingConnectionState): Boolean {
        return when (this) {
            Creating -> newState == Signaling || newState == Closed
            Signaling -> newState == Connecting || newState == Closed
            Connecting -> newState == Authenticating || newState == Closed
            Authenticating -> newState is HandedOff || newState == Closed
            is HandedOff -> false // Terminal state - no transitions allowed
            Closed -> false // Terminal state - no transitions allowed
        }
    }

    /**
     * Check if the connection should be closed when cleanup is called.
     */
    fun shouldCloseOnCleanup(): Boolean {
        return when (this) {
            Creating, Signaling, Connecting, Authenticating -> true
            is HandedOff -> false // Don't close - ConnectionManager owns it
            Closed -> false // Already closed
        }
    }

    override fun toString(): String = when (this) {
        Creating -> "Creating"
        Signaling -> "Signaling"
        Connecting -> "Connecting"
        Authenticating -> "Authenticating"
        is HandedOff -> "HandedOff(to=$to)"
        Closed -> "Closed"
    }
}
