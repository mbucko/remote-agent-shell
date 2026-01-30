package com.ras.data.connection

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Detects Tailscale VPN interface and extracts connection info.
 */
object TailscaleDetector {
    private const val TAG = "TailscaleDetector"

    // Tailscale uses 100.64.0.0/10 (CGNAT range) for its mesh network
    // Interface names vary by platform
    private val VPN_INTERFACE_NAMES = setOf("tun0", "tun1", "tun", "tailscale0")

    // Tailscale IP range: 100.64.0.0 - 100.127.255.255 (100.64.0.0/10)
    private fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val firstOctet = parts[0].toIntOrNull() ?: return false
        val secondOctet = parts[1].toIntOrNull() ?: return false

        // 100.64.0.0/10 means first octet is 100, second octet is 64-127
        return firstOctet == 100 && secondOctet in 64..127
    }

    /**
     * Detect if Tailscale is running and get the Tailscale IP.
     *
     * @return TailscaleInfo if detected, null otherwise
     */
    fun detect(): TailscaleInfo? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            for (networkInterface in interfaces) {
                val name = networkInterface.name.lowercase()

                // Check both by interface name and by IP range
                val addresses = Collections.list(networkInterface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }

                // Check if this interface has a Tailscale IP
                for (ip in addresses) {
                    if (isTailscaleIp(ip)) {
                        Log.i(TAG, "Detected Tailscale: $ip on interface $name")
                        return TailscaleInfo(
                            ip = ip,
                            interfaceName = name
                        )
                    }
                }

                // Also check by interface name for edge cases
                if (VPN_INTERFACE_NAMES.contains(name)) {
                    for (ip in addresses) {
                        Log.i(TAG, "Detected VPN interface $name with IP $ip")
                        // Even if not in Tailscale range, might still be usable
                        if (ip.startsWith("100.")) {
                            return TailscaleInfo(
                                ip = ip,
                                interfaceName = name
                            )
                        }
                    }
                }
            }

            Log.d(TAG, "Tailscale not detected")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting Tailscale", e)
            return null
        }
    }

    /**
     * Get all VPN interface IPs (for logging/debugging).
     */
    fun getAllVpnIps(): List<String> {
        val vpnIps = mutableListOf<String>()

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }

                for (ip in addresses) {
                    if (isTailscaleIp(ip)) {
                        vpnIps.add(ip)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting VPN IPs", e)
        }

        return vpnIps
    }
}

/**
 * Information about a detected Tailscale connection.
 */
data class TailscaleInfo(
    val ip: String,
    val interfaceName: String
)
