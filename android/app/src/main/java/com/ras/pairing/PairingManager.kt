package com.ras.pairing

import android.content.Context
import com.ras.crypto.KeyDerivation
import com.ras.data.keystore.KeyManager
import com.ras.data.webrtc.WebRTCClient
import com.ras.signaling.NtfyClientInterface
import com.ras.signaling.NtfySignalingClient
import com.ras.signaling.PairingSignaler
import com.ras.signaling.PairingSignalerResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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
    @ApplicationContext private val context: Context,
    private val signalingClient: SignalingClient,
    private val keyManager: KeyManager,
    private val webRTCClientFactory: WebRTCClient.Factory,
    private val ntfyClient: NtfyClientInterface
) {
    // Internal scope that can be overridden for testing
    // Using lazy initialization to allow tests to override before first use
    private var _scope: CoroutineScope? = null
    internal var scope: CoroutineScope
        get() = _scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { _scope = it }
        set(value) { _scope = value }

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    private var currentPayload: ParsedQrPayload? = null
    private var authKey: ByteArray? = null
    private var webRTCClient: WebRTCClient? = null

    /**
     * Start pairing with scanned QR payload.
     */
    fun startPairing(payload: ParsedQrPayload) {
        currentPayload = payload
        authKey = KeyDerivation.deriveKey(payload.masterSecret, "auth")

        _state.value = PairingState.QrParsed(payload)

        scope.launch {
            performSignaling()
        }
    }

    private suspend fun performSignaling() {
        val payload = currentPayload ?: return

        _state.value = PairingState.TryingDirect

        // Create WebRTC client and generate offer
        val client = webRTCClientFactory.create()
        webRTCClient = client

        val sdpOffer = try {
            client.createOffer()
        } catch (e: Exception) {
            _state.value = PairingState.Failed(PairingState.FailureReason.CONNECTION_FAILED)
            cleanup()
            return
        }

        // Get device info
        val deviceId = keyManager.getOrCreateDeviceId()
        val deviceName = android.os.Build.MODEL ?: "Unknown Device"

        // Create PairingSignaler that handles direct HTTP and ntfy fallback
        val pairingSignaler = PairingSignaler(
            directClient = signalingClient,
            ntfyClient = ntfyClient
        )

        // Exchange SDP using direct HTTP or ntfy fallback
        val result = pairingSignaler.exchangeSdp(
            ip = payload.ip,
            port = payload.port,
            sessionId = payload.sessionId,
            masterSecret = payload.masterSecret,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        when (result) {
            is PairingSignalerResult.Success -> {
                // Update state based on which path was used
                if (result.usedNtfyPath) {
                    _state.value = PairingState.NtfyWaitingForAnswer
                } else {
                    _state.value = PairingState.DirectSignaling
                }
                _state.value = PairingState.Connecting
                performConnection(result.sdpAnswer)
            }
            is PairingSignalerResult.NtfyTimeout -> {
                _state.value = PairingState.Failed(PairingState.FailureReason.NTFY_TIMEOUT)
                cleanup()
            }
            is PairingSignalerResult.Error -> {
                _state.value = PairingState.Failed(PairingState.FailureReason.SIGNALING_FAILED)
                cleanup()
            }
        }
    }

    private suspend fun performConnection(sdpAnswer: String) {
        val client = webRTCClient ?: return

        try {
            // Set remote description
            client.setRemoteDescription(sdpAnswer)

            // Wait for data channel to open
            val channelOpened = client.waitForDataChannel(timeoutMs = 30_000)

            if (!channelOpened) {
                _state.value = PairingState.Failed(PairingState.FailureReason.CONNECTION_FAILED)
                cleanup()
                return
            }

            _state.value = PairingState.Authenticating
            performAuthentication()
        } catch (e: Exception) {
            _state.value = PairingState.Failed(PairingState.FailureReason.CONNECTION_FAILED)
            cleanup()
        }
    }

    private suspend fun performAuthentication() {
        val client = webRTCClient ?: return
        val key = authKey ?: return

        val authClient = AuthClient(key)

        val result = authClient.runHandshake(
            sendMessage = { data -> client.send(data) },
            receiveMessage = { client.receive() }
        )

        when (result) {
            is AuthResult.Success -> {
                // Save master secret and daemon info to keystore
                currentPayload?.let { payload ->
                    keyManager.storeMasterSecret(payload.masterSecret)
                    keyManager.storeDaemonInfo(payload.ip, payload.port, payload.ntfyTopic)
                }

                _state.value = PairingState.Authenticated(result.deviceId)
            }
            is AuthResult.Error -> {
                _state.value = PairingState.Failed(PairingState.FailureReason.AUTH_FAILED)
                cleanup()
            }
            AuthResult.Timeout -> {
                _state.value = PairingState.Failed(PairingState.FailureReason.TIMEOUT)
                cleanup()
            }
        }
    }

    private fun cleanup() {
        webRTCClient?.close()
        webRTCClient = null

        // Zero sensitive data
        authKey?.fill(0)
        authKey = null

        currentPayload?.masterSecret?.fill(0)
        currentPayload = null
    }

    /**
     * Reset to idle state for retry.
     */
    fun reset() {
        cleanup()
        _state.value = PairingState.Idle
    }

    /**
     * Start scanning mode.
     */
    fun startScanning() {
        _state.value = PairingState.Scanning
    }

    /**
     * Get the current WebRTC client (for maintaining connection after pairing).
     */
    fun getWebRTCClient(): WebRTCClient? = webRTCClient
}
