package com.ras.data.connection

import android.util.Log
import com.ras.data.webrtc.VpnCandidateInjector
import com.ras.data.webrtc.WebRTCClient
import com.ras.util.GlobalConnectionTimer
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
            GlobalConnectionTimer.logMark("webrtc_create_offer_start")
            Log.d(TAG, "Creating WebRTC offer...")

            webRTCClient = webRTCClientFactory.create()
            var offer = webRTCClient.createOffer()
            GlobalConnectionTimer.logMark("offer_created")
            Log.d(TAG, "Offer created with ${countCandidates(offer)} candidates")

            // Filter out Tailscale candidates if local Tailscale isn't available
            // (those IPs won't route properly and just add noise)
            if (!context.localTailscaleAvailable) {
                offer = VpnCandidateInjector.filterTailscaleCandidates(offer)
                Log.d(TAG, "After filtering: ${countCandidates(offer)} candidates")
            }

            // Step 2: Send offer via signaling
            onProgress(ConnectionStep("Signaling", "Sending offer to daemon"))
            GlobalConnectionTimer.logMark("signaling_start")
            Log.d(TAG, "Sending offer via signaling...")

            val answer = context.signaling.sendOffer(offer, context.signalingProgress)
            if (answer == null) {
                Log.e(TAG, "No answer received from daemon")
                webRTCClient.close()
                return ConnectionResult.Failed("No response from daemon", canRetry = true)
            }
            GlobalConnectionTimer.logMark("answer_received")
            Log.d(TAG, "Received answer with ${countCandidates(answer)} candidates")

            // Step 3: Set remote description and start ICE
            onProgress(ConnectionStep("ICE negotiation", "Establishing peer connection"))
            GlobalConnectionTimer.logMark("set_remote_desc_start")
            Log.d(TAG, "Setting remote description...")

            webRTCClient.setRemoteDescription(answer)
            GlobalConnectionTimer.logMark("remote_desc_set")

            // Step 4: Wait for data channel
            onProgress(ConnectionStep("Connecting", "Waiting for data channel"))
            GlobalConnectionTimer.logMark("waiting_for_channel")
            Log.d(TAG, "Waiting for data channel...")

            val connected = webRTCClient.waitForDataChannel(DATA_CHANNEL_TIMEOUT_MS)
            if (!connected) {
                GlobalConnectionTimer.logMark("channel_timeout")
                Log.e(TAG, "Data channel failed to open")
                webRTCClient.close()
                return ConnectionResult.Failed("ICE connection failed", canRetry = true)
            }

            GlobalConnectionTimer.logMark("webrtc_connected")
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
