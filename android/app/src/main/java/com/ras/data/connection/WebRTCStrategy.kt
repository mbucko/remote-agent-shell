package com.ras.data.connection

import android.util.Log
import com.ras.data.webrtc.WebRTCClient
import javax.inject.Inject

/**
 * Connection strategy using WebRTC for NAT traversal.
 *
 * This is the standard P2P connection method that works in most cases.
 * Uses ICE (STUN) for NAT traversal.
 */
class WebRTCStrategy @Inject constructor(
    private val webRTCClientFactory: WebRTCClient.Factory
) : ConnectionStrategy {

    companion object {
        private const val TAG = "WebRTCStrategy"
        private const val DATA_CHANNEL_TIMEOUT_MS = 30_000L
    }

    override val name: String = "WebRTC P2P"
    override val priority: Int = 20  // Standard priority

    override suspend fun detect(): DetectionResult {
        // WebRTC is always available as a fallback
        return DetectionResult.Available("Standard P2P connection")
    }

    override suspend fun connect(
        context: ConnectionContext,
        onProgress: (ConnectionStep) -> Unit
    ): ConnectionResult {
        var webRTCClient: WebRTCClient? = null

        try {
            // Step 1: Create WebRTC client and offer
            onProgress(ConnectionStep("Creating offer", "Gathering ICE candidates"))
            Log.d(TAG, "Creating WebRTC offer...")

            webRTCClient = webRTCClientFactory.create()
            val offer = webRTCClient.createOffer()
            Log.d(TAG, "Offer created with ${countCandidates(offer)} candidates")

            // Step 2: Send offer via signaling
            onProgress(ConnectionStep("Signaling", "Sending offer to daemon"))
            Log.d(TAG, "Sending offer via signaling...")

            val answer = context.signaling.sendOffer(offer, context.signalingProgress)
            if (answer == null) {
                Log.e(TAG, "No answer received from daemon")
                webRTCClient.close()
                return ConnectionResult.Failed("No response from daemon", canRetry = true)
            }
            Log.d(TAG, "Received answer with ${countCandidates(answer)} candidates")

            // Step 3: Set remote description and start ICE
            onProgress(ConnectionStep("ICE negotiation", "Establishing peer connection"))
            Log.d(TAG, "Setting remote description...")

            webRTCClient.setRemoteDescription(answer)

            // Step 4: Wait for data channel
            onProgress(ConnectionStep("Connecting", "Waiting for data channel"))
            Log.d(TAG, "Waiting for data channel...")

            val connected = webRTCClient.waitForDataChannel(DATA_CHANNEL_TIMEOUT_MS)
            if (!connected) {
                Log.e(TAG, "Data channel failed to open")
                webRTCClient.close()
                return ConnectionResult.Failed("ICE connection failed", canRetry = true)
            }

            Log.i(TAG, "WebRTC connected successfully!")
            return ConnectionResult.Success(WebRTCTransport(webRTCClient))

        } catch (e: Exception) {
            Log.e(TAG, "WebRTC connection failed", e)
            webRTCClient?.close()
            return ConnectionResult.Failed(
                error = e.message ?: "WebRTC error",
                exception = e,
                canRetry = true
            )
        }
    }

    private fun countCandidates(sdp: String): Int {
        return sdp.lines().count { it.startsWith("a=candidate:") }
    }
}
