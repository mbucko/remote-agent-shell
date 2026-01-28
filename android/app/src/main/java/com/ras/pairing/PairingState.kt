package com.ras.pairing

sealed class PairingState {
    object Idle : PairingState()
    object Scanning : PairingState()
    data class QrParsed(val payload: ParsedQrPayload) : PairingState()

    // Direct signaling states
    object TryingDirect : PairingState()
    object DirectSignaling : PairingState()

    // ntfy fallback states
    object NtfySubscribing : PairingState()
    object NtfyWaitingForAnswer : PairingState()

    // Legacy state (kept for compatibility)
    object Signaling : PairingState()

    object Connecting : PairingState()
    object Authenticating : PairingState()
    data class Authenticated(val deviceId: String) : PairingState()
    data class Failed(val reason: FailureReason) : PairingState()

    enum class FailureReason {
        QR_PARSE_ERROR,
        SIGNALING_FAILED,
        DIRECT_TIMEOUT,         // Direct signaling timed out, falling back to ntfy
        NTFY_SUBSCRIBE_FAILED,  // Failed to subscribe to ntfy topic
        NTFY_TIMEOUT,           // ntfy signaling timed out
        CONNECTION_FAILED,
        AUTH_FAILED,
        TIMEOUT
    }
}
