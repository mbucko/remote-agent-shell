package com.ras.pairing

sealed class PairingState {
    object Idle : PairingState()
    object Scanning : PairingState()
    data class QrParsed(val payload: ParsedQrPayload) : PairingState()

    object ExchangingCredentials : PairingState()

    data class Authenticated(val deviceId: String) : PairingState()
    data class Failed(val reason: FailureReason) : PairingState()

    enum class FailureReason {
        QR_PARSE_ERROR,
        SIGNALING_FAILED,
        NTFY_TIMEOUT,
        AUTH_FAILED,
        TIMEOUT
    }
}
