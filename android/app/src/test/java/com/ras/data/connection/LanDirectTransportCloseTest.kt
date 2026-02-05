package com.ras.data.connection

import io.mockk.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for LanDirectTransport close/disconnect behavior.
 *
 * Regression tests for the race condition where OkHttp's onClosed callback
 * closes the messageChannel before transport.close() sets the closed flag.
 * Previously, this caused receive() to throw generic TransportException
 * instead of TransportClosedException, which ConnectionManager logged as
 * an ERROR instead of a graceful INFO close.
 *
 * Uses reflection to construct LanDirectTransport with controlled dependencies
 * since the constructor is private (instances are normally created via connect()).
 */
class LanDirectTransportCloseTest {

    private lateinit var mockWebSocket: WebSocket
    private lateinit var messageChannel: Channel<ByteArray>

    @BeforeEach
    fun setup() {
        mockWebSocket = mockk(relaxed = true)
        messageChannel = Channel(Channel.UNLIMITED)
    }

    /**
     * Create a LanDirectTransport via reflection with injected dependencies.
     */
    private fun createTransport(
        ws: WebSocket = mockWebSocket,
        channel: Channel<ByteArray> = messageChannel
    ): LanDirectTransport {
        val constructor = LanDirectTransport::class.java.getDeclaredConstructor(
            WebSocket::class.java,
            Channel::class.java,
            String::class.java,
            Int::class.javaPrimitiveType
        )
        constructor.isAccessible = true
        return constructor.newInstance(ws, channel, "192.168.1.100", 8765) as LanDirectTransport
    }

    // ==================== send() close tests ====================

    @Tag("unit")
    @Test
    fun `send throws TransportClosedException when transport is closed`() = runTest {
        val transport = createTransport()
        transport.close()

        val exception = assertThrows(TransportClosedException::class.java) {
            kotlinx.coroutines.runBlocking { transport.send(byteArrayOf(1, 2, 3)) }
        }

        assertEquals("Transport is closed", exception.message)
    }

    @Tag("unit")
    @Test
    fun `send throws TransportClosedException not generic TransportException`() = runTest {
        val transport = createTransport()
        transport.close()

        // Must be specifically TransportClosedException, not just TransportException
        try {
            transport.send(byteArrayOf(1))
            fail("Expected TransportClosedException")
        } catch (e: TransportClosedException) {
            // Correct - this is the specific type we want
        } catch (e: TransportException) {
            fail("Got generic TransportException instead of TransportClosedException: ${e.message}")
        }
    }

    // ==================== receive() close tests ====================

    @Tag("unit")
    @Test
    fun `receive throws TransportClosedException when transport is closed`() = runTest {
        val transport = createTransport()
        transport.close()

        val exception = assertThrows(TransportClosedException::class.java) {
            kotlinx.coroutines.runBlocking { transport.receive() }
        }

        assertEquals("Transport is closed", exception.message)
    }

    @Tag("unit")
    @Test
    fun `receive throws TransportClosedException not generic TransportException when closed`() = runTest {
        val transport = createTransport()
        transport.close()

        try {
            transport.receive()
            fail("Expected TransportClosedException")
        } catch (e: TransportClosedException) {
            // Correct
        } catch (e: TransportException) {
            fail("Got generic TransportException instead of TransportClosedException: ${e.message}")
        }
    }

    // ==================== Race condition: channel closed before flag ====================

    @Tag("unit")
    @Test
    fun `receive throws TransportClosedException when channel closed but flag not set`() = runTest {
        // This is the exact race condition: OkHttp onClosed closes the channel
        // before transport.close() sets the closed AtomicBoolean to true.
        val transport = createTransport()

        // Close channel directly (simulating OkHttp onClosed callback)
        // without calling transport.close() (which sets the closed flag)
        messageChannel.close()

        val exception = assertThrows(TransportClosedException::class.java) {
            kotlinx.coroutines.runBlocking { transport.receive() }
        }

        // Should have the ClosedReceiveChannelException as cause
        assertNotNull(exception.cause)
        assertTrue(
            exception.cause is kotlinx.coroutines.channels.ClosedReceiveChannelException,
            "Cause should be ClosedReceiveChannelException, got: ${exception.cause}"
        )
    }

    @Tag("unit")
    @Test
    fun `receive throws TransportClosedException when channel closed with error`() = runTest {
        val transport = createTransport()

        // Close channel with an error cause (simulating OkHttp onFailure)
        val cause = java.io.IOException("WebSocket failed: Connection reset")
        messageChannel.close(cause)

        val exception = assertThrows(TransportClosedException::class.java) {
            kotlinx.coroutines.runBlocking { transport.receive() }
        }

        assertNotNull(exception.cause)
    }

    @Tag("unit")
    @Test
    fun `receive with timeout throws TransportClosedException when channel closed`() = runTest {
        val transport = createTransport()

        // Close channel (race condition scenario)
        messageChannel.close()

        val exception = assertThrows(TransportClosedException::class.java) {
            kotlinx.coroutines.runBlocking { transport.receive(timeoutMs = 5000) }
        }

        assertNotNull(exception.cause)
    }

    // ==================== Normal operation still works ====================

    @Tag("unit")
    @Test
    fun `receive returns data normally when transport is open`() = runTest {
        val transport = createTransport()

        messageChannel.trySend(byteArrayOf(1, 2, 3))

        val data = transport.receive()

        assertArrayEquals(byteArrayOf(1, 2, 3), data)

        transport.close()
    }

    @Tag("unit")
    @Test
    fun `send succeeds when transport is open`() = runTest {
        every { mockWebSocket.send(any<okio.ByteString>()) } returns true

        val transport = createTransport()

        transport.send(byteArrayOf(4, 5, 6))

        verify { mockWebSocket.send(byteArrayOf(4, 5, 6).toByteString()) }

        transport.close()
    }

    // ==================== close() idempotency ====================

    @Tag("unit")
    @Test
    fun `close is idempotent`() {
        val transport = createTransport()

        transport.close()
        transport.close() // Should not throw

        // WebSocket.close should only be called once
        verify(exactly = 1) { mockWebSocket.close(1000, "Client closing") }
    }

    @Tag("unit")
    @Test
    fun `isConnected reflects closed state`() {
        val transport = createTransport()

        assertTrue(transport.isConnected)

        transport.close()

        assertFalse(transport.isConnected)
    }
}
