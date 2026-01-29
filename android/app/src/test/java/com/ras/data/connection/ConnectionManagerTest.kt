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
    fun `connect sends ConnectionReady synchronously before returning`() = runTest {
        // connect() is now a suspend function that sends ConnectionReady synchronously
        connectionManager.connect(webRTCClient, authKey)

        // ConnectionReady should be sent IMMEDIATELY - no waiting needed
        // Because connect() is a suspend function that sends before returning
        assertTrue("Should have sent ConnectionReady", sentMessages.isNotEmpty())

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
        // ConnectionReady is sent synchronously, so it's already in sentMessages
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

        // ConnectionReady is sent synchronously, so it's already in sentMessages
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

        // ConnectionReady is sent synchronously, so it's already in sentMessages
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

    // ============================================================================
    // Keepalive Ping Tests
    // ============================================================================

    @Test
    fun `ping loop sends pings periodically to keep connection alive`() = runTest {
        // Create a connection manager with short ping interval for testing
        val fastConfig = ConnectionConfig(pingIntervalMs = 50L)
        val fastPingManager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = Dispatchers.Unconfined,
            config = fastConfig
        )

        fastPingManager.connect(webRTCClient, authKey)

        // ConnectionReady is sent synchronously
        sentMessages.clear()

        // Wait using real time (ConnectionManager uses its own scope with real delays)
        Thread.sleep(100L)

        // Should have sent at least one ping
        assertTrue("Should send ping after interval", sentMessages.isNotEmpty())

        // Verify it's a ping
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages.last())
        val rasCommand = RasCommand.parseFrom(decrypted)

        assertTrue("Should be a Ping command", rasCommand.hasPing())

        fastPingManager.disconnect()
    }

    @Test
    fun `ping loop stops when disconnected`() = runTest {
        // Create a connection manager with short ping interval for testing
        val fastConfig = ConnectionConfig(pingIntervalMs = 50L)
        val fastPingManager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = Dispatchers.Unconfined,
            config = fastConfig
        )

        fastPingManager.connect(webRTCClient, authKey)
        sentMessages.clear()

        // Disconnect immediately
        fastPingManager.disconnect()

        // Wait past the ping interval using real time
        Thread.sleep(100L)

        // Should NOT have sent any pings after disconnect
        assertTrue("Should not send pings after disconnect", sentMessages.isEmpty())
    }

    @Test
    fun `ping loop disabled when pingIntervalMs is 0`() = runTest {
        // Create a connection manager with ping disabled
        val noPingConfig = ConnectionConfig(pingIntervalMs = 0L)
        val noPingManager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = Dispatchers.Unconfined,
            config = noPingConfig
        )

        noPingManager.connect(webRTCClient, authKey)
        sentMessages.clear()

        // Wait a bit using real time
        Thread.sleep(100L)

        // Should NOT have sent any pings
        assertTrue("Should not send pings when disabled", sentMessages.isEmpty())

        noPingManager.disconnect()
    }
}
