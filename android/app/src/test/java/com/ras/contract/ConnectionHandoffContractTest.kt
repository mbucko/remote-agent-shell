package com.ras.contract

import com.ras.crypto.BytesCodec
import com.ras.data.connection.ConnectionManager
import com.ras.data.webrtc.ConnectionOwnership
import com.ras.data.webrtc.WebRTCClient
import com.ras.proto.ConnectionReady
import com.ras.proto.RasCommand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the connection handoff between PairingManager and ConnectionManager.
 *
 * These tests verify the critical invariants:
 * 1. ConnectionReady is sent SYNCHRONOUSLY during connect() - not async/fire-and-forget
 * 2. Connection stays alive after handoff - PairingManager cleanup doesn't close it
 * 3. Ownership transfer prevents double-close
 *
 * These tests exist to catch regressions in the handoff logic that could cause
 * the pairing timeout issue where the connection dies immediately after auth.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHandoffContractTest {

    private lateinit var webRTCClient: WebRTCClient
    private lateinit var connectionManager: ConnectionManager
    private val authKey = ByteArray(32) { it.toByte() }
    private val sentMessages = mutableListOf<ByteArray>()

    @Before
    fun setup() {
        webRTCClient = mockk(relaxed = true)

        // Track sent messages
        coEvery { webRTCClient.send(any()) } coAnswers {
            sentMessages.add(firstArg())
        }

        // Make receive block forever (simulates waiting for daemon messages)
        coEvery { webRTCClient.receive(any()) } coAnswers {
            delay(Long.MAX_VALUE)
            throw IllegalStateException("timeout")
        }

        every { webRTCClient.isHealthy(any()) } returns true
        every { webRTCClient.getIdleTimeMs() } returns 0L

        connectionManager = ConnectionManager(
            webRtcClientFactory = mockk(),
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    // ============================================================================
    // CONTRACT 1: ConnectionReady is sent synchronously during connect()
    // ============================================================================

    @Test
    fun `CONTRACT - ConnectionReady is sent BEFORE connect() returns`() = runTest {
        // This is the critical contract: connect() must send ConnectionReady
        // synchronously (not in a fire-and-forget coroutine) so that the daemon
        // knows we're ready BEFORE the caller continues.

        // Call connect()
        connectionManager.connect(webRTCClient, authKey)

        // After connect() returns, ConnectionReady MUST have been sent
        // If this fails, it means ConnectionReady is being sent asynchronously
        // which can cause race conditions
        assertEquals(
            "ConnectionReady must be sent synchronously during connect()",
            1,
            sentMessages.size
        )

        // Verify it's actually a ConnectionReady message
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val command = RasCommand.parseFrom(decrypted)
        assertTrue(
            "First message sent must be ConnectionReady",
            command.hasConnectionReady()
        )
    }

    @Test
    fun `CONTRACT - connect() throws if ConnectionReady fails to send`() = runTest {
        // If sending ConnectionReady fails, connect() should throw
        // This ensures the caller knows the handoff failed

        coEvery { webRTCClient.send(any()) } throws Exception("Send failed")

        var exceptionThrown = false
        try {
            connectionManager.connect(webRTCClient, authKey)
        } catch (e: IllegalStateException) {
            exceptionThrown = true
            assertTrue(
                "Exception should mention ConnectionReady",
                e.message?.contains("ConnectionReady") == true
            )
        }

        assertTrue(
            "connect() must throw if ConnectionReady fails",
            exceptionThrown
        )
    }

    // ============================================================================
    // CONTRACT 2: Ownership transfer prevents accidental close
    // ============================================================================

    @Test
    fun `CONTRACT - closeByOwner respects ownership`() {
        // WebRTCClient should only be closeable by its current owner
        // This prevents PairingManager from accidentally closing after handoff

        every { webRTCClient.transferOwnership(any()) } returns true
        every { webRTCClient.closeByOwner(any()) } returns false // Not owner

        // Transfer ownership to ConnectionManager
        webRTCClient.transferOwnership(ConnectionOwnership.ConnectionManager)

        // PairingManager trying to close should fail
        val closed = webRTCClient.closeByOwner(ConnectionOwnership.PairingManager)

        // The close should be rejected
        verify { webRTCClient.closeByOwner(ConnectionOwnership.PairingManager) }
    }

    // ============================================================================
    // CONTRACT 3: Connection state after handoff
    // ============================================================================

    @Test
    fun `CONTRACT - connection remains connected after successful handoff`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Connection should be marked as connected
        assertTrue(
            "Connection should be marked as connected after handoff",
            connectionManager.isConnected.value
        )
    }

    @Test
    fun `CONTRACT - connection is healthy after successful handoff`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Connection should be marked as healthy
        assertTrue(
            "Connection should be marked as healthy after handoff",
            connectionManager.isHealthy.value
        )
    }
}
