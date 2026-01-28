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
import com.ras.proto.AuthError
import com.ras.proto.AuthSuccess
import com.ras.proto.AuthVerify
import com.ras.proto.NtfySignalMessage
import com.ras.proto.SignalError
import com.ras.signaling.MockNtfyClient
import com.ras.signaling.NtfySignalingCrypto
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * End-to-End tests for the complete pairing flow.
 *
 * These tests verify the full pairing lifecycle from QR code parsing through
 * WebRTC connection establishment and authentication handshake.
 *
 * Test coverage:
 * - E2E-PAIR-01: Direct signaling happy path
 * - E2E-PAIR-02: ntfy fallback happy path
 * - E2E-PAIR-03: Connection survives lifecycle events
 * - E2E-PAIR-04: Encrypted communication verification
 * - E2E-PAIR-05: Concurrent pairing rejection
 * - E2E-PAIR-06: Auth failure handling
 * - E2E-PAIR-07: Timeout handling
 * - E2E-PAIR-08: Connection handoff to ConnectionManager
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PairingE2ETest {

    // Mock components
    private lateinit var context: Context
    private lateinit var keyManager: KeyManager
    private lateinit var webRTCClientFactory: WebRTCClient.Factory
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var signalingClient: SignalingClient
    private lateinit var mockNtfyClient: MockNtfyClient
    private lateinit var connectionManager: ConnectionManager

    // Test data
    private val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
    private val authKey = KeyDerivation.deriveKey(masterSecret, "auth")
    private val testSessionId = "e2e-test-session-123"
    private val testDeviceId = "e2e-test-device-id"
    private val testDeviceName = "E2E Test Device"
    private val testSdpOffer = "v=0\r\no=- 123456 2 IN IP4 127.0.0.1\r\ns=-\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
    private val testSdpAnswer = "v=0\r\no=- 789012 2 IN IP4 127.0.0.1\r\ns=-\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:0\r\n"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)
        webRTCClientFactory = mockk()
        webRTCClient = mockk(relaxed = true)
        signalingClient = mockk()
        mockNtfyClient = MockNtfyClient()
        connectionManager = mockk(relaxed = true)

        // Default mock behaviors
        every { keyManager.getOrCreateDeviceId() } returns testDeviceId
        every { webRTCClientFactory.create() } returns webRTCClient
    }

    private fun createTestPayload(): ParsedQrPayload {
        return ParsedQrPayload(
            version = 1,
            ip = "192.168.1.100",
            port = 8080,
            masterSecret = masterSecret.copyOf(),
            sessionId = testSessionId,
            ntfyTopic = "ras-4884fdaafea4"
        )
    }

    private fun createPairingManager(testScope: kotlinx.coroutines.CoroutineScope): PairingManager {
        return PairingManager(
            context = context,
            signalingClient = signalingClient,
            keyManager = keyManager,
            webRTCClientFactory = webRTCClientFactory,
            ntfyClient = mockNtfyClient,
            connectionManager = connectionManager
        ).also {
            it.scope = testScope
        }
    }

    // ============================================================================
    // E2E-PAIR-01: Direct Signaling Happy Path
    // ============================================================================

    @Test
    fun `E2E-PAIR-01 direct signaling happy path - complete flow`() = runTest {
        // Setup mocks for successful direct path
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        val pairingManager = createPairingManager(this)
        val payload = createTestPayload()

        // Start auth handshake simulation in parallel
        val authJob = simulateSuccessfulAuthHandshake(clientChannel, serverChannel)

        // Start pairing
        pairingManager.startPairing(payload)

        advanceUntilIdle()
        authJob.join()

        // Verify complete success
        val finalState = pairingManager.state.value
        assertTrue("Expected Authenticated, got $finalState", finalState is PairingState.Authenticated)
        assertEquals("daemon-assigned-device-id", (finalState as PairingState.Authenticated).deviceId)

        // Verify direct signaling was used (no ntfy)
        assertEquals("ntfy should not be used", 0, mockNtfyClient.getPublishedMessages().size)

        // Verify connection handoff
        verify { connectionManager.connect(webRTCClient, any()) }

        // Verify credentials stored
        coVerify { keyManager.storeMasterSecret(any()) }
        coVerify { keyManager.storeDaemonInfo(any(), any(), any()) }
    }

    // ============================================================================
    // E2E-PAIR-02: ntfy Fallback Happy Path
    // ============================================================================

    @Test
    fun `E2E-PAIR-02 ntfy fallback happy path - complete flow`() = runTest {
        // Direct connection fails
        coEvery {
            signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused (NAT)")

        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        val pairingManager = createPairingManager(this)
        val payload = createTestPayload()

        // Start pairing in background
        val pairingJob = launch {
            pairingManager.startPairing(payload)
        }

        // Wait for ntfy subscription
        waitForNtfySubscription()

        // Simulate daemon sending answer via ntfy
        mockNtfyClient.deliverMessage(createEncryptedAnswer())

        // Start auth handshake
        val authJob = simulateSuccessfulAuthHandshake(clientChannel, serverChannel)

        advanceUntilIdle()
        pairingJob.join()
        authJob.join()

        // Verify success via ntfy
        val finalState = pairingManager.state.value
        assertTrue("Expected Authenticated, got $finalState", finalState is PairingState.Authenticated)

        // Verify ntfy was used
        assertTrue("ntfy should be used", mockNtfyClient.getPublishedMessages().isNotEmpty())
    }

    // ============================================================================
    // E2E-PAIR-03: Connection Survives Lifecycle Events
    // ============================================================================

    @Test
    fun `E2E-PAIR-03 reset from Scanning state cleans up`() = runTest {
        val pairingManager = createPairingManager(this)

        pairingManager.startScanning()
        assertEquals(PairingState.Scanning, pairingManager.state.value)

        pairingManager.reset()
        assertEquals(PairingState.Idle, pairingManager.state.value)
    }

    @Test
    fun `E2E-PAIR-03 reset cleans up WebRTC on failure state`() = runTest {
        // Setup offer creation to fail
        coEvery { webRTCClient.createOffer() } throws RuntimeException("ICE gathering failed")
        coEvery { webRTCClient.close() } just Runs

        val pairingManager = createPairingManager(this)

        pairingManager.startPairing(createTestPayload())
        advanceUntilIdle()

        // Should be in failed state
        val failedState = pairingManager.state.value
        assertTrue("Should be Failed, got: $failedState", failedState is PairingState.Failed)

        // Verify WebRTC was closed during cleanup
        verify { webRTCClient.close() }

        // Reset should work
        pairingManager.reset()
        assertEquals(PairingState.Idle, pairingManager.state.value)
    }

    @Test
    fun `E2E-PAIR-03 can start new pairing after failure`() = runTest {
        // First attempt fails
        coEvery { webRTCClient.createOffer() } throws RuntimeException("First attempt failed")
        coEvery { webRTCClient.close() } just Runs

        val pairingManager = createPairingManager(this)

        // First attempt
        pairingManager.startPairing(createTestPayload())
        advanceUntilIdle()

        assertTrue(
            "First attempt should fail",
            pairingManager.state.value is PairingState.Failed
        )

        // Reset
        pairingManager.reset()
        assertEquals(PairingState.Idle, pairingManager.state.value)

        // Setup successful second attempt
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        // Second attempt
        val authJob = simulateSuccessfulAuthHandshake(clientChannel, serverChannel)
        pairingManager.startPairing(createTestPayload())

        advanceUntilIdle()
        authJob.join()

        // Should succeed
        val finalState = pairingManager.state.value
        assertTrue(
            "Second attempt should succeed, got: $finalState",
            finalState is PairingState.Authenticated
        )
    }

    // ============================================================================
    // E2E-PAIR-04: Encrypted Communication Verification
    // ============================================================================

    @Test
    fun `E2E-PAIR-04 auth uses derived key for HMAC`() = runTest {
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        val pairingManager = createPairingManager(this)
        val payload = createTestPayload()

        // Capture the HMAC sent by client
        val capturedHmac = CompletableDeferred<ByteArray>()

        val authJob = launch {
            // Wait for client response (contains client's HMAC of server nonce)
            val responseBytes = serverChannel.receive()
            val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)
            val clientHmac = responseEnvelope.response.hmac.toByteArray()
            val clientNonce = responseEnvelope.response.nonce.toByteArray()

            capturedHmac.complete(clientHmac)

            // Verify the HMAC is correct (client used correct derived key)
            val serverNonce = ByteArray(32) { 0xAA.toByte() }
            val expectedHmac = HmacUtils.computeHmac(authKey, serverNonce)
            assertTrue(
                "Client HMAC should match expected",
                clientHmac.contentEquals(expectedHmac)
            )

            // Complete handshake
            val serverHmac = HmacUtils.computeHmac(authKey, clientNonce)
            val verify = AuthEnvelope.newBuilder()
                .setVerify(AuthVerify.newBuilder().setHmac(ByteString.copyFrom(serverHmac)))
                .build()
            clientChannel.send(verify.toByteArray())

            val success = AuthEnvelope.newBuilder()
                .setSuccess(AuthSuccess.newBuilder().setDeviceId("verified-device"))
                .build()
            clientChannel.send(success.toByteArray())
        }

        // Send challenge
        val serverNonce = ByteArray(32) { 0xAA.toByte() }
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(AuthChallenge.newBuilder().setNonce(ByteString.copyFrom(serverNonce)))
            .build()
        clientChannel.send(challenge.toByteArray())

        pairingManager.startPairing(payload)

        advanceUntilIdle()
        authJob.join()

        // Verify HMAC was captured and validated
        assertNotNull("HMAC should be captured", capturedHmac.await())
    }

    @Test
    fun `E2E-PAIR-04 ntfy messages are encrypted with derived signaling key`() = runTest {
        // Direct fails to trigger ntfy
        coEvery {
            signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        coEvery { webRTCClient.createOffer() } returns testSdpOffer

        val pairingManager = createPairingManager(this)
        pairingManager.startPairing(createTestPayload())

        // Wait for ntfy publish
        waitForNtfySubscription()
        advanceUntilIdle()

        val publishedMessages = mockNtfyClient.getPublishedMessages()
        assertTrue("Should publish to ntfy", publishedMessages.isNotEmpty())

        // Decrypt the published message
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        val decrypted = crypto.decryptFromBase64(publishedMessages[0])
        val msg = NtfySignalMessage.parseFrom(decrypted)

        // Verify message contents
        assertEquals(NtfySignalMessage.MessageType.OFFER, msg.type)
        assertEquals(testSessionId, msg.sessionId)
        assertEquals(testSdpOffer, msg.sdp)
        assertEquals(testDeviceId, msg.deviceId)
    }

    // ============================================================================
    // E2E-PAIR-05: Concurrent Pairing Rejection
    // ============================================================================

    @Test
    fun `E2E-PAIR-05 starting new pairing replaces previous`() = runTest {
        // Slow signaling for first attempt
        var signalingCallCount = 0
        coEvery {
            signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            signalingCallCount++
            if (signalingCallCount == 1) {
                delay(10000)  // First attempt slow
            }
            SignalingResult.Success(testSdpAnswer)
        }

        coEvery { webRTCClient.createOffer() } returns testSdpOffer
        coEvery { webRTCClient.close() } just Runs

        val pairingManager = createPairingManager(this)

        // Start first pairing
        val payload1 = createTestPayload()
        pairingManager.startPairing(payload1)
        advanceTimeBy(100)

        // Start second pairing (should replace first)
        val payload2 = ParsedQrPayload(
            version = 1,
            ip = "192.168.1.200",  // Different IP
            port = 9090,
            masterSecret = masterSecret.copyOf(),
            sessionId = "second-session",
            ntfyTopic = "ras-different"
        )

        pairingManager.reset()
        pairingManager.startPairing(payload2)

        advanceUntilIdle()

        // First pairing's WebRTC should have been closed during reset
        verify { webRTCClient.close() }
    }

    // ============================================================================
    // E2E-PAIR-06: Auth Failure Handling
    // ============================================================================

    @Test
    fun `E2E-PAIR-06 wrong server HMAC fails authentication`() = runTest {
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        val pairingManager = createPairingManager(this)

        // Simulate auth with WRONG server HMAC
        val authJob = launch {
            // Wait for client response
            val responseBytes = serverChannel.receive()
            val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)

            // Send WRONG HMAC (all zeros)
            val wrongHmac = ByteArray(32)
            val verify = AuthEnvelope.newBuilder()
                .setVerify(AuthVerify.newBuilder().setHmac(ByteString.copyFrom(wrongHmac)))
                .build()
            clientChannel.send(verify.toByteArray())
        }

        // Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(AuthChallenge.newBuilder().setNonce(ByteString.copyFrom(ByteArray(32) { 0xBB.toByte() })))
            .build()
        clientChannel.send(challenge.toByteArray())

        pairingManager.startPairing(createTestPayload())

        advanceUntilIdle()
        authJob.join()

        // Should fail with auth error
        val finalState = pairingManager.state.value
        assertTrue("Expected Failed, got $finalState", finalState is PairingState.Failed)
        assertEquals(
            PairingState.FailureReason.AUTH_FAILED,
            (finalState as PairingState.Failed).reason
        )
    }

    @Test
    fun `E2E-PAIR-06 malformed auth challenge fails gracefully`() = runTest {
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)

        coEvery { webRTCClient.receive() } coAnswers { clientChannel.receive() }

        val pairingManager = createPairingManager(this)

        // Send garbage instead of proper challenge
        clientChannel.send(byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte()))

        pairingManager.startPairing(createTestPayload())

        advanceUntilIdle()

        val finalState = pairingManager.state.value
        assertTrue("Expected Failed, got $finalState", finalState is PairingState.Failed)
        assertEquals(
            PairingState.FailureReason.AUTH_FAILED,
            (finalState as PairingState.Failed).reason
        )
    }

    // ============================================================================
    // E2E-PAIR-07: Timeout Handling
    // ============================================================================

    @Test
    fun `E2E-PAIR-07 WebRTC data channel timeout fails connection`() = runTest {
        setupSuccessfulDirectSignaling()
        coEvery { webRTCClient.createOffer() } returns testSdpOffer
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns false  // Timeout
        coEvery { webRTCClient.close() } just Runs

        val pairingManager = createPairingManager(this)
        pairingManager.startPairing(createTestPayload())

        advanceUntilIdle()

        val finalState = pairingManager.state.value
        assertTrue("Expected Failed, got $finalState", finalState is PairingState.Failed)
        assertEquals(
            PairingState.FailureReason.CONNECTION_FAILED,
            (finalState as PairingState.Failed).reason
        )
    }

    @Test
    fun `E2E-PAIR-07 WebRTC offer creation failure handled`() = runTest {
        coEvery { webRTCClient.createOffer() } throws RuntimeException("ICE gathering failed")
        coEvery { webRTCClient.close() } just Runs

        val pairingManager = createPairingManager(this)
        pairingManager.startPairing(createTestPayload())

        advanceUntilIdle()

        val finalState = pairingManager.state.value
        assertTrue("Expected Failed, got $finalState", finalState is PairingState.Failed)
        assertEquals(
            PairingState.FailureReason.CONNECTION_FAILED,
            (finalState as PairingState.Failed).reason
        )
    }

    // ============================================================================
    // E2E-PAIR-08: Connection Handoff to ConnectionManager
    // ============================================================================

    @Test
    fun `E2E-PAIR-08 successful auth hands off connection with encryption key`() = runTest {
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        val capturedKey = slot<ByteArray>()
        every { connectionManager.connect(any(), capture(capturedKey)) } just Runs

        val pairingManager = createPairingManager(this)

        val authJob = simulateSuccessfulAuthHandshake(clientChannel, serverChannel)

        pairingManager.startPairing(createTestPayload())

        advanceUntilIdle()
        authJob.join()

        // Verify handoff occurred with correct client and key
        verify { connectionManager.connect(webRTCClient, any()) }
        assertTrue("Key should be captured", capturedKey.isCaptured)
        assertEquals("Key should be correct size", 32, capturedKey.captured.size)
    }

    @Test
    fun `E2E-PAIR-08 WebRTC client not closed after successful handoff`() = runTest {
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        var closeCallCount = 0
        every { webRTCClient.close() } answers { closeCallCount++ }

        val pairingManager = createPairingManager(this)

        val authJob = simulateSuccessfulAuthHandshake(clientChannel, serverChannel)

        pairingManager.startPairing(createTestPayload())

        advanceUntilIdle()
        authJob.join()

        // WebRTC should NOT be closed (it's now owned by ConnectionManager)
        assertEquals("WebRTC should not be closed on success", 0, closeCallCount)

        // But getWebRTCClient should return null (cleared reference)
        assertEquals(null, pairingManager.getWebRTCClient())
    }

    // ============================================================================
    // Additional E2E Scenarios
    // ============================================================================

    @Test
    fun `E2E state flow observable during entire pairing process`() = runTest {
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        val pairingManager = createPairingManager(this)
        val observedStates = mutableListOf<PairingState>()

        // Observe state changes
        val observerJob = launch {
            pairingManager.state.collect { state ->
                observedStates.add(state)
            }
        }

        // Need to yield to let observer start collecting
        advanceUntilIdle()

        val authJob = simulateSuccessfulAuthHandshake(clientChannel, serverChannel)

        pairingManager.startPairing(createTestPayload())

        advanceUntilIdle()
        authJob.join()

        observerJob.cancel()

        // Verify key state transitions that must occur
        // Note: StateFlow only emits distinct values and some intermediate states
        // may not be captured if transitions happen too quickly
        assertTrue(
            "Should contain QrParsed: ${observedStates.map { it::class.simpleName }}",
            observedStates.any { it is PairingState.QrParsed }
        )
        // At least one of the authentication-related states must be present
        assertTrue(
            "Should contain auth state (Authenticating or Authenticated): ${observedStates.map { it::class.simpleName }}",
            observedStates.any { it is PairingState.Authenticating || it is PairingState.Authenticated }
        )
        assertTrue(
            "Should end with Authenticated: ${observedStates.map { it::class.simpleName }}",
            observedStates.last() is PairingState.Authenticated
        )
        // Verify states are in correct order (QrParsed before Authenticated)
        val qrParsedIndex = observedStates.indexOfFirst { it is PairingState.QrParsed }
        val authenticatedIndex = observedStates.indexOfLast { it is PairingState.Authenticated }
        assertTrue(
            "QrParsed should come before Authenticated",
            qrParsedIndex < authenticatedIndex
        )
    }

    @Test
    fun `E2E signaling client receives correct parameters`() = runTest {
        val capturedIp = slot<String>()
        val capturedPort = slot<Int>()
        val capturedSessionId = slot<String>()
        val capturedAuthKey = slot<ByteArray>()
        val capturedSdp = slot<String>()
        val capturedDeviceId = slot<String>()
        val capturedDeviceName = slot<String>()

        coEvery {
            signalingClient.sendSignal(
                capture(capturedIp),
                capture(capturedPort),
                capture(capturedSessionId),
                capture(capturedAuthKey),
                capture(capturedSdp),
                capture(capturedDeviceId),
                capture(capturedDeviceName)
            )
        } returns SignalingResult.Success(testSdpAnswer)

        setupSuccessfulWebRTC()
        val (clientChannel, serverChannel) = setupAuthChannels()

        val pairingManager = createPairingManager(this)
        val payload = createTestPayload()

        val authJob = simulateSuccessfulAuthHandshake(clientChannel, serverChannel)

        pairingManager.startPairing(payload)

        advanceUntilIdle()
        authJob.join()

        // Verify all parameters passed correctly
        assertEquals("192.168.1.100", capturedIp.captured)
        assertEquals(8080, capturedPort.captured)
        assertEquals(testSessionId, capturedSessionId.captured)
        assertEquals(testSdpOffer, capturedSdp.captured)
        assertEquals(testDeviceId, capturedDeviceId.captured)
    }

    @Test
    fun `E2E handles auth timeout`() = runTest {
        setupSuccessfulDirectSignaling()
        setupSuccessfulWebRTC()

        // Auth channel that never responds after challenge
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)

        coEvery { webRTCClient.receive() } coAnswers { clientChannel.receive() }

        val pairingManager = createPairingManager(this)

        // Send challenge but never respond
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(AuthChallenge.newBuilder().setNonce(ByteString.copyFrom(ByteArray(32))))
            .build()
        clientChannel.send(challenge.toByteArray())

        pairingManager.startPairing(createTestPayload())

        // AuthClient has 10 second timeout
        advanceTimeBy(11000)
        advanceUntilIdle()

        val finalState = pairingManager.state.value
        assertTrue("Expected Failed/Timeout, got $finalState", finalState is PairingState.Failed)
        assertEquals(
            PairingState.FailureReason.TIMEOUT,
            (finalState as PairingState.Failed).reason
        )
    }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun setupSuccessfulDirectSignaling() {
        coEvery {
            signalingClient.sendSignal(any(), any(), any(), any(), any(), any(), any())
        } returns SignalingResult.Success(testSdpAnswer)
    }

    private fun setupSuccessfulWebRTC() {
        coEvery { webRTCClient.createOffer() } returns testSdpOffer
        coEvery { webRTCClient.setRemoteDescription(any()) } just Runs
        coEvery { webRTCClient.waitForDataChannel(any()) } returns true
    }

    private fun setupAuthChannels(): Pair<Channel<ByteArray>, Channel<ByteArray>> {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        coEvery { webRTCClient.send(any()) } coAnswers {
            serverChannel.send(firstArg())
        }
        coEvery { webRTCClient.receive() } coAnswers {
            clientChannel.receive()
        }

        return clientChannel to serverChannel
    }

    private fun kotlinx.coroutines.CoroutineScope.simulateSuccessfulAuthHandshake(
        clientChannel: Channel<ByteArray>,
        serverChannel: Channel<ByteArray>
    ) = launch {
        // Wait for client response
        val responseBytes = serverChannel.receive()
        val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)
        val clientNonce = responseEnvelope.response.nonce.toByteArray()

        // Send verify with correct HMAC
        val serverHmac = HmacUtils.computeHmac(authKey, clientNonce)
        val verify = AuthEnvelope.newBuilder()
            .setVerify(AuthVerify.newBuilder().setHmac(ByteString.copyFrom(serverHmac)))
            .build()
        clientChannel.send(verify.toByteArray())

        // Send success
        val success = AuthEnvelope.newBuilder()
            .setSuccess(AuthSuccess.newBuilder().setDeviceId("daemon-assigned-device-id"))
            .build()
        clientChannel.send(success.toByteArray())
    }.also {
        // Send initial challenge
        val serverNonce = ByteArray(32) { 0xAA.toByte() }
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(AuthChallenge.newBuilder().setNonce(ByteString.copyFrom(serverNonce)))
            .build()

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            clientChannel.send(challenge.toByteArray())
        }
    }

    private suspend fun waitForNtfySubscription(timeoutMs: Long = 5000) {
        val startTime = System.currentTimeMillis()
        while (!mockNtfyClient.isSubscribed && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(10)
        }
        if (!mockNtfyClient.isSubscribed) {
            throw AssertionError("ntfy subscription not active after ${timeoutMs}ms")
        }
    }

    private fun createEncryptedAnswer(
        sessionId: String = testSessionId,
        sdp: String = testSdpAnswer
    ): String {
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.ANSWER)
            .setSessionId(sessionId)
            .setSdp(sdp)
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(nonce))
            .build()

        return crypto.encryptToBase64(msg.toByteArray())
    }
}
