package com.ras.di

import android.content.Context
import androidx.room.Room
import com.ras.data.database.DeviceDatabase
import com.ras.data.database.PairedDeviceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing Room database dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Provide the Room database instance.
     */
    @Provides
    @Singleton
    fun provideDeviceDatabase(@ApplicationContext context: Context): DeviceDatabase {
        return Room.databaseBuilder(
            context,
            DeviceDatabase::class.java,
            "ras_devices.db"
        )
            .addMigrations(DeviceDatabase.MIGRATION_1_2)
            .build()
    }

    /**
     * Provide the PairedDeviceDao from the database.
     */
    @Provides
    fun providePairedDeviceDao(database: DeviceDatabase): PairedDeviceDao {
        return database.pairedDeviceDao()
    }
}
