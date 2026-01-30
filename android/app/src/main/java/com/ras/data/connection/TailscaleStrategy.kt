package com.ras.data.connection

import android.util.Log
import javax.inject.Inject

/**
 * Connection strategy using direct Tailscale connection.
 *
 * When both devices are on the same Tailscale network, we can connect
 * directly using Tailscale IPs without any NAT traversal complexity.
 *
 * This is the fastest and most reliable option when available.
 */
class TailscaleStrategy @Inject constructor() : ConnectionStrategy {

    companion object {
        private const val TAG = "TailscaleStrategy"
        private const val DEFAULT_PORT = 9876
    }

    override val name: String = "Tailscale Direct"
    override val priority: Int = 10  // Try first - fastest if available

    private var detectedInfo: TailscaleInfo? = null

    override suspend fun detect(): DetectionResult {
        detectedInfo = TailscaleDetector.detect()

        return if (detectedInfo != null) {
            DetectionResult.Available(detectedInfo!!.ip)
        } else {
            DetectionResult.Unavailable("Tailscale not running")
        }
    }

    override suspend fun connect(
        context: ConnectionContext,
        onProgress: (ConnectionStep) -> Unit
    ): ConnectionResult {
        val localInfo = detectedInfo
            ?: return ConnectionResult.Failed("Tailscale not detected", canRetry = false)

        try {
            // Step 1: Exchange Tailscale capabilities
            onProgress(ConnectionStep("Exchanging info", "Checking if daemon has Tailscale"))
            Log.d(TAG, "Sending Tailscale capabilities...")

            val ourCapabilities = ConnectionCapabilities(
                tailscaleIp = localInfo.ip,
                tailscalePort = DEFAULT_PORT,
                supportsWebRTC = true,
                supportsTurn = false
            )

            val daemonCapabilities = context.signaling.sendCapabilities(ourCapabilities)

            if (daemonCapabilities == null) {
                Log.w(TAG, "Daemon did not respond to capabilities request")
                return ConnectionResult.Failed(
                    "Daemon not responding",
                    canRetry = false
                )
            }

            if (daemonCapabilities.tailscaleIp == null) {
                Log.i(TAG, "Daemon is not on Tailscale")
                return ConnectionResult.Failed(
                    "Daemon not on Tailscale network",
                    canRetry = false
                )
            }

            val daemonTailscaleIp = daemonCapabilities.tailscaleIp
            val daemonTailscalePort = daemonCapabilities.tailscalePort ?: DEFAULT_PORT

            Log.i(TAG, "Daemon Tailscale: $daemonTailscaleIp:$daemonTailscalePort")

            // Step 2: Establish direct connection
            onProgress(ConnectionStep(
                "Connecting",
                "Direct connection to $daemonTailscaleIp"
            ))
            Log.d(TAG, "Connecting to Tailscale endpoint...")

            val transport = TailscaleTransport.connect(
                localIp = localInfo.ip,
                remoteIp = daemonTailscaleIp,
                remotePort = daemonTailscalePort
            )

            // Step 3: Authenticate
            onProgress(ConnectionStep("Authenticating", "Verifying connection"))
            Log.d(TAG, "Sending authentication...")

            // Send auth token
            transport.send(context.authToken)

            // Wait for auth response
            val response = transport.receive(timeoutMs = 5000)
            if (response.isEmpty() || response[0] != 0x01.toByte()) {
                transport.close()
                return ConnectionResult.Failed("Authentication failed", canRetry = false)
            }

            Log.i(TAG, "Tailscale connection established!")
            return ConnectionResult.Success(transport)

        } catch (e: Exception) {
            Log.e(TAG, "Tailscale connection failed", e)
            return ConnectionResult.Failed(
                error = e.message ?: "Connection failed",
                exception = e,
                canRetry = false  // Don't retry Tailscale - fall back to WebRTC
            )
        }
    }
}
