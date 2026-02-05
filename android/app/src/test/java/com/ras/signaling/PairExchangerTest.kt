package com.ras.signaling

import com.google.protobuf.ByteString
import com.ras.crypto.HmacUtils
import com.ras.crypto.KeyDerivation
import com.ras.data.model.DeviceType
import com.ras.proto.NtfySignalMessage
import com.ras.proto.PairRequest
import com.ras.proto.PairResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * Tests for PairExchanger.
 *
 * Uses MockNtfyClient to simulate ntfy round-trip:
 * 1. PairExchanger subscribes, sends PAIR_REQUEST, then waits for PAIR_RESPONSE
 * 2. Test intercepts the published message, decrypts it, reads the nonce
 * 3. Test builds a valid PAIR_RESPONSE with correct auth_proof and delivers it
 */
class PairExchangerTest {

    private lateinit var mockNtfyClient: MockNtfyClient

    private val masterSecret = ByteArray(32) { 0x42.toByte() }
    private val sessionId = "test-session-abc123"
    private val deviceId = "phone-device-001"
    private val deviceName = "My Test Phone"
    private val ntfyTopic = "ras-testtopic"

    private val daemonDeviceId = "daemon-device-xyz"
    private val daemonHostname = "my-laptop"

    // Derived keys (same derivation as PairExchanger uses)
    private val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
    private val authKey = KeyDerivation.deriveKey(masterSecret, "auth")

    @BeforeEach
    fun setup() {
        mockNtfyClient = MockNtfyClient(connectionDelayMs = 0L)
    }

    // ==================== Happy Path Tests ====================

    @Tag("unit")
    @Test
    fun `successful exchange returns daemon info`() = runTest(UnconfinedTestDispatcher()) {
        val exchanger = PairExchanger(mockNtfyClient, timeoutMs = 5000L)

        val resultDeferred = async {
            exchanger.exchange(masterSecret, sessionId, deviceId, deviceName, ntfyTopic)
        }

        // Wait for PairExchanger to subscribe and publish
        delay(50)

        // Get the published message and decrypt it to extract the nonce
        val publishedMessages = mockNtfyClient.getPublishedMessages()
        assertTrue(publishedMessages.isNotEmpty(), "PairExchanger should have published a PAIR_REQUEST")

        val crypto = NtfySignalingCrypto(signalingKey.copyOf())
        val decryptedBytes = crypto.decryptFromBase64(publishedMessages[0])
        val requestMsg = NtfySignalMessage.parseFrom(decryptedBytes)
        val pairNonce = requestMsg.pairRequest.nonce.toByteArray()

        // Build a valid PAIR_RESPONSE with correct auth_proof
        val responseAuthProof = HmacUtils.computePairResponseHmac(authKey, pairNonce)
        val pairResponse = PairResponse.newBuilder()
            .setDaemonDeviceId(daemonDeviceId)
            .setHostname(daemonHostname)
            .setDeviceType(com.ras.proto.DeviceType.DEVICE_TYPE_LAPTOP)
            .setAuthProof(ByteString.copyFrom(responseAuthProof))
            .build()

        val responseSignalMsg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.PAIR_RESPONSE)
            .setSessionId(sessionId)
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16).also { SecureRandom().nextBytes(it) }))
            .setPairResponse(pairResponse)
            .build()

        val responseCrypto = NtfySignalingCrypto(signalingKey.copyOf())
        val encryptedResponse = responseCrypto.encryptToBase64(responseSignalMsg.toByteArray())

        // Deliver the response via MockNtfyClient
        mockNtfyClient.deliverMessage(encryptedResponse)

        val result = resultDeferred.await()
        assertTrue(result is PairExchangeResult.Success, "Expected Success but got $result")
        val success = result as PairExchangeResult.Success
        assertEquals(daemonDeviceId, success.daemonDeviceId)
        assertEquals(daemonHostname, success.hostname)
        assertEquals(DeviceType.LAPTOP, success.deviceType)
    }

    @Tag("unit")
    @Test
    fun `constructs PairRequest with correct fields`() = runTest(UnconfinedTestDispatcher()) {
        val exchanger = PairExchanger(mockNtfyClient, timeoutMs = 200L)

        // Launch exchange; it will timeout since we don't respond, but we can inspect the published message
        val resultDeferred = async {
            exchanger.exchange(masterSecret, sessionId, deviceId, deviceName, ntfyTopic)
        }

        // Wait for publish
        delay(50)

        val publishedMessages = mockNtfyClient.getPublishedMessages()
        assertTrue(publishedMessages.isNotEmpty(), "PairExchanger should have published a message")

        val crypto = NtfySignalingCrypto(signalingKey.copyOf())
        val decryptedBytes = crypto.decryptFromBase64(publishedMessages[0])
        val signalMsg = NtfySignalMessage.parseFrom(decryptedBytes)

        // Verify outer NtfySignalMessage fields
        assertEquals(NtfySignalMessage.MessageType.PAIR_REQUEST, signalMsg.type)
        assertEquals(sessionId, signalMsg.sessionId)
        assertTrue(signalMsg.timestamp > 0, "Timestamp should be set")
        assertEquals(16, signalMsg.nonce.size(), "Outer nonce should be 16 bytes")

        // Verify inner PairRequest fields
        val pairRequest = signalMsg.pairRequest
        assertEquals(deviceId, pairRequest.deviceId)
        assertEquals(deviceName, pairRequest.deviceName)
        assertEquals(sessionId, pairRequest.sessionId)
        assertEquals(HmacUtils.PAIR_NONCE_LENGTH, pairRequest.nonce.size(), "Pair nonce should be 32 bytes")

        // Verify auth_proof is a valid HMAC
        val expectedHmac = HmacUtils.computePairRequestHmac(
            authKey, sessionId, deviceId, pairRequest.nonce.toByteArray()
        )
        assertTrue(
            HmacUtils.constantTimeEquals(pairRequest.authProof.toByteArray(), expectedHmac),
            "auth_proof should be valid HMAC"
        )

        // Let the exchange timeout so the coroutine completes
        resultDeferred.await()
    }

    // ==================== Error Case Tests ====================

    @Tag("unit")
    @Test
    fun `invalid response HMAC returns AuthFailed`() = runTest(UnconfinedTestDispatcher()) {
        val exchanger = PairExchanger(mockNtfyClient, timeoutMs = 5000L)

        val resultDeferred = async {
            exchanger.exchange(masterSecret, sessionId, deviceId, deviceName, ntfyTopic)
        }

        delay(50)

        val publishedMessages = mockNtfyClient.getPublishedMessages()
        val crypto = NtfySignalingCrypto(signalingKey.copyOf())
        val decryptedBytes = crypto.decryptFromBase64(publishedMessages[0])
        val requestMsg = NtfySignalMessage.parseFrom(decryptedBytes)
        val pairNonce = requestMsg.pairRequest.nonce.toByteArray()

        // Build response with WRONG auth_proof (use garbage bytes)
        val wrongAuthProof = ByteArray(32) { 0xFF.toByte() }
        val pairResponse = PairResponse.newBuilder()
            .setDaemonDeviceId(daemonDeviceId)
            .setHostname(daemonHostname)
            .setDeviceType(com.ras.proto.DeviceType.DEVICE_TYPE_LAPTOP)
            .setAuthProof(ByteString.copyFrom(wrongAuthProof))
            .build()

        val responseSignalMsg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.PAIR_RESPONSE)
            .setSessionId(sessionId)
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16).also { SecureRandom().nextBytes(it) }))
            .setPairResponse(pairResponse)
            .build()

        val responseCrypto = NtfySignalingCrypto(signalingKey.copyOf())
        val encryptedResponse = responseCrypto.encryptToBase64(responseSignalMsg.toByteArray())

        mockNtfyClient.deliverMessage(encryptedResponse)

        val result = resultDeferred.await()
        assertTrue(result is PairExchangeResult.AuthFailed, "Expected AuthFailed but got $result")
    }

    @Tag("unit")
    @Test
    fun `timeout returns Timeout`() = runTest(UnconfinedTestDispatcher()) {
        val exchanger = PairExchanger(mockNtfyClient, timeoutMs = 100L)

        // Don't deliver any response - should timeout
        val result = exchanger.exchange(masterSecret, sessionId, deviceId, deviceName, ntfyTopic)

        assertTrue(result is PairExchangeResult.Timeout, "Expected Timeout but got $result")
    }

    @Tag("unit")
    @Test
    fun `wrong message type is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val exchanger = PairExchanger(mockNtfyClient, timeoutMs = 500L)

        val resultDeferred = async {
            exchanger.exchange(masterSecret, sessionId, deviceId, deviceName, ntfyTopic)
        }

        delay(50)

        // Send an ANSWER message instead of PAIR_RESPONSE -- should be ignored
        val wrongTypeMsg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.ANSWER)
            .setSessionId(sessionId)
            .setSdp("v=0\r\nm=application 9\r\n")
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16).also { SecureRandom().nextBytes(it) }))
            .build()

        val crypto = NtfySignalingCrypto(signalingKey.copyOf())
        val encryptedWrongType = crypto.encryptToBase64(wrongTypeMsg.toByteArray())

        mockNtfyClient.deliverMessage(encryptedWrongType)

        // PairExchanger should keep waiting and eventually timeout
        val result = resultDeferred.await()
        assertTrue(result is PairExchangeResult.Timeout, "Expected Timeout but got $result")
    }

    @Tag("unit")
    @Test
    fun `wrong session ID is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val exchanger = PairExchanger(mockNtfyClient, timeoutMs = 500L)

        val resultDeferred = async {
            exchanger.exchange(masterSecret, sessionId, deviceId, deviceName, ntfyTopic)
        }

        delay(50)

        val publishedMessages = mockNtfyClient.getPublishedMessages()
        val crypto = NtfySignalingCrypto(signalingKey.copyOf())
        val decryptedBytes = crypto.decryptFromBase64(publishedMessages[0])
        val requestMsg = NtfySignalMessage.parseFrom(decryptedBytes)
        val pairNonce = requestMsg.pairRequest.nonce.toByteArray()

        // Build valid PAIR_RESPONSE but with wrong session_id
        val responseAuthProof = HmacUtils.computePairResponseHmac(authKey, pairNonce)
        val pairResponse = PairResponse.newBuilder()
            .setDaemonDeviceId(daemonDeviceId)
            .setHostname(daemonHostname)
            .setDeviceType(com.ras.proto.DeviceType.DEVICE_TYPE_LAPTOP)
            .setAuthProof(ByteString.copyFrom(responseAuthProof))
            .build()

        val responseSignalMsg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.PAIR_RESPONSE)
            .setSessionId("wrong-session-id")  // Wrong session ID
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16).also { SecureRandom().nextBytes(it) }))
            .setPairResponse(pairResponse)
            .build()

        val responseCrypto = NtfySignalingCrypto(signalingKey.copyOf())
        val encryptedResponse = responseCrypto.encryptToBase64(responseSignalMsg.toByteArray())

        mockNtfyClient.deliverMessage(encryptedResponse)

        // Should ignore the message and timeout
        val result = resultDeferred.await()
        assertTrue(result is PairExchangeResult.Timeout, "Expected Timeout but got $result")
    }

    @Tag("unit")
    @Test
    fun `decryption failure is silently ignored`() = runTest(UnconfinedTestDispatcher()) {
        val exchanger = PairExchanger(mockNtfyClient, timeoutMs = 500L)

        val resultDeferred = async {
            exchanger.exchange(masterSecret, sessionId, deviceId, deviceName, ntfyTopic)
        }

        delay(50)

        // Deliver garbled message that cannot be decrypted
        mockNtfyClient.deliverMessage("this-is-not-valid-base64-encrypted-data!!!")

        // Should ignore the garbled message and timeout
        val result = resultDeferred.await()
        assertTrue(result is PairExchangeResult.Timeout, "Expected Timeout but got $result")
    }

    @Tag("unit")
    @Test
    fun `empty daemon_device_id is rejected`() = runTest(UnconfinedTestDispatcher()) {
        val exchanger = PairExchanger(mockNtfyClient, timeoutMs = 5000L)

        val resultDeferred = async {
            exchanger.exchange(masterSecret, sessionId, deviceId, deviceName, ntfyTopic)
        }

        delay(50)

        val publishedMessages = mockNtfyClient.getPublishedMessages()
        val crypto = NtfySignalingCrypto(signalingKey.copyOf())
        val decryptedBytes = crypto.decryptFromBase64(publishedMessages[0])
        val requestMsg = NtfySignalMessage.parseFrom(decryptedBytes)
        val pairNonce = requestMsg.pairRequest.nonce.toByteArray()

        // Build response with valid HMAC but empty daemon_device_id
        val responseAuthProof = HmacUtils.computePairResponseHmac(authKey, pairNonce)
        val pairResponse = PairResponse.newBuilder()
            .setDaemonDeviceId("")  // Empty daemon device ID
            .setHostname(daemonHostname)
            .setDeviceType(com.ras.proto.DeviceType.DEVICE_TYPE_LAPTOP)
            .setAuthProof(ByteString.copyFrom(responseAuthProof))
            .build()

        val responseSignalMsg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.PAIR_RESPONSE)
            .setSessionId(sessionId)
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16).also { SecureRandom().nextBytes(it) }))
            .setPairResponse(pairResponse)
            .build()

        val responseCrypto = NtfySignalingCrypto(signalingKey.copyOf())
        val encryptedResponse = responseCrypto.encryptToBase64(responseSignalMsg.toByteArray())

        mockNtfyClient.deliverMessage(encryptedResponse)

        val result = resultDeferred.await()
        assertTrue(result is PairExchangeResult.Error, "Expected Error but got $result")
        assertEquals("Empty daemon device ID", (result as PairExchangeResult.Error).message)
    }
}
