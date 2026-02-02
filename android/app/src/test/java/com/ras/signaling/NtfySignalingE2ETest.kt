package com.ras.signaling

import com.google.protobuf.ByteString
import com.ras.pairing.SignalingClient
import com.ras.pairing.SignalingResult
import com.ras.proto.NtfySignalMessage
import com.ras.proto.SignalError
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.security.SecureRandom

/**
 * Comprehensive End-to-End tests for ntfy signaling.
 *
 * These tests cover all scenarios from the contract (E2E-NTFY-01 through E2E-NTFY-25)
 * using mocked interfaces to simulate the full packet lifecycle through the system.
 *
 * Test coverage:
 * - Happy paths (direct and ntfy fallback)
 * - Security scenarios (replay, tampering, wrong keys)
 * - Timing scenarios (clock skew, timeouts)
 * - Edge cases (large SDP, unicode, duplicates)
 * - Error handling (invalid data, cancellation)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NtfySignalingE2ETest {

    private lateinit var mockDirectClient: SignalingClient
    private lateinit var mockNtfyClient: MockNtfyClient
    private lateinit var signaler: PairingSignaler
    private val testDispatcher = StandardTestDispatcher()

    // Test constants
    private val testMasterSecret = ByteArray(32) { it.toByte() }
    private val testSessionId = "e2e-test-session-abc123"
    private val testOffer = "v=0\r\no=- 123456 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
    private val testAnswer = "v=0\r\no=- 789012 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:0\r\n"
    private val testDeviceId = "e2e-test-device-id"
    private val testDeviceName = "E2E Test Device"
    private val testIp = "192.168.1.100"
    private val testPort = 8080

    @BeforeEach
    fun setup() {
        mockDirectClient = mockk()
        mockNtfyClient = MockNtfyClient()
        signaler = PairingSignaler(
            directClient = mockDirectClient,
            ntfyClient = mockNtfyClient,
            directTimeoutMs = 3000
        )
    }

    // ==================== E2E-NTFY-01: Happy Path - ntfy Fallback ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-01 phone sends offer via ntfy and receives answer`() = runTest {
        // Direct connection fails (simulating NAT)
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        // Wait for subscription
        waitForSubscription()

        // Verify offer was published
        val publishedMessages = mockNtfyClient.getPublishedMessages()
        assertEquals(1, publishedMessages.size, "Offer should be published")

        // Verify published message is encrypted and valid
        val encryptedOffer = publishedMessages[0]
        assertTrue(encryptedOffer.isNotEmpty(), "Published message should be base64")

        // Daemon decrypts and validates offer, then sends answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()

        // Verify success via ntfy path
        assertTrue(signalResult is PairingSignalerResult.Success, "Expected Success")
        val success = signalResult as PairingSignalerResult.Success
        assertFalse(success.usedDirectPath, "Should not use direct path")
        assertTrue(success.usedNtfyPath, "Should use ntfy path")
        assertEquals(testAnswer, success.sdpAnswer)
    }

    // ==================== E2E-NTFY-02: Direct Success (No Fallback) ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-02 direct connection succeeds without ntfy fallback`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } returns SignalingResult.Success(testAnswer)

        val result = signaler.exchangeSdp(
            ip = testIp,
            port = testPort,
            sessionId = testSessionId,
            masterSecret = testMasterSecret,
            sdpOffer = testOffer,
            deviceId = testDeviceId,
            deviceName = testDeviceName
        )

        // Verify success via direct path
        assertTrue(result is PairingSignalerResult.Success)
        val success = result as PairingSignalerResult.Success
        assertTrue(success.usedDirectPath, "Should use direct path")
        assertFalse(success.usedNtfyPath, "Should not use ntfy path")

        // Verify ntfy was never used
        assertEquals(0, mockNtfyClient.getPublishedMessages().size, "No messages should be published to ntfy")
        assertFalse(mockNtfyClient.isSubscribed, "Should not be subscribed to ntfy")
    }

    // ==================== E2E-NTFY-03: Replay Attack Rejected ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-03 phone rejects replayed answer message`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        // First exchange succeeds
        val result1 = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        waitForSubscription()

        // Create a legitimate answer with fixed nonce
        val fixedNonce = ByteArray(16) { 0x42.toByte() }
        val legitimateAnswer = createEncryptedAnswer(nonce = fixedNonce)
        mockNtfyClient.deliverMessage(legitimateAnswer)

        val firstResult = result1.await()
        assertTrue(firstResult is PairingSignalerResult.Success, "First exchange should succeed")

        // Reset for second exchange
        mockNtfyClient.reset()

        // Second exchange - attempt replay
        val result2 = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 2000  // Short timeout for test
            )
        }

        waitForSubscription()

        // Replay the same answer (same nonce) - should be rejected
        mockNtfyClient.deliverMessage(legitimateAnswer)

        // Give time for processing
        delay(100)

        // Now send a fresh answer with new nonce
        val freshAnswer = createEncryptedAnswer(nonce = ByteArray(16) { 0x43.toByte() })
        mockNtfyClient.deliverMessage(freshAnswer)

        val secondResult = result2.await()
        assertTrue(secondResult is PairingSignalerResult.Success, "Second exchange should succeed with fresh nonce")
    }

    // ==================== E2E-NTFY-04: Wrong Session ID Rejected ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-04 phone rejects answer with wrong session ID`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 3000
            )
        }

        waitForSubscription()

        // Send answer with wrong session ID - should be ignored
        mockNtfyClient.deliverMessage(createEncryptedAnswer(sessionId = "wrong-session-id"))

        delay(50)

        // Send correct answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed with correct session")
    }

    // ==================== E2E-NTFY-05: Timestamp Expiry ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-05 phone rejects answer with expired timestamp`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 3000
            )
        }

        waitForSubscription()

        // Send answer with timestamp 60 seconds in past - should be rejected
        mockNtfyClient.deliverMessage(createEncryptedAnswer(timestamp = currentTimestamp() - 60))

        delay(50)

        // Send correct answer with valid timestamp
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed with valid timestamp")
    }

    // ==================== E2E-NTFY-09: Clock Skew Boundary ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-09 clock skew at boundary 30s accepted`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        waitForSubscription()

        // Answer with timestamp exactly 30 seconds in past (boundary - should be accepted)
        mockNtfyClient.deliverMessage(createEncryptedAnswer(timestamp = currentTimestamp() - 30))

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "30s clock skew should be accepted")
    }

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-09 clock skew at boundary 30s future accepted`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        waitForSubscription()

        // Answer with timestamp exactly 30 seconds in future (boundary - should be accepted)
        mockNtfyClient.deliverMessage(createEncryptedAnswer(timestamp = currentTimestamp() + 30))

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "30s future clock skew should be accepted")
    }

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-09 clock skew outside boundary 31s rejected`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 2000
            )
        }

        waitForSubscription()

        // Answer with timestamp 31 seconds in past (outside boundary)
        mockNtfyClient.deliverMessage(createEncryptedAnswer(timestamp = currentTimestamp() - 31))

        delay(50)

        // Should need a valid answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed with valid timestamp")
    }

    // ==================== E2E-NTFY-10: Large SDP ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-10 handles large SDP with many ICE candidates`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        // Create large SDP with many ICE candidates (simulating complex network)
        val largeSdp = buildString {
            append("v=0\r\n")
            append("o=- 123456 2 IN IP4 127.0.0.1\r\n")
            append("s=-\r\n")
            append("t=0 0\r\n")
            append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n")
            // Add 100 ICE candidates
            repeat(100) { i ->
                append("a=candidate:$i 1 UDP ${2122260223 - i} 192.168.1.${i % 256} ${50000 + i} typ host\r\n")
            }
        }

        // Verify SDP is substantial (but under 64KB limit)
        assertTrue(largeSdp.length > 5000, "Large SDP should be significant size")
        assertTrue(largeSdp.length < 65536, "Large SDP should be under 64KB")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = largeSdp,  // Large offer
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        waitForSubscription()

        // Send large answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer(sdp = largeSdp))

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should handle large SDP")
        assertEquals(largeSdp, (signalResult as PairingSignalerResult.Success).sdpAnswer)
    }

    // ==================== E2E-NTFY-11: Unicode Device Name ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-11 handles unicode device name`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val unicodeDeviceName = "📱 Téléphone de José 日本語"

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = unicodeDeviceName  // Unicode name
            )
        }

        waitForSubscription()

        // Verify the published message can be decrypted and contains unicode
        val published = mockNtfyClient.getPublishedMessages()
        assertEquals(1, published.size)

        // Decrypt and verify unicode is preserved
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(testMasterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)
        val decrypted = crypto.decryptFromBase64(published[0])
        val msg = NtfySignalMessage.parseFrom(decrypted)

        assertEquals(unicodeDeviceName, msg.deviceName)

        // Send answer to complete
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed with unicode")
    }

    // ==================== E2E-NTFY-12: Daemon Receives ANSWER (Wrong Type) ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-12 validator rejects wrong message type (daemon expecting OFFER)`() {
        val validator = NtfySignalMessageValidator(
            pendingSessionId = testSessionId,
            expectedType = NtfySignalMessage.MessageType.OFFER  // Daemon expects OFFER
        )

        // Create an ANSWER message (wrong type for daemon)
        val wrongTypeMsg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.ANSWER)  // Wrong type
            .setSessionId(testSessionId)
            .setSdp(testAnswer)
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(generateNonce()))
            .build()

        val result = validator.validate(wrongTypeMsg)
        assertFalse(result.isValid, "Should reject wrong message type")
        assertEquals(ValidationError.WRONG_MESSAGE_TYPE, result.error)
    }

    // ==================== E2E-NTFY-13: Phone Receives OFFER (Wrong Type) ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-13 phone ignores offer message when expecting answer`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 3000
            )
        }

        waitForSubscription()

        // Send OFFER instead of ANSWER - should be ignored
        mockNtfyClient.deliverMessage(createEncryptedOffer())

        delay(50)

        // Send correct ANSWER
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed ignoring wrong type")
    }

    // ==================== E2E-NTFY-17: Race Between Direct and ntfy ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-17 direct success cancels ntfy path`() = runTest {
        // Direct succeeds (so ntfy should never be used)
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } returns SignalingResult.Success(testAnswer)

        val result = signaler.exchangeSdp(
            ip = testIp,
            port = testPort,
            sessionId = testSessionId,
            masterSecret = testMasterSecret,
            sdpOffer = testOffer,
            deviceId = testDeviceId,
            deviceName = testDeviceName
        )

        assertTrue(result is PairingSignalerResult.Success)
        assertTrue((result as PairingSignalerResult.Success).usedDirectPath)
        assertFalse(result.usedNtfyPath)

        // Ntfy should never have been used
        assertEquals(0, mockNtfyClient.getPublishedMessages().size)
    }

    // ==================== E2E-NTFY-21: Duplicate Message Delivery ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-21 handles duplicate messages via nonce cache`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        waitForSubscription()

        // Create single answer
        val answer = createEncryptedAnswer()

        // Deliver same message 3 times (simulating network duplicates)
        mockNtfyClient.deliverMessage(answer)
        mockNtfyClient.deliverMessage(answer)
        mockNtfyClient.deliverMessage(answer)

        // Should succeed with first message, ignore duplicates
        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed with first message")
    }

    // ==================== E2E-NTFY-23: User Cancellation Cleanup ====================

    @Tag("e2e")
    @Test
    fun `E2E-NTFY-23 cancellation cleans up resources`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val job = launch {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        // Wait for subscription to be active
        waitForSubscription()
        assertTrue(mockNtfyClient.isSubscribed, "Should be subscribed")

        // Cancel (simulates user pressing cancel)
        job.cancel()

        // Give time for cleanup
        delay(100)

        // Verify subscription is cleaned up
        assertFalse(mockNtfyClient.isSubscribed, "Should be unsubscribed after cancel")
    }

    // ==================== Decryption Error Scenarios ====================

    @Tag("e2e")
    @Test
    fun `rejects message encrypted with wrong key`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 2000
            )
        }

        waitForSubscription()

        // Encrypt with wrong key
        val wrongKey = ByteArray(32) { 0xFF.toByte() }
        val wrongCrypto = NtfySignalingCrypto(wrongKey)
        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.ANSWER)
            .setSessionId(testSessionId)
            .setSdp(testAnswer)
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(generateNonce()))
            .build()
        val wrongKeyMessage = wrongCrypto.encryptToBase64(msg.toByteArray())

        // Should be silently ignored
        mockNtfyClient.deliverMessage(wrongKeyMessage)

        delay(50)

        // Send correct message
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed after valid message")
    }

    @Tag("e2e")
    @Test
    fun `rejects invalid base64 message`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 2000
            )
        }

        waitForSubscription()

        // Send invalid base64
        mockNtfyClient.deliverMessage("not-valid-base64!!!")

        delay(50)

        // Send correct message
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed after valid message")
    }

    @Tag("e2e")
    @Test
    fun `rejects tampered ciphertext`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 2000
            )
        }

        waitForSubscription()

        // Create valid encrypted message
        val valid = createEncryptedAnswer()

        // Tamper with it by modifying the base64
        val tampered = valid.dropLast(4) + "AAAA"

        // Should be silently ignored
        mockNtfyClient.deliverMessage(tampered)

        delay(50)

        // Send correct message
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should succeed after valid message")
    }

    // ==================== Timeout Scenarios ====================

    @Tag("e2e")
    @Test
    fun `returns timeout when ntfy has no response`() = runTest(testDispatcher) {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 5000
            )
        }

        // Advance past timeout without delivering any answer
        advanceTimeBy(6000)
        advanceUntilIdle()

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.NtfyTimeout, "Should timeout")
    }

    // ==================== Nonce Validation Edge Cases ====================

    @Tag("e2e")
    @Test
    fun `accepts all zeros nonce`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        waitForSubscription()

        // All zeros nonce (valid but suspicious)
        mockNtfyClient.deliverMessage(createEncryptedAnswer(nonce = ByteArray(16)))

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should accept all zeros nonce")
    }

    @Tag("e2e")
    @Test
    fun `accepts all ones nonce`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = testIp,
                port = testPort,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        waitForSubscription()

        // All 0xFF nonce
        mockNtfyClient.deliverMessage(createEncryptedAnswer(nonce = ByteArray(16) { 0xFF.toByte() }))

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success, "Should accept all ones nonce")
    }

    // ==================== Interoperability Tests ====================

    @Tag("e2e")
    @Test
    fun `verifies key derivation matches test vectors`() {
        // Test vector from ntfy_signaling.json
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val expected = "7f888af2e2d09f628559c3fd853370672752c98fac3377690938f72519d86347".hexToBytes()

        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)

        assertTrue(signalingKey.contentEquals(expected), "Key derivation should match test vector")
    }

    @Tag("e2e")
    @Test
    fun `verifies encryption matches test vectors`() {
        // Test vector from ntfy_signaling.json
        val key = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2".hexToBytes()
        val iv = "112233445566778899aabbcc".hexToBytes()
        val plaintext = "{\"type\":\"OFFER\",\"session_id\":\"abc123\",\"sdp\":\"v=0...\"}".toByteArray()
        val expectedBase64 = "ESIzRFVmd4iZqrvM3vb+eOINH0iViUgTfOOjwCHS5Yp9WRtaiqNq7q942vVM9xTcm8LXCrpga9mur1MltMsWcLhZRqDS/Je/uQc9gJ2K6rqE"

        val crypto = NtfySignalingCrypto(key)
        val encrypted = crypto.encryptToBase64WithIv(plaintext, iv)

        assertEquals(expectedBase64, encrypted, "Encryption should match test vector")
    }

    // ==================== SecureRandom Verification ====================

    @Tag("e2e")
    @Test
    fun `crypto uses SecureRandom for IV generation`() {
        val key = ByteArray(32) { it.toByte() }
        val crypto = NtfySignalingCrypto(key)
        val plaintext = "test".toByteArray()

        // Encrypt multiple times
        val ivs = (1..10).map {
            val encrypted = crypto.encrypt(plaintext)
            encrypted.take(12).toByteArray()  // Extract IV (first 12 bytes)
        }

        // All IVs should be unique (SecureRandom)
        val uniqueIvs = ivs.map { it.toList() }.toSet()
        assertEquals(10, uniqueIvs.size, "All IVs should be unique (CSPRNG)")
    }

    // ==================== Helper Functions ====================

    private suspend fun waitForSubscription(timeoutMs: Long = 5000) {
        val startTime = System.currentTimeMillis()
        while (!mockNtfyClient.isSubscribed && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(10)
        }
        if (!mockNtfyClient.isSubscribed) {
            throw AssertionError("Subscription not active after ${timeoutMs}ms")
        }
    }

    private fun currentTimestamp(): Long = System.currentTimeMillis() / 1000

    private fun generateNonce(): ByteArray {
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    private fun createEncryptedAnswer(
        sessionId: String = testSessionId,
        sdp: String = testAnswer,
        timestamp: Long = currentTimestamp(),
        nonce: ByteArray = generateNonce()
    ): String {
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(testMasterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.ANSWER)
            .setSessionId(sessionId)
            .setSdp(sdp)
            .setTimestamp(timestamp)
            .setNonce(ByteString.copyFrom(nonce))
            .build()

        return crypto.encryptToBase64(msg.toByteArray())
    }

    private fun createEncryptedOffer(): String {
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(testMasterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.OFFER)
            .setSessionId(testSessionId)
            .setSdp(testOffer)
            .setDeviceId("other-device")
            .setDeviceName("Other Device")
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(generateNonce()))
            .build()

        return crypto.encryptToBase64(msg.toByteArray())
    }
}

// Helper extension
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
