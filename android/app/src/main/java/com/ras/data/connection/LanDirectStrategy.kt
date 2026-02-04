package com.ras.data.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Connection strategy using direct WebSocket over LAN.
 *
 * This is the fastest connection option when both devices are on the
 * same local network. It uses WebSocket upgrade on the daemon's existing
 * HTTP port, requiring no additional configuration.
 *
 * Priority: 5 (highest - try before Tailscale and WebRTC)
 *
 * Detection checks:
 * - daemonHost and daemonPort are provided (from QR code or mDNS)
 * - Device has WiFi or Ethernet connectivity
 *
 * Authentication uses HMAC-SHA256 with derived auth key.
 */
class LanDirectStrategy @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient = defaultClient()
) : ConnectionStrategy {

    companion object {
        private const val TAG = "LanDirectStrategy"
        private const val DEFAULT_PORT = 8765  // Default daemon HTTP port

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()
    }

    override val name: String = "LAN Direct"
    override val priority: Int = 5  // Highest priority - fastest when available

    private var detectedHost: String? = null
    private var detectedPort: Int? = null

    override suspend fun detect(): DetectionResult {
        // Check if we have LAN connectivity (WiFi or Ethernet)
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val hasLanConnectivity = capabilities?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } ?: false

        if (!hasLanConnectivity) {
            Log.d(TAG, "No LAN connectivity (WiFi/Ethernet)")
            return DetectionResult.Unavailable("No WiFi or Ethernet connection")
        }

        // LAN Direct requires daemon host/port from context
        // This will be checked in connect() - detection just checks network availability
        Log.d(TAG, "LAN connectivity available")
        return DetectionResult.Available("WiFi/Ethernet connected")
    }

    override suspend fun connect(
        context: ConnectionContext,
        onProgress: (ConnectionStep) -> Unit
    ): ConnectionResult {
        // Get daemon host/port from context
        val host = context.daemonHost
        val port = context.daemonPort ?: DEFAULT_PORT

        if (host == null) {
            Log.d(TAG, "Daemon host not provided in context")
            return ConnectionResult.Failed(
                "Daemon LAN address unknown",
                canRetry = false
            )
        }

        // Treat port 0 as "use default"
        val effectivePort = if (port > 0) port else DEFAULT_PORT

        Log.i(TAG, "Attempting LAN Direct connection to $host:$effectivePort")

        try {
            // Step 1: Check connectivity
            onProgress(ConnectionStep("Checking", "Verifying LAN connectivity"))

            // Step 2: Connect via WebSocket
            onProgress(ConnectionStep(
                "Connecting",
                "WebSocket to $host:$effectivePort"
            ))

            val transport = LanDirectTransport.connect(
                host = host,
                port = effectivePort,
                deviceId = context.deviceId,
                masterSecret = context.authToken,
                client = okHttpClient
            )

            Log.i(TAG, "LAN Direct connection established!")
            return ConnectionResult.Success(transport)

        } catch (e: Exception) {
            Log.e(TAG, "LAN Direct connection failed", e)
            return ConnectionResult.Failed(
                error = e.message ?: "Connection failed",
                exception = e,
                canRetry = true  // Allow retry - network conditions may change
            )
        }
    }
}
