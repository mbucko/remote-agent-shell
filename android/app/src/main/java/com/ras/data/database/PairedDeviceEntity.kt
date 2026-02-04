package com.ras.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for storing paired device credentials.
 *
 * Each device has its own encrypted master_secret.
 * The master_secret is encrypted using Android Keystore AES-GCM.
 */
@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /** Encrypted master secret (AES-GCM ciphertext) */
    @ColumnInfo(name = "master_secret_encrypted")
    val masterSecretEncrypted: ByteArray,

    /** Initialization vector for AES-GCM */
    @ColumnInfo(name = "master_secret_iv")
    val masterSecretIv: ByteArray,

    /** Device hostname or user-friendly name */
    @ColumnInfo(name = "device_name")
    val deviceName: String,

    /** Device type as string (enum stored as name) */
    @ColumnInfo(name = "device_type")
    val deviceType: String,

    /** Device status as string (PAIRED, UNPAIRED_BY_USER, UNPAIRED_BY_DAEMON) */
    @ColumnInfo(name = "status")
    val status: String,

    /** Whether this is the currently selected device for auto-connect */
    @ColumnInfo(name = "is_selected")
    val isSelected: Boolean = false,

    /** When the device was initially paired (Unix timestamp in seconds) */
    @ColumnInfo(name = "paired_at")
    val pairedAt: Long,

    /** Last successful connection timestamp (Unix timestamp in seconds) */
    @ColumnInfo(name = "last_connected_at")
    val lastConnectedAt: Long? = null,

    // Connection hints (optional, discovered via mDNS/ntfy)
    /** Daemon IP address (local network) */
    @ColumnInfo(name = "daemon_host")
    val daemonHost: String? = null,

    /** Daemon port (local network) */
    @ColumnInfo(name = "daemon_port")
    val daemonPort: Int? = null,

    /** Daemon Tailscale IP address */
    @ColumnInfo(name = "daemon_tailscale_ip")
    val daemonTailscaleIp: String? = null,

    /** Daemon Tailscale port */
    @ColumnInfo(name = "daemon_tailscale_port")
    val daemonTailscalePort: Int? = null,

    /** Daemon VPN IP address */
    @ColumnInfo(name = "daemon_vpn_ip")
    val daemonVpnIp: String? = null,

    /** Daemon VPN port */
    @ColumnInfo(name = "daemon_vpn_port")
    val daemonVpnPort: Int? = null,

    /**
     * Phone's own device ID sent during pairing.
     * This is the ID the daemon stores and expects during reconnection.
     * Different from deviceId (primary key) which is the daemon's ID for multi-device UI.
     */
    @ColumnInfo(name = "phone_device_id")
    val phoneDeviceId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PairedDeviceEntity

        if (deviceId != other.deviceId) return false
        if (!masterSecretEncrypted.contentEquals(other.masterSecretEncrypted)) return false
        if (!masterSecretIv.contentEquals(other.masterSecretIv)) return false
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
        result = 31 * result + masterSecretEncrypted.contentHashCode()
        result = 31 * result + masterSecretIv.contentHashCode()
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
