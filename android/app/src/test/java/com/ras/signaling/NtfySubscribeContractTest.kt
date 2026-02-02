package com.ras.signaling

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Contract tests for ntfy subscribe/publish ordering guarantees.
 *
 * These tests document and verify the expected behavior of the ntfy client API.
 * They use a realistic mock that simulates actual ntfy timing behavior.
 */
class NtfySubscribeContractTest {

    /**
     * CONTRACT: Messages published AFTER subscribe() returns MUST be received.
     *
     * This is the primary guarantee that prevents race conditions.
     */
    @Tag("unit")
    @Test
    fun `messages published after subscribe returns are received`() = runTest {
        val client = MockNtfyClient(connectionDelayMs = 100)
        val topic = "test-topic"

        // Subscribe (blocks until connected)
        val flow = client.subscribe(topic)

        // Verify we're connected before publishing
        assertTrue(client.isConnected, "Should be connected after subscribe returns")

        // Publish after connected
        client.publish(topic, "test-message")

        // Should receive the message
        val received = withTimeoutOrNull(1000) {
            flow.first { it.event == "message" }
        }

        assertNotNull(received, "Message published after subscribe should be received")
        assertEquals("test-message", received?.message)
    }

    /**
     * CONTRACT: Messages published BEFORE subscribe() returns are NOT guaranteed.
     *
     * Without message history (?since=), messages published before the WebSocket
     * connects are lost. This test documents this behavior.
     */
    @Tag("unit")
    @Test
    fun `messages published before subscribe completes are lost without history`() = runTest {
        val client = MockNtfyClient(
            connectionDelayMs = 100,
            simulateMessageHistory = false  // No ?since= parameter
        )
        val topic = "test-topic"

        // Start subscribe in background (will take 100ms to connect)
        val flowDeferred = async { client.subscribe(topic) }

        // Publish immediately (before connection completes)
        client.publish(topic, "early-message")

        // Now wait for subscribe to complete
        val flow = flowDeferred.await()

        // The early message should be lost
        val received = withTimeoutOrNull(500) {
            flow.firstOrNull { it.event == "message" }
        }

        assertNull(received, "Message published before connection should be lost")
    }

    /**
     * CONTRACT: With message history enabled, recent messages are retrieved.
     *
     * When using ?since=30s, messages from the last 30 seconds are delivered
     * when the WebSocket connects.
     */
    @Tag("unit")
    @Test
    fun `messages published before subscribe are received with history enabled`() = runTest {
        val client = MockNtfyClient(
            connectionDelayMs = 100,
            simulateMessageHistory = true  // Simulates ?since=30s
        )
        val topic = "test-topic"

        // Publish BEFORE subscribing
        client.publish(topic, "early-message")

        // Small delay to ensure publish timestamp is before subscribe
        delay(10)

        // Now subscribe (will retrieve message history)
        val flow = client.subscribe(topic)

        // Should receive the early message via history
        val received = withTimeoutOrNull(1000) {
            flow.first { it.event == "message" }
        }

        assertNotNull(received, "Message should be received via history")
        assertEquals("early-message", received?.message)
    }

    /**
     * CONTRACT: subscribe() must block until WebSocket is connected.
     *
     * This ensures the API is safe to use - callers can publish immediately
     * after subscribe() returns.
     */
    @Tag("unit")
    @Test
    fun `subscribe blocks until connected`() = runTest {
        val client = MockNtfyClient(connectionDelayMs = 200)
        val topic = "test-topic"

        assertFalse(client.isConnected, "Should not be connected before subscribe")

        // Subscribe - should block until connected
        client.subscribe(topic)

        // The key invariant: after subscribe() returns, we must be connected
        assertTrue(client.isConnected, "Should be connected after subscribe returns")
    }

    /**
     * CONTRACT: Multiple messages published after subscribe are all received.
     */
    @Tag("unit")
    @Test
    fun `multiple messages after subscribe are all received`() = runTest {
        val client = MockNtfyClient(connectionDelayMs = 50)
        val topic = "test-topic"

        val flow = client.subscribe(topic)
        val received = mutableListOf<String>()

        // Publish multiple messages
        client.publish(topic, "msg-1")
        client.publish(topic, "msg-2")
        client.publish(topic, "msg-3")

        // Collect messages
        repeat(3) {
            val msg = withTimeoutOrNull(1000) {
                flow.first { it.event == "message" && it.message !in received }
            }
            msg?.message?.let { received.add(it) }
        }

        assertEquals(3, received.size)
        assertTrue("msg-1" in received)
        assertTrue("msg-2" in received)
        assertTrue("msg-3" in received)
    }

    /**
     * CONTRACT: Unsubscribe stops message delivery.
     */
    @Tag("unit")
    @Test
    fun `unsubscribe stops message delivery`() = runTest {
        val client = MockNtfyClient()
        val topic = "test-topic"

        client.subscribe(topic)
        assertTrue(client.isConnected)

        client.unsubscribe()
        assertFalse(client.isConnected)
    }

    /**
     * CONTRACT: simulateIncomingMessage only delivers to connected subscribers.
     */
    @Tag("unit")
    @Test
    fun `simulateIncomingMessage requires connection`() = runTest {
        val client = MockNtfyClient()
        val topic = "test-topic"

        // Not subscribed yet
        assertFalse(client.isConnected)

        // This message should be dropped (no subscriber)
        client.simulateIncomingMessage("orphan-message")

        // Now subscribe
        val flow = client.subscribe(topic)

        // Send a real message
        client.simulateIncomingMessage("real-message")

        val received = withTimeoutOrNull(500) {
            flow.first { it.event == "message" }
        }

        // Should only get the message sent after subscription
        assertNotNull(received)
        assertEquals("real-message", received?.message)
    }
}
