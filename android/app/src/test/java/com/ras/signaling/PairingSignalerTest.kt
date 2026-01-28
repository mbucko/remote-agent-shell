package com.ras.signaling

import com.google.protobuf.ByteString
import com.ras.pairing.SignalingClient
import com.ras.pairing.SignalingResult
import com.ras.proto.NtfySignalMessage
import com.ras.proto.SignalError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Tests for PairingSignaler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingSignalerTest {

    private lateinit var mockDirectClient: SignalingClient
    private lateinit var mockNtfyClient: MockNtfyClient
    private lateinit var signaler: PairingSignaler
    private val testDispatcher = StandardTestDispatcher()

    private val testMasterSecret = ByteArray(32) { it.toByte() }
    private val testSessionId = "test-session-123"
    private val testOffer = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
    private val testAnswer = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:0\r\n"
    private val testDeviceId = "test-device-id"
    private val testDeviceName = "Test Device"

    @Before
    fun setup() {
        mockDirectClient = mockk()
        mockNtfyClient = MockNtfyClient()
        signaler = PairingSignaler(
            directClient = mockDirectClient,
            ntfyClient = mockNtfyClient,
            directTimeoutMs = 3000
        )
    }

    // ==================== Direct Signaling Tests ====================

    @Test
    fun `uses direct signaling when successful`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } returns SignalingResult.Success(testAnswer)

        val result = signaler.exchangeSdp(
            ip = "192.168.1.1",
            port = 8080,
            sessionId = testSessionId,
            masterSecret = testMasterSecret,
            sdpOffer = testOffer,
            deviceId = testDeviceId,
            deviceName = testDeviceName
        )

        assertTrue(result is PairingSignalerResult.Success)
        assertEquals(testAnswer, (result as PairingSignalerResult.Success).sdpAnswer)
        assertTrue(result.usedDirectPath)
        assertFalse(result.usedNtfyPath)

        coVerify(exactly = 1) {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `does not use ntfy when direct succeeds`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } returns SignalingResult.Success(testAnswer)

        signaler.exchangeSdp(
            ip = "192.168.1.1",
            port = 8080,
            sessionId = testSessionId,
            masterSecret = testMasterSecret,
            sdpOffer = testOffer,
            deviceId = testDeviceId,
            deviceName = testDeviceName
        )

        // Ntfy should not have been used
        assertEquals(0, mockNtfyClient.getPublishedMessages().size)
    }

    // ==================== Ntfy Fallback Tests ====================

    @Test
    fun `falls back to ntfy after direct timeout`() = runTest(testDispatcher) {
        // Direct signaling times out
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            delay(5000)  // Exceeds 3 second timeout
            throw SocketTimeoutException("Timeout")
        }

        val result = async {
            signaler.exchangeSdp(
                ip = "192.168.1.1",
                port = 8080,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        // Advance past direct timeout
        advanceTimeBy(3500)

        // Deliver ntfy answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        advanceUntilIdle()

        val signalResult = result.await()
        assertTrue(signalResult is PairingSignalerResult.Success)
        assertFalse((signalResult as PairingSignalerResult.Success).usedDirectPath)
        assertTrue(signalResult.usedNtfyPath)
    }

    @Test
    fun `falls back to ntfy on connection refused`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = "192.168.1.1",
                port = 8080,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        // Wait for subscription to be active
        waitForSubscription()

        // Deliver ntfy answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue("Expected Success but got $signalResult", signalResult is PairingSignalerResult.Success)
    }

    @Test
    fun `falls back to ntfy on direct signaling error`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } returns SignalingResult.Error(SignalError.ErrorCode.INVALID_SESSION)

        val result = async {
            signaler.exchangeSdp(
                ip = "192.168.1.1",
                port = 8080,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName
            )
        }

        // Wait for subscription
        waitForSubscription()

        // Deliver ntfy answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue("Expected Success but got $signalResult", signalResult is PairingSignalerResult.Success)
    }

    // ==================== Ntfy Message Handling Tests ====================

    @Test
    fun `ignores offer messages when expecting answer`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = "192.168.1.1",
                port = 8080,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 10000
            )
        }

        // Wait for subscription
        waitForSubscription()

        // Send wrong message type (OFFER instead of ANSWER)
        mockNtfyClient.deliverMessage(createEncryptedOffer())

        delay(50)  // Small delay

        // Now send correct answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue("Expected Success but got $signalResult", signalResult is PairingSignalerResult.Success)
    }

    @Test
    fun `ignores messages with wrong session id`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = "192.168.1.1",
                port = 8080,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 10000
            )
        }

        // Wait for subscription
        waitForSubscription()

        // Send answer with wrong session ID
        mockNtfyClient.deliverMessage(createEncryptedAnswer(sessionId = "wrong-session"))

        delay(50)  // Small delay

        // Now send correct answer
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        val signalResult = result.await()
        assertTrue("Expected Success but got $signalResult", signalResult is PairingSignalerResult.Success)
    }

    // ==================== Timeout Tests ====================

    @Test
    fun `returns timeout error when ntfy times out`() = runTest(testDispatcher) {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val result = async {
            signaler.exchangeSdp(
                ip = "192.168.1.1",
                port = 8080,
                sessionId = testSessionId,
                masterSecret = testMasterSecret,
                sdpOffer = testOffer,
                deviceId = testDeviceId,
                deviceName = testDeviceName,
                ntfyTimeoutMs = 5000
            )
        }

        // Advance past ntfy timeout without delivering answer
        advanceTimeBy(6000)
        advanceUntilIdle()

        val signalResult = result.await()
        assertTrue("Expected NtfyTimeout but got $signalResult", signalResult is PairingSignalerResult.NtfyTimeout)
    }

    // ==================== Cleanup Tests ====================

    @Test
    fun `cleans up resources on success`() = runTest {
        coEvery {
            mockDirectClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } returns SignalingResult.Success(testAnswer)

        signaler.exchangeSdp(
            ip = "192.168.1.1",
            port = 8080,
            sessionId = testSessionId,
            masterSecret = testMasterSecret,
            sdpOffer = testOffer,
            deviceId = testDeviceId,
            deviceName = testDeviceName
        )

        // Verify signaler is ready for next use
        assertFalse(mockNtfyClient.isSubscribed)
    }

    // ==================== Helper Functions ====================

    /**
     * Wait for the mock ntfy client to be subscribed.
     */
    private suspend fun waitForSubscription(timeoutMs: Long = 5000) {
        val startTime = System.currentTimeMillis()
        while (!mockNtfyClient.isSubscribed && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(10)
        }
        if (!mockNtfyClient.isSubscribed) {
            throw AssertionError("Subscription not active after ${timeoutMs}ms")
        }
    }

    private fun createEncryptedAnswer(
        sessionId: String = testSessionId,
        sdp: String = testAnswer,
        timestamp: Long = System.currentTimeMillis() / 1000
    ): String {
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(testMasterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.ANSWER)
            .setSessionId(sessionId)
            .setSdp(sdp)
            .setTimestamp(timestamp)
            .setNonce(ByteString.copyFrom(ByteArray(16) { (System.nanoTime() and 0xFF).toByte() }))
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
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(ByteArray(16) { (System.nanoTime() and 0xFF).toByte() }))
            .build()

        return crypto.encryptToBase64(msg.toByteArray())
    }
}
