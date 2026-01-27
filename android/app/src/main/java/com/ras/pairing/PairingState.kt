package com.ras.pairing

sealed class PairingState {
    object Idle : PairingState()
    object Scanning : PairingState()
    data class QrParsed(val payload: ParsedQrPayload) : PairingState()
    object Signaling : PairingState()
    object Connecting : PairingState()
    object Authenticating : PairingState()
    data class Authenticated(val deviceId: String) : PairingState()
    data class Failed(val reason: FailureReason) : PairingState()

    enum class FailureReason {
        QR_PARSE_ERROR,
        SIGNALING_FAILED,
        CONNECTION_FAILED,
        AUTH_FAILED,
        TIMEOUT
    }
}
