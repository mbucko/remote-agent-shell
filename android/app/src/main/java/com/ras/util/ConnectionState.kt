package com.ras.util

/**
 * WebRTC connection state machine.
 */
sealed class ConnectionState {
    /** Not connected to any peer */
    data object Disconnected : ConnectionState()

    /** QR code scanning in progress */
    data object Scanning : ConnectionState()

    /** WebRTC connection being established */
    data object Connecting : ConnectionState()

    /** Mutual authentication handshake in progress */
    data object Authenticating : ConnectionState()

    /** Successfully connected to peer */
    data class Connected(val peerId: String) : ConnectionState()

    /** Attempting to reconnect after disconnect */
    data class Reconnecting(val attempt: Int) : ConnectionState()

    /** Connection error occurred */
    data class Error(val reason: ErrorReason) : ConnectionState()
}

/**
 * Reasons for connection errors.
 */
enum class ErrorReason {
    /** Invalid QR code scanned */
    QR_INVALID,

    /** WebRTC connection failed */
    CONNECTION_FAILED,

    /** Authentication handshake failed */
    AUTH_FAILED,

    /** Connection timed out */
    TIMEOUT,

    /** Peer disconnected */
    PEER_DISCONNECTED,

    /** Network is unavailable */
    NETWORK_UNAVAILABLE
}

/**
 * Internal WebRTC state machine.
 */
sealed class WebRTCState {
    data object Idle : WebRTCState()
    data object CreatingOffer : WebRTCState()
    data object WaitingForAnswer : WebRTCState()
    data object SettingRemoteDescription : WebRTCState()
    data object GatheringIceCandidates : WebRTCState()
    data object Connecting : WebRTCState()
    data object Connected : WebRTCState()
    data object Disconnected : WebRTCState()
    data class Failed(val error: String) : WebRTCState()
}
