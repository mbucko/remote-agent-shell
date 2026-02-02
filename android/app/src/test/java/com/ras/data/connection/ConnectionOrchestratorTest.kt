package com.ras.data.connection

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
 */
class ConnectionOrchestratorTest {

    private lateinit var tailscaleStrategy: ConnectionStrategy
    private lateinit var webRTCStrategy: ConnectionStrategy
    private lateinit var mockTransport: Transport
    private lateinit var mockSignaling: SignalingChannel
    private lateinit var mockContext: Context

    @BeforeEach
    fun setup() {
        mockContext = mockk(relaxed = true)
        tailscaleStrategy = mockk(relaxed = true)
        webRTCStrategy = mockk(relaxed = true)
        mockTransport = mockk(relaxed = true)
        mockSignaling = mockk(relaxed = true)

        // Set up strategy names and priorities
        coEvery { tailscaleStrategy.name } returns "Tailscale Direct"
        coEvery { tailscaleStrategy.priority } returns 10

        coEvery { webRTCStrategy.name } returns "WebRTC P2P"
        coEvery { webRTCStrategy.priority } returns 20
    }

    private fun createContext() = ConnectionContext(
        androidContext = mockContext,
        deviceId = "test-device",
        daemonHost = "192.168.1.100",
        daemonPort = 8765,
        signaling = mockSignaling,
        authToken = ByteArray(32)
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

        val orchestrator = ConnectionOrchestrator(setOf(webRTCStrategy, tailscaleStrategy))
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

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webRTCStrategy))
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

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webRTCStrategy))
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

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webRTCStrategy))
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

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webRTCStrategy))
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

        val orchestrator = ConnectionOrchestrator(setOf(tailscaleStrategy, webRTCStrategy))
        val progressSteps = mutableListOf<ConnectionProgress>()

        orchestrator.connect(createContext()) { progress ->
            progressSteps.add(progress)
        }

        // Should have Connected progress with WebRTC strategy name
        val connected = progressSteps.filterIsInstance<ConnectionProgress.Connected>()
        assertEquals(1, connected.size)
        assertEquals("WebRTC P2P", connected[0].strategyName)
    }
}
