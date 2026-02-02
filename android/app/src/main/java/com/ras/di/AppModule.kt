package com.ras.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.ras.data.connection.ConnectionConfig
import com.ras.data.settings.ModifierKeySettings
import com.ras.data.settings.SettingsRepositoryImpl
import com.ras.pairing.Clock
import com.ras.pairing.SystemClock
import com.ras.util.AndroidClipboardService
import com.ras.util.ClipboardService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AttachTimeoutMs

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    fun provideConnectionConfig(): ConnectionConfig {
        return ConnectionConfig()
    }

    @Provides
    @AttachTimeoutMs
    fun provideAttachTimeoutMs(): Long = 10_000L  // 10 seconds for production

    @Provides
    @Singleton
    fun provideClipboardService(@ApplicationContext context: Context): ClipboardService {
        return AndroidClipboardService(context)
    }
}

/**
 * Bindings module for interface implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    /**
     * Binds ModifierKeySettings interface to SettingsRepositoryImpl.
     */
    @Binds
    @Singleton
    abstract fun bindModifierKeySettings(impl: SettingsRepositoryImpl): ModifierKeySettings

    /**
     * Binds Clock interface to SystemClock.
     */
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
