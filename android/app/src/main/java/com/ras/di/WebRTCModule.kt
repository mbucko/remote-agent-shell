package com.ras.di

import android.content.Context
import com.ras.data.webrtc.WebRTCClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebRTCModule {

    @Provides
    @Singleton
    fun provideEglBase(): EglBase {
        return EglBase.create()
    }

    @Provides
    @Singleton
    fun providePeerConnectionFactory(
        @ApplicationContext context: Context,
        @Suppress("UNUSED_PARAMETER") eglBase: EglBase
    ): PeerConnectionFactory {
        // Initialize WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        // Create factory options
        val options = PeerConnectionFactory.Options()

        return PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    @Provides
    @Singleton
    fun provideWebRTCClientFactory(
        @ApplicationContext context: Context,
        peerConnectionFactory: PeerConnectionFactory
    ): WebRTCClient.Factory {
        return WebRTCClient.Factory(context, peerConnectionFactory)
    }
}
