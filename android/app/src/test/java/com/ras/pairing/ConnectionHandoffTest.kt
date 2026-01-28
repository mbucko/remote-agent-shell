package com.ras.pairing

import android.content.Context
import com.google.protobuf.ByteString
import com.ras.crypto.HmacUtils
import com.ras.crypto.KeyDerivation
import com.ras.crypto.hexToBytes
import com.ras.data.connection.ConnectionManager
import com.ras.data.keystore.KeyManager
import com.ras.data.webrtc.ConnectionOwnership
import com.ras.data.webrtc.WebRTCClient
import com.ras.proto.AuthChallenge
import com.ras.proto.AuthEnvelope
import com.ras.proto.AuthSuccess
import com.ras.proto.AuthVerify
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for connection handoff between PairingManager and ConnectionManager.
 *
 * These tests verify the critical ownership transfer pattern:
 * 1. PairingManager creates and owns WebRTC client during pairing
 * 2. After successful auth, ownership transfers to ConnectionManager
 * 3. PairingManager.reset() does NOT close the connection
 * 4. Connection remains usable for commands
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHandoffTest {

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

    private suspend fun setupSuccessfulAuth(
        clientChannel: Channel<ByteArray>,
        serverChannel: Channel<ByteArray>,
        testScope: kotlinx.coroutines.CoroutineScope
    ) = testScope.launch {
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

    // ============================================================================
    // Connection Survives ViewModel Cleared
    // ============================================================================

    @Test
    fun `connection remains active after ViewModel cleared (reset called)`() = runTest {
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

        // Start auth handshake simulation
        val authJob = setupSuccessfulAuth(clientChannel, serverChannel, this)

        // Send initial challenge
        val serverNonce = ByteArray(32) { 0xAA.toByte() }
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Complete pairing
        pairingManager.startPairing(payload)
        advanceUntilIdle()
        authJob.join()

        // Verify successful authentication
        assertTrue(pairingManager.state.value is PairingState.Authenticated)

        // Simulate ViewModel.onCleared() - this triggers reset()
        pairingManager.reset()

        // Connection should NOT have been closed - it was handed off to ConnectionManager
        // The WebRTCClient.close() should NOT be called after successful handoff
        // Verify only 0 or 1 close calls (might be called during setup/teardown)
        // But after handoff, PairingManager no longer owns the client
        verify(exactly = 0) { webRTCClient.close() }

        // ConnectionManager should still have the connection (connect is now suspend)
        coVerify { connectionManager.connect(webRTCClient, any()) }
    }

    // ============================================================================
    // State Machine - Ownership Transfer
    // ============================================================================

    @Test
    fun `PairingManager releases ownership after successful handoff`() = runTest {
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

        // Start auth handshake simulation
        val authJob = setupSuccessfulAuth(clientChannel, serverChannel, this)

        // Send initial challenge
        val serverNonce = ByteArray(32) { 0xAA.toByte() }
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Complete pairing
        pairingManager.startPairing(payload)
        advanceUntilIdle()
        authJob.join()

        // After successful auth, PairingManager should no longer own the client
        assertNull(
            "PairingManager should release ownership after handoff",
            pairingManager.getWebRTCClient()
        )

        // But ConnectionManager should have received it
        coVerify { connectionManager.connect(any(), any()) }
    }

    @Test
    fun `PairingManager retains ownership on failure (no handoff)`() = runTest {
        // Setup WebRTC mock
        coEvery { webRTCClient.createOffer() } returns "test-sdp-offer"
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns false // Timeout - failure

        // Setup signaling mock
        coEvery { signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any()) } returns
            SignalingResult.Success("test-sdp-answer")

        val pairingManager = createPairingManager(this)

        val payload = createValidPayload()
        pairingManager.startPairing(payload)
        advanceUntilIdle()

        // On failure, ConnectionManager should NOT receive connection
        coVerify(exactly = 0) { connectionManager.connect(any(), any()) }

        // PairingManager should have cleaned up via ownership-aware close
        verify { webRTCClient.closeByOwner(ConnectionOwnership.PairingManager) }
    }

    // ============================================================================
    // E2E: Pairing Success → Commands Work
    // ============================================================================

    @Test
    fun `E2E pairing completes and connection can be used for commands`() = runTest {
        // Track if connection is ready
        val isConnectedFlow = MutableStateFlow(false)
        every { connectionManager.isConnected } returns isConnectedFlow

        // When connect() is called, mark as connected
        coEvery { connectionManager.connect(any(), any()) } coAnswers {
            isConnectedFlow.value = true
        }

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

        // Start auth handshake simulation
        val authJob = setupSuccessfulAuth(clientChannel, serverChannel, this)

        // Send initial challenge
        val serverNonce = ByteArray(32) { 0xAA.toByte() }
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Complete pairing
        pairingManager.startPairing(payload)
        advanceUntilIdle()
        authJob.join()

        // Navigate away (triggers cleanup like ViewModel.onCleared())
        pairingManager.reset()

        // ConnectionManager should still be "connected" after pairing manager cleanup
        assertTrue(
            "Connection should remain active after pairing cleanup",
            connectionManager.isConnected.value
        )
    }
}
