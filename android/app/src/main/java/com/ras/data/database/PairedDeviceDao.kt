package com.ras.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for paired devices.
 *
 * Provides CRUD operations and queries for device management.
 */
@Dao
interface PairedDeviceDao {

    /**
     * Get all devices (including unpaired).
     * @return Flow of all devices, ordered by most recently paired first
     */
    @Query("SELECT * FROM paired_devices ORDER BY paired_at DESC")
    fun getAllDevices(): Flow<List<PairedDeviceEntity>>

    /**
     * Get all paired devices (excludes unpaired devices).
     * @return Flow of paired devices, ordered by most recently paired first
     */
    @Query("SELECT * FROM paired_devices WHERE status = 'PAIRED' ORDER BY paired_at DESC")
    fun getAllPairedDevices(): Flow<List<PairedDeviceEntity>>

    /**
     * Get a specific device by ID.
     * @param deviceId The device identifier
     * @return The device entity, or null if not found
     */
    @Query("SELECT * FROM paired_devices WHERE device_id = :deviceId")
    suspend fun getDevice(deviceId: String): PairedDeviceEntity?

    /**
     * Get the currently selected device (for auto-connect).
     * @return The selected device, or null if none selected or all unpaired
     */
    @Query("SELECT * FROM paired_devices WHERE is_selected = 1 AND status = 'PAIRED' LIMIT 1")
    suspend fun getSelectedDevice(): PairedDeviceEntity?

    /**
     * Insert or replace a device.
     * @param device The device to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: PairedDeviceEntity)

    /**
     * Update the status of a device.
     * @param deviceId The device identifier
     * @param status The new status (PAIRED, UNPAIRED_BY_USER, UNPAIRED_BY_DAEMON)
     */
    @Query("UPDATE paired_devices SET status = :status WHERE device_id = :deviceId")
    suspend fun updateDeviceStatus(deviceId: String, status: String)

    /**
     * Set a device as the selected one (clears all other selections).
     * This is a transaction to ensure atomicity.
     * @param deviceId The device to select
     */
    @Transaction
    suspend fun setSelectedDevice(deviceId: String) {
        clearAllSelections()
        updateIsSelected(deviceId, true)
    }

    /**
     * Clear all device selections.
     */
    @Query("UPDATE paired_devices SET is_selected = 0")
    suspend fun clearAllSelections()

    /**
     * Update the is_selected flag for a specific device.
     * @param deviceId The device identifier
     * @param isSelected Whether the device should be selected
     */
    @Query("UPDATE paired_devices SET is_selected = :isSelected WHERE device_id = :deviceId")
    suspend fun updateIsSelected(deviceId: String, isSelected: Boolean)

    /**
     * Hard delete a device from the database.
     * @param deviceId The device to delete
     */
    @Query("DELETE FROM paired_devices WHERE device_id = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    /**
     * Delete all unpaired devices (cleanup operation).
     */
    @Query("DELETE FROM paired_devices WHERE status != 'PAIRED'")
    suspend fun deleteUnpairedDevices()

    /**
     * Update Tailscale connection info for a device.
     * Used to cache discovered Tailscale IPs for faster reconnection.
     * @param deviceId The device identifier
     * @param ip The Tailscale IP address (100.x.x.x or fd7a:115c:a1e0::/48)
     * @param port The Tailscale port
     */
    @Query("UPDATE paired_devices SET daemon_tailscale_ip = :ip, daemon_tailscale_port = :port WHERE device_id = :deviceId")
    suspend fun updateTailscaleInfo(deviceId: String, ip: String?, port: Int?)
}
