package com.ras.data.connection

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Tests for TailscaleStrategy.
 *
 * Verifies:
 * - Detection returns Available when Tailscale VPN is active
 * - Detection returns Unavailable when no Tailscale
 * - Connect fails gracefully when daemon not on Tailscale
 * - Priority is lower than WebRTC (tried first)
 */
class TailscaleStrategyTest {

    private lateinit var strategy: TailscaleStrategy
    private lateinit var mockSignaling: SignalingChannel
    private lateinit var mockTransport: TailscaleTransport
    private lateinit var mockContext: Context

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        strategy = TailscaleStrategy(mockContext)
        mockSignaling = mockk(relaxed = true)
        mockTransport = mockk(relaxed = true)
        mockkObject(TailscaleDetector)
        mockkObject(TailscaleTransport.Companion)
    }

    @After
    fun tearDown() {
        unmockkObject(TailscaleDetector)
        unmockkObject(TailscaleTransport.Companion)
    }

    private fun createContext(
        daemonTailscaleIp: String? = null,
        daemonTailscalePort: Int? = null
    ) = ConnectionContext(
        androidContext = mockContext,
        deviceId = "test-device",
        daemonHost = "192.168.1.100",
        daemonPort = 8765,
        daemonTailscaleIp = daemonTailscaleIp,
        daemonTailscalePort = daemonTailscalePort,
        signaling = mockSignaling,
        authToken = ByteArray(32)
    )

    // ==================== Priority Tests ====================

    @Test
    fun `priority is 10 - lower than WebRTC`() {
        assertEquals(10, strategy.priority)
    }

    @Test
    fun `name is Tailscale Direct`() {
        assertEquals("Tailscale Direct", strategy.name)
    }

    // ==================== Detection Tests ====================

    @Test
    fun `detect returns Available when Tailscale is running`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo(
            ip = "100.64.0.1",
            interfaceName = "tailscale0"
        )

        val result = strategy.detect()

        assertTrue(result is DetectionResult.Available)
        assertEquals("100.64.0.1", (result as DetectionResult.Available).info)
    }

    @Test
    fun `detect returns Unavailable when Tailscale not running`() = runTest {
        every { TailscaleDetector.detect(any()) } returns null

        val result = strategy.detect()

        assertTrue(result is DetectionResult.Unavailable)
        assertEquals("Tailscale not connected", (result as DetectionResult.Unavailable).reason)
    }

    // ==================== Connection Tests ====================

    @Test
    fun `connect fails when detect was not called first`() = runTest {
        // Don't call detect() first
        every { TailscaleDetector.detect(any()) } returns null

        val result = strategy.connect(createContext()) {}

        assertTrue(result is ConnectionResult.Failed)
        assertEquals("Tailscale not detected", (result as ConnectionResult.Failed).error)
    }

    @Test
    fun `connect fails when daemon Tailscale IP not in credentials`() = runTest {
        // Detect succeeds
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        // Context doesn't have daemon Tailscale IP
        val result = strategy.connect(createContext(daemonTailscaleIp = null)) {}

        assertTrue(result is ConnectionResult.Failed)
        assertEquals("Daemon Tailscale IP unknown", (result as ConnectionResult.Failed).error)
    }

    @Test
    fun `connect uses daemon Tailscale IP from context`() = runTest {
        // Detect succeeds
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        // Mock TailscaleTransport to succeed
        coEvery { TailscaleTransport.connect(any(), any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)  // Auth success

        // Context has daemon Tailscale IP
        val steps = mutableListOf<ConnectionStep>()
        val result = strategy.connect(
            createContext(daemonTailscaleIp = "100.125.247.41", daemonTailscalePort = 9876)
        ) { steps.add(it) }

        // Verify we got to the "Connecting" step with correct IP
        assertTrue(steps.any { it.step == "Connecting" && it.detail?.contains("100.125.247.41") == true })
        // Verify success
        assertTrue(result is ConnectionResult.Success)
    }

    @Test
    fun `connect uses default port when not specified`() = runTest {
        // Detect succeeds
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        // Mock TailscaleTransport to fail (so we can verify default port was used)
        coEvery { TailscaleTransport.connect(any(), any(), eq(9876)) } throws java.io.IOException("Test failure")

        // Context has IP but no port
        val steps = mutableListOf<ConnectionStep>()
        strategy.connect(
            createContext(daemonTailscaleIp = "100.125.247.41", daemonTailscalePort = null)
        ) { steps.add(it) }

        // Verify we got to the "Connecting" step
        assertTrue(steps.any { it.step == "Connecting" })
        // Verify connect was called with default port 9876
        coVerify { TailscaleTransport.connect(any(), any(), 9876, any()) }
    }

    @Test
    fun `connect reports correct progress steps`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        val steps = mutableListOf<ConnectionStep>()
        strategy.connect(createContext(daemonTailscaleIp = null)) { steps.add(it) }

        // Should have the "Checking" step first
        assertTrue(steps.any { it.step == "Checking" })
    }

    // ==================== Auth Message Format Tests ====================
    // Bug 2 regression: Verify auth message format [4-byte len][device_id][32-byte auth]

    @Test
    fun `auth message format is correct`() = runTest {
        // Detect succeeds
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        // Capture the auth message sent
        val capturedMessage = slot<ByteArray>()
        coEvery { TailscaleTransport.connect(any(), any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(capture(capturedMessage)) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        val deviceId = "test-device-123"
        val authToken = ByteArray(32) { it.toByte() }
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = deviceId,
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = authToken
        )

        strategy.connect(context) {}

        // Verify message format: [4-byte len BE][device_id UTF-8][32-byte auth]
        val message = capturedMessage.captured
        val buffer = ByteBuffer.wrap(message)

        // Check device_id length (big-endian)
        val deviceIdLen = buffer.int
        assertEquals(deviceId.length, deviceIdLen)

        // Check device_id content
        val deviceIdBytes = ByteArray(deviceIdLen)
        buffer.get(deviceIdBytes)
        assertEquals(deviceId, String(deviceIdBytes, Charsets.UTF_8))

        // Check auth token
        val actualAuth = ByteArray(32)
        buffer.get(actualAuth)
        assertArrayEquals(authToken, actualAuth)

        // Total length should be 4 + device_id.length + 32
        assertEquals(4 + deviceId.length + 32, message.size)
    }

    @Test
    fun `device id is UTF-8 encoded`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        val capturedMessage = slot<ByteArray>()
        coEvery { TailscaleTransport.connect(any(), any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(capture(capturedMessage)) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        // Device ID with unicode characters
        val deviceId = "test-\u4e2d\u6587-device"  // Chinese characters
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = deviceId,
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        strategy.connect(context) {}

        val message = capturedMessage.captured
        val buffer = ByteBuffer.wrap(message)

        val deviceIdLen = buffer.int
        val deviceIdBytes = ByteArray(deviceIdLen)
        buffer.get(deviceIdBytes)

        assertEquals(deviceId, String(deviceIdBytes, Charsets.UTF_8))
    }

    @Test
    fun `length prefix is big-endian`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        val capturedMessage = slot<ByteArray>()
        coEvery { TailscaleTransport.connect(any(), any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(capture(capturedMessage)) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)

        // Device ID of exactly 4 bytes
        val deviceId = "test"  // 4 characters = 4 bytes in UTF-8
        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = deviceId,
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.125.247.41",
            daemonTailscalePort = 9876,
            signaling = mockSignaling,
            authToken = ByteArray(32)
        )

        strategy.connect(context) {}

        val message = capturedMessage.captured

        // Big-endian 4 = 0x00000004
        assertEquals(0x00.toByte(), message[0])
        assertEquals(0x00.toByte(), message[1])
        assertEquals(0x00.toByte(), message[2])
        assertEquals(0x04.toByte(), message[3])
    }

    // ==================== Auth Response Tests ====================

    @Test
    fun `success response returns connected transport`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        coEvery { TailscaleTransport.connect(any(), any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x01)  // Success

        val result = strategy.connect(
            createContext(daemonTailscaleIp = "100.125.247.41", daemonTailscalePort = 9876)
        ) {}

        assertTrue(result is ConnectionResult.Success)
    }

    @Test
    fun `failure response returns failed result`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        coEvery { TailscaleTransport.connect(any(), any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf(0x00)  // Failure
        coEvery { mockTransport.close() } returns Unit

        val result = strategy.connect(
            createContext(daemonTailscaleIp = "100.125.247.41", daemonTailscalePort = 9876)
        ) {}

        assertTrue(result is ConnectionResult.Failed)
        assertEquals("Authentication failed", (result as ConnectionResult.Failed).error)
    }

    @Test
    fun `empty response returns failed result`() = runTest {
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")
        strategy.detect()

        coEvery { TailscaleTransport.connect(any(), any(), any(), any()) } returns mockTransport
        coEvery { mockTransport.send(any()) } returns Unit
        coEvery { mockTransport.receive(any()) } returns byteArrayOf()  // Empty
        coEvery { mockTransport.close() } returns Unit

        val result = strategy.connect(
            createContext(daemonTailscaleIp = "100.125.247.41", daemonTailscalePort = 9876)
        ) {}

        assertTrue(result is ConnectionResult.Failed)
    }
}
