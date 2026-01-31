package com.ras.data.connection

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Tests demonstrating DatagramSocketFactory usage for dependency injection.
 *
 * These tests verify:
 * - Mock socket injection works correctly
 * - Handshake behavior can be tested without real network
 * - Socket lifecycle is properly managed
 */
class SocketFactoryTest {

    private lateinit var mockSocket: DatagramSocket
    private lateinit var mockSocketFactory: DatagramSocketFactory

    @Before
    fun setup() {
        mockSocket = mockk(relaxed = true)
        mockSocketFactory = mockk()

        // Default: factory returns mock socket
        every { mockSocketFactory.createConnected(any()) } returns mockSocket
    }

    // ==================== Factory Interface Tests ====================

    @Test
    fun `DefaultDatagramSocketFactory creates real socket`() {
        val factory = DefaultDatagramSocketFactory()

        val socket = factory.create()

        assertNotNull(socket)
        assertFalse(socket.isClosed)
        socket.close()
    }

    @Test
    fun `DefaultDatagramSocketFactory creates connected socket`() {
        val factory = DefaultDatagramSocketFactory()
        val address = InetSocketAddress("127.0.0.1", 12345)

        val socket = factory.createConnected(address)

        assertNotNull(socket)
        assertTrue(socket.isConnected)
        assertEquals(address, socket.remoteSocketAddress)
        socket.close()
    }

    // ==================== Mock Socket Injection Tests ====================

    @Test
    fun `mock socket factory is called by TailscaleTransport`() = runTest {
        // Setup mock to simulate successful handshake
        val handshakeMagic = 0x52415354  // "RAST"
        val responseBytes = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(responseBytes, 0, packet.data, 0, responseBytes.size)
            packet.length = responseBytes.size
        }
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321

        val transport = TailscaleTransport.connect(
            localIp = "100.64.0.1",
            remoteIp = "100.64.0.2",
            remotePort = 9876,
            socketFactory = mockSocketFactory
        )

        // Verify factory was used
        verify { mockSocketFactory.createConnected(any()) }

        // Verify socket operations occurred
        verify { mockSocket.send(any()) }
        verify { mockSocket.receive(any()) }

        transport.close()
    }

    @Test
    fun `mock socket can simulate handshake timeout`() = runTest {
        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } throws SocketTimeoutException("Timeout")
        every { mockSocket.close() } just Runs
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321

        try {
            TailscaleTransport.connect(
                localIp = "100.64.0.1",
                remoteIp = "100.64.0.2",
                socketFactory = mockSocketFactory
            )
            fail("Should throw IOException after retries")
        } catch (e: Exception) {
            // Expected - handshake failed after retries
            assertTrue(e.message?.contains("Handshake failed") == true)
        }

        // Verify socket was closed after failure
        verify { mockSocket.close() }
    }

    @Test
    fun `mock socket can verify retry count`() = runTest {
        var receiveCallCount = 0

        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } answers {
            receiveCallCount++
            throw SocketTimeoutException("Timeout")
        }
        every { mockSocket.close() } just Runs
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321

        try {
            TailscaleTransport.connect(
                localIp = "100.64.0.1",
                remoteIp = "100.64.0.2",
                socketFactory = mockSocketFactory
            )
        } catch (e: Exception) {
            // Expected
        }

        // Should have tried 3 times (HANDSHAKE_MAX_RETRIES = 3)
        assertEquals("Should retry 3 times", 3, receiveCallCount)
    }

    @Test
    fun `mock socket can simulate success on second attempt`() = runTest {
        var attemptCount = 0
        val handshakeMagic = 0x52415354
        val responseBytes = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } answers {
            attemptCount++
            if (attemptCount < 2) {
                throw SocketTimeoutException("Timeout")
            }
            // Second attempt succeeds
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(responseBytes, 0, packet.data, 0, responseBytes.size)
            packet.length = responseBytes.size
        }
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321

        val transport = TailscaleTransport.connect(
            localIp = "100.64.0.1",
            remoteIp = "100.64.0.2",
            socketFactory = mockSocketFactory
        )

        assertNotNull(transport)
        assertEquals("Should succeed on second attempt", 2, attemptCount)
        transport.close()
    }

    // ==================== Socket Reuse Tests ====================

    @Test
    fun `handshake retry uses same socket - factory called only once`() = runTest {
        /**
         * Verify that all retry attempts use the same socket instance.
         * This is critical because:
         * - UDP sockets get an ephemeral port when created
         * - The daemon responds to this port
         * - If we create a new socket for each retry, responses go to wrong port
         */
        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } throws SocketTimeoutException("Timeout")
        every { mockSocket.close() } just Runs
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321

        try {
            TailscaleTransport.connect(
                localIp = "100.64.0.1",
                remoteIp = "100.64.0.2",
                socketFactory = mockSocketFactory
            )
        } catch (e: Exception) {
            // Expected
        }

        // Factory should only be called ONCE - same socket reused for all retries
        verify(exactly = 1) { mockSocketFactory.createConnected(any()) }

        // But send should be called 3 times (one per retry)
        verify(exactly = 3) { mockSocket.send(any()) }
    }

    @Test
    fun `socket closed on final failure`() = runTest {
        /**
         * Verify socket is properly cleaned up when all retries are exhausted.
         */
        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } throws SocketTimeoutException("Timeout")
        every { mockSocket.close() } just Runs
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321

        try {
            TailscaleTransport.connect(
                localIp = "100.64.0.1",
                remoteIp = "100.64.0.2",
                socketFactory = mockSocketFactory
            )
            fail("Should throw IOException")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Handshake failed") == true)
        }

        // Socket MUST be closed after all retries exhausted
        verify(exactly = 1) { mockSocket.close() }
    }

    @Test
    fun `socket not closed on successful handshake`() = runTest {
        /**
         * On success, socket should remain open for the transport to use.
         */
        val handshakeMagic = 0x52415354
        val responseBytes = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(responseBytes, 0, packet.data, 0, responseBytes.size)
            packet.length = responseBytes.size
        }
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321

        val transport = TailscaleTransport.connect(
            localIp = "100.64.0.1",
            remoteIp = "100.64.0.2",
            socketFactory = mockSocketFactory
        )

        // Socket should NOT be closed yet - transport is using it
        verify(exactly = 0) { mockSocket.close() }

        // Now close the transport
        transport.close()

        // Now socket should be closed
        verify(exactly = 1) { mockSocket.close() }
    }

    // ==================== Strategy with Mock Socket Tests ====================

    @Test
    fun `TailscaleStrategy uses injected socket factory`() = runTest {
        val handshakeMagic = 0x52415354
        val responseBytes = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(responseBytes, 0, packet.data, 0, responseBytes.size)
            packet.length = responseBytes.size
        }
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321

        mockkObject(TailscaleDetector)
        every { TailscaleDetector.detect(any()) } returns TailscaleInfo("100.64.0.1", "tailscale0")

        val mockContext = mockk<android.content.Context>(relaxed = true)
        val strategy = TailscaleStrategy(mockContext, mockSocketFactory)

        strategy.detect()

        val context = ConnectionContext(
            androidContext = mockContext,
            deviceId = "test-device",
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            daemonTailscaleIp = "100.64.0.2",
            daemonTailscalePort = 9876,
            signaling = mockk(relaxed = true),
            authToken = ByteArray(32) { it.toByte() }
        )

        val result = strategy.connect(context) {}

        // Verify factory was used
        verify { mockSocketFactory.createConnected(any()) }

        unmockkObject(TailscaleDetector)
    }
}
