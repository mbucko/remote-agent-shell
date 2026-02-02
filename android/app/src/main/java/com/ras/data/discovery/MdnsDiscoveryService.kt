package com.ras.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
    val addresses: List<String>
)

/**
 * mDNS service discovery for finding daemons on the local network.
 *
 * Uses Android's NsdManager to discover _ras._tcp services advertised
 * by daemons. This enables fast local discovery (~10-50ms) without
 * needing ntfy.
 *
 * Usage:
 *     val service = MdnsDiscoveryService(context)
 *     val daemon = service.discoverDaemon(timeoutMs = 3000)
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

    /**
     * Discover a daemon on the local network via mDNS.
     *
     * @param deviceId Optional: filter to find a specific daemon by device_id
     * @param timeoutMs Maximum time to wait for discovery (default 3s)
     * @return DiscoveredDaemon if found, null if not found or timeout
     */
    suspend fun discoverDaemon(
        deviceId: String? = null,
        timeoutMs: Long = 3000L
    ): DiscoveredDaemon? {
        Log.d(TAG, "Starting mDNS discovery for _ras._tcp services")

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                var discoveredService: NsdServiceInfo? = null
                var isCompleted = false

                val discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.e(TAG, "Discovery start failed: $errorCode")
                        if (!isCompleted) {
                            isCompleted = true
                            continuation.resume(null)
                        }
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "Discovery stop failed: $errorCode")
                    }

                    override fun onDiscoveryStarted(serviceType: String) {
                        Log.d(TAG, "Discovery started for $serviceType")
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        Log.d(TAG, "Discovery stopped for $serviceType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                        // Keep the first service found
                        if (discoveredService == null) {
                            discoveredService = serviceInfo
                            // Resolve the service to get host/port
                            resolveService(serviceInfo, deviceId) { daemon ->
                                if (!isCompleted && daemon != null) {
                                    isCompleted = true
                                    try {
                                        nsdManager.stopServiceDiscovery(this)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error stopping discovery: ${e.message}")
                                    }
                                    continuation.resume(daemon)
                                }
                            }
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                    }
                }

                continuation.invokeOnCancellation {
                    Log.d(TAG, "Discovery cancelled")
                    try {
                        nsdManager.stopServiceDiscovery(discoveryListener)
                    } catch (e: Exception) {
                        // Ignore - might not have started yet
                    }
                }

                try {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start discovery: ${e.message}")
                    if (!isCompleted) {
                        isCompleted = true
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    /**
     * Start continuous discovery and emit discovered daemons.
     *
     * @return Flow of discovered daemons
     */
    fun discoverDaemonsFlow(): Flow<DiscoveredDaemon> = callbackFlow {
        Log.d(TAG, "Starting continuous mDNS discovery")

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Continuous discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Continuous discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo, null) { daemon ->
                    if (daemon != null) {
                        trySend(daemon)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start continuous discovery: ${e.message}")
            close()
        }

        awaitClose {
            Log.d(TAG, "Stopping continuous discovery")
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery: ${e.message}")
            }
        }
    }

    /**
     * Resolve a service to get its host and port.
     */
    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        expectedDeviceId: String?,
        callback: (DiscoveredDaemon?) -> Unit
    ) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                callback(null)
            }

            @Suppress("DEPRECATION")
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                val port = serviceInfo.port
                val deviceId = serviceInfo.attributes["device_id"]?.toString(Charsets.UTF_8)

                Log.d(TAG, "Service resolved: $host:$port, device_id=$deviceId")

                // If filtering by device_id, check it matches
                if (expectedDeviceId != null && deviceId != null && !deviceId.contains(expectedDeviceId)) {
                    Log.d(TAG, "Device ID mismatch: expected $expectedDeviceId, got $deviceId")
                    callback(null)
                    return
                }

                if (host != null && port > 0) {
                    @Suppress("DEPRECATION")
                    val addresses = serviceInfo.host?.let { inetAddr ->
                        listOfNotNull(inetAddr.hostAddress)
                    } ?: emptyList()

                    callback(DiscoveredDaemon(
                        host = host,
                        port = port,
                        deviceId = deviceId,
                        addresses = addresses
                    ))
                } else {
                    Log.w(TAG, "Invalid service info: host=$host, port=$port")
                    callback(null)
                }
            }
        }

        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service: ${e.message}")
            callback(null)
        }
    }
}
