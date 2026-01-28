package com.ras.data.connection

import com.ras.crypto.BytesCodec
import com.ras.data.webrtc.WebRTCClient
import com.ras.proto.Agent
import com.ras.proto.AttachTerminal
import com.ras.proto.ConnectionReady
import com.ras.proto.InitialState
import com.ras.proto.RasCommand
import com.ras.proto.RasEvent
import com.ras.proto.Session
import com.ras.proto.SessionCommand
import com.ras.proto.SessionEvent
import com.ras.proto.SessionListEvent
import com.ras.proto.TerminalCommand
import com.ras.proto.TerminalEvent
import com.ras.proto.TerminalOutput
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for ConnectionManager's RasEvent/RasCommand protocol handling.
 *
 * These tests verify:
 * 1. ConnectionReady is sent after connect() completes
 * 2. Commands are wrapped in RasCommand before sending
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {

    private lateinit var webRTCClientFactory: WebRTCClient.Factory
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var connectionManager: ConnectionManager
    private val authKey = ByteArray(32) { it.toByte() }

    private lateinit var sentMessages: MutableList<ByteArray>

    @Before
    fun setup() {
        webRTCClientFactory = mockk()
        webRTCClient = mockk(relaxed = true)
        sentMessages = mutableListOf()

        every { webRTCClientFactory.create() } returns webRTCClient

        // Capture sent messages
        coEvery { webRTCClient.send(any()) } coAnswers {
            sentMessages.add(firstArg())
        }

        // Make receive throw after a delay (simulates no messages from daemon)
        coEvery { webRTCClient.receive(any()) } coAnswers {
            delay(30_000) // Long delay, will be cancelled on teardown
            throw IllegalStateException("timeout")
        }

        every { webRTCClient.isHealthy(any()) } returns true
        every { webRTCClient.getIdleTimeMs() } returns 0L

        connectionManager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        connectionManager.disconnect()
    }

    // ============================================================================
    // ConnectionReady Tests
    // ============================================================================

    @Test
    fun `connect sends ConnectionReady after setup`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Wait for the send coroutine to complete
        withTimeout(1000) {
            while (sentMessages.isEmpty()) {
                delay(10)
            }
        }

        // Should have sent ConnectionReady
        assertTrue("Should send at least one message", sentMessages.isNotEmpty())

        // Decrypt and parse the message
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val command = RasCommand.parseFrom(decrypted)

        assertTrue("Should be ConnectionReady", command.hasConnectionReady())
    }

    // ============================================================================
    // RasCommand Wrapping Tests
    // ============================================================================

    @Test
    fun `sendSessionCommand wraps in RasCommand`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Wait for ConnectionReady to be sent
        withTimeout(1000) {
            while (sentMessages.isEmpty()) {
                delay(10)
            }
        }
        sentMessages.clear()

        // Send a session command
        val sessionCommand = SessionCommand.newBuilder()
            .setList(com.ras.proto.ListSessionsCommand.getDefaultInstance())
            .build()
        connectionManager.sendSessionCommand(sessionCommand)

        // Should send a message
        assertTrue("Should send a message", sentMessages.isNotEmpty())

        // Decrypt and parse
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)

        assertTrue("Should be wrapped in RasCommand", rasCommand.hasSession())
        assertTrue("Should have list command", rasCommand.session.hasList())
    }

    @Test
    fun `sendTerminalCommand wraps in RasCommand`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Wait for ConnectionReady to be sent
        withTimeout(1000) {
            while (sentMessages.isEmpty()) {
                delay(10)
            }
        }
        sentMessages.clear()

        // Send a terminal command
        val terminalCommand = TerminalCommand.newBuilder()
            .setAttach(AttachTerminal.newBuilder().setSessionId("session-1"))
            .build()
        connectionManager.sendTerminalCommand(terminalCommand)

        // Should send a message
        assertTrue("Should send a message", sentMessages.isNotEmpty())

        // Decrypt and parse
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)

        assertTrue("Should be wrapped in RasCommand", rasCommand.hasTerminal())
        assertTrue("Should have attach command", rasCommand.terminal.hasAttach())
    }

    @Test
    fun `sendPing wraps in RasCommand`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Wait for ConnectionReady to be sent
        withTimeout(1000) {
            while (sentMessages.isEmpty()) {
                delay(10)
            }
        }
        sentMessages.clear()

        // Send ping
        connectionManager.sendPing()

        // Should send a message
        assertTrue("Should send a message", sentMessages.isNotEmpty())

        // Decrypt and parse
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)

        assertTrue("Should be wrapped in RasCommand", rasCommand.hasPing())
    }
}
