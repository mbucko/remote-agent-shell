package com.ras.data.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Requests a WiFi network handle via ConnectivityManager.requestNetwork().
 *
 * This grants kernel-level socket binding permission, which is required to
 * bypass VPN routing. The NSD-discovered Network doesn't carry this permission.
 *
 * The returned [WifiNetworkLease] keeps the network request alive. The binding
 * permission is valid until the lease is closed. Callers must close the lease
 * after the socket is connected to release the system network request.
 *
 * Requires CHANGE_NETWORK_STATE permission (normal, auto-granted).
 */
class ConnectivityManagerWifiNetworkProvider(
    private val context: Context
) : WifiNetworkProvider {

    companion object {
        private const val TAG = "WifiNetworkProvider"
        private const val REQUEST_TIMEOUT_MS = 2000
    }

    override suspend fun acquireWifiNetwork(): WifiNetworkLease? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Don't unregister here — the binding permission is revoked on unregister.
                        // Return a lease that the caller closes after socket creation.
                        if (cont.isActive) {
                            val lease = WifiNetworkLease(network) {
                                try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}
                            }
                            cont.resume(lease)
                        }
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }

                cont.invokeOnCancellation {
                    try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
                }

                cm.requestNetwork(request, callback, REQUEST_TIMEOUT_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request WiFi network: ${e.message}")
            null
        }
    }
}
