package com.ras.di

import com.ras.data.connection.ConnectionStrategy
import com.ras.data.connection.TailscaleStrategy
import com.ras.data.connection.WebRTCStrategy
import com.ras.data.webrtc.WebRTCClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Hilt module for connection strategies.
 *
 * Provides connection strategies using multibinding so the ConnectionOrchestrator
 * can receive all strategies as a Set and try them in priority order.
 *
 * Strategies are sorted by priority:
 * - TailscaleStrategy (priority 10) - Direct VPN connection, fastest
 * - WebRTCStrategy (priority 20) - Standard P2P with ICE/STUN
 *
 * Add new strategies by creating a @Provides @IntoSet method.
 */
@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {

    /**
     * Provides TailscaleStrategy for direct VPN connections.
     *
     * This has the highest priority (10) and is tried first when
     * both devices are on the same Tailscale network.
     */
    @Provides
    @IntoSet
    @Singleton
    fun provideTailscaleStrategy(): ConnectionStrategy {
        return TailscaleStrategy()
    }

    /**
     * Provides WebRTCStrategy as the standard P2P fallback.
     *
     * This is always available as a fallback and handles NAT traversal
     * using ICE (STUN/TURN).
     */
    @Provides
    @IntoSet
    @Singleton
    fun provideWebRTCStrategy(
        webRTCClientFactory: WebRTCClient.Factory
    ): ConnectionStrategy {
        return WebRTCStrategy(webRTCClientFactory)
    }
}
