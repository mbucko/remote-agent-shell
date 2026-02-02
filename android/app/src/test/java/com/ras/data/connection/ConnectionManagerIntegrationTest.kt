package com.ras.data.connection

import com.ras.crypto.BytesCodec
import com.ras.crypto.CryptoException
import com.ras.data.webrtc.WebRTCClient
import com.ras.proto.ErrorResponse
import com.ras.proto.InitialState
import com.ras.proto.Pong
import com.ras.proto.RasCommand
import com.ras.proto.RasEvent
import com.ras.proto.Session
import com.ras.proto.SessionCreatedEvent
import com.ras.proto.SessionEvent
import com.ras.proto.SessionKilledEvent
import com.ras.proto.SessionListEvent
import com.ras.proto.TerminalAttached
import com.ras.proto.TerminalDetached
import com.ras.proto.TerminalEvent
import com.ras.proto.TerminalOutput
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Comprehensive integration tests for ConnectionManager.
 *
 * Test Vector Coverage:
 * - Transport-based connections (WebRTC, Tailscale)
 * - Event routing from daemon (all event types)
 * - Error handling and disconnection
 * - Health monitoring
 * - Encryption/decryption edge cases
 * - Concurrency scenarios
 * - Message size validation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerIntegrationTest {

    private lateinit var webRTCClientFactory: WebRTCClient.Factory
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var connectionManager: ConnectionManager
    private val authKey = ByteArray(32) { it.toByte() }

    private lateinit var sentMessages: MutableList<ByteArray>
    private lateinit var codec: BytesCodec

    // Channel for simulating incoming messages from daemon
    private lateinit var incomingMessages: Channel<ByteArray>

    @BeforeEach
    fun setup() {
        webRTCClientFactory = mockk()
        webRTCClient = mockk(relaxed = true)
        sentMessages = mutableListOf()
        incomingMessages = Channel(Channel.UNLIMITED)
        codec = BytesCodec(authKey.copyOf())

        every { webRTCClientFactory.create() } returns webRTCClient

        // Capture sent messages
        coEvery { webRTCClient.send(any()) } coAnswers {
            sentMessages.add(firstArg())
        }

        // Receive from channel to simulate daemon responses
        coEvery { webRTCClient.receive(any()) } coAnswers {
            val timeoutMs = firstArg<Long>()
            withTimeout(timeoutMs) {
                incomingMessages.receive()
            }
        }

        every { webRTCClient.isHealthy(any()) } returns true
        every { webRTCClient.getIdleTimeMs() } returns 0L
        every { webRTCClient.isReady() } returns true

        // Disable ping loop for faster tests
        val config = ConnectionConfig(pingIntervalMs = 0L)
        connectionManager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = Dispatchers.Unconfined,
            config = config
        )
    }

    @AfterEach
    fun tearDown() {
        connectionManager.disconnect()
        incomingMessages.close()
    }

    // ============================================================================
    // SECTION 1: Transport Connection Tests
    // ============================================================================

    @Tag("integration")
    @Test
    fun `connectWithTransport establishes connection and sends ConnectionReady`() = runTest {
        val mockTransport = createMockTransport(TransportType.WEBRTC)

        val transportSentMessages = mutableListOf<ByteArray>()
        coEvery { mockTransport.send(any()) } coAnswers {
            transportSentMessages.add(firstArg())
        }

        connectionManager.connectWithTransport(mockTransport, authKey)

        assertTrue(connectionManager.isConnected.value, "Should be connected")
        assertTrue(transportSentMessages.isNotEmpty(), "Should send ConnectionReady")

        val decrypted = codec.decode(transportSentMessages[0])
        val command = RasCommand.parseFrom(decrypted)
        assertTrue(command.hasConnectionReady(), "Should be ConnectionReady")
    }

    @Tag("integration")
    @Test
    fun `connectWithTransport works with WebRTCTransport`() = runTest {
        val transport = WebRTCTransport(webRTCClient)

        connectionManager.connectWithTransport(transport, authKey)

        assertTrue(connectionManager.isConnected.value, "Should be connected")
        assertTrue(sentMessages.isNotEmpty(), "Should send ConnectionReady")
    }

    @Tag("integration")
    @Test
    fun `connectWithTransport works with TailscaleTransport type`() = runTest {
        val mockTransport = createMockTransport(TransportType.TAILSCALE)

        connectionManager.connectWithTransport(mockTransport, authKey)

        assertTrue(connectionManager.isConnected.value, "Should be connected")
        assertTrue(connectionManager.isHealthy.value, "Should be healthy")
    }

    @Tag("integration")
    @Test
    fun `connect rejects invalid auth key size - too short`() = runTest {
        val shortKey = ByteArray(16) { it.toByte() }
        assertFailsWith<IllegalArgumentException> {
            connectionManager.connect(webRTCClient, shortKey)
        }
    }

    @Tag("integration")
    @Test
    fun `connect rejects invalid auth key size - too long`() = runTest {
        val longKey = ByteArray(64) { it.toByte() }
        assertFailsWith<IllegalArgumentException> {
            connectionManager.connect(webRTCClient, longKey)
        }
    }

    @Tag("integration")
    @Test
    fun `connectWithTransport rejects invalid auth key size`() = runTest {
        val mockTransport = createMockTransport(TransportType.WEBRTC)
        val shortKey = ByteArray(16)
        assertFailsWith<IllegalArgumentException> {
            connectionManager.connectWithTransport(mockTransport, shortKey)
        }
    }

    @Tag("integration")
    @Test
    fun `connect when already connected disconnects first`() = runTest {
        // First connection
        connectionManager.connect(webRTCClient, authKey)
        assertTrue(connectionManager.isConnected.value, "Should be connected")

        // Create new client for second connection
        val newClient = mockk<WebRTCClient>(relaxed = true)
        coEvery { newClient.send(any()) } coAnswers { sentMessages.add(firstArg()) }
        coEvery { newClient.receive(any()) } coAnswers {
            delay(30_000)
            throw IllegalStateException("timeout")
        }
        every { newClient.isHealthy(any()) } returns true
        every { newClient.getIdleTimeMs() } returns 0L

        sentMessages.clear()

        // Second connection should work (disconnects first internally)
        connectionManager.connect(newClient, authKey)

        assertTrue(connectionManager.isConnected.value, "Should still be connected")
        // Old client should be closed
        verify { webRTCClient.close() }
    }

    @Tag("integration")
    @Test
    fun `ConnectionReady timeout throws exception`() = runTest {
        val slowTransport = mockk<Transport>(relaxed = true)
        every { slowTransport.type } returns TransportType.WEBRTC
        every { slowTransport.isConnected } returns true

        // Make send hang to simulate timeout
        coEvery { slowTransport.send(any()) } coAnswers {
            delay(20_000) // Longer than default timeout
        }
        coEvery { slowTransport.receive(any()) } coAnswers {
            delay(30_000)
            throw IllegalStateException("timeout")
        }

        val config = ConnectionConfig(connectionReadyTimeoutMs = 100L, pingIntervalMs = 0L)
        val manager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = Dispatchers.Unconfined,
            config = config
        )

        try {
            manager.connectWithTransport(slowTransport, authKey)
            fail("Should throw exception on timeout")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("timeout") == true, "Should mention timeout")
        }

        assertFalse(manager.isConnected.value, "Should not be connected after timeout")
        manager.disconnect()
    }

    @Tag("integration")
    @Test
    fun `connect fails when send throws during ConnectionReady`() = runTest {
        val failingTransport = mockk<Transport>(relaxed = true)
        every { failingTransport.type } returns TransportType.WEBRTC
        every { failingTransport.isConnected } returns true
        coEvery { failingTransport.send(any()) } throws RuntimeException("Network error")
        coEvery { failingTransport.receive(any()) } coAnswers {
            delay(30_000)
            throw IllegalStateException("timeout")
        }

        try {
            connectionManager.connectWithTransport(failingTransport, authKey)
            fail("Should throw exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Failed") == true, "Should mention failed")
        }

        assertFalse(connectionManager.isConnected.value, "Should not be connected")
    }

    // ============================================================================
    // SECTION 2: Event Routing Tests - All Event Types
    // ============================================================================

    @Tag("integration")
    @Test
    fun `routes SessionEvent LIST from daemon`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val session = Session.newBuilder()
            .setId("session-1")
            .setDisplayName("Test Session")
            .build()

        val sessionList = SessionListEvent.newBuilder()
            .addSessions(session)
            .build()

        val sessionEvent = SessionEvent.newBuilder()
            .setList(sessionList)
            .build()

        val rasEvent = RasEvent.newBuilder()
            .setSession(sessionEvent)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        val receivedEvent = async {
            withTimeout(5000) {
                connectionManager.sessionEvents.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val event = receivedEvent.await()
        assertTrue(event.hasList(), "Should have list")
        assertEquals("session-1", event.list.sessionsList[0].id)
    }

    @Tag("integration")
    @Test
    fun `routes SessionEvent CREATED from daemon`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val createdEvent = SessionCreatedEvent.newBuilder()
            .setSession(Session.newBuilder().setId("new-session").build())
            .build()

        val sessionEvent = SessionEvent.newBuilder()
            .setCreated(createdEvent)
            .build()

        val rasEvent = RasEvent.newBuilder()
            .setSession(sessionEvent)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        val receivedEvent = async {
            withTimeout(5000) {
                connectionManager.sessionEvents.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val event = receivedEvent.await()
        assertTrue(event.hasCreated(), "Should have created")
    }

    @Tag("integration")
    @Test
    fun `routes SessionEvent KILLED from daemon`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val killedEvent = SessionKilledEvent.newBuilder()
            .setSessionId("killed-session")
            .build()

        val sessionEvent = SessionEvent.newBuilder()
            .setKilled(killedEvent)
            .build()

        val rasEvent = RasEvent.newBuilder()
            .setSession(sessionEvent)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        val receivedEvent = async {
            withTimeout(5000) {
                connectionManager.sessionEvents.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val event = receivedEvent.await()
        assertTrue(event.hasKilled(), "Should have killed")
        assertEquals("killed-session", event.killed.sessionId)
    }

    @Tag("integration")
    @Test
    fun `routes TerminalEvent OUTPUT from daemon`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val terminalEvent = TerminalEvent.newBuilder()
            .setOutput(
                TerminalOutput.newBuilder()
                    .setSessionId("session-1")
                    .setData(com.google.protobuf.ByteString.copyFromUtf8("Hello World"))
            )
            .build()

        val rasEvent = RasEvent.newBuilder()
            .setTerminal(terminalEvent)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        val receivedEvent = async {
            withTimeout(5000) {
                connectionManager.terminalEvents.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val event = receivedEvent.await()
        assertTrue(event.hasOutput(), "Should have output")
        assertEquals("session-1", event.output.sessionId)
    }

    @Tag("integration")
    @Test
    fun `routes TerminalEvent ATTACHED from daemon`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val terminalEvent = TerminalEvent.newBuilder()
            .setAttached(
                TerminalAttached.newBuilder()
                    .setSessionId("attached-session")
                    .setRows(24)
                    .setCols(80)
            )
            .build()

        val rasEvent = RasEvent.newBuilder()
            .setTerminal(terminalEvent)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        val receivedEvent = async {
            withTimeout(5000) {
                connectionManager.terminalEvents.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val event = receivedEvent.await()
        assertTrue(event.hasAttached(), "Should have attached")
        assertEquals("attached-session", event.attached.sessionId)
    }

    @Tag("integration")
    @Test
    fun `routes TerminalEvent DETACHED from daemon`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val terminalEvent = TerminalEvent.newBuilder()
            .setDetached(
                TerminalDetached.newBuilder()
                    .setSessionId("detached-session")
            )
            .build()

        val rasEvent = RasEvent.newBuilder()
            .setTerminal(terminalEvent)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        val receivedEvent = async {
            withTimeout(5000) {
                connectionManager.terminalEvents.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val event = receivedEvent.await()
        assertTrue(event.hasDetached(), "Should have detached")
    }

    @Tag("integration")
    @Test
    fun `routes InitialState from daemon to initialState flow`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val session = Session.newBuilder()
            .setId("existing-session")
            .setDisplayName("Existing Session")
            .build()

        val initialState = InitialState.newBuilder()
            .addSessions(session)
            .build()

        val rasEvent = RasEvent.newBuilder()
            .setInitialState(initialState)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        val receivedState = async {
            withTimeout(5000) {
                connectionManager.initialState.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val state = receivedState.await()
        assertEquals(1, state.sessionsCount)
        assertEquals("existing-session", state.sessionsList[0].id)
    }

    @Tag("integration")
    @Test
    fun `routes Pong event and calculates latency`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Send a ping first to set the timestamp
        sentMessages.clear()
        connectionManager.sendPing()

        // Parse the ping to get the timestamp
        val pingDecrypted = codec.decode(sentMessages[0])
        val pingCommand = RasCommand.parseFrom(pingDecrypted)
        val pingTimestamp = pingCommand.ping.timestamp

        // Send pong response with same timestamp
        val pongEvent = RasEvent.newBuilder()
            .setPong(Pong.newBuilder().setTimestamp(pingTimestamp))
            .build()

        val encrypted = codec.encode(pongEvent.toByteArray())

        delay(50)
        incomingMessages.send(encrypted)

        // Should process without error (latency is logged)
        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    @Tag("integration")
    @Test
    fun `routes Error event from daemon`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val errorEvent = RasEvent.newBuilder()
            .setError(
                ErrorResponse.newBuilder()
                    .setErrorCode("SESSION_NOT_FOUND")
                    .setMessage("Session does not exist")
            )
            .build()

        val encrypted = codec.encode(errorEvent.toByteArray())

        delay(50)
        incomingMessages.send(encrypted)

        // Should process without crashing
        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    @Tag("integration")
    @Test
    fun `empty SessionEvent wrapper is not emitted`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Create SessionEvent with no actual content
        val emptySessionEvent = SessionEvent.newBuilder().build()
        val rasEvent = RasEvent.newBuilder()
            .setSession(emptySessionEvent)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        // Collect with timeout - should NOT receive anything
        val receivedEvent = async {
            withTimeoutOrNull(200) {
                connectionManager.sessionEvents.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val result = receivedEvent.await()
        assertNull(result, "Should not emit empty SessionEvent")
    }

    @Tag("integration")
    @Test
    fun `empty TerminalEvent wrapper is not emitted`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Create TerminalEvent with no actual content
        val emptyTerminalEvent = TerminalEvent.newBuilder().build()
        val rasEvent = RasEvent.newBuilder()
            .setTerminal(emptyTerminalEvent)
            .build()

        val encrypted = codec.encode(rasEvent.toByteArray())

        val receivedEvent = async {
            withTimeoutOrNull(200) {
                connectionManager.terminalEvents.first()
            }
        }

        delay(50)
        incomingMessages.send(encrypted)

        val result = receivedEvent.await()
        assertNull(result, "Should not emit empty TerminalEvent")
    }

    @Tag("integration")
    @Test
    fun `EVENT_NOT_SET is handled gracefully`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Create RasEvent with no event set
        val emptyEvent = RasEvent.newBuilder().build()
        val encrypted = codec.encode(emptyEvent.toByteArray())

        delay(50)
        incomingMessages.send(encrypted)

        // Should not crash, should remain connected
        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    @Tag("integration")
    @Test
    fun `multiple events in rapid succession are all processed`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val events = mutableListOf<SessionEvent>()
        val collectJob = launch {
            connectionManager.sessionEvents.take(5).toList(events)
        }

        delay(50)

        // Send 5 events rapidly
        for (i in 1..5) {
            val session = Session.newBuilder()
                .setId("session-$i")
                .build()
            val sessionEvent = SessionEvent.newBuilder()
                .setList(SessionListEvent.newBuilder().addSessions(session))
                .build()
            val rasEvent = RasEvent.newBuilder()
                .setSession(sessionEvent)
                .build()
            incomingMessages.send(codec.encode(rasEvent.toByteArray()))
        }

        // Wait for collection
        withTimeout(5000) {
            collectJob.join()
        }

        assertEquals(5, events.size, "Should receive all 5 events")
    }

    // ============================================================================
    // SECTION 3: Encryption/Decryption Edge Cases
    // ============================================================================

    @Tag("integration")
    @Test
    fun `handles malformed encrypted data gracefully`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Send garbage data that will fail decryption
        val garbage = ByteArray(100) { (it % 256).toByte() }

        delay(50)
        incomingMessages.send(garbage)

        // Should still be connected (malformed messages are logged and ignored)
        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    @Tag("integration")
    @Test
    fun `handles tampered encrypted message gracefully`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Create valid encrypted message then tamper with it
        val rasEvent = RasEvent.newBuilder()
            .setInitialState(InitialState.newBuilder().build())
            .build()
        val encrypted = codec.encode(rasEvent.toByteArray())

        // Tamper with the encrypted data (flip some bits)
        encrypted[encrypted.size / 2] = (encrypted[encrypted.size / 2].toInt() xor 0xFF).toByte()

        delay(50)
        incomingMessages.send(encrypted)

        // Should still be connected (decryption failure is logged and ignored)
        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    @Tag("integration")
    @Test
    fun `handles wrong encryption key gracefully`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Encrypt with a different key
        val wrongKey = ByteArray(32) { (it + 100).toByte() }
        val wrongCodec = BytesCodec(wrongKey)

        val rasEvent = RasEvent.newBuilder()
            .setInitialState(InitialState.newBuilder().build())
            .build()
        val encryptedWithWrongKey = wrongCodec.encode(rasEvent.toByteArray())

        delay(50)
        incomingMessages.send(encryptedWithWrongKey)

        // Should still be connected
        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    @Tag("integration")
    @Test
    fun `handles valid encryption but invalid protobuf gracefully`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Encrypt garbage protobuf data
        val garbageProtobuf = ByteArray(50) { 0xFF.toByte() }
        val encrypted = codec.encode(garbageProtobuf)

        delay(50)
        incomingMessages.send(encrypted)

        // Should still be connected (parse failure is logged and ignored)
        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    @Tag("integration")
    @Test
    fun `handles empty encrypted message gracefully`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        val encrypted = codec.encode(ByteArray(0))

        delay(50)
        incomingMessages.send(encrypted)

        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    @Tag("integration")
    @Test
    fun `drops oversized incoming message`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        // Create message larger than 16MB
        val oversized = ByteArray(17 * 1024 * 1024)

        delay(50)
        incomingMessages.send(oversized)

        // Should still be connected (oversized is logged and dropped)
        delay(50)
        assertTrue(connectionManager.isConnected.value, "Should still be connected")
    }

    // ============================================================================
    // SECTION 4: Disconnect and Health Monitoring
    // ============================================================================

    @Tag("integration")
    @Test
    fun `disconnect updates connection state`() = runTest {
        connectionManager.connect(webRTCClient, authKey)
        assertTrue(connectionManager.isConnected.value, "Should be connected")

        connectionManager.disconnect()

        assertFalse(connectionManager.isConnected.value, "Should be disconnected")
        assertFalse(connectionManager.isHealthy.value, "Should not be healthy after disconnect")
    }

    @Tag("integration")
    @Test
    fun `disconnect called multiple times is safe`() = runTest {
        connectionManager.connect(webRTCClient, authKey)

        connectionManager.disconnect()
        connectionManager.disconnect()
        connectionManager.disconnect()

        assertFalse(connectionManager.isConnected.value, "Should be disconnected")
        // Should not throw
    }

    @Tag("integration")
    @Test
    fun `reconnecting after disconnect works`() = runTest {
        connectionManager.connect(webRTCClient, authKey)
        connectionManager.disconnect()

        sentMessages.clear()
        incomingMessages = Channel(Channel.UNLIMITED)

        connectionManager.connect(webRTCClient, authKey)

        assertTrue(connectionManager.isConnected.value, "Should be connected again")
        assertTrue(sentMessages.isNotEmpty(), "Should send ConnectionReady")
    }

    @Tag("integration")
    @Test
    fun `health status updates when connection becomes unhealthy`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val config = ConnectionConfig(
            pingIntervalMs = 0L,
            heartbeatCheckIntervalMs = 50L,
            maxIdleMs = 100L
        )
        val manager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = testDispatcher,
            config = config
        )

        manager.connect(webRTCClient, authKey)

        assertTrue(manager.isHealthy.value, "Should be healthy initially")

        // Simulate unhealthy connection
        every { webRTCClient.isHealthy(any()) } returns false

        // Advance virtual time past heartbeat check interval
        advanceTimeBy(150L)

        assertFalse(manager.isHealthy.value, "Should become unhealthy")

        manager.disconnect()
    }

    @Tag("integration")
    @Test
    fun `health recovers when connection becomes healthy again`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val config = ConnectionConfig(
            pingIntervalMs = 0L,
            heartbeatCheckIntervalMs = 50L,
            maxIdleMs = 100L
        )
        val manager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = testDispatcher,
            config = config
        )

        manager.connect(webRTCClient, authKey)

        // Make unhealthy
        every { webRTCClient.isHealthy(any()) } returns false
        advanceTimeBy(150L)
        assertFalse(manager.isHealthy.value, "Should be unhealthy")

        // Recover
        every { webRTCClient.isHealthy(any()) } returns true
        advanceTimeBy(150L)
        assertTrue(manager.isHealthy.value, "Should recover to healthy")

        manager.disconnect()
    }

    @Tag("integration")
    @Test
    fun `transport disconnects mid-session updates state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testConfig = ConnectionConfig(pingIntervalMs = 0L)
        val manager = ConnectionManager(
            webRtcClientFactory = webRTCClientFactory,
            ioDispatcher = testDispatcher,
            config = testConfig
        )

        val mockTransport = mockk<Transport>(relaxed = true)
        every { mockTransport.type } returns TransportType.WEBRTC
        every { mockTransport.isConnected } returns true

        var receiveCount = 0
        coEvery { mockTransport.receive(any()) } coAnswers {
            receiveCount++
            if (receiveCount > 2) {
                throw RuntimeException("Connection lost")
            }
            delay(100)
            throw IllegalStateException("timeout")
        }

        manager.connectWithTransport(mockTransport, authKey)
        assertTrue(manager.isConnected.value, "Should be connected")

        // Advance virtual time to trigger the event listener loop iterations
        advanceTimeBy(500L)

        assertFalse(manager.isConnected.value, "Should be disconnected after error")
        manager.disconnect()
    }

    // ============================================================================
    // SECTION 5: Command Sending Tests
    // ============================================================================

    @Tag("integration")
    @Test
    fun `send throws when not connected`() = runTest {
        // Don't connect
        assertFailsWith<IllegalStateException> {
            connectionManager.send(ByteArray(10))
        }
    }

    @Tag("integration")
    @Test
    fun `sendSessionCommand throws when not connected`() = runTest {
        val command = com.ras.proto.SessionCommand.newBuilder()
            .setList(com.ras.proto.ListSessionsCommand.getDefaultInstance())
            .build()
        assertFailsWith<IllegalStateException> {
            connectionManager.sendSessionCommand(command)
        }
    }

    @Tag("integration")
    @Test
    fun `sendTerminalCommand throws when not connected`() = runTest {
        val command = com.ras.proto.TerminalCommand.newBuilder()
            .setAttach(com.ras.proto.AttachTerminal.newBuilder().setSessionId("s1"))
            .build()
        assertFailsWith<IllegalStateException> {
            connectionManager.sendTerminalCommand(command)
        }
    }

    @Tag("integration")
    @Test
    fun `rejects oversized outgoing messages`() = runTest {
        connectionManager.connect(webRTCClient, authKey)
        val oversizedData = ByteArray(17 * 1024 * 1024)
        assertFailsWith<IllegalArgumentException> {
            connectionManager.send(oversizedData)
        }
    }

    @Tag("integration")
    @Test
    fun `sendSessionCommand wraps in RasCommand`() = runTest {
        connectionManager.connect(webRTCClient, authKey)
        sentMessages.clear()

        val command = com.ras.proto.SessionCommand.newBuilder()
            .setList(com.ras.proto.ListSessionsCommand.getDefaultInstance())
            .build()
        connectionManager.sendSessionCommand(command)

        assertTrue(sentMessages.isNotEmpty(), "Should send message")
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)
        assertTrue(rasCommand.hasSession(), "Should have session command")
    }

    @Tag("integration")
    @Test
    fun `sendTerminalCommand wraps in RasCommand`() = runTest {
        connectionManager.connect(webRTCClient, authKey)
        sentMessages.clear()

        val command = com.ras.proto.TerminalCommand.newBuilder()
            .setAttach(com.ras.proto.AttachTerminal.newBuilder().setSessionId("s1"))
            .build()
        connectionManager.sendTerminalCommand(command)

        assertTrue(sentMessages.isNotEmpty(), "Should send message")
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)
        assertTrue(rasCommand.hasTerminal(), "Should have terminal command")
    }

    @Tag("integration")
    @Test
    fun `sendPing wraps in RasCommand with timestamp`() = runTest {
        connectionManager.connect(webRTCClient, authKey)
        sentMessages.clear()

        val beforePing = System.currentTimeMillis()
        connectionManager.sendPing()
        val afterPing = System.currentTimeMillis()

        assertTrue(sentMessages.isNotEmpty(), "Should send message")
        val decrypted = codec.decode(sentMessages[0])
        val rasCommand = RasCommand.parseFrom(decrypted)
        assertTrue(rasCommand.hasPing(), "Should have ping")
        assertTrue(
            rasCommand.ping.timestamp in beforePing..afterPing,
            "Timestamp should be in range")
    }

    // ============================================================================
    // SECTION 6: Fallback and Recovery Tests
    // ============================================================================

    @Tag("integration")
    @Test
    fun `connectWithTransport after failed WebRTC works`() = runTest {
        val failingClient = mockk<WebRTCClient>(relaxed = true)
        coEvery { failingClient.send(any()) } throws Exception("Connection failed")

        try {
            connectionManager.connect(failingClient, authKey)
        } catch (e: Exception) {
            // Expected
        }

        val workingTransport = createMockTransport(TransportType.TAILSCALE)
        connectionManager.connectWithTransport(workingTransport, authKey)

        assertTrue(connectionManager.isConnected.value, "Should be connected via fallback")
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    private fun createMockTransport(type: TransportType): Transport {
        val mockTransport = mockk<Transport>(relaxed = true)
        every { mockTransport.type } returns type
        every { mockTransport.isConnected } returns true

        coEvery { mockTransport.receive(any()) } coAnswers {
            delay(30_000)
            throw IllegalStateException("timeout")
        }

        return mockTransport
    }
}
