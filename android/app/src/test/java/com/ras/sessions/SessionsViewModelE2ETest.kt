package com.ras.sessions

import app.cash.turbine.test
import com.ras.data.connection.ConnectionManager
import com.ras.data.keystore.KeyManager
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import com.ras.data.sessions.SessionsScreenState
import com.ras.ui.sessions.SessionsUiEvent
import com.ras.ui.sessions.SessionsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * End-to-end tests for SessionsViewModel.
 *
 * Tests the full integration between:
 * 1. SessionRepository state changes
 * 2. ViewModel state transformation
 * 3. UI state emissions
 * 4. User action handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelE2ETest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var savedStateHandle: androidx.lifecycle.SavedStateHandle
    private lateinit var mockCredentialRepository: com.ras.data.credentials.CredentialRepository
    private lateinit var mockRepository: SessionRepository
    private lateinit var mockKeyManager: KeyManager
    private lateinit var mockConnectionManager: ConnectionManager
    private lateinit var sessionsFlow: MutableStateFlow<List<SessionInfo>>
    private lateinit var eventsFlow: MutableSharedFlow<SessionEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var viewModel: SessionsViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("deviceId" to "test-device-id"))
        mockCredentialRepository = mockk(relaxed = true)
        sessionsFlow = MutableStateFlow(emptyList())
        eventsFlow = MutableSharedFlow()
        isConnectedFlow = MutableStateFlow(true)

        mockRepository = mockk(relaxed = true)
        every { mockRepository.sessions } returns sessionsFlow
        every { mockRepository.events } returns eventsFlow
        every { mockRepository.isConnected } returns isConnectedFlow
        mockKeyManager = mockk(relaxed = true)
        mockConnectionManager = mockk(relaxed = true)

        viewModel = SessionsViewModel(savedStateHandle, mockCredentialRepository, mockRepository, mockKeyManager, mockConnectionManager)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // Initial State Tests
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `initial state is loaded with empty list`() = runTest {
        viewModel.screenState.test {
            val state = awaitItem()
            assertTrue(state is SessionsScreenState.Loaded)
            assertEquals(0, (state as SessionsScreenState.Loaded).sessions.size)
        }
    }

    @Tag("e2e")
    @Test
    fun `transitions to loaded with sessions when sessions received`() = runTest {
        advanceUntilIdle()

        viewModel.screenState.test {
            skipItems(1) // Skip initial Loaded(emptyList())

            sessionsFlow.value = listOf(
                createSession("abc123def456", "Test Session")
            )
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state is SessionsScreenState.Loaded)
            assertEquals(1, (state as SessionsScreenState.Loaded).sessions.size)
        }
    }

    // ==========================================================================
    // Session List Updates
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `UI updates when sessions are added`() = runTest {
        advanceUntilIdle()

        viewModel.screenState.test {
            // Skip initial states
            skipItems(1)

            sessionsFlow.value = listOf(createSession("session1aaaa", "First"))
            advanceUntilIdle()
            assertEquals(1, (awaitItem() as SessionsScreenState.Loaded).sessions.size)

            sessionsFlow.value = listOf(
                createSession("session1aaaa", "First"),
                createSession("session2bbbb", "Second")
            )
            advanceUntilIdle()
            assertEquals(2, (awaitItem() as SessionsScreenState.Loaded).sessions.size)
        }
    }

    @Tag("e2e")
    @Test
    fun `UI updates when sessions are removed`() = runTest {
        sessionsFlow.value = listOf(
            createSession("session1aaaa", "First"),
            createSession("session2bbbb", "Second")
        )
        advanceUntilIdle()

        viewModel.screenState.test {
            // Get current loaded state (already processed by advanceUntilIdle)
            val initial = awaitItem() as SessionsScreenState.Loaded
            assertEquals(2, initial.sessions.size)

            sessionsFlow.value = listOf(createSession("session2bbbb", "Second"))
            advanceUntilIdle()

            val state = awaitItem() as SessionsScreenState.Loaded
            assertEquals(1, state.sessions.size)
            assertEquals("session2bbbb", state.sessions[0].id)
        }
    }

    @Tag("e2e")
    @Test
    fun `UI shows empty state when all sessions removed`() = runTest {
        sessionsFlow.value = listOf(createSession("abc123def456", "Test"))
        advanceUntilIdle()

        viewModel.screenState.test {
            // Get current loaded state
            val initial = awaitItem() as SessionsScreenState.Loaded
            assertEquals(1, initial.sessions.size)

            sessionsFlow.value = emptyList()
            advanceUntilIdle()

            val state = awaitItem() as SessionsScreenState.Loaded
            assertTrue(state.sessions.isEmpty())
        }
    }

    @Tag("e2e")
    @Test
    fun `sessions are sorted by last activity descending`() = runTest {
        val now = Instant.now()
        sessionsFlow.value = listOf(
            createSessionWithActivity("session1aaaa", "Oldest", now.minusSeconds(100)),
            createSessionWithActivity("session2bbbb", "Newest", now),
            createSessionWithActivity("session3cccc", "Middle", now.minusSeconds(50))
        )
        advanceUntilIdle()

        viewModel.screenState.test {
            // Get current loaded state (already processed)
            val state = awaitItem() as SessionsScreenState.Loaded
            assertEquals("session2bbbb", state.sessions[0].id) // Newest first
            assertEquals("session3cccc", state.sessions[1].id) // Middle
            assertEquals("session1aaaa", state.sessions[2].id) // Oldest last
        }
    }

    // ==========================================================================
    // Dialog State Tests
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `showKillDialog sets dialog state`() = runTest {
        val session = createSession("abc123def456", "Test")
        advanceUntilIdle()

        viewModel.showKillDialog.test {
            assertNull(awaitItem())

            viewModel.showKillDialog(session)
            assertEquals(session, awaitItem())
        }
    }

    @Tag("e2e")
    @Test
    fun `dismissKillDialog clears dialog state`() = runTest {
        val session = createSession("abc123def456", "Test")
        advanceUntilIdle()

        viewModel.showKillDialog(session)

        viewModel.showKillDialog.test {
            assertEquals(session, awaitItem())

            viewModel.dismissKillDialog()
            assertNull(awaitItem())
        }
    }

    @Tag("e2e")
    @Test
    fun `showRenameDialog sets dialog state`() = runTest {
        val session = createSession("abc123def456", "Test")
        advanceUntilIdle()

        viewModel.showRenameDialog.test {
            assertNull(awaitItem())

            viewModel.showRenameDialog(session)
            assertEquals(session, awaitItem())
        }
    }

    @Tag("e2e")
    @Test
    fun `dismissRenameDialog clears dialog state`() = runTest {
        val session = createSession("abc123def456", "Test")
        advanceUntilIdle()

        viewModel.showRenameDialog(session)

        viewModel.showRenameDialog.test {
            assertEquals(session, awaitItem())

            viewModel.dismissRenameDialog()
            assertNull(awaitItem())
        }
    }

    // ==========================================================================
    // Kill Session Flow
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `confirmKillSession calls repository and dismisses dialog`() = runTest {
        val session = createSession("abc123def456", "Test")
        advanceUntilIdle()

        viewModel.showKillDialog(session)
        viewModel.confirmKillSession()
        advanceUntilIdle()

        coVerify { mockRepository.killSession("abc123def456") }

        viewModel.showKillDialog.test {
            assertNull(awaitItem())
        }
    }

    @Tag("e2e")
    @Test
    fun `confirmKillSession does nothing if no dialog shown`() = runTest {
        advanceUntilIdle()

        viewModel.confirmKillSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { mockRepository.killSession(any()) }
    }

    @Tag("e2e")
    @Test
    fun `kill session error emits error event`() = runTest {
        val session = createSession("abc123def456", "Test")
        coEvery { mockRepository.killSession(any()) } throws RuntimeException("Network error")
        advanceUntilIdle()

        viewModel.uiEvents.test {
            viewModel.showKillDialog(session)
            viewModel.confirmKillSession()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.Error)
            assertTrue((event as SessionsUiEvent.Error).message.contains("Network error"))
        }
    }

    // ==========================================================================
    // Rename Session Flow
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `confirmRenameSession calls repository and dismisses dialog`() = runTest {
        val session = createSession("abc123def456", "Old Name")
        advanceUntilIdle()

        viewModel.showRenameDialog(session)
        viewModel.confirmRenameSession("New Name")
        advanceUntilIdle()

        coVerify { mockRepository.renameSession("abc123def456", "New Name") }

        viewModel.showRenameDialog.test {
            assertNull(awaitItem())
        }
    }

    @Tag("e2e")
    @Test
    fun `confirmRenameSession does nothing if no dialog shown`() = runTest {
        advanceUntilIdle()

        viewModel.confirmRenameSession("New Name")
        advanceUntilIdle()

        coVerify(exactly = 0) { mockRepository.renameSession(any(), any()) }
    }

    @Tag("e2e")
    @Test
    fun `rename session error emits error event`() = runTest {
        val session = createSession("abc123def456", "Test")
        coEvery { mockRepository.renameSession(any(), any()) } throws RuntimeException("Invalid name")
        advanceUntilIdle()

        viewModel.uiEvents.test {
            viewModel.showRenameDialog(session)
            viewModel.confirmRenameSession("@Invalid")
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.Error)
            assertTrue((event as SessionsUiEvent.Error).message.contains("Invalid name"))
        }
    }

    // ==========================================================================
    // Refresh Flow
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `refreshSessions triggers repository list sessions`() = runTest {
        sessionsFlow.value = listOf(createSession("abc123def456", "Test"))
        advanceUntilIdle()

        viewModel.refreshSessions()
        advanceUntilIdle()

        // Called once on init and once on refresh
        coVerify(atLeast = 2) { mockRepository.listSessions() }
    }

    @Tag("e2e")
    @Test
    fun `refreshSessions completes without leaving isRefreshing flag stuck`() = runTest {
        // Wait for initial state to settle and sessions to load
        advanceUntilIdle()

        // Initial state should be Loaded (either empty or with sessions)
        val initialState = viewModel.screenState.value
        assertTrue(initialState is SessionsScreenState.Loaded, "Expected Loaded state but got: ${initialState::class.simpleName}")

        // Call refresh
        viewModel.refreshSessions()
        advanceUntilIdle()

        // After refresh completes, state should still be Loaded and not stuck refreshing
        val afterRefresh = viewModel.screenState.value
        assertTrue(afterRefresh is SessionsScreenState.Loaded, "Expected Loaded state after refresh but got: ${afterRefresh::class.simpleName}")
        assertEquals(
            false,
            (afterRefresh as SessionsScreenState.Loaded).isRefreshing,
            "isRefreshing should be false after refresh completes"
        )
    }

    // ==========================================================================
    // Repository Event Handling
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `SessionCreated event emits UI event`() = runTest {
        advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionCreated(createSession("abc123def456", "New Session")))
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.SessionCreated)
            assertEquals("New Session", (event as SessionsUiEvent.SessionCreated).name)
        }
    }

    @Tag("e2e")
    @Test
    fun `SessionKilled event emits UI event`() = runTest {
        advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionKilled("abc123def456"))
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.SessionKilled)
        }
    }

    @Tag("e2e")
    @Test
    fun `SessionRenamed event emits UI event`() = runTest {
        advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionRenamed("abc123def456", "Renamed"))
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.SessionRenamed)
            assertEquals("Renamed", (event as SessionsUiEvent.SessionRenamed).newName)
        }
    }

    @Tag("e2e")
    @Test
    fun `SessionError event emits error UI event`() = runTest {
        advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionError("DIR_NOT_FOUND", "Directory not found", null))
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.Error)
            assertEquals("Directory not found", (event as SessionsUiEvent.Error).message)
        }
    }

    @Tag("e2e")
    @Test
    fun `SessionActivity event does not emit UI event`() = runTest {
        advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionActivity("abc123def456", Instant.now()))
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    // ==========================================================================
    // Session Status Display
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `sessions with different statuses are displayed correctly`() = runTest {
        sessionsFlow.value = listOf(
            createSession("session1aaaa", "Active", SessionStatus.ACTIVE),
            createSession("session2bbbb", "Creating", SessionStatus.CREATING),
            createSession("session3cccc", "Killing", SessionStatus.KILLING)
        )
        advanceUntilIdle()

        viewModel.screenState.test {
            // Get current loaded state (already processed)
            val state = awaitItem() as SessionsScreenState.Loaded
            assertEquals(3, state.sessions.size)
        }
    }

    // ==========================================================================
    // Edge Cases
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `handles rapid state changes gracefully`() = runTest {
        advanceUntilIdle()

        viewModel.screenState.test {
            // Get initial state (empty Loaded after advanceUntilIdle)
            awaitItem()

            // Rapid updates
            for (i in 1..20) {
                sessionsFlow.value = (1..i).map {
                    createSession("session${it.toString().padStart(7, '0')}a", "Session $it")
                }
            }
            advanceUntilIdle()

            // Due to StateFlow conflation, we get the final state
            val finalState = expectMostRecentItem() as SessionsScreenState.Loaded
            assertEquals(20, finalState.sessions.size)
        }
    }

    @Tag("e2e")
    @Test
    fun `session with empty display name uses tmux name for displayText`() = runTest {
        sessionsFlow.value = listOf(
            SessionInfo(
                id = "abc123def456",
                tmuxName = "ras-claude-project",
                displayName = "",
                directory = "/home/project",
                agent = "claude",
                createdAt = Instant.now(),
                lastActivityAt = Instant.now(),
                status = SessionStatus.ACTIVE
            )
        )
        advanceUntilIdle()

        viewModel.screenState.test {
            // Get current loaded state (already processed)
            val state = awaitItem() as SessionsScreenState.Loaded
            assertEquals("ras-claude-project", state.sessions[0].displayText)
        }
    }

    @Tag("e2e")
    @Test
    fun `directory basename is correctly extracted`() = runTest {
        sessionsFlow.value = listOf(
            createSession("abc123def456", "Test", SessionStatus.ACTIVE, "/home/user/my-project")
        )
        advanceUntilIdle()

        viewModel.screenState.test {
            // Get current loaded state (already processed)
            val state = awaitItem() as SessionsScreenState.Loaded
            assertEquals("my-project", state.sessions[0].directoryBasename)
        }
    }

    @Tag("e2e")
    @Test
    fun `connection state is exposed from repository`() = runTest {
        viewModel.isConnected.test {
            assertTrue(awaitItem())

            isConnectedFlow.value = false
            advanceUntilIdle()

            assertEquals(false, awaitItem())
        }
    }

    // ==========================================================================
    // Helper Functions
    // ==========================================================================

    private fun createSession(
        id: String,
        displayName: String,
        status: SessionStatus = SessionStatus.ACTIVE,
        directory: String = "/home/user/project"
    ) = SessionInfo(
        id = id,
        tmuxName = "ras-claude-${displayName.lowercase().replace(" ", "-")}",
        displayName = displayName,
        directory = directory,
        agent = "claude",
        createdAt = Instant.now(),
        lastActivityAt = Instant.now(),
        status = status
    )

    private fun createSessionWithActivity(
        id: String,
        displayName: String,
        lastActivityAt: Instant
    ) = SessionInfo(
        id = id,
        tmuxName = "ras-claude-${displayName.lowercase().replace(" ", "-")}",
        displayName = displayName,
        directory = "/home/user/project",
        agent = "claude",
        createdAt = Instant.now(),
        lastActivityAt = lastActivityAt,
        status = SessionStatus.ACTIVE
    )
}
