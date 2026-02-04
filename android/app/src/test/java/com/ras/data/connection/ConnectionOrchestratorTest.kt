package com.ras.data.connection

import android.content.Context
import com.ras.data.credentials.CredentialRepository
import com.ras.data.webrtc.WebRTCClient
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for ConnectionOrchestrator.
 *
 * Verifies:
 * - Strategies are tried in priority order
 * - TailscaleStrategy is tried first (priority 10)
 * - WebRTCStrategy is used as fallback (priority 20)
 * - Progress callbacks are invoked correctly
 * - Failed strategies result in fallback to next
 * - Tailscale IP is cached from capability exchange
 * - Tailscale IP is extracted and cached from WebRTC ICE candidates
 */
class ConnectionOrchestratorTest {

    private lateinit var tailscaleStrategy: ConnectionStrategy
    private lateinit var webRTCStrategy: ConnectionStrategy
    private lateinit var mockTransport: Transport
    private lateinit var mockSignaling: SignalingChannel
    private lateinit var mockContext: Context
    private lateinit var mockCredentialRepository: CredentialRepository

    @BeforeEach
    fun setup() {
        mockContext = mockk(relaxed = true)
        tailscaleStrategy = mockk(relaxed = true)
        webRTCStrategy = mockk(relaxed = true)
        mockTransport = mockk(relaxed = true)
        mockSignaling = mockk(relaxed = true)
        mockCredentialRepository = mockk(relaxed = true)

        // Mock TailscaleDetector for consistent test behavior
        mockkObject(TailscaleDetector)

        // Set up strategy names and priorities
        coEvery { tailscaleStrategy.name } returns "Tailscale Direct"
        coEvery { tailscaleStrategy.priority } returns 10

        coEvery { webRTCStrategy.name } returns "WebRTC P2P"
        coEvery { webRTCStrategy.priority } returns 20

        // Default: no Tailscale IP caching
        coJustRun { mockCredentialRepository.updateTailscaleInfo(any(), any(), any()) }

        // Default: no local Tailscale
        every { TailscaleDetector.detect(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(TailscaleDetector)
    }

    private fun createContext(localTailscaleAvailable: Boolean = false) = ConnectionContext(
        androidContext = mockContext,
        deviceId = "test-device",
        daemonHost = "192.168.1.100",
        daemonPort = 8765,
        signaling = mockSignaling,
        authToken = ByteArray(32),
        localTailscaleAvailable = localTailscaleAvailable
    )

    private fun createOrchestrator() = ConnectionOrchestrator(
        setOf(tailscaleStrategy, webRTCStrategy),
        mockCredentialRepository
    )

    @Tag("unit")
    @Test
    fun `strategies are tried in priority order - lowest priority first`() = runTest {
        // Both strategies available
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Available("100.64.0.1")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        // Tailscale succeeds
        coEvery { tailscaleStrategy.connect(any(), any()) } returns
                ConnectionResult.Success(mockTransport)

        val orchestrator = createOrchestrator()
        val progressSteps = mutableListOf<ConnectionProgress>()

        val result = orchestrator.connect(createContext()) { progress ->
            progressSteps.add(progress)
        }

        // Should have connected via Tailscale (not WebRTC)
        assertNotNull(result)
        coVerify(exactly = 1) { tailscaleStrategy.connect(any(), any()) }
        coVerify(exactly = 0) { webRTCStrategy.connect(any(), any()) }

        // Progress should show Tailscale detected first (lower priority = first)
        val detectingSteps = progressSteps.filterIsInstance<ConnectionProgress.Detecting>()
        assertEquals(2, detectingSteps.size)
        assertEquals("Tailscale Direct", detectingSteps[0].strategyName)
        assertEquals("WebRTC P2P", detectingSteps[1].strategyName)
    }

    @Tag("unit")
    @Test
    fun `falls back to WebRTC when Tailscale unavailable`() = runTest {
        // Tailscale unavailable, WebRTC available
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("Not on Tailscale")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        // WebRTC succeeds
        coEvery { webRTCStrategy.connect(any(), any()) } returns
                ConnectionResult.Success(mockTransport)

        val orchestrator = createOrchestrator()
        val progressSteps = mutableListOf<ConnectionProgress>()

        val result = orchestrator.connect(createContext()) { progress ->
            progressSteps.add(progress)
        }

        // Should have connected via WebRTC
        assertNotNull(result)
        coVerify(exactly = 0) { tailscaleStrategy.connect(any(), any()) }
        coVerify(exactly = 1) { webRTCStrategy.connect(any(), any()) }

        // Progress should show Tailscale unavailable
        val unavailable = progressSteps.filterIsInstance<ConnectionProgress.StrategyUnavailable>()
        assertEquals(1, unavailable.size)
        assertEquals("Tailscale Direct", unavailable[0].strategyName)
    }

    @Tag("unit")
    @Test
    fun `falls back to WebRTC when Tailscale connection fails`() = runTest {
        // Both available
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Available("100.64.0.1")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        // Tailscale fails, WebRTC succeeds
        coEvery { tailscaleStrategy.connect(any(), any()) } returns
                ConnectionResult.Failed("Connection refused", canRetry = false)
        coEvery { webRTCStrategy.connect(any(), any()) } returns
                ConnectionResult.Success(mockTransport)

        val orchestrator = createOrchestrator()
        val progressSteps = mutableListOf<ConnectionProgress>()

        val result = orchestrator.connect(createContext()) { progress ->
            progressSteps.add(progress)
        }

        // Should have connected via WebRTC after Tailscale failed
        assertNotNull(result)
        coVerify(exactly = 1) { tailscaleStrategy.connect(any(), any()) }
        coVerify(exactly = 1) { webRTCStrategy.connect(any(), any()) }

        // Progress should show Tailscale failed with willTryNext=true
        val failed = progressSteps.filterIsInstance<ConnectionProgress.StrategyFailed>()
        assertEquals(1, failed.size)
        assertEquals("Tailscale Direct", failed[0].strategyName)
        assertTrue(failed[0].willTryNext)
    }

    @Tag("unit")
    @Test
    fun `reports all failed when no strategy succeeds`() = runTest {
        // Both available but both fail
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Available()
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        coEvery { tailscaleStrategy.connect(any(), any()) } returns
                ConnectionResult.Failed("Tailscale error")
        coEvery { webRTCStrategy.connect(any(), any()) } returns
                ConnectionResult.Failed("WebRTC error")

        val orchestrator = createOrchestrator()
        val progressSteps = mutableListOf<ConnectionProgress>()

        val result = orchestrator.connect(createContext()) { progress ->
            progressSteps.add(progress)
        }

        // Should return null
        assertNull(result)

        // Last progress should be AllFailed
        val lastProgress = progressSteps.last()
        assertTrue(lastProgress is ConnectionProgress.AllFailed)
        assertEquals(2, (lastProgress as ConnectionProgress.AllFailed).attempts.size)
    }

    @Tag("unit")
    @Test
    fun `reports correct progress when connected via Tailscale`() = runTest {
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Available("100.64.0.1")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        coEvery { tailscaleStrategy.connect(any(), any()) } returns
                ConnectionResult.Success(mockTransport)

        val orchestrator = createOrchestrator()
        val progressSteps = mutableListOf<ConnectionProgress>()

        orchestrator.connect(createContext()) { progress ->
            progressSteps.add(progress)
        }

        // Should have Connected progress with Tailscale strategy name
        val connected = progressSteps.filterIsInstance<ConnectionProgress.Connected>()
        assertEquals(1, connected.size)
        assertEquals("Tailscale Direct", connected[0].strategyName)
    }

    @Tag("unit")
    @Test
    fun `reports correct progress when connected via WebRTC`() = runTest {
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No VPN")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        coEvery { webRTCStrategy.connect(any(), any()) } returns
                ConnectionResult.Success(mockTransport)

        val orchestrator = createOrchestrator()
        val progressSteps = mutableListOf<ConnectionProgress>()

        orchestrator.connect(createContext()) { progress ->
            progressSteps.add(progress)
        }

        // Should have Connected progress with WebRTC strategy name
        val connected = progressSteps.filterIsInstance<ConnectionProgress.Connected>()
        assertEquals(1, connected.size)
        assertEquals("WebRTC P2P", connected[0].strategyName)
    }

    // ==================== Tailscale IP Caching Tests ====================

    @Tag("unit")
    @Test
    fun `caches Tailscale IP from WebRTC ICE candidates when local Tailscale available`() = runTest {
        // Setup: Local Tailscale is available (phone is on Tailscale network)
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.1.1", "tailscale0")

        // Tailscale strategy fails (no daemon IP), WebRTC succeeds
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No daemon IP")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        // Create mock WebRTC transport with Tailscale IP in remote candidate
        // Note: ICE ports (54321) are ephemeral - should cache fixed port 9876 instead
        val mockWebRTCClient = mockk<WebRTCClient>(relaxed = true)
        val tailscalePath = ConnectionPath(
            local = CandidateInfo("host", "100.64.1.1", 12345, false),
            remote = CandidateInfo("host", "100.64.2.2", 54321, false),  // Ephemeral ICE port
            type = PathType.TAILSCALE
        )
        coEvery { mockWebRTCClient.getActivePath() } returns tailscalePath

        val mockWebRTCTransport = WebRTCTransport(mockWebRTCClient)
        coEvery { webRTCStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockWebRTCTransport)

        val orchestrator = createOrchestrator()
        orchestrator.connect(createContext(localTailscaleAvailable = true)) {}

        // Should cache daemon's Tailscale IP with fixed port 9876, not ephemeral ICE port
        coVerify { mockCredentialRepository.updateTailscaleInfo("test-device", "100.64.2.2", 9876) }
    }

    @Tag("unit")
    @Test
    fun `does not cache Tailscale IP when local Tailscale not available`() = runTest {
        // Setup: WebRTC succeeds but local Tailscale is NOT available
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No VPN")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        val mockWebRTCClient = mockk<WebRTCClient>(relaxed = true)
        val tailscalePath = ConnectionPath(
            local = CandidateInfo("host", "192.168.1.100", 12345, true),
            remote = CandidateInfo("host", "100.64.2.2", 9876, false),  // Daemon has Tailscale
            type = PathType.LAN_DIRECT
        )
        coEvery { mockWebRTCClient.getActivePath() } returns tailscalePath

        val mockWebRTCTransport = WebRTCTransport(mockWebRTCClient)
        coEvery { webRTCStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockWebRTCTransport)

        val orchestrator = createOrchestrator()
        orchestrator.connect(createContext(localTailscaleAvailable = false)) {}

        // Should NOT cache because local Tailscale is not available
        coVerify(exactly = 0) { mockCredentialRepository.updateTailscaleInfo(any(), any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `does not cache when remote candidate is not Tailscale IP`() = runTest {
        // Local Tailscale is available
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.1.1", "tailscale0")

        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No VPN")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        val mockWebRTCClient = mockk<WebRTCClient>(relaxed = true)
        // Remote is a regular LAN IP, not Tailscale
        val lanPath = ConnectionPath(
            local = CandidateInfo("host", "100.64.1.1", 12345, false),
            remote = CandidateInfo("host", "192.168.1.50", 9876, true),  // LAN IP, not Tailscale
            type = PathType.LAN_DIRECT
        )
        coEvery { mockWebRTCClient.getActivePath() } returns lanPath

        val mockWebRTCTransport = WebRTCTransport(mockWebRTCClient)
        coEvery { webRTCStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockWebRTCTransport)

        val orchestrator = createOrchestrator()
        orchestrator.connect(createContext(localTailscaleAvailable = true)) {}

        // Should NOT cache because remote is not a Tailscale IP
        coVerify(exactly = 0) { mockCredentialRepository.updateTailscaleInfo(any(), any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `does not cache when transport is not WebRTC`() = runTest {
        // Local Tailscale is available
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.1.1", "tailscale0")

        // Tailscale Direct succeeds (non-WebRTC transport)
        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Available("100.64.1.1")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        // mockTransport is not a WebRTCTransport
        coEvery { tailscaleStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockTransport)

        val orchestrator = createOrchestrator()
        orchestrator.connect(createContext(localTailscaleAvailable = true)) {}

        // Should NOT cache because transport is not WebRTC (no ICE candidates)
        coVerify(exactly = 0) { mockCredentialRepository.updateTailscaleInfo(any(), any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `caches IPv6 Tailscale IP from ICE candidates`() = runTest {
        // Local Tailscale is available (using IPv6 address)
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("fd7a:115c:a1e0::1", "tailscale0")

        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No daemon IP")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        val mockWebRTCClient = mockk<WebRTCClient>(relaxed = true)
        // Use IPv6 Tailscale address with ephemeral ICE port
        val ipv6TailscalePath = ConnectionPath(
            local = CandidateInfo("host", "fd7a:115c:a1e0::1", 12345, false),
            remote = CandidateInfo("host", "fd7a:115c:a1e0:ab12::2", 54321, false),  // Ephemeral ICE port
            type = PathType.TAILSCALE
        )
        coEvery { mockWebRTCClient.getActivePath() } returns ipv6TailscalePath

        val mockWebRTCTransport = WebRTCTransport(mockWebRTCClient)
        coEvery { webRTCStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockWebRTCTransport)

        val orchestrator = createOrchestrator()
        orchestrator.connect(createContext(localTailscaleAvailable = true)) {}

        // Should cache IPv6 Tailscale IP with fixed port 9876, not ephemeral ICE port
        coVerify { mockCredentialRepository.updateTailscaleInfo("test-device", "fd7a:115c:a1e0:ab12::2", 9876) }
    }

    @Tag("unit")
    @Test
    fun `handles null path gracefully`() = runTest {
        // Local Tailscale is available
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.1.1", "tailscale0")

        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No VPN")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        val mockWebRTCClient = mockk<WebRTCClient>(relaxed = true)
        coEvery { mockWebRTCClient.getActivePath() } returns null  // No path available

        val mockWebRTCTransport = WebRTCTransport(mockWebRTCClient)
        coEvery { webRTCStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockWebRTCTransport)

        val orchestrator = createOrchestrator()
        // Should not throw
        val result = orchestrator.connect(createContext(localTailscaleAvailable = true)) {}

        assertNotNull(result)
        coVerify(exactly = 0) { mockCredentialRepository.updateTailscaleInfo(any(), any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `handles getActivePath exception gracefully`() = runTest {
        // Local Tailscale is available
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.1.1", "tailscale0")

        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No VPN")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        val mockWebRTCClient = mockk<WebRTCClient>(relaxed = true)
        // getActivePath throws an exception (e.g., WebRTC stats API failure)
        coEvery { mockWebRTCClient.getActivePath() } throws RuntimeException("Stats collection failed")

        val mockWebRTCTransport = WebRTCTransport(mockWebRTCClient)
        coEvery { webRTCStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockWebRTCTransport)

        val orchestrator = createOrchestrator()
        // Should not throw - exception should be caught and logged
        val result = orchestrator.connect(createContext(localTailscaleAvailable = true)) {}

        assertNotNull(result)
        coVerify(exactly = 0) { mockCredentialRepository.updateTailscaleInfo(any(), any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `handles updateTailscaleInfo exception gracefully`() = runTest {
        // Local Tailscale is available
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.1.1", "tailscale0")

        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No VPN")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        val mockWebRTCClient = mockk<WebRTCClient>(relaxed = true)
        val tailscalePath = ConnectionPath(
            local = CandidateInfo("host", "100.64.1.1", 12345, false),
            remote = CandidateInfo("host", "100.64.2.2", 54321, false),
            type = PathType.TAILSCALE
        )
        coEvery { mockWebRTCClient.getActivePath() } returns tailscalePath

        val mockWebRTCTransport = WebRTCTransport(mockWebRTCClient)
        coEvery { webRTCStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockWebRTCTransport)

        // Database write fails
        coEvery { mockCredentialRepository.updateTailscaleInfo(any(), any(), any()) } throws RuntimeException("DB write failed")

        val orchestrator = createOrchestrator()
        // Should not throw - exception should be caught and logged
        val result = orchestrator.connect(createContext(localTailscaleAvailable = true)) {}

        // Connection should still succeed even if caching fails
        assertNotNull(result)
    }

    @Tag("unit")
    @Test
    fun `uses fixed port 9876 regardless of ICE candidate port`() = runTest {
        // This test explicitly verifies that ephemeral ICE ports are ignored
        // and the fixed daemon listening port (9876) is always cached
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.1.1", "tailscale0")

        coEvery { tailscaleStrategy.detect() } returns DetectionResult.Unavailable("No daemon IP")
        coEvery { webRTCStrategy.detect() } returns DetectionResult.Available()

        val mockWebRTCClient = mockk<WebRTCClient>(relaxed = true)
        // ICE candidate has port 0 (common for some ICE implementations)
        val pathWithZeroPort = ConnectionPath(
            local = CandidateInfo("host", "100.64.1.1", 12345, false),
            remote = CandidateInfo("host", "100.64.2.2", 0, false),  // Port 0 from ICE
            type = PathType.TAILSCALE
        )
        coEvery { mockWebRTCClient.getActivePath() } returns pathWithZeroPort

        val mockWebRTCTransport = WebRTCTransport(mockWebRTCClient)
        coEvery { webRTCStrategy.connect(any(), any()) } returns ConnectionResult.Success(mockWebRTCTransport)

        val orchestrator = createOrchestrator()
        orchestrator.connect(createContext(localTailscaleAvailable = true)) {}

        // Should cache with fixed port 9876, NOT the ICE port 0
        coVerify { mockCredentialRepository.updateTailscaleInfo("test-device", "100.64.2.2", 9876) }
    }
}
