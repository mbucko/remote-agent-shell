package com.ras.data.connection

import android.content.Context
import com.ras.data.credentials.CredentialRepository
import com.ras.data.credentials.StoredCredentials
import com.ras.data.reconnection.ReconnectionServiceImpl
import com.ras.data.webrtc.WebRTCClient
import com.ras.domain.startup.ReconnectionResult
import com.ras.signaling.NtfyClientInterface
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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

/**
 * End-to-end integration tests for WebRTC connection flow.
 *
 * These tests verify the COMPLETE packet flow for WebRTC/NAT traversal:
 * ReconnectionServiceImpl → ConnectionOrchestrator → WebRTCStrategy → WebRTCClient → WebRTCTransport
 *
 * Scenarios tested:
 * - Same network (LAN) - direct host candidate connection
 * - NAT traversal - STUN reflexive candidates
 * - Signaling flow - SDP offer/answer exchange
 * - ICE negotiation - candidate gathering and connectivity checks
 * - Data channel establishment
 *
 * Only the lowest-level WebRTC API is mocked (WebRTCClient).
 * Everything else uses real implementations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebRTCIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    // Real implementations
    private lateinit var orchestrator: ConnectionOrchestrator
    private lateinit var webRTCStrategy: WebRTCStrategy

    // Mocked at boundaries
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var mockSignaling: SignalingChannel
    private lateinit var mockWebRTCClient: WebRTCClient
    private lateinit var mockWebRTCClientFactory: WebRTCClient.Factory
    private lateinit var httpClient: OkHttpClient
    private lateinit var ntfyClient: NtfyClientInterface

    // Mock Tailscale strategy (unavailable for these tests)
    private lateinit var tailscaleStrategy: ConnectionStrategy
    private lateinit var mockContext: Context

    private val testCredentials = StoredCredentials(
        deviceId = "test-device-abc123",
        masterSecret = ByteArray(32) { it.toByte() },
        daemonHost = "192.168.1.100",
        daemonPort = 8765,
        ntfyTopic = "ras-test123"
    )

    // Sample SDP for testing - mimics real WebRTC offers/answers
    private val sampleOffer = """
        v=0
        o=- 123456789 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE 0
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        c=IN IP4 0.0.0.0
        a=ice-ufrag:abcd
        a=ice-pwd:efghijklmnopqrstuvwxyz1234
        a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99
        a=setup:actpass
        a=mid:0
        a=sctp-port:5000
        a=candidate:1 1 udp 2130706431 192.168.1.50 55000 typ host
        a=candidate:2 1 udp 1694498815 203.0.113.50 55000 typ srflx raddr 192.168.1.50 rport 55000
    """.trimIndent()

    private val sampleAnswer = """
        v=0
        o=- 987654321 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE 0
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        c=IN IP4 0.0.0.0
        a=ice-ufrag:wxyz
        a=ice-pwd:1234567890abcdefghijklmn
        a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00
        a=setup:active
        a=mid:0
        a=sctp-port:5000
        a=candidate:1 1 udp 2130706431 192.168.1.100 56000 typ host
        a=candidate:2 1 udp 1694498815 203.0.113.100 56000 typ srflx raddr 192.168.1.100 rport 56000
    """.trimIndent()

    // Same network (LAN) SDP - only host candidates, no STUN
    private val lanOnlyOffer = """
        v=0
        o=- 123456789 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        c=IN IP4 0.0.0.0
        a=ice-ufrag:abcd
        a=ice-pwd:efghijklmnopqrstuvwxyz1234
        a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99
        a=setup:actpass
        a=mid:0
        a=candidate:1 1 udp 2130706431 192.168.1.50 55000 typ host
    """.trimIndent()

    private val lanOnlyAnswer = """
        v=0
        o=- 987654321 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        c=IN IP4 0.0.0.0
        a=ice-ufrag:wxyz
        a=ice-pwd:1234567890abcdefghijklmn
        a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00
        a=setup:active
        a=mid:0
        a=candidate:1 1 udp 2130706431 192.168.1.100 56000 typ host
    """.trimIndent()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock boundaries
        mockContext = mockk(relaxed = true)
        mockSignaling = mockk(relaxed = true)
        mockWebRTCClient = mockk(relaxed = true)
        mockWebRTCClientFactory = mockk()
        credentialRepository = mockk()
        connectionManager = mockk(relaxed = true)
        httpClient = mockk()
        ntfyClient = mockk(relaxed = true)

        // Factory creates our mock client
        every { mockWebRTCClientFactory.create() } returns mockWebRTCClient

        // Tailscale is unavailable for WebRTC tests
        tailscaleStrategy = mockk {
            every { name } returns "Tailscale Direct"
            every { priority } returns 10
            coEvery { detect() } returns DetectionResult.Unavailable("Not on Tailscale network")
        }

        // Use REAL WebRTCStrategy with mocked factory
        webRTCStrategy = WebRTCStrategy(mockWebRTCClientFactory)

        // Use REAL ConnectionOrchestrator
        orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webRTCStrategy))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createContext() = ConnectionContext(
        androidContext = mockContext,
        deviceId = testCredentials.deviceId,
        daemonHost = testCredentials.daemonHost,
        daemonPort = testCredentials.daemonPort,
        signaling = mockSignaling,
        authToken = ByteArray(32) { it.toByte() }
    )

    // ============================================================================
    // SCENARIO 1: Happy Path - WebRTC Connection Succeeds
    // ============================================================================

    @Test
    fun `full flow - WebRTC connects successfully with SDP exchange`() = runTest {
        // Setup: WebRTC client creates offer
        coEvery { mockWebRTCClient.createOffer() } returns sampleOffer

        // Setup: Signaling returns answer (use any() for optional onProgress param)
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null  // No Tailscale
        coEvery { mockSignaling.sendOffer(sampleOffer, any()) } returns sampleAnswer

        // Setup: WebRTC negotiation succeeds
        coEvery { mockWebRTCClient.setRemoteDescription(sampleAnswer) } returns Unit
        coEvery { mockWebRTCClient.waitForDataChannel(any()) } returns true

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val transport = orchestrator.connect(createContext()) { progressUpdates.add(it) }

        // Verify: Transport returned
        assertNotNull("Should return transport", transport)
        assertEquals(TransportType.WEBRTC, transport?.type)

        // Verify: WebRTC flow was executed
        coVerify { mockWebRTCClient.createOffer() }
        coVerify { mockSignaling.sendOffer(sampleOffer, any()) }
        coVerify { mockWebRTCClient.setRemoteDescription(sampleAnswer) }
        coVerify { mockWebRTCClient.waitForDataChannel(any()) }

        // Verify: Connected via WebRTC
        val connected = progressUpdates.filterIsInstance<ConnectionProgress.Connected>().firstOrNull()
        assertNotNull("Should have Connected progress", connected)
        assertEquals("WebRTC P2P", connected?.strategyName)
    }

    @Test
    fun `WebRTC used when Tailscale unavailable`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns sampleOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } returns sampleAnswer
        coEvery { mockWebRTCClient.setRemoteDescription(any()) } returns Unit
        coEvery { mockWebRTCClient.waitForDataChannel(any()) } returns true

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(createContext()) { progressUpdates.add(it) }

        // Verify: Tailscale was unavailable
        val unavailable = progressUpdates.filterIsInstance<ConnectionProgress.StrategyUnavailable>()
            .find { it.strategyName == "Tailscale Direct" }
        assertNotNull("Tailscale should be unavailable", unavailable)

        // Verify: WebRTC was available and used
        val available = progressUpdates.filterIsInstance<ConnectionProgress.StrategyAvailable>()
            .find { it.strategyName == "WebRTC P2P" }
        assertNotNull("WebRTC should be available", available)

        val connected = progressUpdates.filterIsInstance<ConnectionProgress.Connected>()
            .find { it.strategyName == "WebRTC P2P" }
        assertNotNull("Should connect via WebRTC", connected)
    }

    // ============================================================================
    // SCENARIO 2: Same Network (LAN) Connection
    // ============================================================================

    @Test
    fun `same network - connects using host candidates only`() = runTest {
        // LAN scenario: only host candidates, no STUN reflexive candidates
        coEvery { mockWebRTCClient.createOffer() } returns lanOnlyOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(lanOnlyOffer, any()) } returns lanOnlyAnswer
        coEvery { mockWebRTCClient.setRemoteDescription(lanOnlyAnswer) } returns Unit
        coEvery { mockWebRTCClient.waitForDataChannel(any()) } returns true

        val transport = orchestrator.connect(createContext()) {}

        // Verify: Connection succeeded with LAN-only candidates
        assertNotNull("Should connect on same network", transport)

        // Verify: The offer sent had only host candidates
        val capturedOffer = slot<String>()
        coVerify { mockSignaling.sendOffer(capture(capturedOffer), any()) }
        assertTrue("Offer should have host candidate",
            capturedOffer.captured.contains("typ host"))
        // LAN-only offer shouldn't have srflx (STUN) candidates
        assertTrue("LAN offer should not have srflx",
            !capturedOffer.captured.contains("typ srflx"))
    }

    @Test
    fun `same network - progress reports correct steps`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns lanOnlyOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } returns lanOnlyAnswer
        coEvery { mockWebRTCClient.setRemoteDescription(any()) } returns Unit
        coEvery { mockWebRTCClient.waitForDataChannel(any()) } returns true

        val progressUpdates = mutableListOf<ConnectionProgress>()
        orchestrator.connect(createContext()) { progressUpdates.add(it) }

        // Verify: Progress shows ICE negotiation steps
        val connecting = progressUpdates.filterIsInstance<ConnectionProgress.Connecting>()
        assertTrue("Should have connecting steps", connecting.isNotEmpty())

        // Check for expected WebRTC steps
        val steps = connecting.map { it.step }
        assertTrue("Should have 'Creating offer' step", steps.any { it.contains("offer") })
        assertTrue("Should have 'Signaling' step", steps.any { it.contains("Signaling") })
    }

    // ============================================================================
    // SCENARIO 3: NAT Traversal with STUN
    // ============================================================================

    @Test
    fun `NAT traversal - connects using reflexive candidates`() = runTest {
        // NAT scenario: includes srflx (STUN reflexive) candidates
        coEvery { mockWebRTCClient.createOffer() } returns sampleOffer  // Has srflx
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } returns sampleAnswer  // Has srflx
        coEvery { mockWebRTCClient.setRemoteDescription(any()) } returns Unit
        coEvery { mockWebRTCClient.waitForDataChannel(any()) } returns true

        val transport = orchestrator.connect(createContext()) {}

        assertNotNull("Should connect through NAT", transport)

        // Verify: Offer included srflx candidates
        val capturedOffer = slot<String>()
        coVerify { mockSignaling.sendOffer(capture(capturedOffer), any()) }
        assertTrue("Should have srflx candidate for NAT",
            capturedOffer.captured.contains("typ srflx"))
    }

    // ============================================================================
    // SCENARIO 4: Signaling Failures
    // ============================================================================

    @Test
    fun `signaling failure - no answer received`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns sampleOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } returns null  // No answer!

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val transport = orchestrator.connect(createContext()) { progressUpdates.add(it) }

        // Verify: Connection failed
        assertEquals(null, transport)

        // Verify: WebRTC client was cleaned up
        coVerify { mockWebRTCClient.close() }

        // Verify: Strategy failed
        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "WebRTC P2P" }
        assertNotNull("WebRTC should have failed", failed)
        assertTrue("Error should mention daemon", failed?.error?.contains("daemon") == true)
    }

    @Test
    fun `signaling failure - exception during offer send`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns sampleOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } throws Exception("Network error")

        val transport = orchestrator.connect(createContext()) {}

        assertEquals(null, transport)
        coVerify { mockWebRTCClient.close() }
    }

    // ============================================================================
    // SCENARIO 5: ICE Failures
    // ============================================================================

    @Test
    fun `ICE failure - data channel timeout`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns sampleOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } returns sampleAnswer
        coEvery { mockWebRTCClient.setRemoteDescription(any()) } returns Unit
        coEvery { mockWebRTCClient.waitForDataChannel(any()) } returns false  // Timeout!

        val progressUpdates = mutableListOf<ConnectionProgress>()
        val transport = orchestrator.connect(createContext()) { progressUpdates.add(it) }

        assertEquals(null, transport)
        coVerify { mockWebRTCClient.close() }

        val failed = progressUpdates.filterIsInstance<ConnectionProgress.StrategyFailed>()
            .find { it.strategyName == "WebRTC P2P" }
        assertNotNull("Should have failed", failed)
        assertTrue("Error should mention ICE", failed?.error?.contains("ICE") == true)
    }

    @Test
    fun `ICE failure - setRemoteDescription throws`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns sampleOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } returns sampleAnswer
        coEvery { mockWebRTCClient.setRemoteDescription(any()) } throws Exception("Invalid SDP")

        val transport = orchestrator.connect(createContext()) {}

        assertEquals(null, transport)
        coVerify { mockWebRTCClient.close() }
    }

    // ============================================================================
    // SCENARIO 6: Offer Creation Failures
    // ============================================================================

    @Test
    fun `offer creation failure`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } throws Exception("Failed to gather candidates")
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null

        val transport = orchestrator.connect(createContext()) {}

        assertEquals(null, transport)
        coVerify { mockWebRTCClient.close() }
    }

    // ============================================================================
    // SCENARIO 7: Full ReconnectionService Integration
    // ============================================================================

    @Test
    fun `ReconnectionServiceImpl performs auth after WebRTC connection`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        // Create a mock WebRTC transport
        val webrtcTransport = mockk<Transport>(relaxed = true)
        every { webrtcTransport.type } returns TransportType.WEBRTC
        every { webrtcTransport.isConnected } returns true
        // Auth will fail (for test - real auth tested elsewhere)
        coEvery { webrtcTransport.receive(any()) } throws Exception("Test auth failure")

        // Mock orchestrator to return WebRTC transport
        val customOrchestrator = mockk<ConnectionOrchestrator>()
        coEvery { customOrchestrator.connect(any(), any()) } coAnswers {
            val callback = secondArg<(ConnectionProgress) -> Unit>()
            callback(ConnectionProgress.Connected("WebRTC P2P", webrtcTransport, 200L))
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

        // Verify: Authenticating progress WAS reported for WebRTC (unlike Tailscale)
        val authenticating = progressUpdates.filterIsInstance<ConnectionProgress.Authenticating>()
        assertTrue("Should perform authentication for WebRTC", authenticating.isNotEmpty())
    }

    // ============================================================================
    // SCENARIO 8: Candidate Types Validation
    // ============================================================================

    @Test
    fun `validates offer contains valid candidates`() = runTest {
        // Offer with multiple candidate types
        val multiCandidateOffer = """
            v=0
            o=- 123456789 2 IN IP4 127.0.0.1
            s=-
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            a=ice-ufrag:abcd
            a=ice-pwd:efghijklmnopqrstuvwxyz1234
            a=candidate:1 1 udp 2130706431 192.168.1.50 55000 typ host
            a=candidate:2 1 udp 2130706431 10.0.0.50 55001 typ host
            a=candidate:3 1 udp 1694498815 203.0.113.50 55000 typ srflx raddr 192.168.1.50 rport 55000
            a=candidate:4 1 tcp 1518214911 192.168.1.50 9 typ host tcptype active
        """.trimIndent()

        coEvery { mockWebRTCClient.createOffer() } returns multiCandidateOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } returns sampleAnswer
        coEvery { mockWebRTCClient.setRemoteDescription(any()) } returns Unit
        coEvery { mockWebRTCClient.waitForDataChannel(any()) } returns true

        val transport = orchestrator.connect(createContext()) {}

        assertNotNull(transport)

        // Verify the offer had multiple candidates
        val capturedOffer = slot<String>()
        coVerify { mockSignaling.sendOffer(capture(capturedOffer), any()) }
        val candidates = capturedOffer.captured.lines().filter { it.contains("a=candidate:") }
        assertEquals("Should have 4 candidates", 4, candidates.size)
    }

    // ============================================================================
    // SCENARIO 9: Empty or Invalid SDP
    // ============================================================================

    @Test
    fun `handles empty answer gracefully`() = runTest {
        coEvery { mockWebRTCClient.createOffer() } returns sampleOffer
        coEvery { mockSignaling.exchangeCapabilities(any(), any()) } returns null
        coEvery { mockSignaling.sendOffer(any(), any()) } returns ""  // Empty answer

        val transport = orchestrator.connect(createContext()) {}

        // Empty string is not null, so setRemoteDescription will be called
        // The WebRTCClient should handle invalid SDP
        coEvery { mockWebRTCClient.setRemoteDescription("") } throws Exception("Invalid SDP")

        // Connection should fail
        assertEquals(null, transport)
    }
}
