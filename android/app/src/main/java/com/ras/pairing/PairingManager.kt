package com.ras.pairing

import android.content.Context
import com.ras.crypto.KeyDerivation
import com.ras.data.connection.ConnectionManager
import com.ras.data.keystore.KeyManager
import com.ras.data.webrtc.ConnectionOwnership
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
    private val ntfyClient: NtfyClientInterface,
    private val connectionManager: ConnectionManager
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

    // Connection lifecycle state machine
    @Volatile
    private var connectionState: PairingConnectionState = PairingConnectionState.Closed

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
        connectionState = PairingConnectionState.Creating
        val client = webRTCClientFactory.create()
        webRTCClient = client
        connectionState = PairingConnectionState.Signaling

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

        connectionState = PairingConnectionState.Connecting

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
            connectionState = PairingConnectionState.Authenticating
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

                // Hand off WebRTC connection to ConnectionManager with encryption key
                // IMPORTANT: This is a critical section - we must complete the handoff
                // before returning, so ConnectionManager has a fully working connection
                val client = webRTCClient
                val key = authKey

                if (client != null && key != null) {
                    // Transfer ownership BEFORE passing to ConnectionManager
                    // This ensures cleanup() won't close it even if called concurrently
                    client.transferOwnership(ConnectionOwnership.ConnectionManager)
                    connectionState = PairingConnectionState.HandedOff("ConnectionManager")
                    webRTCClient = null  // Clear reference BEFORE calling connect

                    try {
                        // AWAIT the connection setup - this sends ConnectionReady synchronously
                        connectionManager.connect(client, key)
                        android.util.Log.i("PairingManager", "Handoff to ConnectionManager complete")
                    } catch (e: Exception) {
                        // Handoff failed - connection is broken
                        android.util.Log.e("PairingManager", "Handoff failed: ${e.message}")
                        _state.value = PairingState.Failed(PairingState.FailureReason.CONNECTION_FAILED)
                        return
                    }
                } else {
                    android.util.Log.e("PairingManager", "No client or key available for handoff")
                    _state.value = PairingState.Failed(PairingState.FailureReason.CONNECTION_FAILED)
                    return
                }

                // Zero auth key after successful handoff (ConnectionManager has its own copy)
                authKey?.fill(0)
                authKey = null

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
        // Only close if we still own the connection (not handed off)
        if (connectionState.shouldCloseOnCleanup()) {
            webRTCClient?.closeByOwner(ConnectionOwnership.PairingManager)
        }
        webRTCClient = null
        connectionState = PairingConnectionState.Closed

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
