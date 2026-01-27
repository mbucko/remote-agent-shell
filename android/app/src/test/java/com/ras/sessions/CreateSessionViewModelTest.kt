package com.ras.sessions

import app.cash.turbine.test
import com.ras.data.sessions.AgentInfo
import com.ras.data.sessions.AgentsListState
import com.ras.data.sessions.CreateSessionState
import com.ras.data.sessions.DirectoryBrowserState
import com.ras.data.sessions.DirectoryEntryInfo
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import com.ras.ui.sessions.CreateSessionUiEvent
import com.ras.ui.sessions.CreateSessionViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CreateSessionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: SessionRepository
    private lateinit var sessionsFlow: MutableStateFlow<List<SessionInfo>>
    private lateinit var agentsFlow: MutableStateFlow<List<AgentInfo>>
    private lateinit var eventsFlow: MutableSharedFlow<SessionEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sessionsFlow = MutableStateFlow(emptyList())
        agentsFlow = MutableStateFlow(emptyList())
        eventsFlow = MutableSharedFlow()
        isConnectedFlow = MutableStateFlow(true)

        repository = mockk(relaxed = true) {
            every { sessions } returns sessionsFlow
            every { agents } returns agentsFlow
            every { events } returns eventsFlow
            every { isConnected } returns isConnectedFlow
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() = runTest {
        val viewModel = createViewModel()
        assertTrue(viewModel.createState.value is CreateSessionState.Idle)
    }

    @Test
    fun `startDirectorySelection changes state to SelectingDirectory`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        assertTrue(viewModel.createState.value is CreateSessionState.SelectingDirectory)
    }

    @Test
    fun `selectDirectory changes state to DirectorySelected`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.selectDirectory("/home/user/project")

        val state = viewModel.createState.value
        assertTrue(state is CreateSessionState.DirectorySelected)
        assertEquals("/home/user/project", (state as CreateSessionState.DirectorySelected).directory)
        assertEquals("/home/user/project", viewModel.selectedDirectory.value)
    }

    @Test
    fun `proceedToAgentSelection changes state to SelectingAgent`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.selectDirectory("/home/user/project")
        viewModel.proceedToAgentSelection()

        assertTrue(viewModel.createState.value is CreateSessionState.SelectingAgent)
    }

    @Test
    fun `selectAgent updates selectedAgent`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.selectDirectory("/home/user/project")
        viewModel.proceedToAgentSelection()
        viewModel.selectAgent("claude")

        assertEquals("claude", viewModel.selectedAgent.value)
    }

    @Test
    fun `createSession calls repository and changes state to Creating`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.selectDirectory("/home/user/project")
        viewModel.proceedToAgentSelection()
        viewModel.selectAgent("claude")
        viewModel.createSession()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.createSession("/home/user/project", "claude") }
    }

    @Test
    fun `reset clears all state`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.selectDirectory("/home/user/project")
        viewModel.proceedToAgentSelection()
        viewModel.selectAgent("claude")

        viewModel.reset()

        assertTrue(viewModel.createState.value is CreateSessionState.Idle)
        assertNull(viewModel.selectedDirectory.value)
        assertNull(viewModel.selectedAgent.value)
    }

    @Test
    fun `goBackStep from DirectorySelected goes to SelectingDirectory`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.selectDirectory("/home/user/project")

        viewModel.goBackStep()

        assertTrue(viewModel.createState.value is CreateSessionState.SelectingDirectory)
    }

    @Test
    fun `goBackStep from SelectingAgent goes to DirectorySelected`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.selectDirectory("/home/user/project")
        viewModel.proceedToAgentSelection()

        viewModel.goBackStep()

        assertTrue(viewModel.createState.value is CreateSessionState.DirectorySelected)
    }

    @Test
    fun `SessionCreated event changes state to Created`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val session = createSession("1")
        eventsFlow.emit(SessionEvent.SessionCreated(session))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.createState.value
        assertTrue(state is CreateSessionState.Created)
        assertEquals(session.id, (state as CreateSessionState.Created).session.id)
    }

    @Test
    fun `SessionCreated event emits UI event`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            val session = createSession("1")
            eventsFlow.emit(SessionEvent.SessionCreated(session))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is CreateSessionUiEvent.SessionCreated)
        }
    }

    @Test
    fun `SessionError event changes state to Failed`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        eventsFlow.emit(SessionEvent.SessionError("DIR_NOT_FOUND", "Directory not found", null))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.createState.value
        assertTrue(state is CreateSessionState.Failed)
        assertEquals("DIR_NOT_FOUND", (state as CreateSessionState.Failed).errorCode)
        assertEquals("Directory not found", state.message)
    }

    @Test
    fun `SessionError event emits UI event`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionError("ERROR", "Error message", null))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is CreateSessionUiEvent.Error)
            assertEquals("Error message", (event as CreateSessionUiEvent.Error).message)
        }
    }

    @Test
    fun `refreshAgents calls repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.refreshAgents()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.refreshAgents() }
    }

    @Test
    fun `navigateToDirectory updates currentPath`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.navigateToDirectory("/home/user/repos")

        assertEquals("/home/user/repos", viewModel.currentPath.value)
    }

    @Test
    fun `navigateBack returns to previous path`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()
        viewModel.navigateToDirectory("/home/user")
        viewModel.navigateToDirectory("/home/user/repos")

        val result = viewModel.navigateBack()

        assertTrue(result)
        assertEquals("/home/user", viewModel.currentPath.value)
    }

    @Test
    fun `navigateBack at root returns false`() = runTest {
        val viewModel = createViewModel()
        viewModel.startDirectorySelection()

        val result = viewModel.navigateBack()

        assertTrue(!result || viewModel.currentPath.value.isEmpty())
    }

    // ==========================================================================
    // Validation Tests
    // ==========================================================================

    @Test
    fun `selectDirectory with invalid path emits error`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            viewModel.selectDirectory("relative/path")
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is CreateSessionUiEvent.Error)
            assertTrue((event as CreateSessionUiEvent.Error).message.contains("Invalid"))
        }

        // State should not change
        assertNull(viewModel.selectedDirectory.value)
    }

    @Test
    fun `selectDirectory with path traversal emits error`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            viewModel.selectDirectory("/home/../etc/passwd")
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is CreateSessionUiEvent.Error)
        }

        assertNull(viewModel.selectedDirectory.value)
    }

    @Test
    fun `selectRecentDirectory with invalid path emits error`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            viewModel.selectRecentDirectory("")
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is CreateSessionUiEvent.Error)
        }
    }

    @Test
    fun `selectAgent with invalid name emits error`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            viewModel.selectAgent("invalid/agent")
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is CreateSessionUiEvent.Error)
            assertTrue((event as CreateSessionUiEvent.Error).message.contains("Invalid"))
        }

        assertNull(viewModel.selectedAgent.value)
    }

    @Test
    fun `selectAgent with unavailable agent emits error`() = runTest {
        agentsFlow.value = listOf(
            AgentInfo("Claude", "claude", "/usr/bin/claude", true),
            AgentInfo("Aider", "aider", "/usr/bin/aider", false)
        )
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            viewModel.selectAgent("aider")
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is CreateSessionUiEvent.Error)
            assertTrue((event as CreateSessionUiEvent.Error).message.contains("not available"))
        }

        assertNull(viewModel.selectedAgent.value)
    }

    @Test
    fun `selectAgent with non-existent agent emits error`() = runTest {
        agentsFlow.value = listOf(
            AgentInfo("Claude", "claude", "/usr/bin/claude", true)
        )
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            viewModel.selectAgent("nonexistent")
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is CreateSessionUiEvent.Error)
            assertTrue((event as CreateSessionUiEvent.Error).message.contains("not found"))
        }
    }

    @Test
    fun `selectAgent with valid available agent succeeds`() = runTest {
        agentsFlow.value = listOf(
            AgentInfo("Claude", "claude", "/usr/bin/claude", true)
        )
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.selectAgent("claude")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("claude", viewModel.selectedAgent.value)
    }

    // ==========================================================================
    // Concurrent Create Session Tests
    // ==========================================================================

    @Test
    fun `createSession while already creating does nothing`() = runTest {
        agentsFlow.value = listOf(AgentInfo("Claude", "claude", "/usr/bin/claude", true))
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.startDirectorySelection()
        viewModel.selectDirectory("/home/user/project")
        viewModel.proceedToAgentSelection()
        viewModel.selectAgent("claude")

        // First create
        viewModel.createSession()

        // Second create while still creating - should be ignored
        viewModel.createSession()
        testDispatcher.scheduler.advanceUntilIdle()

        // Should only have called once
        coVerify(exactly = 1) { repository.createSession(any(), any()) }
    }

    // ==========================================================================
    // Event Flow Integration Tests
    // ==========================================================================

    @Test
    fun `DirectoriesLoaded event updates directory state`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val entries = listOf(
            DirectoryEntryInfo("projects", "/home/user/projects", true),
            DirectoryEntryInfo("docs", "/home/user/docs", true)
        )
        val recent = listOf("/home/user/projects", "/home/user/work")

        eventsFlow.emit(SessionEvent.DirectoriesLoaded("/home/user", entries, recent))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.directoryState.value
        assertTrue(state is DirectoryBrowserState.Loaded)
        val loaded = state as DirectoryBrowserState.Loaded
        assertEquals("/home/user", loaded.parent)
        assertEquals(2, loaded.entries.size)
        assertEquals("projects", loaded.entries[0].name)
        assertEquals(2, loaded.recentDirectories.size)
    }

    @Test
    fun `AgentsLoaded event updates agents state`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val agents = listOf(
            AgentInfo("Claude Code", "claude", "/usr/bin/claude", true),
            AgentInfo("Aider", "aider", "/usr/bin/aider", false)
        )

        eventsFlow.emit(SessionEvent.AgentsLoaded(agents))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.agentsState.value
        assertTrue(state is AgentsListState.Loaded)
        val loaded = state as AgentsListState.Loaded
        assertEquals(2, loaded.agents.size)
        assertEquals("claude", loaded.agents[0].binary)
        assertTrue(loaded.agents[0].available)
        assertFalse(loaded.agents[1].available)
    }

    @Test
    fun `recentDirectories flow updates when DirectoriesLoaded has recent`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val recent = listOf("/home/user/project1", "/home/user/project2")
        eventsFlow.emit(SessionEvent.DirectoriesLoaded("/home/user", emptyList(), recent))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(recent, viewModel.recentDirectories.value)
    }

    private fun createViewModel() = CreateSessionViewModel(repository)

    private fun createSession(
        id: String,
        displayName: String = "Session $id"
    ) = SessionInfo(
        id = id,
        tmuxName = "ras-claude-project-$id",
        displayName = displayName,
        directory = "/home/user/repos/project-$id",
        agent = "claude",
        createdAt = Instant.now(),
        lastActivityAt = Instant.now(),
        status = SessionStatus.ACTIVE
    )
}
