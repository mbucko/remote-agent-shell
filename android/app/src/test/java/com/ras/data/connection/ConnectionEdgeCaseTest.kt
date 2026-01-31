package com.ras.data.connection

import android.content.Context
import com.ras.data.credentials.CredentialRepository
import com.ras.data.credentials.StoredCredentials
import com.ras.data.reconnection.ReconnectionServiceImpl
import com.ras.data.webrtc.WebRTCClient
import com.ras.domain.startup.ReconnectionResult
import com.ras.signaling.NtfyClientInterface
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Comprehensive edge case tests for the connection system.
 *
 * Tests unusual inputs, boundary conditions, error handling,
 * and resource cleanup across all connection components.
 *
 * Categories:
 * 1. Credential edge cases
 * 2. Capability exchange edge cases
 * 3. TailscaleDetector edge cases
 * 4. TailscaleStrategy edge cases
 * 5. TailscaleTransport edge cases
 * 6. WebRTC edge cases
 * 7. Auth handshake edge cases
 * 8. Resource cleanup verification
 * 9. Concurrency edge cases
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionEdgeCaseTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var credentialRepository: CredentialRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var httpClient: OkHttpClient
    private lateinit var ntfyClient: NtfyClientInterface
    private lateinit var mockSignaling: SignalingChannel
    private lateinit var mockTransport: TailscaleTransport
    private lateinit var mockWebRTCClient: WebRTCClient
    private lateinit var mockWebRTCClientFactory: WebRTCClient.Factory
    private lateinit var mockContext: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockContext = mockk(relaxed = true)
        credentialRepository = mockk()
        connectionManager = mockk(relaxed = true)
        httpClient = mockk()
        ntfyClient = mockk(relaxed = true)
        mockSignaling = mockk(relaxed = true)
        mockTransport = mockk(relaxed = true)
        mockWebRTCClient = mockk(relaxed = true)
        mockWebRTCClientFactory = mockk()

        mockkObject(TailscaleDetector)
        mockkObject(TailscaleTransport.Companion)

        every { mockWebRTCClientFactory.create() } returns mockWebRTCClient
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(TailscaleDetector)
        unmockkObject(TailscaleTransport.Companion)
    }

    // ============================================================================
    // SECTION 1: Credential Edge Cases
    // ============================================================================

    @Test
    fun `empty device ID in credentials - still attempts connection`() = runTest {
        val credentials = StoredCredentials(
            deviceId = "",  // Empty!
            masterSecret = ByteArray(32),
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            ntfyTopic = "test"
        )
        coEvery { credentialRepository.getCredentials() } returns credentials

        val orchestrator = mockk<ConnectionOrchestrator>()
        coEvery { orchestrator.connect(any(), any()) } returns null

        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = orchestrator
        )
        val result = service.reconnect()

        // Should attempt connection (empty deviceId passed to orchestrator)
        coVerify { orchestrator.connect(any(), any()) }
        assertTrue(result is ReconnectionResult.Failure.NetworkError)
    }

    @Test
    fun `whitespace-only device ID`() = runTest {
        val credentials = StoredCredentials(
            deviceId = "   ",  // Whitespace only
            masterSecret = ByteArray(32),
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            ntfyTopic = "test"
        )
        coEvery { credentialRepository.getCredentials() } returns credentials

        val orchestrator = mockk<ConnectionOrchestrator>()
        val contextSlot = slot<ConnectionContext>()
        coEvery { orchestrator.connect(capture(contextSlot), any()) } returns null

        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = orchestrator
        )
        service.reconnect()

        // Device ID should be passed as-is (whitespace preserved)
        assertEquals("   ", contextSlot.captured.deviceId)
    }

    @Test
    fun `daemon port at boundary values`() = runTest {
        // Test port 1 (minimum valid)
        val credentials1 = StoredCredentials(
            deviceId = "test",
            masterSecret = ByteArray(32),
            daemonHost = "192.168.1.100",
            daemonPort = 1,
            ntfyTopic = "test"
        )
        coEvery { credentialRepository.getCredentials() } returns credentials1

        val orchestrator = mockk<ConnectionOrchestrator>()
        val contextSlot = slot<ConnectionContext>()
        coEvery { orchestrator.connect(capture(contextSlot), any()) } returns null

        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = orchestrator
        )
        service.reconnect()

        assertEquals(1, contextSlot.captured.daemonPort)

        // Test port 65535 (maximum valid)
        val credentials2 = credentials1.copy(daemonPort = 65535)
        coEvery { credentialRepository.getCredentials() } returns credentials2
        service.reconnect()

        assertEquals(65535, contextSlot.captured.daemonPort)
    }

    @Test
    fun `IPv6 daemon host`() = runTest {
        val credentials = StoredCredentials(
            deviceId = "test",
            masterSecret = ByteArray(32),
            daemonHost = "2001:db8::1",  // IPv6
            daemonPort = 8765,
            ntfyTopic = "test"
        )
        coEvery { credentialRepository.getCredentials() } returns credentials

        val orchestrator = mockk<ConnectionOrchestrator>()
        val contextSlot = slot<ConnectionContext>()
        coEvery { orchestrator.connect(capture(contextSlot), any()) } returns null

        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = orchestrator
        )
        service.reconnect()

        assertEquals("2001:db8::1", contextSlot.captured.daemonHost)
    }

    @Test
    fun `localhost daemon host`() = runTest {
        val credentials = StoredCredentials(
            deviceId = "test",
            masterSecret = ByteArray(32),
            daemonHost = "localhost",
            daemonPort = 8765,
            ntfyTopic = "test"
        )
        coEvery { credentialRepository.getCredentials() } returns credentials

        val orchestrator = mockk<ConnectionOrchestrator>()
        val contextSlot = slot<ConnectionContext>()
        coEvery { orchestrator.connect(capture(contextSlot), any()) } returns null

        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = orchestrator
        )
        service.reconnect()

        assertEquals("localhost", contextSlot.captured.daemonHost)
    }

    // ============================================================================
    // SECTION 2: Capability Exchange Edge Cases
    // ============================================================================

    @Test
    fun `daemon returns empty string for Tailscale IP (not null)`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        // Daemon returns empty string, not null
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = "",  // Empty string!
            tailscalePort = 9876,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        val tailscaleStrategy = TailscaleStrategy(mockContext)
        val webrtcStrategy = mockk<ConnectionStrategy> {
            every { name } returns "WebRTC"
            every { priority } returns 20
            coEvery { detect() } returns DetectionResult.Unavailable("test")
        }

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(context) { progressUpdates.add(it) }

        // Tailscale should fail because empty string should be treated as "no IP"
        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "Tailscale Direct" }
        assertNotNull("Tailscale should fail with empty IP", failed)
    }

    @Test
    fun `daemon returns port 0`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = "100.125.247.41",
            tailscalePort = 0,  // Invalid port!
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        // Should use default port 9876 instead of 0
        coEvery { TailscaleTransport.connect(any(), any(), eq(9876)) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        val tailscaleStrategy = TailscaleStrategy(mockContext)
        val webrtcStrategy = mockk<ConnectionStrategy> {
            every { name } returns "WebRTC"
            every { priority } returns 20
            coEvery { detect() } returns DetectionResult.Unavailable("test")
        }

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        orchestrator.connect(context) {}

        // Should use default port when daemon returns 0
        coVerify { TailscaleTransport.connect(any(), any(), 9876) }
    }

    @Test
    fun `capability exchange throws exception`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } throws RuntimeException("Network error")

        val tailscaleStrategy = TailscaleStrategy(mockContext)
        val webrtcStrategy = mockk<ConnectionStrategy> {
            every { name } returns "WebRTC"
            every { priority } returns 20
            coEvery { detect() } returns DetectionResult.Unavailable("test")
        }

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(context) { progressUpdates.add(it) }

        // Should report capability exchange failed
        val capFailed = progressUpdates.filterIsInstance<ConnectionProgress.CapabilityExchangeFailed>()
        assertTrue("Should report capability exchange failed", capFailed.isNotEmpty())
    }

    // ============================================================================
    // SECTION 3: TailscaleDetector Edge Cases
    // ============================================================================

    @Test
    fun `TailscaleDetector returns null - strategy unavailable`() = runTest {
        every { TailscaleDetector.detect(any()) } returns null

        val strategy = TailscaleStrategy(mockContext)
        val result = strategy.detect()

        assertTrue(result is DetectionResult.Unavailable)
        assertEquals("Tailscale not running", (result as DetectionResult.Unavailable).reason)
    }

    @Test
    fun `TailscaleDetector throws exception`() = runTest {
        every { TailscaleDetector.detect(any()) } throws SecurityException("Permission denied")

        val strategy = TailscaleStrategy(mockContext)

        // detect() should propagate exception or handle gracefully
        try {
            strategy.detect()
            // If it catches and returns Unavailable, that's fine too
        } catch (e: SecurityException) {
            // Expected
        }
    }

    @Test
    fun `Tailscale IP at boundary - 100_64_0_0`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.0", "tun0")

        val strategy = TailscaleStrategy(mockContext)
        val result = strategy.detect()

        assertTrue(result is DetectionResult.Available)
        assertEquals("100.64.0.0", (result as DetectionResult.Available).info)
    }

    @Test
    fun `Tailscale IP at boundary - 100_127_255_255`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.127.255.255", "tun0")

        val strategy = TailscaleStrategy(mockContext)
        val result = strategy.detect()

        assertTrue(result is DetectionResult.Available)
        assertEquals("100.127.255.255", (result as DetectionResult.Available).info)
    }

    // ============================================================================
    // SECTION 4: TailscaleStrategy Edge Cases
    // ============================================================================

    @Test
    fun `very long device ID`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = "100.125.247.41",
            tailscalePort = 9876,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport

        val capturedMessage = slot<ByteArray>()
        coEvery { mockTransport.send(capture(capturedMessage)) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        // Very long device ID (1000 characters)
        val longDeviceId = "a".repeat(1000)

        val strategy = TailscaleStrategy(mockContext)
        strategy.detect()

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = longDeviceId,
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val result = strategy.connect(context) {}

        assertTrue(result is ConnectionResult.Success)

        // Verify the message format handles long device ID
        val message = capturedMessage.captured
        val buffer = ByteBuffer.wrap(message)
        val deviceIdLen = buffer.int
        assertEquals(1000, deviceIdLen)
    }

    @Test
    fun `Unicode device ID with emoji`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport

        val capturedMessage = slot<ByteArray>()
        coEvery { mockTransport.send(capture(capturedMessage)) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        // Device ID with Unicode and emoji
        val unicodeDeviceId = "设备-📱-тест"

        val strategy = TailscaleStrategy(mockContext)
        strategy.detect()

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = unicodeDeviceId,
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        strategy.connect(context) {}

        // Verify UTF-8 encoding
        val message = capturedMessage.captured
        val buffer = ByteBuffer.wrap(message)
        val deviceIdLen = buffer.int
        val deviceIdBytes = ByteArray(deviceIdLen)
        buffer.get(deviceIdBytes)
        assertEquals(unicodeDeviceId, String(deviceIdBytes, Charsets.UTF_8))
    }

    @Test
    fun `connect called before detect`() = runTest {
        // Don't call detect() first
        val strategy = TailscaleStrategy(mockContext)

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val result = strategy.connect(context) {}

        assertTrue(result is ConnectionResult.Failed)
        assertEquals("Tailscale not detected", (result as ConnectionResult.Failed).error)
    }

    @Test
    fun `auth response with extra bytes`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit

        // Response with extra bytes after success indicator
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01, 0x42, 0x43, 0x44)

        val strategy = TailscaleStrategy(mockContext)
        strategy.detect()

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val result = strategy.connect(context) {}

        // Should still succeed - we only check first byte
        assertTrue(result is ConnectionResult.Success)
    }

    @Test
    fun `auth response with unknown status code`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit

        // Unknown status code (not 0x00 or 0x01)
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x42)

        val strategy = TailscaleStrategy(mockContext)
        strategy.detect()

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val result = strategy.connect(context) {}

        // Should fail - unknown response treated as failure
        assertTrue(result is ConnectionResult.Failed)
    }

    // ============================================================================
    // SECTION 5: WebRTC Edge Cases
    // ============================================================================

    @Test
    fun `WebRTC createOffer returns empty string`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns ""
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns null

        val webrtcStrategy = WebRTCStrategy(mockWebRTCClientFactory)
        val tailscaleStrategy = mockk<ConnectionStrategy> {
            every { name } returns "Tailscale"
            every { priority } returns 10
            coEvery { detect() } returns DetectionResult.Unavailable("test")
        }

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        // Should still try to send empty offer (daemon will reject)
        coEvery { mockSignaling.sendOffer("") } returns null

        orchestrator.connect(context) {}

        coVerify { mockSignaling.sendOffer("") }
    }

    @Test
    fun `WebRTC offer with zero candidates`() = runTest {
        val offerWithNoCandidates = """
            v=0
            o=- 123456789 2 IN IP4 127.0.0.1
            s=-
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            a=ice-ufrag:abcd
            a=ice-pwd:efghijklmnopqrstuvwxyz1234
        """.trimIndent()  // No a=candidate lines

        coEvery { mockWebRTCClient.createOffer() } returns offerWithNoCandidates
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns null
        coEvery { mockSignaling.sendOffer(any()) } returns null

        val webrtcStrategy = WebRTCStrategy(mockWebRTCClientFactory)
        val tailscaleStrategy = mockk<ConnectionStrategy> {
            every { name } returns "Tailscale"
            every { priority } returns 10
            coEvery { detect() } returns DetectionResult.Unavailable("test")
        }

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(context) { progressUpdates.add(it) }

        // Should fail - no answer received
        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "WebRTC P2P" }
        assertNotNull(failed)
    }

    @Test
    fun `WebRTCClient factory throws`() = runTest {
        every { mockWebRTCClientFactory.create() } throws RuntimeException("Failed to initialize")
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns null

        val webrtcStrategy = WebRTCStrategy(mockWebRTCClientFactory)
        val tailscaleStrategy = mockk<ConnectionStrategy> {
            every { name } returns "Tailscale"
            every { priority } returns 10
            coEvery { detect() } returns DetectionResult.Unavailable("test")
        }

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(context) { progressUpdates.add(it) }

        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "WebRTC P2P" }
        assertNotNull(failed)
        assertTrue(failed?.error?.contains("initialize") == true)
    }

    // ============================================================================
    // SECTION 6: Resource Cleanup Verification
    // ============================================================================

    @Test
    fun `TailscaleStrategy closes transport on auth failure`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x00)  // Auth failure

        val strategy = TailscaleStrategy(mockContext)
        strategy.detect()

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        strategy.connect(context) {}

        // Verify transport was closed
        coVerify { mockTransport.close() }
    }

    @Test
    fun `TailscaleStrategy closes transport on empty response`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf()  // Empty response

        val strategy = TailscaleStrategy(mockContext)
        strategy.detect()

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        strategy.connect(context) {}

        coVerify { mockTransport.close() }
    }

    @Test
    fun `WebRTCStrategy closes client on signaling failure`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns "offer"
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns null
        coEvery { mockSignaling.sendOffer(any()) } returns null  // Failure

        val strategy = WebRTCStrategy(mockWebRTCClientFactory)

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        strategy.connect(context) {}

        coVerify { mockWebRTCClient.close() }
    }

    @Test
    fun `WebRTCStrategy closes client on ICE failure`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns "offer"
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns null
        coEvery { mockSignaling.sendOffer(any()) } returns "answer"
        coEvery { mockWebRTCClient.setRemoteDescription(any()) } returns Unit
        coEvery { mockWebRTCClient.waitForDataChannel(any()) } returns false  // ICE failure

        val strategy = WebRTCStrategy(mockWebRTCClientFactory)

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        strategy.connect(context) {}

        coVerify { mockWebRTCClient.close() }
    }

    // ============================================================================
    // SECTION 7: Concurrency Edge Cases
    // ============================================================================

    @Test
    fun `CancellationException propagates correctly`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } throws CancellationException("Cancelled")

        val tailscaleStrategy = TailscaleStrategy(mockContext)
        val webrtcStrategy = mockk<ConnectionStrategy> {
            every { name } returns "WebRTC"
            every { priority } returns 20
            coEvery { detect() } returns DetectionResult.Available()
        }

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        var caught = false
        try {
            orchestrator.connect(context) {}
        } catch (e: CancellationException) {
            caught = true
        }

        assertTrue("CancellationException should propagate", caught)
    }

    // ============================================================================
    // SECTION 8: Transport Type Detection Edge Cases
    // ============================================================================

    @Test
    fun `isTailscale check with proxy transport`() = runTest {
        // Test that a transport whose class name doesn't contain "Tailscale"
        // is correctly identified as non-Tailscale
        coEvery { credentialRepository.getCredentials() } returns StoredCredentials(
            deviceId = "test",
            masterSecret = ByteArray(32),
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            ntfyTopic = "test"
        )

        // Create a mock transport with type TAILSCALE but class name without "Tailscale"
        val proxyTransport = mockk<Transport>(relaxed = true)
        every { proxyTransport.type } returns TransportType.TAILSCALE
        every { proxyTransport.isConnected } returns true
        // Auth will be attempted because class name check fails
        coEvery { proxyTransport.receive(any()) } throws Exception("Auth attempted")

        val orchestrator = mockk<ConnectionOrchestrator>()
        coEvery { orchestrator.connect(any(), any()) } coAnswers {
            val callback = secondArg<(ConnectionProgress) -> Unit>()
            callback(ConnectionProgress.Connected("Test", proxyTransport, 100L))
            proxyTransport
        }

        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = orchestrator
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        service.reconnect { progressUpdates.add(it) }

        // Since class name doesn't contain "Tailscale", auth should be attempted
        val authenticating = progressUpdates.filterIsInstance<ConnectionProgress.Authenticating>()
        assertTrue("Should attempt auth for non-Tailscale class name", authenticating.isNotEmpty())
    }

    // ============================================================================
    // SECTION 9: Orchestrator Edge Cases
    // ============================================================================

    @Test
    fun `no strategies provided`() = runTest {
        val orchestrator = ConnectionOrchestrator(emptySet())
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val result = orchestrator.connect(context) { progressUpdates.add(it) }

        assertNull(result)
        val allFailed = progressUpdates.filterIsInstance<ConnectionProgress.AllFailed>()
        assertTrue(allFailed.isNotEmpty())
        assertEquals(0, allFailed.first().attempts.size)
    }

    @Test
    fun `all strategies unavailable`() = runTest {
        val strategy1 = mockk<ConnectionStrategy> {
            every { name } returns "Strategy1"
            every { priority } returns 10
            coEvery { detect() } returns DetectionResult.Unavailable("Not available")
        }
        val strategy2 = mockk<ConnectionStrategy> {
            every { name } returns "Strategy2"
            every { priority } returns 20
            coEvery { detect() } returns DetectionResult.Unavailable("Also not available")
        }

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns null

        val orchestrator = ConnectionOrchestrator(setOf(strategy1, strategy2))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val result = orchestrator.connect(context) { progressUpdates.add(it) }

        assertNull(result)

        // Both strategies should be reported as unavailable
        val unavailable = progressUpdates.filterIsInstance<ConnectionProgress.StrategyUnavailable>()
        assertEquals(2, unavailable.size)
    }

    @Test
    fun `progress callback throws exception`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = "100.125.247.41",
            tailscalePort = 9876,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        val tailscaleStrategy = TailscaleStrategy(mockContext)
        val webrtcStrategy = mockk<ConnectionStrategy> {
            every { name } returns "WebRTC"
            every { priority } returns 20
            coEvery { detect() } returns DetectionResult.Unavailable("test")
        }

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        var callCount = 0
        var caughtException = false

        try {
            orchestrator.connect(context) {
                callCount++
                if (callCount == 3) throw RuntimeException("Callback error")
            }
        } catch (e: RuntimeException) {
            caughtException = true
        }

        // Exception from callback should propagate
        assertTrue(caughtException)
    }
}
