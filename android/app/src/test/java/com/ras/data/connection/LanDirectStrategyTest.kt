package com.ras.data.connection

import android.content.Context
import android.net.Network
import com.ras.data.discovery.DiscoveredDaemon
import com.ras.data.discovery.MdnsDiscoveryService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import javax.net.SocketFactory

/**
 * Tests for LanDirectStrategy.
 *
 * Verifies:
 * - Detection uses mDNS to find daemon
 * - Detection caches result for connect()
 * - Connect uses cached daemon
 * - Connect falls back to mDNS query if cache empty
 * - Auth failures are handled correctly
 * - Priority is highest (5)
 */
class LanDirectStrategyTest {

    private lateinit var strategy: LanDirectStrategy
    private lateinit var mockContext: Context
    private lateinit var mockMdnsService: MdnsDiscoveryService
    private lateinit var mockOkHttpClient: OkHttpClient  // Real OkHttpClient for newBuilder() support
    private lateinit var mockSignaling: SignalingChannel
    private lateinit var mockWifiNetworkProvider: WifiNetworkProvider

    @BeforeEach
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockMdnsService = mockk(relaxed = true)
        mockOkHttpClient = OkHttpClient()
        mockSignaling = mockk(relaxed = true)
        mockWifiNetworkProvider = mockk(relaxed = true)
        strategy = LanDirectStrategy(mockContext, mockMdnsService, mockOkHttpClient, mockWifiNetworkProvider)
        mockkObject(LanDirectTransport.Companion)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(LanDirectTransport.Companion)
    }

    private fun createContext() = ConnectionContext(
        androidContext = mockContext,
        deviceId = "test-device",
        daemonHost = "192.168.1.100",
        daemonPort = 8765,
        signaling = mockSignaling,
        authToken = ByteArray(32) { it.toByte() }
    )

    private fun createDaemon(host: String = "192.168.1.38", port: Int = 8765) = DiscoveredDaemon(
        host = host,
        port = port,
        deviceId = "daemon-123",
        addresses = listOf(host)
    )

    // ==================== Priority Tests ====================

    @Tag("unit")
    @Test
    fun `priority is 5 - highest priority`() {
        assertEquals(5, strategy.priority)
    }

    @Tag("unit")
    @Test
    fun `name is LAN Direct`() {
        assertEquals("LAN Direct", strategy.name)
    }

    // ==================== Detection Tests ====================

    @Tag("unit")
    @Test
    fun `detect returns Available when mDNS finds daemon`() = runTest {
        val daemon = createDaemon()
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        val result = strategy.detect()

        assertTrue(result is DetectionResult.Available)
        assertEquals("${daemon.host}:${daemon.port}", (result as DetectionResult.Available).info)
    }

    @Tag("unit")
    @Test
    fun `detect returns Unavailable when mDNS times out`() = runTest {
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns null

        val result = strategy.detect()

        assertTrue(result is DetectionResult.Unavailable)
        assertEquals("Daemon not on local network", (result as DetectionResult.Unavailable).reason)
    }

    @Tag("unit")
    @Test
    fun `detect returns Unavailable when mDNS throws exception`() = runTest {
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } throws RuntimeException("Network error")

        val result = strategy.detect()

        assertTrue(result is DetectionResult.Unavailable)
    }

    @Tag("unit")
    @Test
    fun `detect caches daemon for connect`() = runTest {
        val daemon = createDaemon()
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        strategy.detect()

        // Reset mock to return null - should use cached daemon
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns null

        // Mock transport
        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), any()) } returns mockTransport

        val result = strategy.connect(createContext()) {}

        // Should have used cached daemon, not called mDNS again
        coVerify(exactly = 1) { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) }
        assertTrue(result is ConnectionResult.Success)
    }

    // ==================== Connection Tests ====================

    @Tag("unit")
    @Test
    fun `connect uses cached daemon from detect`() = runTest {
        val daemon = createDaemon(host = "192.168.1.50", port = 9999)
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        strategy.detect()

        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        coEvery { LanDirectTransport.connect(eq("192.168.1.50"), eq(9999), any(), any(), any()) } returns mockTransport

        val result = strategy.connect(createContext()) {}

        assertTrue(result is ConnectionResult.Success)
        coVerify { LanDirectTransport.connect("192.168.1.50", 9999, any(), any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `connect falls back to mDNS query if cache empty`() = runTest {
        // First detect() returns null (nothing cached)
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = eq(1000L)) } returns null

        strategy.detect()

        // Then connect() queries mDNS again with shorter timeout
        val daemon = createDaemon()
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = eq(500L)) } returns daemon

        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), any()) } returns mockTransport

        val result = strategy.connect(createContext()) {}

        assertTrue(result is ConnectionResult.Success)
    }

    @Tag("unit")
    @Test
    fun `connect returns Failed if daemon not found`() = runTest {
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns null

        strategy.detect()

        val result = strategy.connect(createContext()) {}

        assertTrue(result is ConnectionResult.Failed)
        assertEquals("Daemon no longer on local network", (result as ConnectionResult.Failed).error)
        assertFalse(result.canRetry)
    }

    @Tag("unit")
    @Test
    fun `connect clears cache after attempt`() = runTest {
        val daemon = createDaemon()
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        strategy.detect()

        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), any()) } returns mockTransport

        strategy.connect(createContext()) {}

        // Clear mock and return null
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns null

        // Second connect should fail because cache was cleared
        val result = strategy.connect(createContext()) {}

        assertTrue(result is ConnectionResult.Failed)
    }

    @Tag("unit")
    @Test
    fun `connect returns Failed on auth exception`() = runTest {
        val daemon = createDaemon()
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        strategy.detect()

        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), any()) } throws
            LanDirectAuthException("Invalid signature")

        val result = strategy.connect(createContext()) {}

        assertTrue(result is ConnectionResult.Failed)
        assertEquals("Authentication failed", (result as ConnectionResult.Failed).error)
        assertFalse(result.canRetry)
    }

    @Tag("unit")
    @Test
    fun `connect returns Failed on connection exception`() = runTest {
        val daemon = createDaemon()
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        strategy.detect()

        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), any()) } throws
            java.io.IOException("Connection refused")

        val result = strategy.connect(createContext()) {}

        assertTrue(result is ConnectionResult.Failed)
        assertEquals("Connection refused", (result as ConnectionResult.Failed).error)
        assertFalse(result.canRetry)  // Fall back to next strategy
    }

    @Tag("unit")
    @Test
    fun `connect reports progress steps`() = runTest {
        val daemon = createDaemon()
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        strategy.detect()

        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), any()) } returns mockTransport

        val steps = mutableListOf<ConnectionStep>()
        strategy.connect(createContext()) { steps.add(it) }

        assertTrue(steps.any { it.step == "Connecting" })
        assertTrue(steps.any { it.step == "Authenticating" })
    }

    @Tag("unit")
    @Test
    fun `connect passes correct parameters to transport`() = runTest {
        val daemon = createDaemon(host = "10.0.0.5", port = 1234)
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        strategy.detect()

        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        val authToken = ByteArray(32) { (it * 2).toByte() }
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), any()) } returns mockTransport

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "my-device-id",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = authToken
        )

        strategy.connect(context) {}

        coVerify {
            LanDirectTransport.connect(
                host = "10.0.0.5",
                port = 1234,
                deviceId = "my-device-id",
                authKey = authToken,
                client = mockOkHttpClient
            )
        }
    }

    // ==================== VPN Bypass Tests ====================

    @Tag("unit")
    @Test
    fun `connect uses WiFi network socket factory when daemon has network`() = runTest {
        val mockNsdNetwork = mockk<Network>()
        val daemon = createDaemon().copy(network = mockNsdNetwork)
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        // WifiNetworkProvider returns a network with binding permission
        val mockWifiNetwork = mockk<Network>()
        val mockSocketFactory = mockk<SocketFactory>()
        every { mockWifiNetwork.socketFactory } returns mockSocketFactory
        coEvery { mockWifiNetworkProvider.getWifiNetwork() } returns mockWifiNetwork

        strategy.detect()

        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        val clientSlot = slot<OkHttpClient>()
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), capture(clientSlot)) } returns mockTransport

        strategy.connect(createContext()) {}

        assertNotEquals(mockOkHttpClient, clientSlot.captured, "Should create a new client, not use injected one")
        assertEquals(mockSocketFactory, clientSlot.captured.socketFactory, "Client should use WiFi network's socket factory")
    }

    @Tag("unit")
    @Test
    fun `connect uses default client when daemon has no network`() = runTest {
        val daemon = createDaemon() // network defaults to null
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        strategy.detect()

        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        val clientSlot = slot<OkHttpClient>()
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), capture(clientSlot)) } returns mockTransport

        strategy.connect(createContext()) {}

        // Should pass the injected client unchanged — no WiFi network request needed
        assertEquals(mockOkHttpClient, clientSlot.captured, "Should use injected client when no network")
        coVerify(exactly = 0) { mockWifiNetworkProvider.getWifiNetwork() }
    }

    @Tag("unit")
    @Test
    fun `connect falls back to default client when WiFi network unavailable`() = runTest {
        val mockNsdNetwork = mockk<Network>()
        val daemon = createDaemon().copy(network = mockNsdNetwork)
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        // WiFi network request returns null
        coEvery { mockWifiNetworkProvider.getWifiNetwork() } returns null

        strategy.detect()

        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        val clientSlot = slot<OkHttpClient>()
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), capture(clientSlot)) } returns mockTransport

        strategy.connect(createContext()) {}

        assertEquals(mockOkHttpClient, clientSlot.captured, "Should fall back to injected client when WiFi unavailable")
    }

    @Tag("unit")
    @Test
    fun `detect caches network from mDNS for VPN bypass`() = runTest {
        val mockNsdNetwork = mockk<Network>()
        val daemon = createDaemon().copy(network = mockNsdNetwork)
        coEvery { mockMdnsService.getDiscoveredDaemon(deviceId = any(), timeoutMs = any()) } returns daemon

        val mockWifiNetwork = mockk<Network>()
        val mockSocketFactory = mockk<SocketFactory>()
        every { mockWifiNetwork.socketFactory } returns mockSocketFactory
        coEvery { mockWifiNetworkProvider.getWifiNetwork() } returns mockWifiNetwork

        val result = strategy.detect()
        assertTrue(result is DetectionResult.Available)

        // Connect — cached daemon should trigger VPN bypass
        val mockTransport = mockk<LanDirectTransport>(relaxed = true)
        val clientSlot = slot<OkHttpClient>()
        coEvery { LanDirectTransport.connect(any(), any(), any(), any(), capture(clientSlot)) } returns mockTransport

        strategy.connect(createContext()) {}

        assertEquals(mockSocketFactory, clientSlot.captured.socketFactory, "Cached daemon should trigger VPN bypass")
    }
}
