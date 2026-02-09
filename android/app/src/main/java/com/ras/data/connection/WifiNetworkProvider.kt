package com.ras.data.connection

import android.net.Network

/**
 * Provides a WiFi network handle with socket binding permission.
 *
 * Used to bypass VPN routing for LAN Direct connections. When a VPN is active,
 * sockets must be explicitly bound to the WiFi network interface. The Network
 * from NSD discovery doesn't carry binding permission — only networks obtained
 * through ConnectivityManager.requestNetwork() have kernel-level permission.
 */
fun interface WifiNetworkProvider {
    suspend fun getWifiNetwork(): Network?
}
