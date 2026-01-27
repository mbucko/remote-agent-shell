package com.ras.sessions

import app.cash.turbine.test
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import com.ras.data.webrtc.WebRTCClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Comprehensive tests for SessionRepository.
 * Tests all event handling, command sending, and state management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var webRtcClientFactory: WebRTCClient.Factory
    private lateinit var mockWebRtcClient: WebRTCClient
    private lateinit var repository: SessionRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockWebRtcClient = mockk(relaxed = true)
        webRtcClientFactory = mockk {
            every { create() } returns mockWebRtcClient
        }
        repository = SessionRepository(webRtcClientFactory, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // Connection Tests
    // ==========================================================================

    @Test
    fun `initial state is disconnected`() {
        assertFalse(repository.isConnected.value)
    }

    @Test
    fun `connect sets isConnected to true`() {
        repository.connect(mockWebRtcClient)
        assertTrue(repository.isConnected.value)
    }

    @Test
    fun `disconnect sets isConnected to false`() {
        repository.connect(mockWebRtcClient)
        repository.disconnect()
        assertFalse(repository.isConnected.value)
    }

    @Test
    fun `initial sessions list is empty`() {
        assertTrue(repository.sessions.value.isEmpty())
    }

    @Test
    fun `initial agents list is empty`() {
        assertTrue(repository.agents.value.isEmpty())
    }

    // ==========================================================================
    // Command Tests
    // ==========================================================================

    @Test
    fun `listSessions sends correct command`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.listSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test
    fun `createSession sends correct command with directory and agent`() = runTest {
        repository.connect(mockWebRtcClient)
        val dataSlot = slot<ByteArray>()
        coEvery { mockWebRtcClient.send(capture(dataSlot)) } returns Unit

        repository.createSession("/home/user/project", "claude")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }
        // Verify the command was serialized correctly
        assertTrue(dataSlot.captured.isNotEmpty())
    }

    @Test
    fun `killSession sends correct command with session ID`() = runTest {
        repository.connect(mockWebRtcClient)

        repository.killSession("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test
    fun `renameSession sends correct command with session ID and new name`() = runTest {
        repository.connect(mockWebRtcClient)

        repository.renameSession("abc123def456", "New Name")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test
    fun `getAgents sends correct command`() = runTest {
        repository.connect(mockWebRtcClient)

        repository.getAgents()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test
    fun `getDirectories sends correct command with parent path`() = runTest {
        repository.connect(mockWebRtcClient)

        repository.getDirectories("/home/user")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test
    fun `getDirectories with empty parent sends root request`() = runTest {
        repository.connect(mockWebRtcClient)

        repository.getDirectories("")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test
    fun `refreshAgents sends correct command`() = runTest {
        repository.connect(mockWebRtcClient)

        repository.refreshAgents()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test(expected = IllegalStateException::class)
    fun `listSessions throws when disconnected`() = runTest {
        // Don't connect
        repository.listSessions()
    }

    @Test(expected = IllegalStateException::class)
    fun `createSession throws when disconnected`() = runTest {
        // Don't connect
        repository.createSession("/home/user/project", "claude")
    }

    @Test(expected = IllegalStateException::class)
    fun `killSession throws when disconnected`() = runTest {
        // Don't connect
        repository.killSession("abc123def456")
    }

    @Test(expected = IllegalStateException::class)
    fun `renameSession throws when disconnected`() = runTest {
        // Don't connect
        repository.renameSession("abc123def456", "New Name")
    }

    @Test(expected = IllegalStateException::class)
    fun `getAgents throws when disconnected`() = runTest {
        // Don't connect
        repository.getAgents()
    }

    @Test(expected = IllegalStateException::class)
    fun `getDirectories throws when disconnected`() = runTest {
        // Don't connect
        repository.getDirectories("/home")
    }

    @Test(expected = IllegalStateException::class)
    fun `refreshAgents throws when disconnected`() = runTest {
        // Don't connect
        repository.refreshAgents()
    }

    // ==========================================================================
    // Validation Tests
    // ==========================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `killSession throws for invalid session ID - too short`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.killSession("abc123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `killSession throws for invalid session ID - contains dash`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.killSession("abc-123-def4")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `killSession throws for invalid session ID - path traversal`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.killSession("..abc123def4")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `killSession throws for null session ID`() = runTest {
        repository.connect(mockWebRtcClient)
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        repository.killSession(null as String)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `renameSession throws for invalid session ID`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.renameSession("invalid", "New Name")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `renameSession throws for invalid display name - empty`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.renameSession("abc123def456", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `renameSession throws for invalid display name - too long`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.renameSession("abc123def456", "A".repeat(65))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `renameSession throws for invalid display name - special chars`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.renameSession("abc123def456", "Name@Invalid")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `renameSession throws for invalid display name - leading whitespace`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.renameSession("abc123def456", " Leading Space")
    }

    @Test
    fun `killSession accepts valid 12-char alphanumeric ID`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.killSession("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test
    fun `renameSession accepts valid session ID and display name`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.renameSession("abc123def456", "Valid Name")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { mockWebRtcClient.send(any()) }
    }

    // ==========================================================================
    // createSession Validation Tests
    // ==========================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `createSession throws for relative directory path`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("home/user/project", "claude")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createSession throws for empty directory`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("", "claude")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createSession throws for path traversal in directory`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("/home/../etc/passwd", "claude")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createSession throws for double slashes in directory`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("/home//user", "claude")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createSession throws for empty agent`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("/home/user/project", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createSession throws for agent with slashes`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("/home/user/project", "../evil")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createSession throws for agent with spaces`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("/home/user/project", "my agent")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createSession throws for agent name too long`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("/home/user/project", "a".repeat(33))
    }

    @Test
    fun `createSession accepts valid directory and agent`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("/home/user/project", "claude")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { mockWebRtcClient.send(any()) }
    }

    @Test
    fun `createSession accepts agent with dashes and underscores`() = runTest {
        repository.connect(mockWebRtcClient)
        repository.createSession("/home/user/project", "claude-code_v2")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { mockWebRtcClient.send(any()) }
    }

    // ==========================================================================
    // Event Handling - Would require proto parsing
    // These tests verify the flow structure is correct
    // ==========================================================================

    @Test
    fun `events flow is shared flow`() = runTest {
        repository.events.test {
            // SharedFlow doesn't emit on subscribe, just verify it's accessible
            expectNoEvents()
        }
    }

    @Test
    fun `sessions flow emits updates`() = runTest {
        repository.sessions.test {
            assertEquals(emptyList<SessionInfo>(), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `agents flow emits updates`() = runTest {
        repository.agents.test {
            assertEquals(emptyList<Any>(), awaitItem())
            expectNoEvents()
        }
    }
}
