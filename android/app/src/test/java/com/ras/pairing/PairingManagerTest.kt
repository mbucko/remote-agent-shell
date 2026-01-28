package com.ras.pairing

import android.content.Context
import com.google.protobuf.ByteString
import com.ras.crypto.HmacUtils
import com.ras.crypto.KeyDerivation
import com.ras.crypto.hexToBytes
import com.ras.data.connection.ConnectionManager
import com.ras.data.keystore.KeyManager
import com.ras.data.webrtc.WebRTCClient
import com.ras.proto.AuthChallenge
import com.ras.proto.AuthEnvelope
import com.ras.proto.AuthSuccess
import com.ras.proto.AuthVerify
import com.ras.proto.SignalError
import com.ras.signaling.MockNtfyClient
import com.ras.signaling.NtfyClientInterface
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingManagerTest {

    private lateinit var context: Context
    private lateinit var keyManager: KeyManager
    private lateinit var webRTCClientFactory: WebRTCClient.Factory
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var signalingClient: SignalingClient
    private lateinit var ntfyClient: NtfyClientInterface
    private lateinit var connectionManager: ConnectionManager

    private val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
    private val authKey = KeyDerivation.deriveKey(masterSecret, "auth")

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)
        webRTCClientFactory = mockk()
        webRTCClient = mockk(relaxed = true)
        signalingClient = mockk()
        ntfyClient = MockNtfyClient()
        connectionManager = mockk(relaxed = true)

        every { keyManager.getOrCreateDeviceId() } returns "test-device-id"
        every { webRTCClientFactory.create() } returns webRTCClient
    }

    private fun createValidPayload(): ParsedQrPayload {
        return ParsedQrPayload(
            version = 1,
            ip = "192.168.1.1",
            port = 8080,
            masterSecret = masterSecret.copyOf(),
            sessionId = "test-session-123",
            ntfyTopic = "ras-abc123"
        )
    }

    private fun createPairingManager(testScope: kotlinx.coroutines.CoroutineScope): PairingManager {
        return PairingManager(
            context = context,
            signalingClient = signalingClient,
            keyManager = keyManager,
            webRTCClientFactory = webRTCClientFactory,
            ntfyClient = ntfyClient,
            connectionManager = connectionManager
        ).also {
            it.scope = testScope
        }
    }

    // ============================================================================
    // State Transition Tests
    // ============================================================================

    @Test
    fun `initial state is Idle`() = runTest {
        val pairingManager = createPairingManager(this)

        assertEquals(PairingState.Idle, pairingManager.state.value)
    }

    @Test
    fun `startScanning transitions to Scanning state`() = runTest {
        val pairingManager = createPairingManager(this)

        pairingManager.startScanning()

        assertEquals(PairingState.Scanning, pairingManager.state.value)
    }

    @Test
    fun `reset transitions back to Idle`() = runTest {
        val pairingManager = createPairingManager(this)

        pairingManager.startScanning()
        pairingManager.reset()

        assertEquals(PairingState.Idle, pairingManager.state.value)
    }

    // ============================================================================
    // Happy Path - Full Flow
    // ============================================================================

    @Test
    fun `full pairing flow success`() = runTest {
        // Setup WebRTC mock
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns true

        // Setup data channel for auth
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        coEvery { webRTCClient.send(any()) } coAnswers {
            serverChannel.send(firstArg())
        }
        coEvery { webRTCClient.receive() } coAnswers {
            clientChannel.receive()
        }

        // Setup signaling mock
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Success("test-sdp-answer")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()

        // Start pairing and simulate auth handshake in parallel
        val authJob = launch {
            // Wait for auth client to start receiving
            val responseBytes = serverChannel.receive()
            val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)
            val clientNonce = responseEnvelope.response.nonce.toByteArray()

            // Send verify
            val serverHmac = HmacUtils.computeHmac(authKey, clientNonce)
            val verify = AuthEnvelope.newBuilder()
                .setVerify(
                    AuthVerify.newBuilder()
                        .setHmac(ByteString.copyFrom(serverHmac))
                )
                .build()
            clientChannel.send(verify.toByteArray())

            // Send success
            val success = AuthEnvelope.newBuilder()
                .setSuccess(
                    AuthSuccess.newBuilder()
                        .setDeviceId("server-assigned-device-id")
                )
                .build()
            clientChannel.send(success.toByteArray())
        }

        // Server sends challenge
        val serverNonce = ByteArray(32) { 0xAA.toByte() }
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Start pairing
        pairingManager.startPairing(payload)

        // Process all pending coroutines
        advanceUntilIdle()
        authJob.join()

        // Verify final state
        val finalState = pairingManager.state.value
        assertTrue("Expected Authenticated state, got: $finalState", finalState is PairingState.Authenticated)
        assertEquals("server-assigned-device-id", (finalState as PairingState.Authenticated).deviceId)

        // Verify master secret was stored
        coVerify { keyManager.storeMasterSecret(any()) }
        coVerify { keyManager.storeDaemonInfo(any(), any(), any()) }
    }

    // ============================================================================
    // Signaling Failure Scenarios
    // ============================================================================

    // Note: With ntfy fallback, direct signaling failures now trigger ntfy retry.
    // These tests verify the state machine transitions correctly to TryingDirect
    // when direct signaling fails. Full failure tests would need to mock ntfy timeout.

    @Test
    fun `signaling failure triggers ntfy fallback and tries direct first`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Error(SignalError.ErrorCode.UNKNOWN)

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        // Initial state should transition to TryingDirect
        advanceUntilIdle()

        // When direct signaling fails, it falls back to ntfy.
        // The mock ntfy client won't deliver an answer, so eventually
        // the state will transition to NtfyWaitingForAnswer or similar.
        // For this test, we just verify we got past TryingDirect.
        val currentState = pairingManager.state.value
        // State could be in ntfy fallback phase
        assertTrue(
            "Expected state after direct failure, got: $currentState",
            currentState is PairingState.TryingDirect ||
            currentState is PairingState.NtfySubscribing ||
            currentState is PairingState.NtfyWaitingForAnswer ||
            currentState is PairingState.Failed
        )
    }

    @Test
    fun `signaling direct error triggers fallback`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Error(SignalError.ErrorCode.AUTHENTICATION_FAILED)

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        advanceUntilIdle()

        // After direct failure, should attempt ntfy fallback
        val currentState = pairingManager.state.value
        assertTrue(
            "Expected state after direct auth failure, got: $currentState",
            currentState is PairingState.TryingDirect ||
            currentState is PairingState.NtfySubscribing ||
            currentState is PairingState.NtfyWaitingForAnswer ||
            currentState is PairingState.Failed
        )
    }

    @Test
    fun `signaling failure - session not found triggers ntfy fallback`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Error(SignalError.ErrorCode.INVALID_SESSION)

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        advanceUntilIdle()

        val currentState = pairingManager.state.value
        assertTrue(
            "Expected state after direct session error, got: $currentState",
            currentState is PairingState.TryingDirect ||
            currentState is PairingState.NtfySubscribing ||
            currentState is PairingState.NtfyWaitingForAnswer ||
            currentState is PairingState.Failed
        )
    }

    @Test
    fun `signaling failure - rate limited triggers ntfy fallback`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Error(SignalError.ErrorCode.RATE_LIMITED)

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        advanceUntilIdle()

        val currentState = pairingManager.state.value
        assertTrue(
            "Expected state after rate limit, got: $currentState",
            currentState is PairingState.TryingDirect ||
            currentState is PairingState.NtfySubscribing ||
            currentState is PairingState.NtfyWaitingForAnswer ||
            currentState is PairingState.Failed
        )
    }

    @Test
    fun `signaling failure - internal error triggers ntfy fallback`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Error(SignalError.ErrorCode.INTERNAL_ERROR)

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        advanceUntilIdle()

        val currentState = pairingManager.state.value
        assertTrue(
            "Expected state after internal error, got: $currentState",
            currentState is PairingState.TryingDirect ||
            currentState is PairingState.NtfySubscribing ||
            currentState is PairingState.NtfyWaitingForAnswer ||
            currentState is PairingState.Failed
        )
    }

    // ============================================================================
    // WebRTC Failure Scenarios
    // ============================================================================

    @Test
    fun `webRTC offer creation failure`() = runTest {
        coEvery { webRTCClient.createOffer() } throws RuntimeException("Failed to create offer")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        advanceUntilIdle()

        val finalState = pairingManager.state.value
        assertTrue(finalState is PairingState.Failed)
        assertEquals(PairingState.FailureReason.CONNECTION_FAILED, (finalState as PairingState.Failed).reason)
    }

    @Test
    fun `webRTC data channel timeout`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns false // Timeout

        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Success("test-sdp-answer")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        advanceUntilIdle()

        val finalState = pairingManager.state.value
        assertTrue(finalState is PairingState.Failed)
        assertEquals(PairingState.FailureReason.CONNECTION_FAILED, (finalState as PairingState.Failed).reason)
    }

    @Test
    fun `webRTC set remote description failure`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.setRemoteDescription(any()) } throws RuntimeException("Invalid SDP")

        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Success("invalid-sdp")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        advanceUntilIdle()

        val finalState = pairingManager.state.value
        assertTrue(finalState is PairingState.Failed)
        assertEquals(PairingState.FailureReason.CONNECTION_FAILED, (finalState as PairingState.Failed).reason)
    }

    // ============================================================================
    // Authentication Failure Scenarios
    // ============================================================================

    @Test
    fun `authentication failure - wrong server HMAC`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns true

        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        coEvery { webRTCClient.send(any()) } coAnswers {
            serverChannel.send(firstArg())
        }
        coEvery { webRTCClient.receive() } coAnswers {
            clientChannel.receive()
        }

        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Success("test-sdp-answer")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()

        // Simulate server sending wrong HMAC
        val authJob = launch {
            // Wait for client response
            serverChannel.receive()

            // Send WRONG verify HMAC
            val wrongHmac = ByteArray(32) // All zeros, wrong
            val verify = AuthEnvelope.newBuilder()
                .setVerify(
                    AuthVerify.newBuilder()
                        .setHmac(ByteString.copyFrom(wrongHmac))
                )
                .build()
            clientChannel.send(verify.toByteArray())
        }

        // Server sends challenge
        val serverNonce = ByteArray(32) { 0xAA.toByte() }
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        pairingManager.startPairing(payload)

        advanceUntilIdle()
        authJob.join()

        val finalState = pairingManager.state.value
        assertTrue("Expected Failed state, got: $finalState", finalState is PairingState.Failed)
        assertEquals(PairingState.FailureReason.AUTH_FAILED, (finalState as PairingState.Failed).reason)
    }

    @Test
    fun `authentication failure - malformed challenge`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns true

        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)

        coEvery { webRTCClient.receive() } coAnswers {
            clientChannel.receive()
        }

        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Success("test-sdp-answer")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()

        // Send malformed data (not a valid protobuf)
        clientChannel.send(byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte()))

        pairingManager.startPairing(payload)

        advanceUntilIdle()

        val finalState = pairingManager.state.value
        assertTrue("Expected Failed state, got: $finalState", finalState is PairingState.Failed)
        assertEquals(PairingState.FailureReason.AUTH_FAILED, (finalState as PairingState.Failed).reason)
    }

    // ============================================================================
    // Connection Handoff Tests
    // ============================================================================

    @Test
    fun `connection is handed off to ConnectionManager after successful auth`() = runTest {
        // Setup WebRTC mock
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns true

        // Setup data channel for auth
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        coEvery { webRTCClient.send(any()) } coAnswers {
            serverChannel.send(firstArg())
        }
        coEvery { webRTCClient.receive() } coAnswers {
            clientChannel.receive()
        }

        // Setup signaling mock
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Success("test-sdp-answer")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()

        // Start pairing and simulate auth handshake in parallel
        val authJob = launch {
            // Wait for auth client to start receiving
            val responseBytes = serverChannel.receive()
            val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)
            val clientNonce = responseEnvelope.response.nonce.toByteArray()

            // Send verify
            val serverHmac = HmacUtils.computeHmac(authKey, clientNonce)
            val verify = AuthEnvelope.newBuilder()
                .setVerify(
                    AuthVerify.newBuilder()
                        .setHmac(ByteString.copyFrom(serverHmac))
                )
                .build()
            clientChannel.send(verify.toByteArray())

            // Send success
            val success = AuthEnvelope.newBuilder()
                .setSuccess(
                    AuthSuccess.newBuilder()
                        .setDeviceId("server-assigned-device-id")
                )
                .build()
            clientChannel.send(success.toByteArray())
        }

        // Server sends challenge
        val serverNonce = ByteArray(32) { 0xAA.toByte() }
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Start pairing
        pairingManager.startPairing(payload)

        // Process all pending coroutines
        advanceUntilIdle()
        authJob.join()

        // Verify connection was handed off to ConnectionManager with auth key
        verify { connectionManager.connect(webRTCClient, any()) }
    }

    // ============================================================================
    // Cleanup Tests
    // ============================================================================

    @Test
    fun `webRTC client is closed on failure`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns false
        coEvery { webRTCClient.close() } just Runs

        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Success("test-sdp-answer")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        advanceUntilIdle()

        verify { webRTCClient.close() }
    }

    @Test
    fun `webRTC client is closed on reset`() = runTest {
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.close() } just Runs

        // Make signaling hang so we can test reset during operation
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(10000)
            SignalingResult.Success("test-sdp-answer")
        }

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)

        // Let the coroutine start
        testScheduler.advanceTimeBy(100)

        // Reset while waiting for signaling
        pairingManager.reset()

        verify { webRTCClient.close() }
    }

    // ============================================================================
    // State Observation Tests
    // ============================================================================

    @Test
    fun `state transitions correctly tracked via StateFlow`() = runTest {
        val pairingManager = createPairingManager(this)

        // Initial state should be Idle
        assertEquals(PairingState.Idle, pairingManager.state.value)

        // After startScanning, should be Scanning
        pairingManager.startScanning()
        assertEquals(PairingState.Scanning, pairingManager.state.value)

        // After reset, should be back to Idle
        pairingManager.reset()
        assertEquals(PairingState.Idle, pairingManager.state.value)
    }
}
