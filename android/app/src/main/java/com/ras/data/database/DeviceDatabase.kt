package com.ras.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for storing paired device information.
 *
 * Stores encrypted credentials and connection info for multiple devices.
 */
@Database(
    entities = [PairedDeviceEntity::class],
    version = 2,
    exportSchema = true
)
abstract class DeviceDatabase : RoomDatabase() {
    /**
     * Get the DAO for paired device operations.
     */
    abstract fun pairedDeviceDao(): PairedDeviceDao

    companion object {
        /**
         * Migration from version 1 to 2: adds phone_device_id column.
         *
         * This column stores the phone's own device ID (what was sent to daemon during pairing).
         * Required for reconnection because daemon looks up devices by phone's ID, not daemon's ID.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE paired_devices ADD COLUMN phone_device_id TEXT DEFAULT NULL"
                )
            }
        }
    }
}
