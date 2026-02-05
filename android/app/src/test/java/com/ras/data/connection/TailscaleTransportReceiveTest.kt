package com.ras.data.connection

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Tests for TailscaleTransport.receive() stale handshake packet handling.
 *
 * Bug: UDP can deliver duplicate handshake response packets during the auth phase.
 * The receive() method must skip these (8-byte packets starting with HANDSHAKE_MAGIC)
 * instead of interpreting the magic bytes as a length prefix.
 *
 * HANDSHAKE_MAGIC = 0x52415354 = 1380012884 = "RAST" in ASCII.
 */
class TailscaleTransportReceiveTest {

    private lateinit var mockSocket: DatagramSocket
    private lateinit var mockSocketFactory: DatagramSocketFactory
    private val handshakeMagic = 0x52415354  // "RAST"

    @BeforeEach
    fun setup() {
        mockSocket = mockk(relaxed = true)
        mockSocketFactory = mockk()
        every { mockSocketFactory.createConnected(any()) } returns mockSocket
        every { mockSocket.localAddress } returns mockk(relaxed = true)
        every { mockSocket.localPort } returns 54321
    }

    @AfterEach
    fun tearDown() {
        // nothing
    }

    /**
     * Create a connected TailscaleTransport using mock socket.
     * Simulates successful handshake, then allows receive() testing.
     */
    private suspend fun createConnectedTransport(): TailscaleTransport {
        val handshakeResponse = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        // Handshake receive: return magic response
        every { mockSocket.soTimeout = any() } just Runs
        every { mockSocket.send(any()) } just Runs
        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(handshakeResponse, 0, packet.data, 0, handshakeResponse.size)
            packet.length = handshakeResponse.size
        }

        val transport = TailscaleTransport.connect(
            localIp = "100.64.0.1",
            remoteIp = "100.64.0.2",
            remotePort = 9876,
            socketFactory = mockSocketFactory
        )

        return transport
    }

    // ==================== Stale Handshake Skip Tests ====================

    @Tag("unit")
    @Test
    fun `receive skips stale handshake packet and returns next valid data`() = runTest {
        val transport = createConnectedTransport()

        // Build a stale handshake packet (8 bytes: HANDSHAKE_MAGIC + reserved)
        val staleHandshake = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        // Build a valid data packet (4-byte length prefix + payload)
        val payload = "hello".toByteArray()
        val validPacket = ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()

        var receiveCount = 0
        // Now override receive for the data phase
        every { mockSocket.receive(any()) } answers {
            receiveCount++
            val packet = firstArg<DatagramPacket>()
            if (receiveCount == 1) {
                // First: stale handshake
                System.arraycopy(staleHandshake, 0, packet.data, 0, staleHandshake.size)
                packet.length = staleHandshake.size
            } else {
                // Second: valid data
                System.arraycopy(validPacket, 0, packet.data, 0, validPacket.size)
                packet.length = validPacket.size
            }
        }

        val data = transport.receive()

        assertEquals("hello", String(data))
        assertEquals(2, receiveCount, "Should have called socket.receive twice (skip + valid)")

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `receive skips multiple stale handshake packets before valid data`() = runTest {
        val transport = createConnectedTransport()

        val staleHandshake = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        val payload = "data".toByteArray()
        val validPacket = ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()

        var receiveCount = 0
        every { mockSocket.receive(any()) } answers {
            receiveCount++
            val packet = firstArg<DatagramPacket>()
            if (receiveCount <= 2) {
                // First two: stale handshakes
                System.arraycopy(staleHandshake, 0, packet.data, 0, staleHandshake.size)
                packet.length = staleHandshake.size
            } else {
                // Third: valid data
                System.arraycopy(validPacket, 0, packet.data, 0, validPacket.size)
                packet.length = validPacket.size
            }
        }

        val data = transport.receive()

        assertEquals("data", String(data))
        assertEquals(3, receiveCount, "Should skip 2 handshake packets then read valid data")

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `receive throws after max stale handshake packets exceeded`() = runTest {
        val transport = createConnectedTransport()

        val staleHandshake = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        // Always return stale handshake packets
        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(staleHandshake, 0, packet.data, 0, staleHandshake.size)
            packet.length = staleHandshake.size
        }

        val exception = assertThrows(TransportException::class.java) {
            kotlinx.coroutines.runBlocking {
                transport.receive()
            }
        }

        assertTrue(
            exception.message?.contains("Too many stale handshake packets") == true,
            "Should indicate too many stale packets, got: ${exception.message}"
        )

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `receive still throws on genuinely invalid length prefix`() = runTest {
        val transport = createConnectedTransport()

        // Build a packet with an invalid length (larger than packet data)
        val invalidPacket = ByteBuffer.allocate(20)
            .putInt(999999)  // Invalid length - way larger than remaining 16 bytes
            .put(ByteArray(16))
            .array()

        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(invalidPacket, 0, packet.data, 0, invalidPacket.size)
            packet.length = invalidPacket.size
        }

        val exception = assertThrows(TransportException::class.java) {
            kotlinx.coroutines.runBlocking {
                transport.receive()
            }
        }

        assertTrue(
            exception.message?.contains("Invalid message length") == true,
            "Should report invalid length, got: ${exception.message}"
        )

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `receive does not skip non-handshake 8-byte packets`() = runTest {
        val transport = createConnectedTransport()

        // Build a valid 8-byte data packet (4-byte length=4, 4 bytes of data)
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val validSmallPacket = ByteBuffer.allocate(8)
            .putInt(4)  // Not HANDSHAKE_MAGIC (4 != 0x52415354)
            .put(payload)
            .array()

        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(validSmallPacket, 0, packet.data, 0, validSmallPacket.size)
            packet.length = validSmallPacket.size
        }

        val data = transport.receive()

        assertArrayEquals(payload, data, "Should return the 4-byte payload without skipping")

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `receive normal packet works without stale handshake interference`() = runTest {
        val transport = createConnectedTransport()

        val payload = "normal data packet".toByteArray()
        val validPacket = ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()

        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(validPacket, 0, packet.data, 0, validPacket.size)
            packet.length = validPacket.size
        }

        val data = transport.receive()

        assertEquals("normal data packet", String(data))

        transport.close()
    }

    // ==================== Boundary Condition Tests ====================

    @Tag("unit")
    @Test
    fun `receive does not skip packet with HANDSHAKE_MAGIC but larger than 8 bytes`() = runTest {
        /**
         * Only exactly 8-byte packets are handshake packets.
         * A larger packet that happens to start with HANDSHAKE_MAGIC is NOT a handshake -
         * it's a (likely invalid) data packet and should be rejected via length validation.
         */
        val transport = createConnectedTransport()

        // 12-byte packet starting with HANDSHAKE_MAGIC (not exactly 8 bytes)
        val oversizedHandshake = ByteBuffer.allocate(12)
            .putInt(handshakeMagic)  // length field = 0x52415354 = 1380012884
            .putInt(0)
            .putInt(0)
            .array()

        every { mockSocket.receive(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            System.arraycopy(oversizedHandshake, 0, packet.data, 0, oversizedHandshake.size)
            packet.length = oversizedHandshake.size
        }

        // Should NOT skip - should fail with "Invalid message length" since 1380012884 > 8
        val exception = assertThrows(TransportException::class.java) {
            kotlinx.coroutines.runBlocking { transport.receive() }
        }

        assertTrue(
            exception.message?.contains("Invalid message length") == true,
            "Non-8-byte packet with magic should fail length validation, got: ${exception.message}"
        )

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `receive handles too-small packet after skipping a handshake`() = runTest {
        /**
         * After skipping a stale handshake, the next packet might be malformed (too small).
         * The "packet too small" check must work within the skip loop.
         */
        val transport = createConnectedTransport()

        val staleHandshake = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        var receiveCount = 0
        every { mockSocket.receive(any()) } answers {
            receiveCount++
            val packet = firstArg<DatagramPacket>()
            if (receiveCount == 1) {
                // First: stale handshake (skipped)
                System.arraycopy(staleHandshake, 0, packet.data, 0, staleHandshake.size)
                packet.length = staleHandshake.size
            } else {
                // Second: too-small packet (only 2 bytes)
                packet.data[0] = 0x01
                packet.data[1] = 0x02
                packet.length = 2
            }
        }

        val exception = assertThrows(TransportException::class.java) {
            kotlinx.coroutines.runBlocking { transport.receive() }
        }

        assertTrue(
            exception.message?.contains("Packet too small") == true,
            "Should detect too-small packet after skipping handshake, got: ${exception.message}"
        )

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `receive timeout during skip loop propagates as timeout`() = runTest {
        /**
         * If a SocketTimeoutException occurs while waiting for a valid packet
         * (after already skipping a stale handshake), it should propagate as
         * a recoverable TransportException timeout, not be swallowed.
         */
        val transport = createConnectedTransport()

        val staleHandshake = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        var receiveCount = 0
        every { mockSocket.receive(any()) } answers {
            receiveCount++
            val packet = firstArg<DatagramPacket>()
            if (receiveCount == 1) {
                // First: stale handshake (skipped)
                System.arraycopy(staleHandshake, 0, packet.data, 0, staleHandshake.size)
                packet.length = staleHandshake.size
            } else {
                // Second: timeout waiting for real data
                throw SocketTimeoutException("Read timed out")
            }
        }

        val exception = assertThrows(TransportException::class.java) {
            kotlinx.coroutines.runBlocking { transport.receive(timeoutMs = 1000) }
        }

        assertTrue(
            exception.message?.contains("Receive timeout") == true,
            "Should propagate timeout after skipping handshake, got: ${exception.message}"
        )
        assertTrue(exception.isRecoverable, "Timeout should be recoverable")

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `receive skip counter resets between calls`() = runTest {
        /**
         * Each receive() call gets a fresh budget of 3 skips.
         * Skipping 2 handshakes in one call should not affect the next call's budget.
         */
        val transport = createConnectedTransport()

        val staleHandshake = ByteBuffer.allocate(8)
            .putInt(handshakeMagic)
            .putInt(0)
            .array()

        val payload = "ok".toByteArray()
        val validPacket = ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()

        // First receive() call: skip 2 handshakes, then get valid data
        var callCount = 0
        every { mockSocket.receive(any()) } answers {
            callCount++
            val packet = firstArg<DatagramPacket>()
            if (callCount <= 2) {
                System.arraycopy(staleHandshake, 0, packet.data, 0, staleHandshake.size)
                packet.length = staleHandshake.size
            } else {
                System.arraycopy(validPacket, 0, packet.data, 0, validPacket.size)
                packet.length = validPacket.size
            }
        }

        val data1 = transport.receive()
        assertEquals("ok", String(data1))

        // Second receive() call: skip 2 more handshakes, then get valid data
        // This should work because the skip counter resets per call
        callCount = 0
        every { mockSocket.receive(any()) } answers {
            callCount++
            val packet = firstArg<DatagramPacket>()
            if (callCount <= 2) {
                System.arraycopy(staleHandshake, 0, packet.data, 0, staleHandshake.size)
                packet.length = staleHandshake.size
            } else {
                System.arraycopy(validPacket, 0, packet.data, 0, validPacket.size)
                packet.length = validPacket.size
            }
        }

        val data2 = transport.receive()
        assertEquals("ok", String(data2), "Second call should also succeed with fresh skip budget")

        transport.close()
    }
}
