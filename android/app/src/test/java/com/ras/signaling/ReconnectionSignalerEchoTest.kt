package com.ras.signaling

import com.google.protobuf.ByteString
import com.ras.proto.ConnectionCapabilities
import com.ras.proto.NtfySignalMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * Tests for ReconnectionSignaler ntfy echo filtering.
 *
 * These tests prevent regression of Bug 3: Ntfy Echo Bug
 * where Android received its own CAPABILITIES request echoed back via ntfy
 * and mistakenly treated it as the daemon's response.
 *
 * IMPORTANT: When subscribed to ntfy and publishing a message, ntfy broadcasts
 * to ALL subscribers including the sender. We filter these out by checking
 * device_id: requests have device_id set, responses have it empty.
 */
class ReconnectionSignalerEchoTest {

    private lateinit var mockDirectClient: DirectReconnectionClient
    private lateinit var mockNtfyClient: NtfyClientInterface
    private lateinit var signaler: ReconnectionSignaler

    // Test keys
    private val testMasterSecret = ByteArray(32) { it.toByte() }
    private val testSignalingKey = NtfySignalingCrypto.deriveSignalingKey(testMasterSecret)

    // Test device/capabilities
    private val ourDeviceId = "our-device-id"
    private val ourCapabilities = ConnectionCapabilities.newBuilder()
        .setTailscaleIp("100.64.0.1")
        .setTailscalePort(9876)
        .build()

    private val daemonCapabilities = ConnectionCapabilities.newBuilder()
        .setTailscaleIp("100.64.0.2")
        .setTailscalePort(9876)
        .build()

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

    /**
     * Encrypt a message using the test signaling key.
     */
    private fun encryptMessage(msg: NtfySignalMessage): String {
        val crypto = NtfySignalingCrypto(testSignalingKey.copyOf())
        return crypto.encryptToBase64(msg.toByteArray())
    }

    /**
     * Create a CAPABILITIES request message (has device_id set).
     */
    private fun createCapabilitiesRequest(
        deviceId: String,
        capabilities: ConnectionCapabilities
    ): NtfySignalMessage {
        return NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.CAPABILITIES)
            .setSessionId("")
            .setDeviceId(deviceId)  // Non-empty = this is a request
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16).also { SecureRandom().nextBytes(it) }))
            .setCapabilities(capabilities)
            .build()
    }

    /**
     * Create a CAPABILITIES response message (empty device_id).
     */
    private fun createCapabilitiesResponse(
        capabilities: ConnectionCapabilities
    ): NtfySignalMessage {
        return NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.CAPABILITIES)
            .setSessionId("")
            .setDeviceId("")  // Empty = this is a response
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16).also { SecureRandom().nextBytes(it) }))
            .setCapabilities(capabilities)
            .build()
    }

    // ==================== Echo Filtering Tests ====================

    @Tag("unit")
    @Test
    fun `exchangeCapabilities filters out own request echoed back via ntfy`() = runTest {
        /**
         * Bug 3 regression: ntfy echoes our request back, must filter it out.
         *
         * Flow:
         * 1. We subscribe and publish CAPABILITIES request
         * 2. Ntfy echoes our request back to us (has device_id)
         * 3. Daemon sends response (empty device_id)
         * 4. We should return daemon's capabilities, not our own
         */

        // Direct client fails (triggers ntfy fallback)
        coEvery {
            mockDirectClient.exchangeCapabilities(any(), any(), any(), any(), any())
        } throws java.io.IOException("Network error")

        // Create echoed request (our own message echoed back)
        val echoedRequest = createCapabilitiesRequest(ourDeviceId, ourCapabilities)
        val encryptedEcho = encryptMessage(echoedRequest)

        // Create daemon's response (empty device_id)
        val daemonResponse = createCapabilitiesResponse(daemonCapabilities)
        val encryptedResponse = encryptMessage(daemonResponse)

        // Mock ntfy to deliver echoed request first, then daemon response
        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            // First: our own request echoed back
            emit(NtfyMessage("message", encryptedEcho))
            // Second: daemon's actual response
            emit(NtfyMessage("message", encryptedResponse))
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        // When
        val result = signaler.exchangeCapabilities(
            host = "192.168.1.100",
            port = 8765,
            masterSecret = testMasterSecret,
            deviceId = ourDeviceId,
            ourCapabilities = ourCapabilities,
            ntfyTimeoutMs = 5000
        )

        // Then: Should return daemon's capabilities, not our own echoed back
        assertTrue(result is CapabilityExchangeResult.Success, "Should be success")
        val success = result as CapabilityExchangeResult.Success
        assertEquals(
            "100.64.0.2",
            success.capabilities.tailscaleIp,
            "Should have daemon's IP, not ours"
        )
    }

    @Tag("unit")
    @Test
    fun `exchangeCapabilities handles multiple echoed requests before response`() = runTest {
        /**
         * Multiple echoed requests (from different phones) should all be filtered out.
         */

        coEvery {
            mockDirectClient.exchangeCapabilities(any(), any(), any(), any(), any())
        } throws java.io.IOException("Network error")

        // Multiple echoed requests from different devices
        val echo1 = createCapabilitiesRequest("device-1", ourCapabilities)
        val echo2 = createCapabilitiesRequest("device-2", ourCapabilities)
        val echo3 = createCapabilitiesRequest("device-3", ourCapabilities)
        val daemonResponse = createCapabilitiesResponse(daemonCapabilities)

        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            emit(NtfyMessage("message", encryptMessage(echo1)))
            emit(NtfyMessage("message", encryptMessage(echo2)))
            emit(NtfyMessage("message", encryptMessage(echo3)))
            emit(NtfyMessage("message", encryptMessage(daemonResponse)))
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        val result = signaler.exchangeCapabilities(
            host = "192.168.1.100",
            port = 8765,
            masterSecret = testMasterSecret,
            deviceId = ourDeviceId,
            ourCapabilities = ourCapabilities,
            ntfyTimeoutMs = 5000
        )

        assertTrue(result is CapabilityExchangeResult.Success)
        assertEquals(
            "100.64.0.2",
            (result as CapabilityExchangeResult.Success).capabilities.tailscaleIp
        )
    }

    @Tag("unit")
    @Test
    fun `exchangeCapabilities ignores invalid messages`() = runTest {
        /**
         * Corrupted/invalid messages should be silently ignored.
         */

        coEvery {
            mockDirectClient.exchangeCapabilities(any(), any(), any(), any(), any())
        } throws java.io.IOException("Network error")

        val daemonResponse = createCapabilitiesResponse(daemonCapabilities)

        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            // Invalid message (not valid base64)
            emit(NtfyMessage("message", "this is not valid encrypted data!!!"))
            // Invalid message (valid base64 but wrong encryption)
            emit(NtfyMessage("message", "dGhpcyBpcyBub3QgZW5jcnlwdGVk"))  // "this is not encrypted"
            // Valid daemon response
            emit(NtfyMessage("message", encryptMessage(daemonResponse)))
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        val result = signaler.exchangeCapabilities(
            host = "192.168.1.100",
            port = 8765,
            masterSecret = testMasterSecret,
            deviceId = ourDeviceId,
            ourCapabilities = ourCapabilities,
            ntfyTimeoutMs = 5000
        )

        assertTrue(result is CapabilityExchangeResult.Success)
        assertEquals(
            "100.64.0.2",
            (result as CapabilityExchangeResult.Success).capabilities.tailscaleIp
        )
    }

    @Tag("unit")
    @Test
    fun `exchangeCapabilities handles response arriving before echo`() = runTest {
        /**
         * Daemon response arriving before echo should still work.
         * (In practice, echoes usually arrive first due to network latency.)
         */

        coEvery {
            mockDirectClient.exchangeCapabilities(any(), any(), any(), any(), any())
        } throws java.io.IOException("Network error")

        val daemonResponse = createCapabilitiesResponse(daemonCapabilities)

        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            // Daemon response first
            emit(NtfyMessage("message", encryptMessage(daemonResponse)))
            // Echo arrives later (but we should have already finished)
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        val result = signaler.exchangeCapabilities(
            host = "192.168.1.100",
            port = 8765,
            masterSecret = testMasterSecret,
            deviceId = ourDeviceId,
            ourCapabilities = ourCapabilities,
            ntfyTimeoutMs = 5000
        )

        assertTrue(result is CapabilityExchangeResult.Success)
        assertEquals(
            "100.64.0.2",
            (result as CapabilityExchangeResult.Success).capabilities.tailscaleIp
        )
    }

    @Tag("unit")
    @Test
    fun `exchangeCapabilities ignores non-CAPABILITIES messages`() = runTest {
        /**
         * Non-CAPABILITIES message types should be ignored.
         */

        coEvery {
            mockDirectClient.exchangeCapabilities(any(), any(), any(), any(), any())
        } throws java.io.IOException("Network error")

        // OFFER message (wrong type)
        val offerMsg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.OFFER)
            .setSessionId("")
            .setDeviceId("")
            .setSdp("v=0...")
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16).also { SecureRandom().nextBytes(it) }))
            .build()

        val daemonResponse = createCapabilitiesResponse(daemonCapabilities)

        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            emit(NtfyMessage("message", encryptMessage(offerMsg)))
            emit(NtfyMessage("message", encryptMessage(daemonResponse)))
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        val result = signaler.exchangeCapabilities(
            host = "192.168.1.100",
            port = 8765,
            masterSecret = testMasterSecret,
            deviceId = ourDeviceId,
            ourCapabilities = ourCapabilities,
            ntfyTimeoutMs = 5000
        )

        assertTrue(result is CapabilityExchangeResult.Success)
    }

    @Tag("unit")
    @Test
    fun `exchangeCapabilities ignores keepalive and other ntfy events`() = runTest {
        /**
         * Non-message ntfy events should be ignored.
         */

        coEvery {
            mockDirectClient.exchangeCapabilities(any(), any(), any(), any(), any())
        } throws java.io.IOException("Network error")

        val daemonResponse = createCapabilitiesResponse(daemonCapabilities)

        coEvery { mockNtfyClient.subscribe(any()) } returns flow {
            emit(NtfyMessage("open", ""))
            emit(NtfyMessage("keepalive", ""))
            emit(NtfyMessage("message", encryptMessage(daemonResponse)))
        }

        coEvery { mockNtfyClient.publishWithRetry(any(), any()) } returns Unit
        coEvery { mockNtfyClient.unsubscribe() } returns Unit

        val result = signaler.exchangeCapabilities(
            host = "192.168.1.100",
            port = 8765,
            masterSecret = testMasterSecret,
            deviceId = ourDeviceId,
            ourCapabilities = ourCapabilities,
            ntfyTimeoutMs = 5000
        )

        assertTrue(result is CapabilityExchangeResult.Success)
    }

    // ==================== Documentation Tests ====================

    @Tag("unit")
    @Test
    fun `CAPABILITIES request has device_id set`() {
        /**
         * Verify that requests have device_id set (used for filtering).
         */
        val request = createCapabilitiesRequest("test-device", ourCapabilities)

        assertTrue(
            request.deviceId.isNotEmpty(),
            "Request should have device_id set"
        )
        assertEquals("test-device", request.deviceId)
    }

    @Tag("unit")
    @Test
    fun `CAPABILITIES response has empty device_id`() {
        /**
         * Verify that responses have empty device_id (used for filtering).
         */
        val response = createCapabilitiesResponse(daemonCapabilities)

        assertTrue(
            response.deviceId.isEmpty(),
            "Response should have empty device_id"
        )
    }
}
