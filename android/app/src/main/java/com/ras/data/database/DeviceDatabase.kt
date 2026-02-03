package com.ras.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for storing paired device information.
 *
 * Stores encrypted credentials and connection info for multiple devices.
 */
@Database(
    entities = [PairedDeviceEntity::class],
    version = 1,
    exportSchema = true
)
abstract class DeviceDatabase : RoomDatabase() {
    /**
     * Get the DAO for paired device operations.
     */
    abstract fun pairedDeviceDao(): PairedDeviceDao
}
