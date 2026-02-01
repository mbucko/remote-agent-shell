package com.ras.data.credentials

/**
 * Stored credentials from a previous pairing session.
 * Used for automatic reconnection on app startup.
 *
 * The daemonHost and daemonPort are optional hints - the actual
 * daemon can be discovered via mDNS or ntfy DISCOVER at reconnection time.
 */
data class StoredCredentials(
    val deviceId: String,
    val masterSecret: ByteArray,
    val daemonHost: String? = null,  // Optional - can use mDNS/ntfy discovery
    val daemonPort: Int? = null,     // Optional - can use mDNS/ntfy discovery
    val ntfyTopic: String,
    val daemonTailscaleIp: String? = null,
    val daemonTailscalePort: Int? = null,
    val daemonVpnIp: String? = null,
    val daemonVpnPort: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StoredCredentials

        if (deviceId != other.deviceId) return false
        if (!masterSecret.contentEquals(other.masterSecret)) return false
        if (daemonHost != other.daemonHost) return false
        if (daemonPort != other.daemonPort) return false
        if (ntfyTopic != other.ntfyTopic) return false
        if (daemonTailscaleIp != other.daemonTailscaleIp) return false
        if (daemonTailscalePort != other.daemonTailscalePort) return false
        if (daemonVpnIp != other.daemonVpnIp) return false
        if (daemonVpnPort != other.daemonVpnPort) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + masterSecret.contentHashCode()
        result = 31 * result + (daemonHost?.hashCode() ?: 0)
        result = 31 * result + (daemonPort ?: 0)
        result = 31 * result + ntfyTopic.hashCode()
        result = 31 * result + (daemonTailscaleIp?.hashCode() ?: 0)
        result = 31 * result + (daemonTailscalePort ?: 0)
        result = 31 * result + (daemonVpnIp?.hashCode() ?: 0)
        result = 31 * result + (daemonVpnPort ?: 0)
        return result
    }

    /**
     * Check if this credentials has any direct IP (LAN, VPN, or Tailscale).
     * If false, discovery via mDNS or ntfy DISCOVER is required.
     */
    fun hasDirectIp(): Boolean = daemonHost != null || daemonVpnIp != null || daemonTailscaleIp != null
}
