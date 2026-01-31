package com.ras.data.webrtc

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Detects VPN interfaces and injects their IPs as ICE candidates.
 *
 * Android WebRTC doesn't properly enumerate VPN/TUN interfaces for ICE candidates.
 * This utility detects VPN IPs (like Tailscale) and injects them into the SDP.
 */
object VpnCandidateInjector {
    private const val TAG = "VpnCandidateInjector"

    // Tailscale uses 100.64.0.0/10 (CGNAT range) for its addresses
    // But carriers also use this range, so we need to check interface names
    private val VPN_INTERFACE_PREFIXES = listOf("tun", "tap", "ppp", "tailscale")

    /**
     * Get all VPN interface IPv4 addresses.
     */
    fun getVpnAddresses(): List<String> {
        val vpnAddresses = mutableListOf<String>()

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val name = networkInterface.name.lowercase()

                // Check if this looks like a VPN interface
                val isVpnInterface = VPN_INTERFACE_PREFIXES.any { name.startsWith(it) }

                if (isVpnInterface && networkInterface.isUp) {
                    val addresses = Collections.list(networkInterface.inetAddresses)
                    for (addr in addresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress
                            if (ip != null) {
                                Log.d(TAG, "Found VPN address: $ip on interface $name")
                                vpnAddresses.add(ip)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating network interfaces", e)
        }

        return vpnAddresses
    }

    /**
     * Inject VPN addresses as ICE candidates into an SDP.
     *
     * @param sdp The original SDP
     * @param port The port to use for the candidates (extracted from existing candidates)
     * @return Modified SDP with VPN candidates added
     */
    fun injectVpnCandidates(sdp: String, port: Int? = null): String {
        val vpnAddresses = getVpnAddresses()
        if (vpnAddresses.isEmpty()) {
            Log.d(TAG, "No VPN addresses found, returning original SDP")
            return sdp
        }

        // Find the port from an existing UDP candidate if not provided
        val candidatePort = port ?: extractUdpPort(sdp) ?: run {
            Log.w(TAG, "No UDP port found in SDP, cannot inject VPN candidates")
            return sdp
        }

        // Generate candidate lines for each VPN address
        val newCandidates = vpnAddresses.mapIndexed { index, ip ->
            // Priority: high priority for VPN (should be tried first)
            // foundation: unique ID based on index
            // component: 1 (RTP)
            // priority: 2122063615 is typical for host candidates, we use slightly higher
            val foundation = 900000000 + index
            val priority = 2130706431 // Maximum host priority

            "a=candidate:$foundation 1 udp $priority $ip $candidatePort typ host generation 0"
        }

        if (newCandidates.isEmpty()) {
            return sdp
        }

        Log.i(TAG, "Injecting ${newCandidates.size} VPN candidates: $vpnAddresses")

        // Find where to insert the new candidates (after existing candidates, before m= line ends)
        val lines = sdp.lines().toMutableList()
        val lastCandidateIndex = lines.indexOfLast { it.startsWith("a=candidate:") }

        if (lastCandidateIndex >= 0) {
            // Insert after the last existing candidate
            lines.addAll(lastCandidateIndex + 1, newCandidates)
        } else {
            // No existing candidates, find the media line and add after it
            val mediaIndex = lines.indexOfFirst { it.startsWith("m=") }
            if (mediaIndex >= 0) {
                // Find a good spot after the media line (after c= line if present)
                var insertIndex = mediaIndex + 1
                while (insertIndex < lines.size &&
                       (lines[insertIndex].startsWith("c=") ||
                        lines[insertIndex].startsWith("a=ice-") ||
                        lines[insertIndex].startsWith("a=fingerprint") ||
                        lines[insertIndex].startsWith("a=setup"))) {
                    insertIndex++
                }
                lines.addAll(insertIndex, newCandidates)
            }
        }

        return lines.joinToString("\r\n")
    }

    /**
     * Extract UDP port from existing candidates in SDP.
     */
    private fun extractUdpPort(sdp: String): Int? {
        // Look for UDP candidate line: a=candidate:... udp ... IP PORT ...
        val udpCandidateRegex = Regex("""a=candidate:\S+\s+\d+\s+udp\s+\d+\s+[\d.]+\s+(\d+)""", RegexOption.IGNORE_CASE)
        val match = udpCandidateRegex.find(sdp)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Filter out Tailscale/VPN IP candidates from SDP.
     *
     * Use this when Tailscale isn't available - those IPs won't route
     * properly and just add noise/confusion to ICE negotiation.
     *
     * @param sdp The original SDP
     * @return Modified SDP with Tailscale candidates removed
     */
    fun filterTailscaleCandidates(sdp: String): String {
        val lines = sdp.lines()
        val filtered = lines.filter { line ->
            if (!line.startsWith("a=candidate:")) {
                true // Keep non-candidate lines
            } else {
                // Check if this candidate contains a Tailscale IP (100.64.0.0/10)
                !isTailscaleCandidate(line)
            }
        }

        val removedCount = lines.size - filtered.size
        if (removedCount > 0) {
            Log.i(TAG, "Filtered $removedCount Tailscale candidates from SDP")
        }

        // Preserve original line endings - detect from input
        val lineEnding = if (sdp.contains("\r\n")) "\r\n" else "\n"
        return filtered.joinToString(lineEnding)
    }

    /**
     * Check if a candidate line contains a Tailscale IP (100.64.0.0/10 range).
     */
    private fun isTailscaleCandidate(candidateLine: String): Boolean {
        // Extract IP from candidate line
        // Format: a=candidate:foundation component protocol priority ip port typ type ...
        val parts = candidateLine.split(Regex("\\s+"))
        if (parts.size < 6) return false

        val ip = parts[4]
        return isTailscaleIp(ip)
    }

    /**
     * Check if an IP is in the Tailscale range (100.64.0.0/10).
     * This is CGNAT space that Tailscale uses for its addresses.
     */
    fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        val firstOctet = parts[0].toIntOrNull() ?: return false
        val secondOctet = parts[1].toIntOrNull() ?: return false

        // 100.64.0.0/10 means first octet is 100, second octet is 64-127
        return firstOctet == 100 && secondOctet in 64..127
    }
}
