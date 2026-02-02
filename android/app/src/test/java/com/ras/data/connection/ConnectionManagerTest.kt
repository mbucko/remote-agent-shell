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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

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

    @BeforeEach
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

    @AfterEach
    fun tearDown() {
        connectionManager.disconnect()
    }

    // ============================================================================
    // ConnectionReady Tests
    // ============================================================================

    @Tag("unit")
    @Test
    fun `connect sends ConnectionReady synchronously before returning`() = runTest {
        // connect() is now a suspend function that sends ConnectionReady synchronously
        connectionManager.connect(webRTCClient, authKey)

        // ConnectionReady should be sent IMMEDIATELY - no waiting needed
        // Because connect() is a suspend function that sends before returning
        assertTrue(sentMessages.isNotEmpty(), "Should have sent ConnectionReady")

        // Decrypt and parse the message
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val command = RasCommand.parseFrom(decrypted)

        assertTrue(command.hasConnectionReady(), "Should be ConnectionReady")
    }

    // ============================================================================
    // RasCommand Wrapping Tests
    // ============================================================================

    @Tag("unit")
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
        assertTrue(sentMessages.isNotEmpty(), "Should send a message")

        // Decrypt and parse
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)

        assertTrue(rasCommand.hasSession(), "Should be wrapped in RasCommand")
        assertTrue(rasCommand.session.hasList(), "Should have list command")
    }

    @Tag("unit")
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
        assertTrue(sentMessages.isNotEmpty(), "Should send a message")

        // Decrypt and parse
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)

        assertTrue(rasCommand.hasTerminal(), "Should be wrapped in RasCommand")
        assertTrue(rasCommand.terminal.hasAttach(), "Should have attach command")
    }

    @Tag("unit")
    @Test
    fun `sendPing wraps in RasCommand`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // ConnectionReady is sent synchronously, so it's already in sentMessages
        sentMessages.clear()

        // Send ping
        connectionManager.sendPing()

        // Should send a message
        assertTrue(sentMessages.isNotEmpty(), "Should send a message")

        // Decrypt and parse
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)

        assertTrue(rasCommand.hasPing(), "Should be wrapped in RasCommand")
    }

    // ============================================================================
    // Keepalive Ping Tests
    // ============================================================================

    @Tag("unit")
    @Test
    fun `ping loop sends pings periodically to keep connection alive`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Create a connection manager with short ping interval for testing
        val fastConfig = ConnectionConfig(pingIntervalMs = 50L)
        val fastPingManager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = testDispatcher,
            config = fastConfig
        )

        fastPingManager.connect(webRTCClient, authKey)

        // ConnectionReady is sent synchronously
        sentMessages.clear()

        // Advance virtual time past the ping interval
        advanceTimeBy(100L)

        // Should have sent at least one ping
        assertTrue(sentMessages.isNotEmpty(), "Should send ping after interval")

        // Verify it's a ping
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages.last())
        val rasCommand = RasCommand.parseFrom(decrypted)

        assertTrue(rasCommand.hasPing(), "Should be a Ping command")

        fastPingManager.disconnect()
    }

    @Tag("unit")
    @Test
    fun `ping loop stops when disconnected`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Create a connection manager with short ping interval for testing
        val fastConfig = ConnectionConfig(pingIntervalMs = 50L)
        val fastPingManager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = testDispatcher,
            config = fastConfig
        )

        fastPingManager.connect(webRTCClient, authKey)
        sentMessages.clear()

        // Disconnect immediately
        fastPingManager.disconnect()

        // Advance virtual time past the ping interval
        advanceTimeBy(100L)

        // Should NOT have sent any pings after disconnect
        assertTrue(sentMessages.isEmpty(), "Should not send pings after disconnect")
    }

    @Tag("unit")
    @Test
    fun `ping loop disabled when pingIntervalMs is 0`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Create a connection manager with ping disabled
        val noPingConfig = ConnectionConfig(pingIntervalMs = 0L)
        val noPingManager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = testDispatcher,
            config = noPingConfig
        )

        noPingManager.connect(webRTCClient, authKey)
        sentMessages.clear()

        // Advance virtual time
        advanceTimeBy(100L)

        // Should NOT have sent any pings
        assertTrue(sentMessages.isEmpty(), "Should not send pings when disabled")

        noPingManager.disconnect()
    }

    // ============================================================================
    // Unpair Tests
    // ============================================================================

    @Tag("unit")
    @Test
    fun `sendUnpairRequest wraps message in RasCommand`() = runTest {
        connectionManager.connect(webRTCClient, authKey)
        sentMessages.clear()

        // Send unpair request
        val deviceId = "test-device-123"
        connectionManager.sendUnpairRequest(deviceId)

        // Verify message was sent
        assertTrue(sentMessages.isNotEmpty(), "Should have sent unpair request")

        // Decrypt and parse
        val codec = BytesCodec(authKey.copyOf())
        val decrypted = codec.decode(sentMessages.last())
        val rasCommand = RasCommand.parseFrom(decrypted)

        // Verify it's wrapped in RasCommand
        assertTrue(rasCommand.hasUnpairRequest(), "Should be wrapped in RasCommand")
        assertEquals(deviceId, rasCommand.unpairRequest.deviceId, "Device ID should match")
    }

}
