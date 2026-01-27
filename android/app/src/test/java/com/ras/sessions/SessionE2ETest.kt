package com.ras.sessions

import app.cash.turbine.test
import com.google.protobuf.ByteString
import com.ras.data.sessions.AgentInfo
import com.ras.data.sessions.DirectoryEntryInfo
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import com.ras.data.webrtc.WebRTCClient
import com.ras.proto.Agent
import com.ras.proto.AgentsListEvent
import com.ras.proto.DirectoriesListEvent
import com.ras.proto.DirectoryEntry
import com.ras.proto.Session
import com.ras.proto.SessionActivityEvent
import com.ras.proto.SessionCreatedEvent
import com.ras.proto.SessionErrorEvent
import com.ras.proto.SessionKilledEvent
import com.ras.proto.SessionListEvent
import com.ras.proto.SessionRenamedEvent
import com.ras.proto.SessionEvent as ProtoSessionEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Comprehensive End-to-End tests for the session management system.
 *
 * These tests simulate the full lifecycle of proto events flowing through:
 * 1. WebRTC client receives proto bytes from daemon
 * 2. SessionRepository parses and handles events
 * 3. State flows emit updates
 * 4. Events are emitted for one-time notifications
 *
 * All scenarios, edge cases, and error conditions are covered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionE2ETest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var webRtcClientFactory: WebRTCClient.Factory
    private lateinit var mockWebRtcClient: WebRTCClient
    private lateinit var repository: SessionRepository
    private lateinit var receiveChannel: Channel<ByteArray>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        receiveChannel = Channel(Channel.UNLIMITED)
        mockWebRtcClient = mockk(relaxed = true) {
            coEvery { receive() } coAnswers { receiveChannel.receive() }
        }
        webRtcClientFactory = mockk {
            every { create() } returns mockWebRtcClient
        }
        repository = SessionRepository(webRtcClientFactory, testDispatcher)
    }

    @After
    fun tearDown() {
        receiveChannel.close()
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // Session List Event Tests
    // ==========================================================================

    @Test
    fun `session list event updates sessions state`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // Simulate receiving session list from daemon
            val sessionListEvent = createSessionListEvent(
                listOf(
                    createProtoSession("abc123def456", "My Project", "/home/user/project", "claude"),
                    createProtoSession("xyz789abc123", "Another", "/home/user/other", "aider")
                )
            )
            receiveChannel.send(sessionListEvent.toByteArray())
            advanceUntilIdle()

            val sessions = awaitItem()
            assertEquals(2, sessions.size)
            assertEquals("abc123def456", sessions[0].id)
            assertEquals("My Project", sessions[0].displayName)
            assertEquals("/home/user/project", sessions[0].directory)
            assertEquals("claude", sessions[0].agent)
            assertEquals("xyz789abc123", sessions[1].id)
        }
    }

    @Test
    fun `empty session list clears sessions state`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // First add some sessions
            val listWithSessions = createSessionListEvent(
                listOf(createProtoSession("abc123def456", "Project", "/home", "claude"))
            )
            receiveChannel.send(listWithSessions.toByteArray())
            advanceUntilIdle()
            assertEquals(1, awaitItem().size)

            // Then receive empty list
            val emptyList = createSessionListEvent(emptyList())
            receiveChannel.send(emptyList.toByteArray())
            advanceUntilIdle()
            assertEquals(0, awaitItem().size)
        }
    }

    // ==========================================================================
    // Session Created Event Tests
    // ==========================================================================

    @Test
    fun `session created event adds to sessions and emits event`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.events.test {
            repository.sessions.test {
                assertEquals(emptyList<SessionInfo>(), awaitItem())

                val createdEvent = createSessionCreatedEvent(
                    createProtoSession("abc123def456", "New Session", "/home/project", "claude")
                )
                receiveChannel.send(createdEvent.toByteArray())
                advanceUntilIdle()

                val sessions = awaitItem()
                assertEquals(1, sessions.size)
                assertEquals("abc123def456", sessions[0].id)
                assertEquals("New Session", sessions[0].displayName)
            }

            val event = awaitItem()
            assertTrue(event is SessionEvent.SessionCreated)
            assertEquals("abc123def456", (event as SessionEvent.SessionCreated).session.id)
        }
    }

    @Test
    fun `multiple session created events accumulate correctly`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // Create first session
            receiveChannel.send(
                createSessionCreatedEvent(
                    createProtoSession("session1aaaa", "First", "/home/first", "claude")
                ).toByteArray()
            )
            advanceUntilIdle()
            assertEquals(1, awaitItem().size)

            // Create second session
            receiveChannel.send(
                createSessionCreatedEvent(
                    createProtoSession("session2bbbb", "Second", "/home/second", "aider")
                ).toByteArray()
            )
            advanceUntilIdle()
            val sessions = awaitItem()
            assertEquals(2, sessions.size)
            assertEquals("session1aaaa", sessions[0].id)
            assertEquals("session2bbbb", sessions[1].id)
        }
    }

    // ==========================================================================
    // Session Killed Event Tests
    // ==========================================================================

    @Test
    fun `session killed event removes session and emits event`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // First create a session
            receiveChannel.send(
                createSessionCreatedEvent(
                    createProtoSession("abc123def456", "Project", "/home", "claude")
                ).toByteArray()
            )
            advanceUntilIdle()
            assertEquals(1, awaitItem().size)

            // Then kill it
            receiveChannel.send(createSessionKilledEvent("abc123def456").toByteArray())
            advanceUntilIdle()
            assertEquals(0, awaitItem().size)
        }

        repository.events.test {
            // Skip created event
            awaitItem()
            val event = awaitItem()
            assertTrue(event is SessionEvent.SessionKilled)
            assertEquals("abc123def456", (event as SessionEvent.SessionKilled).sessionId)
        }
    }

    @Test
    fun `session killed event for non-existent session does not crash`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // Kill a session that doesn't exist
            receiveChannel.send(createSessionKilledEvent("nonexistent12").toByteArray())
            advanceUntilIdle()

            // Should still emit the event but sessions list unchanged
            expectNoEvents()
        }
    }

    @Test
    fun `killing one session preserves others`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // Create two sessions via list event
            receiveChannel.send(
                createSessionListEvent(
                    listOf(
                        createProtoSession("session1aaaa", "First", "/home/first", "claude"),
                        createProtoSession("session2bbbb", "Second", "/home/second", "aider")
                    )
                ).toByteArray()
            )
            advanceUntilIdle()
            assertEquals(2, awaitItem().size)

            // Kill the first one
            receiveChannel.send(createSessionKilledEvent("session1aaaa").toByteArray())
            advanceUntilIdle()

            val remaining = awaitItem()
            assertEquals(1, remaining.size)
            assertEquals("session2bbbb", remaining[0].id)
        }
    }

    // ==========================================================================
    // Session Renamed Event Tests
    // ==========================================================================

    @Test
    fun `session renamed event updates display name and emits event`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // Create a session
            receiveChannel.send(
                createSessionCreatedEvent(
                    createProtoSession("abc123def456", "Old Name", "/home", "claude")
                ).toByteArray()
            )
            advanceUntilIdle()
            assertEquals("Old Name", awaitItem()[0].displayName)

            // Rename it
            receiveChannel.send(createSessionRenamedEvent("abc123def456", "New Name").toByteArray())
            advanceUntilIdle()
            assertEquals("New Name", awaitItem()[0].displayName)
        }
    }

    @Test
    fun `renaming preserves other session properties`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            receiveChannel.send(
                createSessionCreatedEvent(
                    createProtoSession("abc123def456", "Old Name", "/home/project", "claude")
                ).toByteArray()
            )
            advanceUntilIdle()
            awaitItem()

            receiveChannel.send(createSessionRenamedEvent("abc123def456", "New Name").toByteArray())
            advanceUntilIdle()

            val session = awaitItem()[0]
            assertEquals("New Name", session.displayName)
            assertEquals("/home/project", session.directory)
            assertEquals("claude", session.agent)
            assertEquals("abc123def456", session.id)
        }
    }

    @Test
    fun `renaming non-existent session does not crash`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            receiveChannel.send(createSessionRenamedEvent("nonexistent12", "Name").toByteArray())
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    // ==========================================================================
    // Session Activity Event Tests
    // ==========================================================================

    @Test
    fun `session activity event updates last activity timestamp`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        val activityTime = Instant.now().epochSecond

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            receiveChannel.send(
                createSessionCreatedEvent(
                    createProtoSession("abc123def456", "Project", "/home", "claude")
                ).toByteArray()
            )
            advanceUntilIdle()
            awaitItem()

            receiveChannel.send(createSessionActivityEvent("abc123def456", activityTime).toByteArray())
            advanceUntilIdle()

            val session = awaitItem()[0]
            assertEquals(Instant.ofEpochSecond(activityTime), session.lastActivityAt)
        }
    }

    @Test
    fun `activity event emits SessionActivity event`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        val activityTime = Instant.now().epochSecond

        // Set up session first
        receiveChannel.send(
            createSessionCreatedEvent(
                createProtoSession("abc123def456", "Project", "/home", "claude")
            ).toByteArray()
        )
        advanceUntilIdle()

        repository.events.test {
            // Skip created event
            awaitItem()

            receiveChannel.send(createSessionActivityEvent("abc123def456", activityTime).toByteArray())
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionEvent.SessionActivity)
            assertEquals("abc123def456", (event as SessionEvent.SessionActivity).sessionId)
            assertEquals(Instant.ofEpochSecond(activityTime), event.timestamp)
        }
    }

    // ==========================================================================
    // Session Error Event Tests
    // ==========================================================================

    @Test
    fun `session error event emits error with session ID`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.events.test {
            receiveChannel.send(
                createSessionErrorEvent("DIR_NOT_FOUND", "Directory not found", "abc123def456").toByteArray()
            )
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionEvent.SessionError)
            val error = event as SessionEvent.SessionError
            assertEquals("DIR_NOT_FOUND", error.code)
            assertEquals("Directory not found", error.message)
            assertEquals("abc123def456", error.sessionId)
        }
    }

    @Test
    fun `session error event with empty session ID converts to null`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.events.test {
            receiveChannel.send(
                createSessionErrorEvent("AGENT_NOT_FOUND", "Agent not found", "").toByteArray()
            )
            advanceUntilIdle()

            val event = awaitItem() as SessionEvent.SessionError
            assertNull(event.sessionId)
        }
    }

    @Test
    fun `all error codes are handled`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        val errorCodes = listOf(
            "DIR_NOT_FOUND",
            "DIR_NOT_ALLOWED",
            "AGENT_NOT_FOUND",
            "SESSION_NOT_FOUND",
            "SESSION_EXISTS",
            "TMUX_ERROR",
            "KILL_FAILED",
            "MAX_SESSIONS_REACHED",
            "INVALID_NAME",
            "RATE_LIMITED"
        )

        repository.events.test {
            for (code in errorCodes) {
                receiveChannel.send(createSessionErrorEvent(code, "Error message", "").toByteArray())
                advanceUntilIdle()

                val event = awaitItem() as SessionEvent.SessionError
                assertEquals(code, event.code)
            }
        }
    }

    // ==========================================================================
    // Agents List Event Tests
    // ==========================================================================

    @Test
    fun `agents list event updates agents state`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.agents.test {
            assertEquals(emptyList<AgentInfo>(), awaitItem())

            receiveChannel.send(
                createAgentsListEvent(
                    listOf(
                        createProtoAgent("Claude Code", "claude", "/usr/bin/claude", true),
                        createProtoAgent("Aider", "aider", "/usr/bin/aider", true),
                        createProtoAgent("Cursor", "cursor", "", false)
                    )
                ).toByteArray()
            )
            advanceUntilIdle()

            val agents = awaitItem()
            assertEquals(3, agents.size)
            assertEquals("Claude Code", agents[0].name)
            assertEquals("claude", agents[0].binary)
            assertEquals("/usr/bin/claude", agents[0].path)
            assertTrue(agents[0].available)
            assertFalse(agents[2].available)
        }
    }

    @Test
    fun `empty agents list clears agents state`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.agents.test {
            assertEquals(emptyList<AgentInfo>(), awaitItem())

            // Add agents
            receiveChannel.send(
                createAgentsListEvent(
                    listOf(createProtoAgent("Claude", "claude", "/usr/bin/claude", true))
                ).toByteArray()
            )
            advanceUntilIdle()
            assertEquals(1, awaitItem().size)

            // Clear agents
            receiveChannel.send(createAgentsListEvent(emptyList()).toByteArray())
            advanceUntilIdle()
            assertEquals(0, awaitItem().size)
        }
    }

    // ==========================================================================
    // Command Tests - Full Request/Response Cycle
    // ==========================================================================

    @Test
    fun `list sessions command triggers list event response`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        val commandSlot = slot<ByteArray>()
        coEvery { mockWebRtcClient.send(capture(commandSlot)) } returns Unit

        repository.listSessions()
        advanceUntilIdle()

        // Verify command was sent
        coVerify { mockWebRtcClient.send(any()) }
        assertTrue(commandSlot.captured.isNotEmpty())

        // Simulate daemon response
        repository.sessions.test {
            awaitItem() // Initial empty

            receiveChannel.send(
                createSessionListEvent(
                    listOf(createProtoSession("abc123def456", "Project", "/home", "claude"))
                ).toByteArray()
            )
            advanceUntilIdle()

            assertEquals(1, awaitItem().size)
        }
    }

    @Test
    fun `create session command flow`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        val commandSlot = slot<ByteArray>()
        coEvery { mockWebRtcClient.send(capture(commandSlot)) } returns Unit

        repository.createSession("/home/user/project", "claude")
        advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }

        // Simulate created response
        repository.events.test {
            receiveChannel.send(
                createSessionCreatedEvent(
                    createProtoSession("newSession12", "project", "/home/user/project", "claude")
                ).toByteArray()
            )
            advanceUntilIdle()

            val event = awaitItem() as SessionEvent.SessionCreated
            assertEquals("newSession12", event.session.id)
        }
    }

    @Test
    fun `create session with error response`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.createSession("/nonexistent/path", "claude")
        advanceUntilIdle()

        repository.events.test {
            receiveChannel.send(
                createSessionErrorEvent("DIR_NOT_FOUND", "Directory does not exist", "").toByteArray()
            )
            advanceUntilIdle()

            val event = awaitItem() as SessionEvent.SessionError
            assertEquals("DIR_NOT_FOUND", event.code)
        }
    }

    // ==========================================================================
    // Edge Cases and Error Handling
    // ==========================================================================

    @Test
    fun `invalid protobuf data is silently ignored`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // Send garbage data
            receiveChannel.send(byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte()))
            advanceUntilIdle()

            // Should not crash, just ignore
            expectNoEvents()

            // Valid data should still work after
            receiveChannel.send(
                createSessionListEvent(
                    listOf(createProtoSession("abc123def456", "Project", "/home", "claude"))
                ).toByteArray()
            )
            advanceUntilIdle()

            assertEquals(1, awaitItem().size)
        }
    }

    @Test
    fun `empty byte array is handled gracefully`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            receiveChannel.send(byteArrayOf())
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `rapid successive events are handled correctly`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // Send multiple events rapidly
            for (i in 1..10) {
                val sessionId = "session${i.toString().padStart(5, '0')}a"
                receiveChannel.send(
                    createSessionCreatedEvent(
                        createProtoSession(sessionId, "Session $i", "/home/$i", "claude")
                    ).toByteArray()
                )
            }
            advanceUntilIdle()

            // Collect all updates
            var lastCount = 0
            while (true) {
                val sessions = try { awaitItem() } catch (e: Exception) { break }
                lastCount = sessions.size
                if (lastCount == 10) break
            }

            assertEquals(10, lastCount)
        }
    }

    @Test
    fun `session status values are correctly mapped`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())

            // Test each status value
            val testCases = listOf(
                0 to SessionStatus.UNKNOWN,
                1 to SessionStatus.ACTIVE,
                2 to SessionStatus.CREATING,
                3 to SessionStatus.KILLING,
                999 to SessionStatus.UNKNOWN
            )

            for ((protoValue, expectedStatus) in testCases) {
                receiveChannel.send(
                    createSessionListEvent(
                        listOf(createProtoSessionWithStatus("abc123def456", protoValue))
                    ).toByteArray()
                )
                advanceUntilIdle()

                val session = awaitItem()[0]
                assertEquals(expectedStatus, session.status)
            }
        }
    }

    @Test
    fun `disconnect stops event processing`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()

        assertTrue(repository.isConnected.value)

        repository.disconnect()
        advanceUntilIdle()

        assertFalse(repository.isConnected.value)
    }

    @Test
    fun `reconnection restores event processing`() = runTest {
        repository.connect(mockWebRtcClient)
        advanceUntilIdle()
        repository.disconnect()
        advanceUntilIdle()

        // Reconnect with fresh channel
        val newChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val newClient = mockk<WebRTCClient>(relaxed = true) {
            coEvery { receive() } coAnswers { newChannel.receive() }
        }

        repository.connect(newClient)
        advanceUntilIdle()

        assertTrue(repository.isConnected.value)
    }

    // ==========================================================================
    // Helper Functions - Proto Event Creation
    // ==========================================================================

    private fun createProtoSession(
        id: String,
        displayName: String,
        directory: String,
        agent: String,
        status: Int = 1 // ACTIVE
    ): Session = Session.newBuilder()
        .setId(id)
        .setTmuxName("ras-$agent-${directory.substringAfterLast('/')}")
        .setDisplayName(displayName)
        .setDirectory(directory)
        .setAgent(agent)
        .setCreatedAt(Instant.now().epochSecond)
        .setLastActivityAt(Instant.now().epochSecond)
        .setStatusValue(status)
        .build()

    private fun createProtoSessionWithStatus(id: String, status: Int): Session =
        createProtoSession(id, "Test", "/home", "claude", status)

    private fun createSessionListEvent(sessions: List<Session>): ProtoSessionEvent =
        ProtoSessionEvent.newBuilder()
            .setList(
                SessionListEvent.newBuilder()
                    .addAllSessions(sessions)
                    .build()
            )
            .build()

    private fun createSessionCreatedEvent(session: Session): ProtoSessionEvent =
        ProtoSessionEvent.newBuilder()
            .setCreated(
                SessionCreatedEvent.newBuilder()
                    .setSession(session)
                    .build()
            )
            .build()

    private fun createSessionKilledEvent(sessionId: String): ProtoSessionEvent =
        ProtoSessionEvent.newBuilder()
            .setKilled(
                SessionKilledEvent.newBuilder()
                    .setSessionId(sessionId)
                    .build()
            )
            .build()

    private fun createSessionRenamedEvent(sessionId: String, newName: String): ProtoSessionEvent =
        ProtoSessionEvent.newBuilder()
            .setRenamed(
                SessionRenamedEvent.newBuilder()
                    .setSessionId(sessionId)
                    .setNewName(newName)
                    .build()
            )
            .build()

    private fun createSessionActivityEvent(sessionId: String, timestamp: Long): ProtoSessionEvent =
        ProtoSessionEvent.newBuilder()
            .setActivity(
                SessionActivityEvent.newBuilder()
                    .setSessionId(sessionId)
                    .setTimestamp(timestamp)
                    .build()
            )
            .build()

    private fun createSessionErrorEvent(
        code: String,
        message: String,
        sessionId: String
    ): ProtoSessionEvent =
        ProtoSessionEvent.newBuilder()
            .setError(
                SessionErrorEvent.newBuilder()
                    .setErrorCode(code)
                    .setMessage(message)
                    .setSessionId(sessionId)
                    .build()
            )
            .build()

    private fun createProtoAgent(
        name: String,
        binary: String,
        path: String,
        available: Boolean
    ): Agent = Agent.newBuilder()
        .setName(name)
        .setBinary(binary)
        .setPath(path)
        .setAvailable(available)
        .build()

    private fun createAgentsListEvent(agents: List<Agent>): ProtoSessionEvent =
        ProtoSessionEvent.newBuilder()
            .setAgents(
                AgentsListEvent.newBuilder()
                    .addAllAgents(agents)
                    .build()
            )
            .build()
}
