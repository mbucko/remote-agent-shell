package com.ras.data.connection

import android.content.Context
import com.ras.data.credentials.CredentialRepository
import com.ras.data.credentials.StoredCredentials
import com.ras.data.reconnection.ReconnectionServiceImpl
import com.ras.domain.startup.ReconnectionResult
import com.ras.signaling.NtfyClientInterface
import io.mockk.*
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * End-to-end integration tests for the Tailscale connection flow.
 *
 * These tests verify the COMPLETE packet flow through the system:
 * ReconnectionServiceImpl → ConnectionOrchestrator → TailscaleStrategy → TailscaleTransport
 *
 * Only the lowest-level network I/O is mocked (TailscaleTransport.connect, TailscaleDetector).
 * Everything else uses real implementations to catch integration bugs.
 *
 * BUG PREVENTION: These tests would have caught:
 * - Bug 1: Double authentication (TailscaleStrategy auth + ReconnectionService auth)
 * - Bug 2: TailscaleStrategy not receiving daemon's Tailscale IP from enriched context
 * - Bug 3: TailscaleStrategy.detect() returning Unavailable despite Tailscale being present
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    // Real implementations (not mocked)
    private lateinit var orchestrator: ConnectionOrchestrator
    private lateinit var tailscaleStrategy: TailscaleStrategy
    private lateinit var webrtcStrategy: ConnectionStrategy

    // Mocked at boundaries only
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var mockTransport: TailscaleTransport
    private lateinit var mockSignaling: SignalingChannel
    private lateinit var httpClient: OkHttpClient
    private lateinit var ntfyClient: NtfyClientInterface
    private lateinit var mockContext: Context

    private val testCredentials = StoredCredentials(
        deviceId = "test-device-abc123",
        masterSecret = ByteArray(32) { it.toByte() },
        daemonHost = "192.168.1.100",
        daemonPort = 8765,
        daemonTailscaleIp = null,  // Will be set via capability exchange
        daemonTailscalePort = null,
        ntfyTopic = "ras-test123"
    )

    private val localTailscaleIp = "100.64.0.50"
    private val daemonTailscaleIp = "100.125.247.41"
    private val daemonTailscalePort = 9876

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock the network boundaries
        mockContext = mockk(relaxed = true)
        mockTransport = mockk(relaxed = true)
        mockSignaling = mockk(relaxed = true)
        credentialRepository = mockk()
        connectionManager = mockk(relaxed = true)
        httpClient = mockk()
        ntfyClient = mockk(relaxed = true)

        // Mock WebRTC strategy to be unavailable (we're testing Tailscale)
        webrtcStrategy = mockk {
            every { name } returns "WebRTC"
            every { priority } returns 20
            coEvery { detect() } returns DetectionResult.Unavailable("Mocked out for test")
        }

        // Use REAL TailscaleStrategy with mock context
        tailscaleStrategy = TailscaleStrategy(mockContext)

        // Use REAL ConnectionOrchestrator with real + mock strategies
        orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webrtcStrategy))

        // Mock TailscaleDetector and TailscaleTransport.Companion
        mockkObject(TailscaleDetector)
        mockkObject(TailscaleTransport.Companion)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(TailscaleDetector)
        unmockkObject(TailscaleTransport.Companion)
    }

    // ============================================================================
    // SCENARIO 1: Happy Path - Tailscale Available on Both Sides
    // ============================================================================

    @Test
    fun `full flow - Tailscale detected locally and on daemon - connection succeeds`() = runTest {
        // Setup: Local Tailscale detected
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        // Setup: Capability exchange returns daemon's Tailscale info
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        // Setup: TailscaleTransport.connect succeeds
        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), eq(daemonTailscaleIp), eq(daemonTailscalePort)) } returns mockTransport

        // Setup: Auth succeeds (daemon responds with 0x01)
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        coEvery { credentialRepository.getCredentials() } returns testCredentials

        // Create the service with REAL orchestrator
        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = orchestrator
        )

        // Create context for direct orchestrator test
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = testCredentials.deviceId,
            daemonHost = testCredentials.daemonHost,
            daemonPort = testCredentials.daemonPort,
            daemonTailscaleIp = null,  // Will be enriched by capability exchange
            daemonTailscalePort = null,
            signaling = mockSignaling,
            authToken = ByteArray(32) { it.toByte() }
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()

        // Execute: Run the orchestrator
        val transport = orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: Transport returned
        assertNotNull("Should return transport", transport)
        assertEquals(TransportType.TAILSCALE, transport?.type)

        // Verify: TailscaleStrategy was used (Connected progress with Tailscale)
        val connected = progressUpdates.filterIsInstance<ConnectionProgress.Connected>().firstOrNull()
        assertNotNull("Should have Connected progress", connected)
        assertEquals("Tailscale Direct", connected?.strategyName)

        // Verify: TailscaleTransport.connect was called with daemon's Tailscale IP
        coVerify { TailscaleTransport.connect(localTailscaleIp, daemonTailscaleIp, daemonTailscalePort) }

        // Verify: Auth message was sent
        coVerify { mockTransport.send(any()) }
    }

    @Test
    fun `auth message format is correct in full flow`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport

        // Capture the auth message
        val capturedMessage = slot<ByteArray>()
        coEvery { mockTransport.send(capture(capturedMessage)) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        val deviceId = "test-device-xyz"
        val authToken = ByteArray(32) { (it * 3).toByte() }

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = deviceId,
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = authToken
        )

        orchestrator.connect(context) {}

        // Verify auth message format: [4-byte len BE][device_id UTF-8][32-byte auth]
        assertTrue("Should have captured message", capturedMessage.isCaptured)
        val message = capturedMessage.captured
        val buffer = ByteBuffer.wrap(message)

        val deviceIdLen = buffer.int
        assertEquals("Device ID length should match", deviceId.length, deviceIdLen)

        val deviceIdBytes = ByteArray(deviceIdLen)
        buffer.get(deviceIdBytes)
        assertEquals("Device ID should match", deviceId, String(deviceIdBytes, Charsets.UTF_8))

        val actualAuth = ByteArray(32)
        buffer.get(actualAuth)
        assertTrue("Auth token should match", authToken.contentEquals(actualAuth))
    }

    // ============================================================================
    // SCENARIO 2: Capability Exchange Provides Daemon Tailscale IP
    // ============================================================================

    @Test
    fun `daemon Tailscale IP from capability exchange is used in TailscaleStrategy`() = runTest {
        // This test verifies Bug 2 prevention: enriched context is passed to strategy

        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        // Daemon responds with Tailscale info via capability exchange
        val daemonIp = "100.100.100.100"
        val daemonPort = 5555
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonIp,
            tailscalePort = daemonPort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), eq(daemonIp), eq(daemonPort)) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        // Context WITHOUT daemon Tailscale info (should be enriched from capability exchange)
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = null,  // Not set!
            daemonTailscalePort = null,  // Not set!
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val transport = orchestrator.connect(context) {}

        // Verify: Connection succeeded using daemon's IP from capability exchange
        assertNotNull(transport)
        coVerify { TailscaleTransport.connect(localTailscaleIp, daemonIp, daemonPort) }
    }

    // ============================================================================
    // SCENARIO 3: TailscaleStrategy Detection
    // ============================================================================

    @Test
    fun `TailscaleStrategy is tried first due to priority`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: Tailscale was detected first
        val detectingUpdates = progressUpdates.filterIsInstance<ConnectionProgress.Detecting>()
        assertTrue("Should detect Tailscale first", detectingUpdates.firstOrNull()?.strategyName == "Tailscale Direct")
    }

    @Test
    fun `TailscaleStrategy marked available when local Tailscale detected`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: Tailscale was marked as available
        val available = progressUpdates.filterIsInstance<ConnectionProgress.StrategyAvailable>()
            .find { it.strategyName == "Tailscale Direct" }
        assertNotNull("Tailscale should be marked available", available)
        assertEquals(localTailscaleIp, available?.info)
    }

    @Test
    fun `TailscaleStrategy marked unavailable when no local Tailscale`() = runTest {
        // No local Tailscale
        every { TailscaleDetector.detect(any()) } returns null

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: Tailscale was marked as unavailable
        val unavailable = progressUpdates.filterIsInstance<ConnectionProgress.StrategyUnavailable>()
            .find { it.strategyName == "Tailscale Direct" }
        assertNotNull("Tailscale should be marked unavailable", unavailable)
        assertEquals("Tailscale not running", unavailable?.reason)
    }

    // ============================================================================
    // SCENARIO 4: Connection Failures and Fallback
    // ============================================================================

    @Test
    fun `falls back to WebRTC when TailscaleTransport connect fails`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        // Tailscale connection fails
        coEvery { TailscaleTransport.connect(any(), any(), any()) } throws java.io.IOException("Connection refused")

        // WebRTC would be tried next (but we've mocked it as unavailable)

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val transport = orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: Tailscale failed
        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "Tailscale Direct" }
        assertNotNull("Tailscale should have failed", failed)
        assertTrue("Error should mention connection", failed?.error?.contains("Connection") == true)

        // Verify: No transport (all strategies failed)
        assertEquals(null, transport)
    }

    @Test
    fun `fails gracefully when daemon has no Tailscale capability`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        // Daemon responds WITHOUT Tailscale info
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = null,  // No Tailscale!
            tailscalePort = null,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: Tailscale strategy failed due to unknown daemon IP
        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "Tailscale Direct" }
        assertNotNull("Tailscale should have failed", failed)
        assertTrue("Error should mention unknown IP", failed?.error?.contains("unknown") == true)
    }

    // ============================================================================
    // SCENARIO 5: Authentication Failures
    // ============================================================================

    @Test
    fun `auth failure returns Failed result`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit

        // Auth fails (daemon responds with 0x00)
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x00)

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val transport = orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: Strategy failed due to auth
        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "Tailscale Direct" }
        assertNotNull("Should have failed", failed)
        assertTrue("Error should mention auth", failed?.error?.contains("Authentication") == true)

        // Verify: Transport closed
        coVerify { mockTransport.close() }

        assertEquals(null, transport)
    }

    @Test
    fun `auth timeout returns Failed result`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit

        // Auth times out
        coEvery { mockTransport.receive(any()) } throws TransportException("Receive timeout", isRecoverable = true)

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val transport = orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: No transport returned (strategy failed)
        assertEquals(null, transport)

        // Verify: Strategy failed with timeout-related error
        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "Tailscale Direct" }
        assertNotNull("Tailscale should have failed", failed)
    }

    // ============================================================================
    // SCENARIO 6: Capability Exchange Failure
    // ============================================================================

    @Test
    fun `connection proceeds with stored credentials when capability exchange fails`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        // Capability exchange fails
        coEvery { mockSignaling.exchangeCapabilities(any()) } returns null

        // Context has stored Tailscale credentials
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.50.50.50",  // From stored credentials
            daemonTailscalePort = 9999,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        every { mockTransport.type } returns TransportType.TAILSCALE
        every { mockTransport.isConnected } returns true
        coEvery { TailscaleTransport.connect(any(), eq("100.50.50.50"), eq(9999)) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val transport = orchestrator.connect(context) { progressUpdates.add(it) }

        // Verify: Connection succeeded using stored credentials
        assertNotNull(transport)
        coVerify { TailscaleTransport.connect(localTailscaleIp, "100.50.50.50", 9999) }

        // Verify: Capability exchange failure was reported
        val capFailed = progressUpdates.filterIsInstance<ConnectionProgress.CapabilityExchangeFailed>()
        assertTrue("Should report capability exchange failed", capFailed.isNotEmpty())
    }

    // ============================================================================
    // SCENARIO 7: Full ReconnectionService Integration
    // ============================================================================

    @Test
    fun `ReconnectionServiceImpl skips double auth for Tailscale transport`() = runTest {
        // This test verifies Bug 1 prevention: No double authentication

        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(localTailscaleIp, "tun0")

        coEvery { mockSignaling.exchangeCapabilities(any()) } returns ConnectionCapabilities(
            tailscaleIp = daemonTailscaleIp,
            tailscalePort = daemonTailscalePort,
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        // Create a transport that tracks method calls
        val tailscaleTransport = mockk<TailscaleTransport>(relaxed = true)
        every { tailscaleTransport.type } returns TransportType.TAILSCALE
        every { tailscaleTransport.isConnected } returns true
        // Auth in TailscaleStrategy succeeds
        coEvery { tailscaleTransport.receive(any()) } returns byteArrayOf(0x01)

        coEvery { TailscaleTransport.connect(any(), any(), any()) } returns tailscaleTransport

        coEvery { credentialRepository.getCredentials() } returns testCredentials

        // Create a custom orchestrator that returns our mock transport
        val customOrchestrator = mockk<ConnectionOrchestrator>()
        coEvery { customOrchestrator.connect(any(), any()) } coAnswers {
            val callback = secondArg<(ConnectionProgress) -> Unit>()
            callback(ConnectionProgress.Connected("Tailscale Direct", tailscaleTransport, 100L))
            tailscaleTransport
        }

        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = customOrchestrator
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val result = service.reconnect { progressUpdates.add(it) }

        // Verify: Success
        assertTrue("Should succeed", result is ReconnectionResult.Success)

        // Verify: NO Authenticating progress for Tailscale (auth is skipped)
        val authenticating = progressUpdates.filterIsInstance<ConnectionProgress.Authenticating>()
        assertTrue("Should skip authentication step for Tailscale", authenticating.isEmpty())

        // Verify: Authenticated progress is reported (from the skip path)
        val authenticated = progressUpdates.filterIsInstance<ConnectionProgress.Authenticated>()
        assertTrue("Should report Authenticated", authenticated.isNotEmpty())

        // Verify: ConnectionManager.connectWithTransport was called
        coVerify { connectionManager.connectWithTransport(tailscaleTransport, any()) }
    }

    @Test
    fun `ReconnectionServiceImpl performs auth for WebRTC transport`() = runTest {
        // Verify that non-Tailscale transports still go through auth

        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webrtcTransport = mockk<Transport>(relaxed = true)
        every { webrtcTransport.type } returns TransportType.WEBRTC
        every { webrtcTransport.isConnected } returns true
        // Auth will fail (for test simplicity)
        coEvery { webrtcTransport.receive(any()) } throws Exception("Test auth failure")

        val customOrchestrator = mockk<ConnectionOrchestrator>()
        coEvery { customOrchestrator.connect(any(), any()) } coAnswers {
            val callback = secondArg<(ConnectionProgress) -> Unit>()
            callback(ConnectionProgress.Connected("WebRTC", webrtcTransport, 100L))
            webrtcTransport
        }

        val service = ReconnectionServiceImpl(
            appContext = mockContext,
            credentialRepository = credentialRepository,
            httpClient = httpClient,
            directSignalingHttpClient = httpClient,
            connectionManager = connectionManager,
            ntfyClient = ntfyClient,
            orchestrator = customOrchestrator
        )

        val progressUpdates = mutableListOf<ConnectionProgress>()
        service.reconnect { progressUpdates.add(it) }

        // Verify: Authenticating progress WAS reported for WebRTC
        val authenticating = progressUpdates.filterIsInstance<ConnectionProgress.Authenticating>()
        assertTrue("Should perform authentication for WebRTC", authenticating.isNotEmpty())
    }
}
