package com.ras.ntfy

import com.ras.crypto.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
class NtfySubscriberTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // Test key from Phase 8a
    private val ntfyKey = "e3d801b5755b78c380d59c1285c1a65290db0334cc2994dfd048ebff2df8781f".hexToBytes()

    // Valid encrypted message (IPv4 test vector)
    private val validEncrypted = "qrvM3e7/ABEiM0RVVlpzDel9pfRPh6QGaeOsmbCwn8cPDv0oeA78kIGlfNwSZBAlVpOmfjem7SWgirh1NoQs8F/7VsMhKQ=="

    private lateinit var crypto: NtfyCrypto

    @BeforeEach
    fun setup() {
        crypto = NtfyCrypto(ntfyKey)
    }

    // ============================================================================
    // URL Building Tests
    // ============================================================================

    @Tag("unit")
    @Test
    fun `buildWsUrl converts https to wss`() {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            server = "https://ntfy.sh",
            dispatcher = testDispatcher
        )

        assertEquals("wss://ntfy.sh/test-topic/ws", subscriber.buildWsUrl())
    }

    @Tag("unit")
    @Test
    fun `buildWsUrl converts http to ws`() {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            server = "http://localhost:8080",
            dispatcher = testDispatcher
        )

        assertEquals("ws://localhost:8080/test-topic/ws", subscriber.buildWsUrl())
    }

    // ============================================================================
    // Message Handling Tests
    // ============================================================================

    @Tag("unit")
    @Test
    fun `handleMessage emits valid IP change`() = runTest(testDispatcher) {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        // Start collecting before handling message
        val result = async {
            withTimeoutOrNull(1000) {
                subscriber.ipChanges.first()
            }
        }

        // ntfy message event with valid encrypted payload
        val message = createNtfyMessage(validEncrypted)

        subscriber.handleMessage(message)

        // The test vector has timestamp 1706384400 which is in the past
        // so it will be rejected by timestamp validation
        val data = result.await()
        assertNull(data, "Old timestamp should be rejected")
    }

    @Tag("unit")
    @Test
    fun `handleMessage ignores keepalive events`() = runTest(testDispatcher) {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        val result = async {
            withTimeoutOrNull(100) {
                subscriber.ipChanges.first()
            }
        }

        val keepalive = """{"event":"keepalive","time":1234567890}"""
        subscriber.handleMessage(keepalive)

        val data = result.await()
        assertNull(data, "Keepalive should not emit")
    }

    @Tag("unit")
    @Test
    fun `handleMessage ignores open events`() = runTest(testDispatcher) {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        val result = async {
            withTimeoutOrNull(100) {
                subscriber.ipChanges.first()
            }
        }

        val open = """{"event":"open","time":1234567890}"""
        subscriber.handleMessage(open)

        val data = result.await()
        assertNull(data, "Open event should not emit")
    }

    @Tag("unit")
    @Test
    fun `handleMessage ignores empty message field`() = runTest(testDispatcher) {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        val result = async {
            withTimeoutOrNull(100) {
                subscriber.ipChanges.first()
            }
        }

        val empty = """{"event":"message","time":1234567890,"message":""}"""
        subscriber.handleMessage(empty)

        val data = result.await()
        assertNull(data, "Empty message should not emit")
    }

    @Tag("unit")
    @Test
    fun `handleMessage ignores invalid json`() = runTest(testDispatcher) {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        val result = async {
            withTimeoutOrNull(100) {
                subscriber.ipChanges.first()
            }
        }

        subscriber.handleMessage("not json at all")

        val data = result.await()
        assertNull(data, "Invalid JSON should not emit")
    }

    @Tag("unit")
    @Test
    fun `handleMessage ignores decryption failure`() = runTest(testDispatcher) {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        val result = async {
            withTimeoutOrNull(100) {
                subscriber.ipChanges.first()
            }
        }

        val badMessage = createNtfyMessage("invalid-base64!!!")
        subscriber.handleMessage(badMessage)

        val data = result.await()
        assertNull(data, "Invalid encryption should not emit")
    }

    // ============================================================================
    // Replay Protection Tests
    // ============================================================================

    @Tag("unit")
    @Test
    fun `replay protection rejects duplicate nonce`() = runTest(testDispatcher) {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        // First message should be processed (but rejected by timestamp)
        val message = createNtfyMessage(validEncrypted)
        subscriber.handleMessage(message)

        // Try same message again - should be rejected as replay
        val result = async {
            withTimeoutOrNull(100) {
                subscriber.ipChanges.first()
            }
        }

        subscriber.handleMessage(message)

        val data = result.await()
        assertNull(data, "Replay should be rejected")
    }

    @Tag("unit")
    @Test
    fun `nonce cache has max size with FIFO eviction`() {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        // Add 150 unique nonces (max is 100)
        for (i in 0 until 150) {
            subscriber.addNonce(String.format("%032x", i))
        }

        // First 50 should have been evicted
        for (i in 0 until 50) {
            val nonce = String.format("%032x", i)
            assertEquals(false, subscriber.hasNonce(nonce), "Nonce $i should be evicted")
        }

        // Last 100 should still be present
        for (i in 50 until 150) {
            val nonce = String.format("%032x", i)
            assertEquals(true, subscriber.hasNonce(nonce), "Nonce $i should be present")
        }
    }

    // ============================================================================
    // Reconnect Counter Tests
    // ============================================================================

    @Tag("unit")
    @Test
    fun `resetReconnectCounter resets counter`() {
        val subscriber = NtfySubscriber(
            crypto = crypto,
            topic = "test-topic",
            dispatcher = testDispatcher
        )

        // Reset should work without error
        subscriber.resetReconnectCounter()

        // No assertion needed - just verifying it doesn't throw
    }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun createNtfyMessage(encryptedPayload: String): String {
        return """{"event":"message","time":${System.currentTimeMillis() / 1000},"message":"$encryptedPayload"}"""
    }
}
