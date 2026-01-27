package com.ras.pairing

import android.content.Context
import com.ras.crypto.KeyDerivation
import com.ras.data.keystore.KeyManager
import com.ras.data.webrtc.WebRTCClient
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
    private val webRTCClientFactory: WebRTCClient.Factory
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
        val key = authKey ?: return

        _state.value = PairingState.Signaling

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

        // Send signaling request
        val result = signalingClient.sendSignal(
            ip = payload.ip,
            port = payload.port,
            sessionId = payload.sessionId,
            authKey = key,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        when (result) {
            is SignalingResult.Success -> {
                _state.value = PairingState.Connecting
                performConnection(result.sdpAnswer)
            }
            is SignalingResult.Error -> {
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
