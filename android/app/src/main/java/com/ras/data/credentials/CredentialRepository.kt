package com.ras.data.credentials

import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing stored pairing credentials.
 * Supports multiple paired devices.
 */
interface CredentialRepository {
    // Multi-device operations
    /**
     * Get all devices (including unpaired).
     * @return List of all devices
     */
    suspend fun getAllDevices(): List<PairedDevice>

    /**
     * Get all devices as a Flow (for observing changes).
     * Only includes PAIRED devices.
     * @return Flow of paired devices list
     */
    fun getAllDevicesFlow(): Flow<List<PairedDevice>>

    /**
     * Get a specific device by ID.
     * @param deviceId The device identifier
     * @return The device, or null if not found
     */
    suspend fun getDevice(deviceId: String): PairedDevice?

    /**
     * Get the currently selected device (for auto-connect).
     * @return The selected device, or null if none selected
     */
    suspend fun getSelectedDevice(): PairedDevice?

    /**
     * Add a new device.
     * @param deviceId Device identifier
     * @param masterSecret Master secret for authentication
     * @param deviceName Device hostname or name
     * @param deviceType Type of device
     * @param isSelected Whether this is the selected device
     * @param daemonHost Optional daemon host
     * @param daemonPort Optional daemon port
     * @param daemonTailscaleIp Optional Tailscale IP
     * @param daemonTailscalePort Optional Tailscale port
     * @param daemonVpnIp Optional VPN IP
     * @param daemonVpnPort Optional VPN port
     */
    suspend fun addDevice(
        deviceId: String,
        masterSecret: ByteArray,
        deviceName: String,
        deviceType: DeviceType,
        isSelected: Boolean = false,
        daemonHost: String? = null,
        daemonPort: Int? = null,
        daemonTailscaleIp: String? = null,
        daemonTailscalePort: Int? = null,
        daemonVpnIp: String? = null,
        daemonVpnPort: Int? = null
    )

    /**
     * Set the selected device (for auto-connect).
     * Clears all other selections.
     * @param deviceId The device to select
     */
    suspend fun setSelectedDevice(deviceId: String)

    /**
     * Update device status (e.g., mark as unpaired).
     * @param deviceId The device to update
     * @param status The new status
     */
    suspend fun updateDeviceStatus(deviceId: String, status: DeviceStatus)

    /**
     * Hard delete a device from storage.
     * @param deviceId The device to remove
     */
    suspend fun removeDevice(deviceId: String)

    /**
     * Soft delete a device (mark as unpaired).
     * @param deviceId The device to unpair
     */
    suspend fun unpairDevice(deviceId: String)

    // Backward compatible methods (for gradual migration)
    /**
     * Check if any valid credentials are stored.
     * @return true if selected device exists
     */
    suspend fun hasCredentials(): Boolean

    /**
     * Get stored credentials for the selected device.
     * @return StoredCredentials if selected device exists, null otherwise
     */
    suspend fun getCredentials(): StoredCredentials?

    /**
     * Get stored device name (hostname) for selected device.
     * @return device name, or null if not stored
     */
    suspend fun getDeviceName(): String?

    /**
     * Get stored device type for selected device.
     * @return device type, or UNKNOWN if not stored
     */
    suspend fun getDeviceType(): DeviceType

    /**
     * Clear all stored credentials (for unpairing/re-pairing).
     * @deprecated Use unpairDevice() or removeDevice() instead
     */
    suspend fun clearCredentials()
}
