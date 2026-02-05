package com.ras.di

import android.content.Context
import com.ras.data.keystore.KeyManager
import com.ras.pairing.PairingManager
import com.ras.pairing.PairingProgressTracker
import com.ras.pairing.SignalingClient
import com.ras.signaling.NtfyClientInterface
import com.ras.signaling.NtfySignalingClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DirectSignalingClient

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideKeyManager(
        @ApplicationContext context: Context
    ): KeyManager {
        return KeyManager(context)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @DirectSignalingClient
    fun provideDirectSignalingOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideSignalingClient(
        okHttpClient: OkHttpClient
    ): SignalingClient {
        return SignalingClient(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideNtfyClient(): NtfyClientInterface {
        return NtfySignalingClient()
    }

    @Provides
    @Singleton
    fun providePairingManager(
        keyManager: KeyManager,
        credentialRepository: com.ras.data.credentials.CredentialRepository,
        ntfyClient: NtfyClientInterface,
        progressTracker: PairingProgressTracker
    ): PairingManager {
        return PairingManager(keyManager, credentialRepository, ntfyClient, progressTracker)
    }
}
