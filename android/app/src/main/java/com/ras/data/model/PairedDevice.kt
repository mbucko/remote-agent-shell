package com.ras.data.model

import java.time.Instant

/**
 * Domain model for a paired device.
 *
 * Represents a computer/daemon that the phone has paired with.
 * Each device has its own master_secret and connection info.
 */
data class PairedDevice(
    /** Unique device identifier (derived from master_secret) */
    val deviceId: String,

    /** Master secret for authentication (decrypted) */
    val masterSecret: ByteArray,

    /** Device hostname or user-friendly name */
    val deviceName: String,

    /** Type of device (laptop, desktop, server) */
    val deviceType: DeviceType,

    /** Current status of the device */
    val status: DeviceStatus,

    /** Whether this is the currently selected device for auto-connect */
    val isSelected: Boolean,

    /** When the device was initially paired */
    val pairedAt: Instant,

    /** Last successful connection timestamp */
    val lastConnectedAt: Instant? = null,

    // Optional connection hints (discovered via mDNS/ntfy)
    /** Daemon IP address (local network) */
    val daemonHost: String? = null,

    /** Daemon port (local network) */
    val daemonPort: Int? = null,

    /** Daemon Tailscale IP address */
    val daemonTailscaleIp: String? = null,

    /** Daemon Tailscale port */
    val daemonTailscalePort: Int? = null,

    /** Daemon VPN IP address */
    val daemonVpnIp: String? = null,

    /** Daemon VPN port */
    val daemonVpnPort: Int? = null,

    /**
     * Phone's own device ID sent during pairing.
     * This is the ID the daemon stores and expects during reconnection.
     * Different from deviceId (primary key) which is the daemon's ID for multi-device UI.
     */
    val phoneDeviceId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PairedDevice

        if (deviceId != other.deviceId) return false
        if (!masterSecret.contentEquals(other.masterSecret)) return false
        if (deviceName != other.deviceName) return false
        if (deviceType != other.deviceType) return false
        if (status != other.status) return false
        if (isSelected != other.isSelected) return false
        if (pairedAt != other.pairedAt) return false
        if (lastConnectedAt != other.lastConnectedAt) return false
        if (daemonHost != other.daemonHost) return false
        if (daemonPort != other.daemonPort) return false
        if (daemonTailscaleIp != other.daemonTailscaleIp) return false
        if (daemonTailscalePort != other.daemonTailscalePort) return false
        if (daemonVpnIp != other.daemonVpnIp) return false
        if (daemonVpnPort != other.daemonVpnPort) return false
        if (phoneDeviceId != other.phoneDeviceId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + masterSecret.contentHashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + isSelected.hashCode()
        result = 31 * result + pairedAt.hashCode()
        result = 31 * result + (lastConnectedAt?.hashCode() ?: 0)
        result = 31 * result + (daemonHost?.hashCode() ?: 0)
        result = 31 * result + (daemonPort ?: 0)
        result = 31 * result + (daemonTailscaleIp?.hashCode() ?: 0)
        result = 31 * result + (daemonTailscalePort ?: 0)
        result = 31 * result + (daemonVpnIp?.hashCode() ?: 0)
        result = 31 * result + (daemonVpnPort ?: 0)
        result = 31 * result + (phoneDeviceId?.hashCode() ?: 0)
        return result
    }
}
