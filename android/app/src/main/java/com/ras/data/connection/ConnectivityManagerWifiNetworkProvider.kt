package com.ras.data.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Requests a WiFi network handle via ConnectivityManager.requestNetwork().
 *
 * This grants kernel-level socket binding permission, which is required to
 * bypass VPN routing. The NSD-discovered Network doesn't carry this permission.
 *
 * Uses the persistent variant of requestNetwork() (no timeout parameter) because
 * the timeout variant is one-shot: the system auto-releases the network request
 * after onAvailable, revoking binding permission before OkHttp creates the socket.
 * Timeout is handled via coroutine withTimeoutOrNull instead.
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
        private const val REQUEST_TIMEOUT_MS = 2000L
    }

    override suspend fun acquireWifiNetwork(): WifiNetworkLease? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()

                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (cont.isActive) {
                                val lease = WifiNetworkLease(network) {
                                    try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}
                                }
                                cont.resume(lease)
                            }
                        }
                    }

                    cont.invokeOnCancellation {
                        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
                    }

                    // Use persistent variant — the timeout variant (3-arg) auto-releases
                    // the network after onAvailable, revoking binding permission.
                    cm.requestNetwork(request, callback)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request WiFi network: ${e.message}")
            null
        }
    }
}
