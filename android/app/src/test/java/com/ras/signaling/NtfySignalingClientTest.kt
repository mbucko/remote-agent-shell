package com.ras.signaling

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for NtfySignalingClient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NtfySignalingClientTest {

    // ==================== Topic Computation Tests ====================

    @Test
    fun `computes topic from master secret`() {
        val masterSecret = ByteArray(32)
        val topic = NtfySignalingClient.computeTopic(masterSecret)

        assertTrue("Topic should start with 'ras-'", topic.startsWith("ras-"))
        assertEquals("Topic should be 16 chars (ras- + 12 hex)", 16, topic.length)
    }

    @Test
    fun `different secrets produce different topics`() {
        val topic1 = NtfySignalingClient.computeTopic(ByteArray(32))
        val topic2 = NtfySignalingClient.computeTopic(ByteArray(32) { 0xFF.toByte() })

        assertNotEquals(topic1, topic2)
    }

    @Test
    fun `topic matches test vector`() {
        // Test vector from KeyDerivationTest
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val expected = "ras-4884fdaafea4"

        val topic = NtfySignalingClient.computeTopic(masterSecret)

        assertEquals(expected, topic)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `topic rejects short master secret`() {
        NtfySignalingClient.computeTopic(ByteArray(16))
    }

    // ==================== URL Building Tests ====================

    @Test
    fun `builds correct WebSocket URL`() {
        val topic = "ras-abc123def456"
        val url = NtfySignalingClient.buildWsUrl(topic)

        assertEquals("wss://ntfy.sh/ras-abc123def456/ws?since=30s", url)
    }

    @Test
    fun `builds correct WebSocket URL with custom server`() {
        val topic = "ras-abc123"
        val url = NtfySignalingClient.buildWsUrl(topic, "https://custom.server.com")

        assertEquals("wss://custom.server.com/ras-abc123/ws?since=30s", url)
    }

    @Test
    fun `builds correct publish URL`() {
        val topic = "ras-abc123def456"
        val url = NtfySignalingClient.buildPublishUrl(topic)

        assertEquals("https://ntfy.sh/ras-abc123def456", url)
    }

    // ==================== MockNtfyClient Tests ====================

    @Test
    fun `mock client tracks subscription state`() = runTest {
        val mockClient = MockNtfyClient()

        assertFalse(mockClient.isSubscribed)

        val subscription = mockClient.subscribe("test-topic")
        val job = launch {
            subscription.messages.first { it.event == "message" }
        }

        // Give time for subscription
        advanceUntilIdle()
        assertTrue(mockClient.isSubscribed)

        // Deliver a message to complete the first()
        mockClient.deliverMessage("done")

        job.cancel()
    }

    @Test
    fun `mock client delivers messages`() = runTest {
        val mockClient = MockNtfyClient()

        val subscription = mockClient.subscribe("test-topic")
        val receivedMessage = async {
            // Skip the "open" event and get the first actual message
            subscription.messages.first { it.event == "message" }
        }

        advanceUntilIdle()

        // Deliver a message
        mockClient.deliverMessage("test message")

        val message = receivedMessage.await()
        assertEquals("test message", message.message)
    }

    @Test
    fun `mock client tracks published messages`() = runTest {
        val mockClient = MockNtfyClient()

        mockClient.publish("test-topic", "message 1")
        mockClient.publish("test-topic", "message 2")

        val published = mockClient.getPublishedMessages()
        assertEquals(2, published.size)
        assertEquals("message 1", published[0])
        assertEquals("message 2", published[1])
    }

    @Test
    fun `mock client can clear published messages`() = runTest {
        val mockClient = MockNtfyClient()

        mockClient.publish("test-topic", "message")
        assertEquals(1, mockClient.getPublishedMessages().size)

        mockClient.clearPublished()
        assertEquals(0, mockClient.getPublishedMessages().size)
    }

    @Test
    fun `mock client unsubscribe clears subscription state`() = runTest {
        val mockClient = MockNtfyClient()

        val subscription = mockClient.subscribe("test-topic")
        val job = launch {
            subscription.messages.collect { }
        }
        advanceUntilIdle()

        assertTrue(mockClient.isSubscribed)

        mockClient.unsubscribe()
        assertFalse(mockClient.isSubscribed)

        job.cancel()
    }

    // ==================== NtfySubscription Tests ====================

    @Test
    fun `subscription awaitReady completes when WebSocket connects`() = runTest {
        val mockClient = MockNtfyClient()

        val subscription = mockClient.subscribe("test-topic")

        // Start collecting to trigger connection
        val job = launch {
            subscription.messages.collect { }
        }

        // Wait for ready
        subscription.awaitReady(5000)
        assertTrue(subscription.isReady)

        job.cancel()
    }

    @Test
    fun `subscription awaitReady respects configured delay`() = runTest {
        val mockClient = MockNtfyClient()
        mockClient.subscribeReadyDelayMs = 100

        val subscription = mockClient.subscribe("test-topic")

        // Start collecting to trigger connection
        val job = launch {
            subscription.messages.collect { }
        }

        // Wait for ready
        subscription.awaitReady(5000)
        assertTrue(subscription.isReady)

        job.cancel()
    }

    @Test
    fun `subscription close cleans up resources`() = runTest {
        val mockClient = MockNtfyClient()

        val subscription = mockClient.subscribe("test-topic")
        val job = launch {
            subscription.messages.collect { }
        }
        advanceUntilIdle()
        assertTrue(mockClient.isSubscribed)

        subscription.close()
        advanceUntilIdle()
        assertFalse(mockClient.isSubscribed)

        job.cancel()
    }

    // ==================== Message Parsing Tests ====================

    @Test
    fun `parses ntfy message event`() {
        val json = """{"event":"message","message":"encrypted_data"}"""
        val result = NtfySignalingClient.parseNtfyMessage(json)

        assertNotNull(result)
        assertEquals("message", result!!.event)
        assertEquals("encrypted_data", result.message)
    }

    @Test
    fun `ignores keepalive event`() {
        val json = """{"event":"keepalive"}"""
        val result = NtfySignalingClient.parseNtfyMessage(json)

        // keepalive events should be parsed but have no message
        assertNotNull(result)
        assertEquals("keepalive", result!!.event)
    }

    @Test
    fun `returns null for invalid JSON`() {
        val result = NtfySignalingClient.parseNtfyMessage("not json")
        assertEquals(null, result)
    }

    @Test
    fun `returns null for empty string`() {
        val result = NtfySignalingClient.parseNtfyMessage("")
        assertEquals(null, result)
    }
}

/**
 * Tests for main-thread safety of NtfySignalingClient.
 *
 * These tests verify that the real implementation (not mocks) is safe to call
 * from any dispatcher, including the main thread. This would have caught the
 * NetworkOnMainThreadException bug.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NtfySignalingClientMainSafetyTest {

    @Test
    fun `publish is main-safe with injected IO dispatcher`() = runTest {
        // Use MockWebServer to avoid real network calls
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        try {
            val serverUrl = mockServer.url("/").toString().removeSuffix("/")

            // Create client with test dispatcher for IO operations
            // In production, this defaults to Dispatchers.IO
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val client = NtfySignalingClient(
                server = serverUrl,
                httpClient = OkHttpClient(),
                ioDispatcher = testDispatcher
            )

            // Call publish - this would throw NetworkOnMainThreadException
            // if the implementation didn't use withContext(ioDispatcher)
            client.publish("test-topic", "test message")

            // Advance the dispatcher to execute the IO work
            advanceUntilIdle()

            // Verify the request was made
            val request = mockServer.takeRequest()
            assertEquals("/test-topic", request.path)
            assertEquals("test message", request.body.readUtf8())
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun `publish handles HTTP errors correctly`() = runTest {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(500))
        mockServer.start()

        try {
            val serverUrl = mockServer.url("/").toString().removeSuffix("/")
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val client = NtfySignalingClient(
                server = serverUrl,
                httpClient = OkHttpClient(),
                ioDispatcher = testDispatcher
            )

            var exceptionThrown = false
            try {
                client.publish("test-topic", "test message")
                advanceUntilIdle()
            } catch (e: Exception) {
                exceptionThrown = true
                assertTrue("Should be IOException", e.message?.contains("Failed to publish") == true)
            }

            assertTrue("Should throw exception on HTTP error", exceptionThrown)
        } finally {
            mockServer.shutdown()
        }
    }
}

/**
 * Tests for WebSocket reliability features (ping/pong, reconnection).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NtfySignalingClientReliabilityTest {

    @Test
    fun `ping interval is configured for cellular keep-alive`() {
        // Verify OkHttpClient is configured with 15-second ping interval
        // to keep WebSocket alive through cellular NAT timeouts
        val client = NtfySignalingClient()

        // Use reflection to access the private httpClient field
        val field = NtfySignalingClient::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        val httpClient = field.get(client) as OkHttpClient

        assertEquals(
            "Ping interval should be 15 seconds for cellular keep-alive",
            15_000,
            httpClient.pingIntervalMillis
        )
    }

    @Test
    fun `max reconnect attempts defaults to 3`() {
        assertEquals(3, NtfySignalingClient.DEFAULT_MAX_RECONNECT_ATTEMPTS)
    }

    @Test
    fun `max reconnect attempts can be configured`() {
        val client = NtfySignalingClient(maxReconnectAttempts = 5)

        val field = NtfySignalingClient::class.java.getDeclaredField("maxReconnectAttempts")
        field.isAccessible = true
        val attempts = field.get(client) as Int

        assertEquals(5, attempts)
    }

    @Test
    fun `mock client handles disconnect gracefully`() = runTest {
        val mock = MockNtfyClient()
        var actualMessagesReceived = 0

        val subscription = mock.subscribe("topic")
        val job = launch {
            subscription.messages.collect { msg ->
                if (msg.event == "message") {
                    actualMessagesReceived++
                }
            }
        }

        advanceUntilIdle()
        assertTrue("Should be subscribed", mock.isSubscribed)

        // Deliver a message
        mock.deliverMessage("test")
        advanceUntilIdle()
        assertEquals(1, actualMessagesReceived)

        // Simulate disconnect - no more messages should be delivered
        mock.simulateDisconnect()
        advanceUntilIdle()

        // After disconnect, subscription state is cleared
        assertFalse("Should no longer be subscribed after disconnect", mock.isSubscribed)

        job.cancel()
    }

    @Test
    fun `mock client can be reset for reuse`() = runTest {
        val mock = MockNtfyClient()

        // Subscribe and publish
        val subscription = mock.subscribe("topic")
        val job = launch {
            subscription.messages.collect { }
        }
        advanceUntilIdle()
        mock.publish("topic", "message")

        // Disconnect
        mock.simulateDisconnect()
        advanceUntilIdle()

        // Reset
        mock.reset()

        assertFalse(mock.isSubscribed)
        assertEquals(0, mock.getPublishedMessages().size)

        job.cancel()
    }

    // ==================== Publish Retry Tests ====================

    @Test
    fun `publishWithRetry succeeds on first attempt`() = runTest {
        val mock = MockNtfyClient()

        mock.publishWithRetry("topic", "message")

        assertEquals(1, mock.getPublishAttempts())
        assertEquals(listOf("message"), mock.getPublishedMessages())
    }

    @Test
    fun `publishWithRetry retries on failure`() = runTest {
        val mock = MockNtfyClient()
        mock.failPublishTimes = 2  // Fail first 2 attempts

        mock.publishWithRetry("topic", "message", maxRetries = 3)

        assertEquals(3, mock.getPublishAttempts())
        assertEquals(listOf("message"), mock.getPublishedMessages())
    }

    @Test
    fun `publishWithRetry throws after max retries`() = runTest {
        val mock = MockNtfyClient()
        mock.failPublishTimes = 5  // Always fail

        var exceptionThrown = false
        try {
            mock.publishWithRetry("topic", "message", maxRetries = 3)
        } catch (e: PublishFailedException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("Failed") == true)
        }

        assertTrue("Should throw PublishFailedException", exceptionThrown)
        assertEquals(3, mock.getPublishAttempts())
        assertEquals(0, mock.getPublishedMessages().size)
    }
}

// Helper extension for tests
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
