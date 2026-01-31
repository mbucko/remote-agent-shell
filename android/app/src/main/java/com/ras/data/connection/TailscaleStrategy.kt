package com.ras.data.connection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Connection strategy using direct Tailscale connection.
 *
 * When both devices are on the same Tailscale network, we can connect
 * directly using Tailscale IPs without any NAT traversal complexity.
 *
 * This is the fastest and most reliable option when available.
 */
class TailscaleStrategy @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val socketFactory: DatagramSocketFactory = DefaultDatagramSocketFactory()
) : ConnectionStrategy {

    companion object {
        private const val TAG = "TailscaleStrategy"
        private const val DEFAULT_PORT = 9876
    }

    override val name: String = "Tailscale Direct"
    override val priority: Int = 10  // Try first - fastest if available

    private var detectedInfo: TailscaleInfo? = null

    override suspend fun detect(): DetectionResult {
        detectedInfo = TailscaleDetector.detect(appContext)

        return if (detectedInfo != null) {
            DetectionResult.Available(detectedInfo!!.ip)
        } else {
            DetectionResult.Unavailable("Tailscale not connected")
        }
    }

    override suspend fun connect(
        context: ConnectionContext,
        onProgress: (ConnectionStep) -> Unit
    ): ConnectionResult {
        val localInfo = detectedInfo
            ?: return ConnectionResult.Failed("Tailscale not detected", canRetry = false)

        try {
            // Step 1: Get daemon Tailscale info from context (stored credentials)
            onProgress(ConnectionStep("Checking", "Looking for daemon Tailscale info"))

            val daemonTailscaleIp = context.daemonTailscaleIp
            // Treat port 0 or null as "use default" - port 0 is not a valid server port
            val daemonTailscalePort = context.daemonTailscalePort?.takeIf { it > 0 } ?: DEFAULT_PORT

            if (daemonTailscaleIp == null) {
                Log.i(TAG, "Daemon Tailscale IP not stored in credentials")
                return ConnectionResult.Failed(
                    "Daemon Tailscale IP unknown",
                    canRetry = false
                )
            }

            Log.i(TAG, "Daemon Tailscale from credentials: $daemonTailscaleIp:$daemonTailscalePort")

            // Step 2: Establish direct connection
            onProgress(ConnectionStep(
                "Connecting",
                "Direct connection to $daemonTailscaleIp"
            ))
            Log.d(TAG, "Connecting to Tailscale endpoint...")

            var transport: TailscaleTransport? = null
            try {
                transport = TailscaleTransport.connect(
                    localIp = localInfo.ip,
                    remoteIp = daemonTailscaleIp,
                    remotePort = daemonTailscalePort,
                    socketFactory = socketFactory
                )

                // Step 3: Authenticate
                onProgress(ConnectionStep("Authenticating", "Verifying connection"))
                Log.d(TAG, "Sending authentication...")

                // Send device_id (length-prefixed) + auth token
                // Format: [device_id_len: 4 bytes BE][device_id: N bytes UTF-8][auth: 32 bytes]
                val deviceIdBytes = context.deviceId.toByteArray(Charsets.UTF_8)
                val authMessage = java.nio.ByteBuffer.allocate(4 + deviceIdBytes.size + context.authToken.size)
                    .putInt(deviceIdBytes.size)
                    .put(deviceIdBytes)
                    .put(context.authToken)
                    .array()
                transport.send(authMessage)

                // Wait for auth response
                val response = transport.receive(timeoutMs = 5000)
                if (response.isEmpty() || response[0] != 0x01.toByte()) {
                    transport.close()
                    return ConnectionResult.Failed("Authentication failed", canRetry = false)
                }

                Log.i(TAG, "Tailscale connection established!")
                return ConnectionResult.Success(transport)

            } catch (e: Exception) {
                transport?.close()
                throw e
            }

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
