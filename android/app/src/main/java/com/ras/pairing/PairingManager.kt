package com.ras.pairing

import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.signaling.NtfyClientInterface
import com.ras.signaling.PairExchangeResult
import com.ras.signaling.PairExchanger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingManager @Inject constructor(
    private val keyManager: KeyManager,
    private val credentialRepository: CredentialRepository,
    private val ntfyClient: NtfyClientInterface,
    private val progressTracker: PairingProgressTracker
) {
    // Internal scope that can be overridden for testing
    private var _scope: CoroutineScope? = null
    internal var scope: CoroutineScope
        get() = _scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { _scope = it }
        set(value) { _scope = value }

    // Injectable factory for PairExchanger (for testing)
    internal var pairExchangerFactory: (NtfyClientInterface) -> PairExchanger = { client ->
        PairExchanger(client)
    }

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    val progress = progressTracker.progress

    private var currentPayload: ParsedQrPayload? = null

    /**
     * Start pairing with scanned QR payload.
     */
    fun startPairing(payload: ParsedQrPayload) {
        currentPayload = payload

        progressTracker.start()
        progressTracker.onQrParsed()
        _state.value = PairingState.QrParsed(payload)

        scope.launch {
            performExchange(payload)
        }
    }

    private suspend fun performExchange(payload: ParsedQrPayload) {
        progressTracker.onExchanging()
        _state.value = PairingState.ExchangingCredentials

        val deviceId = keyManager.getOrCreateDeviceId()
        val deviceName = android.os.Build.MODEL ?: "Unknown Device"
        val ntfyTopic = payload.ntfyTopic

        val exchanger = pairExchangerFactory(ntfyClient)

        val result = exchanger.exchange(
            masterSecret = payload.masterSecret,
            sessionId = payload.sessionId,
            deviceId = deviceId,
            deviceName = deviceName,
            ntfyTopic = ntfyTopic
        )

        when (result) {
            is PairExchangeResult.Success -> {
                // Save device credentials
                credentialRepository.addDevice(
                    deviceId = result.daemonDeviceId,
                    masterSecret = payload.masterSecret,
                    deviceName = result.hostname,
                    deviceType = result.deviceType,
                    daemonHost = payload.ip,
                    daemonPort = payload.port,
                    daemonTailscaleIp = payload.tailscaleIp,
                    daemonTailscalePort = payload.tailscalePort,
                    daemonVpnIp = payload.vpnIp,
                    daemonVpnPort = payload.vpnPort,
                    phoneDeviceId = deviceId
                )

                credentialRepository.setSelectedDevice(result.daemonDeviceId)

                progressTracker.onComplete()
                _state.value = PairingState.Authenticated(result.daemonDeviceId)

                // Zero sensitive data after successful pairing
                cleanup()
            }
            is PairExchangeResult.Timeout -> {
                progressTracker.onFailed()
                _state.value = PairingState.Failed(PairingState.FailureReason.NTFY_TIMEOUT)
                cleanup()
            }
            is PairExchangeResult.AuthFailed -> {
                progressTracker.onFailed()
                _state.value = PairingState.Failed(PairingState.FailureReason.AUTH_FAILED)
                cleanup()
            }
            is PairExchangeResult.Error -> {
                progressTracker.onFailed()
                _state.value = PairingState.Failed(PairingState.FailureReason.SIGNALING_FAILED)
                cleanup()
            }
        }
    }

    private fun cleanup() {
        currentPayload?.masterSecret?.fill(0)
        currentPayload = null
    }

    /**
     * Reset to idle state for retry.
     */
    fun reset() {
        progressTracker.reset()
        cleanup()
        _state.value = PairingState.Idle
    }

    /**
     * Start scanning mode.
     */
    fun startScanning() {
        _state.value = PairingState.Scanning
    }
}
