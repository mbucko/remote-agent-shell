package com.ras.data.connection

/**
 * Represents the connection path between phone and laptop.
 * Contains information about local and remote candidates and the detected path type.
 */
data class ConnectionPath(
    val local: CandidateInfo,
    val remote: CandidateInfo,
    val type: PathType,
    val latencyMs: Long? = null
) {
    /**
     * Human-readable label for the connection type
     */
    val label: String
        get() = when (type) {
            PathType.LAN_DIRECT -> "LAN Direct"
            PathType.WEBRTC_DIRECT -> "WebRTC Direct"
            PathType.TAILSCALE -> "Tailscale VPN"
            PathType.RELAY -> "WebRTC Relay"
        }

    /**
     * Whether to show local IPs in the diagram
     * (hidden for Internet-based connections)
     */
    val showLocalIps: Boolean
        get() = type == PathType.LAN_DIRECT || type == PathType.TAILSCALE
}

/**
 * Enum representing different connection path types
 */
enum class PathType {
    /** Direct LAN connection - both devices on same subnet */
    LAN_DIRECT,

    /** WebRTC direct P2P through NAT traversal */
    WEBRTC_DIRECT,

    /** Connection through Tailscale VPN (WireGuard) */
    TAILSCALE,

    /** Connection through TURN relay server */
    RELAY
}

/**
 * Information about a single ICE candidate
 */
data class CandidateInfo(
    /** Candidate type: "host", "srflx" (server reflexive), "relay" */
    val type: String,

    /** IP address (local, public, or Tailscale) */
    val ip: String,

    /** Port number */
    val port: Int,

    /** Whether this is a local/private IP */
    val isLocal: Boolean
) {
    /**
     * Check if this is a Tailscale IP (100.64.0.0/10 range)
     */
    fun isTailscaleIp(): Boolean {
        return ip.startsWith("100.") && ip.split(".")[1].toIntOrNull()?.let { it in 64..127 } == true
    }

    /**
     * Check if this is a private/local IP (not public routable)
     */
    fun isPrivateIp(): Boolean {
        return when {
            ip.startsWith("10.") -> true
            ip.startsWith("192.168.") -> true
            ip.startsWith("172.") -> {
                val second = ip.split(".")[1].toIntOrNull() ?: 0
                second in 16..31
            }
            else -> false
        }
    }

    /**
     * Check if this is a relay candidate (TURN)
     */
    fun isRelay(): Boolean = type == "relay"

    /**
     * Check if this is a server reflexive candidate (srflx - NAT traversal)
     */
    fun isServerReflexive(): Boolean = type == "srflx"

    /**
     * Check if this is a host candidate (direct local connection)
     */
    fun isHost(): Boolean = type == "host"
}

/**
 * Utility class for classifying connection paths based on candidate pair
 */
object PathClassifier {
    /**
     * Classify the connection path type based on local and remote candidates
     */
    fun classifyPath(local: CandidateInfo, remote: CandidateInfo): PathType {
        // Check for relay first (highest priority - means we're using TURN)
        if (local.isRelay() || remote.isRelay()) {
            return PathType.RELAY
        }

        // Check for Tailscale IPs (100.x.x.x range)
        if (local.isTailscaleIp() || remote.isTailscaleIp()) {
            return PathType.TAILSCALE
        }

        // Check for direct LAN (both host candidates on same subnet)
        if (local.isHost() && remote.isHost()) {
            if (areOnSameSubnet(local.ip, remote.ip)) {
                return PathType.LAN_DIRECT
            }
        }

        // Server reflexive means WebRTC direct through NAT
        if (local.isServerReflexive() || remote.isServerReflexive()) {
            return PathType.WEBRTC_DIRECT
        }

        // Default to WebRTC direct if we have any kind of working connection
        return PathType.WEBRTC_DIRECT
    }

    /**
     * Check if two IPs are on the same /24 subnet
     */
    private fun areOnSameSubnet(ip1: String, ip2: String): Boolean {
        val parts1 = ip1.split(".")
        val parts2 = ip2.split(".")

        // Must both be valid IPv4
        if (parts1.size != 4 || parts2.size != 4) return false

        // Compare first 3 octets (same /24 subnet)
        return parts1[0] == parts2[0] &&
               parts1[1] == parts2[1] &&
               parts1[2] == parts2[2]
    }
}
