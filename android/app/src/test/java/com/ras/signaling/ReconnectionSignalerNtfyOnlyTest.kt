package com.ras.signaling

import com.ras.data.connection.ConnectionProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for ntfy-only signaling path (Bug 45 regression test).
 *
 * These tests prevent regression of the bug where reconnection failed when
 * no direct hosts were available (no cached IP, mDNS failed, no VPN).
 *
 * The fix ensures that when host="" and port=0, the signaler correctly
 * triggers ntfy-only signaling instead of failing with "All hosts failed".
 */
class ReconnectionSignalerNtfyOnlyTest {

    private lateinit var mockDirectClient: DirectReconnectionClient
    private lateinit var mockNtfyClient: NtfyClientInterface
    private lateinit var signaler: ReconnectionSignaler

    private val testMasterSecret = ByteArray(32) { it.toByte() }
    private val testSdpOffer = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
    private val testSdpAnswer = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=candidate:1 1 udp 2122194687 192.168.1.100 8765 typ host\r\n"

    @BeforeEach
    fun setup() {
        mockDirectClient = mockk(relaxed = true)
        mockNtfyClient = mockk(relaxed = true)
        signaler = ReconnectionSignaler(
            directClient = mockDirectClient,
            ntfyClient = mockNtfyClient,
            directTimeoutMs = 100  // Short timeout for tests
        )
    }

    @Tag("unit")
    @Test
    fun `exchangeSdp with empty host and port uses ntfy-only path`() = runTest {
        /**
         * Regression test for Bug 45:
         * When no direct hosts are available (host="", port=0), the signaler
         * should use ntfy-only signaling instead of failing immediately.
         */

        // Direct signaling fails (no direct connectivity)
        coEvery {
            mockDirectClient.exchangeSdp(any(), any(), any(), any(), any(), any(), any())
        } throws java.io.IOException("No direct connectivity")

        // ntfy subscription returns daemon's answer
        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            emit(NtfyMessage("message", createEncryptedAnswer()))
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        // When: Call exchangeSdp with empty host/port (ntfy-only scenario)
        val result = signaler.exchangeSdp(
            host = "",
            port = 0,
            masterSecret = testMasterSecret,
            sdpOffer = testSdpOffer,
            deviceId = "test-device",
            deviceName = "Test Device",
            ntfyTimeoutMs = 5000
        )

        // Then: Should succeed via ntfy
        assertTrue(result is ReconnectionSignalerResult.Success, "Should succeed via ntfy")
        val success = result as ReconnectionSignalerResult.Success
        assertTrue(success.usedNtfyPath, "Should use ntfy path")
        assertFalse(success.usedDirectPath, "Should not use direct path")

        // Verify ntfy was used
        coVerify(exactly = 1) { mockNtfyClient.subscribe(any()) }
        coVerify(exactly = 1) { mockNtfyClient.publishWithRetry(any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `exchangeSdp with no direct hosts falls back to ntfy`() = runTest {
        /**
         * When direct signaling fails, should automatically fall back to ntfy.
         */

        // Direct signaling fails
        coEvery {
            mockDirectClient.exchangeSdp(any(), any(), any(), any(), any(), any(), any())
        } throws java.io.IOException("Connection refused")

        // ntfy works
        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            emit(NtfyMessage("message", createEncryptedAnswer()))
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        // When: Try with a real host that fails
        val result = signaler.exchangeSdp(
            host = "192.168.1.100",
            port = 8765,
            masterSecret = testMasterSecret,
            sdpOffer = testSdpOffer,
            deviceId = "test-device",
            deviceName = "Test Device",
            ntfyTimeoutMs = 5000
        )

        // Then: Should succeed via ntfy fallback
        assertTrue(result is ReconnectionSignalerResult.Success)
        val success = result as ReconnectionSignalerResult.Success
        assertTrue(success.usedNtfyPath, "Should fall back to ntfy")
    }

    @Tag("unit")
    @Test
    fun `exchangeSdp calls progress callback with ntfy events`() = runTest {
        /**
         * Progress callback should receive ntfy events during ntfy-only signaling.
         */

        val progressEvents = mutableListOf<String>()
        val progressCallback: (ConnectionProgress) -> Unit = { progress ->
            progressEvents.add(progress.toString())
        }

        // Direct fails, ntfy succeeds
        coEvery {
            mockDirectClient.exchangeSdp(any(), any(), any(), any(), any(), any(), any())
        } throws java.io.IOException("No direct connectivity")

        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            emit(NtfyMessage("message", createEncryptedAnswer()))
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        // When
        signaler.exchangeSdp(
            host = "",
            port = 0,
            masterSecret = testMasterSecret,
            sdpOffer = testSdpOffer,
            deviceId = "test-device",
            deviceName = "Test Device",
            ntfyTimeoutMs = 5000,
            onProgress = progressCallback
        )

        // Then: Should have received ntfy progress events
        assertTrue(progressEvents.any { it.contains("Ntfy") || it.contains("ntfy") },
            "Should receive ntfy progress events")
    }

    @Tag("unit")
    @Test
    fun `exchangeSdp returns error when both direct and ntfy fail`() = runTest {
        /**
         * When both direct and ntfy fail, should return error result.
         */

        // Direct fails
        coEvery {
            mockDirectClient.exchangeSdp(any(), any(), any(), any(), any(), any(), any())
        } throws java.io.IOException("No direct connectivity")

        // ntfy also fails (timeout)
        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            // Never emits answer - will timeout
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        // When
        val result = signaler.exchangeSdp(
            host = "",
            port = 0,
            masterSecret = testMasterSecret,
            sdpOffer = testSdpOffer,
            deviceId = "test-device",
            deviceName = "Test Device",
            ntfyTimeoutMs = 100  // Short timeout for test
        )

        // Then: Should fail with timeout
        assertTrue(result is ReconnectionSignalerResult.Error || result == ReconnectionSignalerResult.NtfyTimeout,
            "Should return error when both methods fail")
    }

    /**
     * Helper to create encrypted answer for testing.
     */
    private fun createEncryptedAnswer(): String {
        val crypto = NtfySignalingCrypto(testMasterSecret)
        // Create a mock encrypted message - in real tests would use proper protobuf
        val mockMessage = "mock-encrypted-answer-bytes"
        return android.util.Base64.encodeToString(
            crypto.encrypt(mockMessage.toByteArray()),
            android.util.Base64.NO_WRAP
        )
    }
}
