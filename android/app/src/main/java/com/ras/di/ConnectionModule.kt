package com.ras.di

import android.content.Context
import com.ras.data.connection.ConnectionStrategy
import com.ras.data.connection.DatagramSocketFactory
import com.ras.data.connection.DefaultDatagramSocketFactory
import com.ras.data.connection.LanDirectStrategy
import com.ras.data.connection.TailscaleStrategy
import com.ras.data.connection.WebRTCStrategy
import okhttp3.OkHttpClient
import com.ras.data.discovery.MdnsDiscoveryService
import com.ras.data.webrtc.WebRTCClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
     * Provides the default DatagramSocketFactory for UDP socket creation.
     *
     * This abstraction allows for:
     * - Dependency injection for testability
     * - Future platform-specific implementations (VPN routing)
     */
    @Provides
    @Singleton
    fun provideDatagramSocketFactory(): DatagramSocketFactory {
        return DefaultDatagramSocketFactory()
    }

    /**
     * Provides MdnsDiscoveryService for local network daemon discovery.
     *
     * Uses Android's NsdManager to discover _ras._tcp services on the local network.
     * This enables fast local discovery (~10-50ms) without needing ntfy.
     */
    @Provides
    @Singleton
    fun provideMdnsDiscoveryService(
        @ApplicationContext context: Context
    ): MdnsDiscoveryService {
        return MdnsDiscoveryService(context)
    }

    /**
     * Provides LanDirectStrategy for WebSocket connections over LAN.
     *
     * This has the highest priority (5) and is tried first when
     * both devices are on the same local network (detected via mDNS).
     */
    @Provides
    @IntoSet
    @Singleton
    fun provideLanDirectStrategy(
        @ApplicationContext context: Context,
        mdnsService: MdnsDiscoveryService,
        okHttpClient: OkHttpClient
    ): ConnectionStrategy {
        return LanDirectStrategy(context, mdnsService, okHttpClient)
    }

    /**
     * Provides TailscaleStrategy for direct VPN connections.
     *
     * This has priority 10 and is tried after LAN Direct.
     */
    @Provides
    @IntoSet
    @Singleton
    fun provideTailscaleStrategy(
        @ApplicationContext context: Context,
        socketFactory: DatagramSocketFactory
    ): ConnectionStrategy {
        return TailscaleStrategy(context, socketFactory)
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
