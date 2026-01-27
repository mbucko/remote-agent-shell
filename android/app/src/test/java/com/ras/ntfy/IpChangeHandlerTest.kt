package com.ras.ntfy

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class IpChangeHandlerTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockIpChanges: MutableSharedFlow<IpChangeData>
    private lateinit var mockSubscriber: NtfySubscriber

    @Before
    fun setup() {
        mockIpChanges = MutableSharedFlow()
        mockSubscriber = mockk(relaxed = true) {
            every { ipChanges } returns mockIpChanges
        }
    }

    // ============================================================================
    // Successful Reconnection Tests
    // ============================================================================

    @Test
    fun `successful reconnection triggers onReconnectSuccess callback`() = runTest(testDispatcher) {
        val successCalled = AtomicBoolean(false)
        val reconnectCalled = AtomicBoolean(false)

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { _, _ ->
                reconnectCalled.set(true)
                true  // Success
            },
            onReconnectSuccess = {
                successCalled.set(true)
            },
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        // Emit IP change
        val testData = createTestIpChangeData()
        mockIpChanges.emit(testData)
        advanceUntilIdle()

        assertTrue("onReconnect should be called", reconnectCalled.get())
        assertTrue("onReconnectSuccess should be called", successCalled.get())
        verify { mockSubscriber.resetReconnectCounter() }

        handler.stop()
    }

    @Test
    fun `reconnection receives correct IP and port`() = runTest(testDispatcher) {
        var receivedIp: String? = null
        var receivedPort: Int? = null

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { ip, port ->
                receivedIp = ip
                receivedPort = port
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        val testData = IpChangeData(
            ip = "10.0.0.1",
            port = 9999,
            timestamp = System.currentTimeMillis() / 1000,
            nonce = ByteArray(16)
        )
        mockIpChanges.emit(testData)
        advanceUntilIdle()

        assertEquals("10.0.0.1", receivedIp)
        assertEquals(9999, receivedPort)

        handler.stop()
    }

    // ============================================================================
    // Failed Reconnection Tests
    // ============================================================================

    @Test
    fun `failed reconnection does not trigger onReconnectSuccess`() = runTest(testDispatcher) {
        val successCalled = AtomicBoolean(false)

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { _, _ -> false },  // Failure
            onReconnectSuccess = { successCalled.set(true) },
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        mockIpChanges.emit(createTestIpChangeData())
        advanceUntilIdle()

        assertTrue("onReconnectSuccess should NOT be called", !successCalled.get())
        verify(exactly = 0) { mockSubscriber.resetReconnectCounter() }

        handler.stop()
    }

    @Test
    fun `reconnection exception does not crash handler`() = runTest(testDispatcher) {
        var exceptionThrown = false

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { _, _ ->
                exceptionThrown = true
                throw RuntimeException("Network error")
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        // Should not throw
        mockIpChanges.emit(createTestIpChangeData())
        advanceUntilIdle()

        assertTrue("Exception should have been thrown in callback", exceptionThrown)
        verify(exactly = 0) { mockSubscriber.resetReconnectCounter() }

        handler.stop()
    }

    // ============================================================================
    // Multiple IP Change Tests
    // ============================================================================

    @Test
    fun `handles multiple IP changes sequentially`() = runTest(testDispatcher) {
        val reconnectCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { _, _ ->
                reconnectCount.incrementAndGet()
                true
            },
            onReconnectSuccess = { successCount.incrementAndGet() },
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        // Emit multiple IP changes
        repeat(5) { i ->
            val data = IpChangeData(
                ip = "192.168.1.$i",
                port = 8821 + i,
                timestamp = System.currentTimeMillis() / 1000,
                nonce = ByteArray(16) { i.toByte() }
            )
            mockIpChanges.emit(data)
            advanceUntilIdle()
        }

        assertEquals("Should handle 5 reconnects", 5, reconnectCount.get())
        assertEquals("Should call success 5 times", 5, successCount.get())

        handler.stop()
    }

    // ============================================================================
    // Start/Stop Lifecycle Tests
    // ============================================================================

    @Test
    fun `stop cancels collection`() = runTest(testDispatcher) {
        val reconnectCalled = AtomicBoolean(false)

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { _, _ ->
                reconnectCalled.set(true)
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        handler.stop()
        advanceUntilIdle()

        // Emit after stop - should not process
        mockIpChanges.emit(createTestIpChangeData())
        advanceUntilIdle()

        assertTrue("onReconnect should NOT be called after stop", !reconnectCalled.get())
    }

    @Test
    fun `can restart after stop`() = runTest(testDispatcher) {
        val reconnectCalled = AtomicBoolean(false)

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { _, _ ->
                reconnectCalled.set(true)
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()
        handler.stop()
        advanceUntilIdle()

        // Restart
        handler.start()
        advanceUntilIdle()

        mockIpChanges.emit(createTestIpChangeData())
        advanceUntilIdle()

        assertTrue("onReconnect should be called after restart", reconnectCalled.get())

        handler.stop()
    }

    // ============================================================================
    // IPv6 Tests
    // ============================================================================

    @Test
    fun `handles IPv6 addresses correctly`() = runTest(testDispatcher) {
        var receivedIp: String? = null

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { ip, _ ->
                receivedIp = ip
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        val ipv6Data = IpChangeData(
            ip = "2001:db8::1",
            port = 8821,
            timestamp = System.currentTimeMillis() / 1000,
            nonce = ByteArray(16)
        )
        mockIpChanges.emit(ipv6Data)
        advanceUntilIdle()

        assertEquals("2001:db8::1", receivedIp)

        handler.stop()
    }

    @Test
    fun `handles full IPv6 addresses`() = runTest(testDispatcher) {
        var receivedIp: String? = null

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { ip, _ ->
                receivedIp = ip
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        val ipv6Data = IpChangeData(
            ip = "2001:0db8:0000:0000:0000:0000:0000:0001",
            port = 8821,
            timestamp = System.currentTimeMillis() / 1000,
            nonce = ByteArray(16)
        )
        mockIpChanges.emit(ipv6Data)
        advanceUntilIdle()

        assertEquals("2001:0db8:0000:0000:0000:0000:0000:0001", receivedIp)

        handler.stop()
    }

    // ============================================================================
    // Edge Case Tests
    // ============================================================================

    @Test
    fun `handles port 0`() = runTest(testDispatcher) {
        var receivedPort: Int? = null

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { _, port ->
                receivedPort = port
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        val data = IpChangeData(
            ip = "127.0.0.1",
            port = 0,
            timestamp = System.currentTimeMillis() / 1000,
            nonce = ByteArray(16)
        )
        mockIpChanges.emit(data)
        advanceUntilIdle()

        assertEquals(0, receivedPort)

        handler.stop()
    }

    @Test
    fun `handles max port number`() = runTest(testDispatcher) {
        var receivedPort: Int? = null

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { _, port ->
                receivedPort = port
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        val data = IpChangeData(
            ip = "127.0.0.1",
            port = 65535,
            timestamp = System.currentTimeMillis() / 1000,
            nonce = ByteArray(16)
        )
        mockIpChanges.emit(data)
        advanceUntilIdle()

        assertEquals(65535, receivedPort)

        handler.stop()
    }

    @Test
    fun `handles localhost address`() = runTest(testDispatcher) {
        var receivedIp: String? = null

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { ip, _ ->
                receivedIp = ip
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        val data = IpChangeData(
            ip = "127.0.0.1",
            port = 8821,
            timestamp = System.currentTimeMillis() / 1000,
            nonce = ByteArray(16)
        )
        mockIpChanges.emit(data)
        advanceUntilIdle()

        assertEquals("127.0.0.1", receivedIp)

        handler.stop()
    }

    @Test
    fun `handles private network address`() = runTest(testDispatcher) {
        var receivedIp: String? = null

        val handler = IpChangeHandler(
            ntfySubscriber = mockSubscriber,
            onReconnect = { ip, _ ->
                receivedIp = ip
                true
            },
            onReconnectSuccess = {},
            dispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        handler.start()
        advanceUntilIdle()

        val data = IpChangeData(
            ip = "10.0.0.1",
            port = 8821,
            timestamp = System.currentTimeMillis() / 1000,
            nonce = ByteArray(16)
        )
        mockIpChanges.emit(data)
        advanceUntilIdle()

        assertEquals("10.0.0.1", receivedIp)

        handler.stop()
    }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun createTestIpChangeData(): IpChangeData {
        return IpChangeData(
            ip = "192.168.1.100",
            port = 8821,
            timestamp = System.currentTimeMillis() / 1000,
            nonce = ByteArray(16) { it.toByte() }
        )
    }
}
