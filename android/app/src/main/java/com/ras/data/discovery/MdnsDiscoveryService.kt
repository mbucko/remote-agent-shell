package com.ras.data.discovery

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service type for RAS daemon discovery.
 */
private const val SERVICE_TYPE = "_ras._tcp."
private const val TAG = "MdnsDiscoveryService"

/**
 * Discovered daemon info from mDNS.
 */
data class DiscoveredDaemon(
    val host: String,
    val port: Int,
    val deviceId: String?,
    val addresses: List<String>,
    val network: Network? = null
)

/**
 * mDNS service discovery for finding daemons on the local network.
 *
 * Uses Android's NsdManager to discover _ras._tcp services advertised
 * by daemons. This enables fast local discovery (~10-50ms) without
 * needing ntfy.
 *
 * This service uses continuous discovery (industry standard approach) to avoid
 * NsdManager caching issues. The discovery runs continuously and maintains
 * a state flow of discovered services. Query the current state instead of
 * starting/stopping discovery for each connection attempt.
 *
 * Usage:
 *     val service = MdnsDiscoveryService(context)
 *     val daemon = service.getDiscoveredDaemon(timeoutMs = 3000)
 *     if (daemon != null) {
 *         // Found daemon at daemon.host:daemon.port
 *     }
 */
class MdnsDiscoveryService(
    private val context: Context
) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    // Continuous discovery state - maintains list of discovered daemons
    private val _discoveredDaemons = MutableStateFlow<List<DiscoveredDaemon>>(emptyList())
    val discoveredDaemons: StateFlow<List<DiscoveredDaemon>> = _discoveredDaemons

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscoveryActive = false

    companion object {
        // Timeout for waiting for a daemon to appear in the discovered list
        private const val DISCOVERY_TIMEOUT_MS = 3000L
    }

    init {
        // Start continuous discovery immediately
        startContinuousDiscovery()
    }

    /**
     * Get a discovered daemon, optionally filtered by device ID.
     * Waits up to timeoutMs for a daemon to be discovered.
     *
     * This queries the continuous discovery state rather than starting
     * a new discovery, avoiding NsdManager caching issues.
     *
     * @param deviceId Optional: filter to find a specific daemon by device_id
     * @param timeoutMs Maximum time to wait for discovery (default 3s)
     * @return DiscoveredDaemon if found, null if not found or timeout
     */
    suspend fun getDiscoveredDaemon(
        deviceId: String? = null,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS
    ): DiscoveredDaemon? {
        Log.d(TAG, "Querying discovered daemons (timeout: ${timeoutMs}ms)")

        return withTimeoutOrNull(timeoutMs) {
            // Wait for at least one daemon to be discovered
            _discoveredDaemons.first { daemons ->
                daemons.isNotEmpty() && (deviceId == null || daemons.any { it.deviceId == deviceId })
            }.firstOrNull { deviceId == null || it.deviceId == deviceId }
        }.also { result ->
            if (result != null) {
                Log.i(TAG, "Found daemon at ${result.host}:${result.port}")
            } else {
                Log.w(TAG, "No daemon found (timeout)")
            }
        }
    }

    /**
     * Start continuous mDNS discovery.
     * This runs continuously and updates the discoveredDaemons flow.
     * Called once during initialization.
     */
    private fun startContinuousDiscovery() {
        if (isDiscoveryActive) {
            Log.d(TAG, "Discovery already active")
            return
        }

        Log.d(TAG, "Starting continuous mDNS discovery")
        isDiscoveryActive = true

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                isDiscoveryActive = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Continuous discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Continuous discovery stopped")
                isDiscoveryActive = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo) { daemon ->
                    daemon?.let { addDiscoveredDaemon(it) }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                removeDiscoveredDaemon(serviceInfo.serviceName)
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            isDiscoveryActive = false
        }
    }

    /**
     * Stop continuous discovery. Call this when the service is destroyed.
     */
    fun stopDiscovery() {
        if (!isDiscoveryActive) return

        Log.d(TAG, "Stopping continuous discovery")
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        isDiscoveryActive = false
        _discoveredDaemons.value = emptyList()
    }

    /**
     * Add a discovered daemon to the list.
     */
    private fun addDiscoveredDaemon(daemon: DiscoveredDaemon) {
        val currentList = _discoveredDaemons.value.toMutableList()
        // Remove any existing entry for this device to avoid duplicates
        currentList.removeAll { it.deviceId == daemon.deviceId }
        currentList.add(daemon)
        _discoveredDaemons.value = currentList
        Log.d(TAG, "Added daemon to list: ${daemon.host}:${daemon.port}")
    }

    /**
     * Remove a daemon from the list when service is lost.
     */
    private fun removeDiscoveredDaemon(serviceName: String) {
        val currentList = _discoveredDaemons.value.toMutableList()
        currentList.removeAll { it.deviceId == null || serviceName.contains(it.deviceId) }
        _discoveredDaemons.value = currentList
        Log.d(TAG, "Removed daemon from list: $serviceName")
    }

    /**
     * Resolve a service to get host and port information.
     */
    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        callback: (DiscoveredDaemon?) -> Unit
    ) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                callback(null)
            }

            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${resolvedService.serviceName} at ${resolvedService.host}:${resolvedService.port}")

                // Extract device ID from service name if present
                val deviceId = extractDeviceId(resolvedService.serviceName)

                val addresses = mutableListOf<String>()
                resolvedService.host?.hostAddress?.let { addresses.add(it) }

                // NsdServiceInfo.getNetwork() requires API 33+
                val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    resolvedService.network
                } else {
                    null
                }

                val daemon = DiscoveredDaemon(
                    host = resolvedService.host?.hostAddress ?: return callback(null),
                    port = resolvedService.port,
                    deviceId = deviceId,
                    addresses = addresses,
                    network = network
                )
                callback(daemon)
            }
        })
    }

    /**
     * Extract device ID from service name.
     * Service names are typically "daemon_<deviceId>._ras._tcp"
     */
    private fun extractDeviceId(serviceName: String): String? {
        return when {
            serviceName.startsWith("daemon_") -> {
                serviceName.removePrefix("daemon_").substringBefore(".")
            }
            else -> null
        }
    }
}
