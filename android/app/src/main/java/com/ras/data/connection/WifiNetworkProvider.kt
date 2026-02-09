package com.ras.data.connection

import android.net.Network
import java.io.Closeable

/**
 * A lease on a WiFi network obtained via ConnectivityManager.requestNetwork().
 *
 * The network binding permission remains active until [close] is called.
 * Callers must hold this lease open for the duration of socket creation,
 * then close it to release the system network request.
 */
class WifiNetworkLease(
    val network: Network,
    private val onRelease: () -> Unit
) : Closeable {
    override fun close() = onRelease()
}

/**
 * Acquires a WiFi network handle with socket binding permission.
 *
 * Used to bypass VPN routing for LAN Direct connections. When a VPN is active,
 * sockets must be explicitly bound to the WiFi network interface. The Network
 * from NSD discovery doesn't carry binding permission — only networks obtained
 * through ConnectivityManager.requestNetwork() have kernel-level permission.
 *
 * Returns a [WifiNetworkLease] that holds the permission alive. The caller
 * must close the lease after the socket is connected.
 */
fun interface WifiNetworkProvider {
    suspend fun acquireWifiNetwork(): WifiNetworkLease?
}
