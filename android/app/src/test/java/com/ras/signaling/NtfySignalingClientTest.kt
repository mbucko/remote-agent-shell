package com.ras.signaling

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

        assertEquals("wss://ntfy.sh/ras-abc123def456/ws", url)
    }

    @Test
    fun `builds correct WebSocket URL with custom server`() {
        val topic = "ras-abc123"
        val url = NtfySignalingClient.buildWsUrl(topic, "https://custom.server.com")

        assertEquals("wss://custom.server.com/ras-abc123/ws", url)
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

        val job = launch {
            mockClient.subscribe("test-topic").first()
        }

        // Give time for subscription
        advanceUntilIdle()
        assertTrue(mockClient.isSubscribed)

        job.cancel()
    }

    @Test
    fun `mock client delivers messages`() = runTest {
        val mockClient = MockNtfyClient()

        val receivedMessage = async {
            mockClient.subscribe("test-topic").first()
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

        val job = launch {
            mockClient.subscribe("test-topic").collect { }
        }
        advanceUntilIdle()

        assertTrue(mockClient.isSubscribed)

        mockClient.unsubscribe()
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

// Helper extension for tests
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
