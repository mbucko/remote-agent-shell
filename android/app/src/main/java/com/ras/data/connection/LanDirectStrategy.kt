package com.ras.data.connection

import android.content.Context
import android.util.Log
import com.ras.data.discovery.DiscoveredDaemon
import com.ras.data.discovery.MdnsDiscoveryService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Connection strategy using direct WebSocket over LAN.
 *
 * When both devices are on the same local network (detected via mDNS),
 * we can connect directly via WebSocket without WebRTC overhead.
 *
 * This is the fastest option when available (~100ms vs ~5s for WebRTC).
 *
 * Priority: 5 (highest - try before Tailscale and WebRTC)
 *
 * Detection:
 * - Uses mDNS to discover daemon on local network
 * - Caches discovery result between detect() and connect()
 *
 * Authentication uses HMAC-SHA256 with derived auth key.
 */
class LanDirectStrategy @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val mdnsService: MdnsDiscoveryService,
    private val okHttpClient: OkHttpClient
) : ConnectionStrategy {

    companion object {
        private const val TAG = "LanDirectStrategy"
        private const val DEFAULT_PORT = 8765  // Default daemon HTTP port
        private const val MDNS_DETECT_TIMEOUT_MS = 1000L
        private const val MDNS_CONNECT_TIMEOUT_MS = 500L  // Shorter - should be cached
    }

    override val name: String = "LAN Direct"
    override val priority: Int = 5  // Highest priority - fastest when available

    // Cache mDNS result between detect() and connect() to avoid race condition
    private var cachedDaemon: DiscoveredDaemon? = null

    override suspend fun detect(): DetectionResult {
        // Query mDNS for daemon on local network
        cachedDaemon = try {
            mdnsService.getDiscoveredDaemon(timeoutMs = MDNS_DETECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS detection error: ${e.message}")
            null
        }

        return if (cachedDaemon != null) {
            Log.i(TAG, "Daemon found via mDNS: ${cachedDaemon!!.host}:${cachedDaemon!!.port}")
            DetectionResult.Available("${cachedDaemon!!.host}:${cachedDaemon!!.port}")
        } else {
            DetectionResult.Unavailable("Daemon not on local network")
        }
    }

    override suspend fun connect(
        context: ConnectionContext,
        onProgress: (ConnectionStep) -> Unit
    ): ConnectionResult {
        // Use cached daemon from detect(), with fallback query
        val daemon = cachedDaemon
            ?: mdnsService.getDiscoveredDaemon(timeoutMs = MDNS_CONNECT_TIMEOUT_MS)
            ?: return ConnectionResult.Failed(
                error = "Daemon no longer on local network",
                canRetry = false
            )

        try {
            // Step 1: Build WebSocket URL
            onProgress(ConnectionStep("Connecting", "Opening WebSocket to ${daemon.host}"))
            val wsUrl = "ws://${daemon.host}:${daemon.port}/ws/${context.deviceId}"
            Log.i(TAG, "Connecting to $wsUrl")

            // Step 2: Connect and authenticate
            onProgress(ConnectionStep("Authenticating", "Verifying connection"))
            val transport = LanDirectTransport.connect(
                host = daemon.host,
                port = daemon.port,
                deviceId = context.deviceId,
                authKey = context.authToken,
                client = okHttpClient
            )

            Log.i(TAG, "LAN Direct connection established!")
            return ConnectionResult.Success(transport)

        } catch (e: CancellationException) {
            // CRITICAL: Never swallow CancellationException
            throw e
        } catch (e: LanDirectAuthException) {
            Log.w(TAG, "LAN Direct auth failed: ${e.message}")
            return ConnectionResult.Failed(
                error = "Authentication failed",
                exception = e,
                canRetry = false  // Don't retry auth failures
            )
        } catch (e: Exception) {
            Log.e(TAG, "LAN Direct connection failed", e)
            return ConnectionResult.Failed(
                error = e.message ?: "Connection failed",
                exception = e,
                canRetry = false  // Fall back to next strategy
            )
        } finally {
            // Clear cache after connect attempt
            cachedDaemon = null
        }
    }
}

/**
 * Exception thrown when LAN Direct authentication fails.
 */
class LanDirectAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
