package com.ras.data.reconnection

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests for HttpDirectReconnectionClient timeout behavior.
 *
 * This test exposes the bug where blocking OkHttp execute() calls could not
 * be cancelled by coroutine timeouts. The fix uses async enqueue() with
 * suspendCancellableCoroutine for proper cancellation support.
 *
 * Bug: withTimeoutOrNull() cannot cancel blocking execute() calls
 * Fix: Use enqueue() with suspendCancellableCoroutine and cancel on timeout
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpDirectReconnectionClientTimeoutTest {

    private val testAuthKey = ByteArray(32) { it.toByte() }
    private val testDeviceId = "test-device-123"

    /**
     * This test verifies that HTTP calls are properly cancelled when the coroutine
     * timeout expires.
     *
     * Before the fix:
     * - execute() blocked the thread indefinitely
     * - withTimeoutOrNull() waited forever because it couldn't interrupt the blocking call
     *
     * After the fix:
     * - enqueue() + suspendCancellableCoroutine allows proper cancellation
     * - When timeout expires, call.cancel() is invoked
     */
    @Test
    fun `exchangeCapabilities times out when server never responds`() = runTest {
        val callCancelled = AtomicBoolean(false)

        // Create a mock Call that never completes its callback
        val mockCall = mockk<Call>(relaxed = true)
        every { mockCall.cancel() } answers { callCancelled.set(true) }
        every { mockCall.enqueue(any()) } answers {
            // Simulate a server that never responds - callback is never invoked
            // In the old blocking code, this would hang forever
        }

        val httpClient = mockk<OkHttpClient>()
        every { httpClient.newCall(any()) } returns mockCall

        val client = HttpDirectReconnectionClient(httpClient)

        // Use a short timeout - if blocking execute() was used, this would hang
        val result = withTimeoutOrNull(100L) {
            client.exchangeCapabilities(
                host = "192.168.1.100",
                port = 8765,
                deviceId = testDeviceId,
                authKey = testAuthKey,
                ourCapabilities = com.ras.proto.ConnectionCapabilities.getDefaultInstance()
            )
        }

        // Should return null due to timeout (not hang forever)
        assertNull("Should timeout and return null", result)

        // The OkHttp call should have been cancelled when coroutine was cancelled
        assertTrue("Call should be cancelled on timeout", callCancelled.get())
    }

    @Test
    fun `sendReconnect times out when server never responds`() = runTest {
        val callCancelled = AtomicBoolean(false)

        val mockCall = mockk<Call>(relaxed = true)
        every { mockCall.cancel() } answers { callCancelled.set(true) }
        every { mockCall.enqueue(any()) } answers {
            // Never invoke callback - simulates unresponsive server
        }

        val httpClient = mockk<OkHttpClient>()
        every { httpClient.newCall(any()) } returns mockCall

        val client = HttpDirectReconnectionClient(httpClient)

        val result = withTimeoutOrNull(100L) {
            client.sendReconnect(
                host = "192.168.1.100",
                port = 8765,
                deviceId = testDeviceId,
                authKey = testAuthKey,
                sdpOffer = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\n",
                deviceName = "Test Device"
            )
        }

        assertNull("Should timeout and return null", result)
        assertTrue("Call should be cancelled on timeout", callCancelled.get())
    }

    /**
     * Verifies that when the server eventually responds after timeout, the response
     * is properly ignored and doesn't crash.
     */
    @Test
    fun `late response after timeout is safely ignored`() = runTest {
        var capturedCallback: Callback? = null
        val callCancelled = AtomicBoolean(false)

        val mockCall = mockk<Call>(relaxed = true)
        every { mockCall.cancel() } answers { callCancelled.set(true) }
        every { mockCall.enqueue(any()) } answers {
            capturedCallback = firstArg()
            // Don't invoke callback immediately
        }

        val httpClient = mockk<OkHttpClient>()
        every { httpClient.newCall(any()) } returns mockCall

        val client = HttpDirectReconnectionClient(httpClient)

        // Start the call and let it timeout
        val result = withTimeoutOrNull(100L) {
            client.exchangeCapabilities(
                host = "192.168.1.100",
                port = 8765,
                deviceId = testDeviceId,
                authKey = testAuthKey,
                ourCapabilities = com.ras.proto.ConnectionCapabilities.getDefaultInstance()
            )
        }

        assertNull("Should timeout", result)

        // Now simulate the response arriving late (after timeout)
        // This should not crash - the continuation should be inactive
        val mockResponse = mockk<Response>(relaxed = true)
        every { mockResponse.isSuccessful } returns true
        every { mockResponse.body } returns null

        // This should not throw even though continuation is already cancelled
        capturedCallback?.onResponse(mockCall, mockResponse)

        // Test passes if no exception is thrown
    }

    @Test
    fun `network error is handled gracefully`() = runTest {
        val mockCall = mockk<Call>(relaxed = true)
        every { mockCall.enqueue(any()) } answers {
            val callback = firstArg<Callback>()
            callback.onFailure(mockCall, java.io.IOException("Connection refused"))
        }

        val httpClient = mockk<OkHttpClient>()
        every { httpClient.newCall(any()) } returns mockCall

        val client = HttpDirectReconnectionClient(httpClient)

        val result = client.exchangeCapabilities(
            host = "192.168.1.100",
            port = 8765,
            deviceId = testDeviceId,
            authKey = testAuthKey,
            ourCapabilities = com.ras.proto.ConnectionCapabilities.getDefaultInstance()
        )

        // Should return null on network error, not throw
        assertNull("Should return null on network error", result)
    }
}
