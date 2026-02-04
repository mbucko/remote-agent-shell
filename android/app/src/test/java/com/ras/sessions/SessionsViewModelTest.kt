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

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var savedStateHandle: androidx.lifecycle.SavedStateHandle
    private lateinit var credentialRepository: com.ras.data.credentials.CredentialRepository
    private lateinit var repository: SessionRepository
    private lateinit var keyManager: KeyManager
    private lateinit var connectionManager: ConnectionManager
    private lateinit var sessionsFlow: MutableStateFlow<List<SessionInfo>>
    private lateinit var eventsFlow: MutableSharedFlow<SessionEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("deviceId" to "test-device-id"))
        credentialRepository = mockk(relaxed = true)
        sessionsFlow = MutableStateFlow(emptyList())
        eventsFlow = MutableSharedFlow()
        isConnectedFlow = MutableStateFlow(true)

        repository = mockk(relaxed = true)
        every { repository.sessions } returns sessionsFlow
        every { repository.events } returns eventsFlow
        every { repository.isConnected } returns isConnectedFlow
        keyManager = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Tag("unit")
    @Test
    fun `initial state is Loaded with empty list`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.screenState.value
        assertTrue(state is SessionsScreenState.Loaded)
        assertEquals(0, (state as SessionsScreenState.Loaded).sessions.size)
    }

    @Tag("unit")
    @Test
    fun `sessions from repository update screen state`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val sessions = listOf(createSession("1"), createSession("2"))
        sessionsFlow.value = sessions

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.screenState.value
        assertTrue(state is SessionsScreenState.Loaded)
        assertEquals(2, (state as SessionsScreenState.Loaded).sessions.size)
    }

    @Tag("unit")
    @Test
    fun `refreshSessions calls repository`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refreshSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(atLeast = 2) { repository.listSessions() }
    }

    @Tag("unit")
    @Test
    fun `showKillDialog updates dialog state`() = runTest {
        val viewModel = createViewModel()
        val session = createSession("1")

        viewModel.showKillDialog(session)

        assertEquals(session, viewModel.showKillDialog.value)
    }

    @Tag("unit")
    @Test
    fun `dismissKillDialog clears dialog state`() = runTest {
        val viewModel = createViewModel()
        val session = createSession("1")

        viewModel.showKillDialog(session)
        viewModel.dismissKillDialog()

        assertNull(viewModel.showKillDialog.value)
    }

    @Tag("unit")
    @Test
    fun `confirmKillSession calls repository and clears dialog`() = runTest {
        val viewModel = createViewModel()
        val session = createSession("1")

        viewModel.showKillDialog(session)
        viewModel.confirmKillSession()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.killSession("1") }
        assertNull(viewModel.showKillDialog.value)
    }

    @Tag("unit")
    @Test
    fun `showRenameDialog updates dialog state`() = runTest {
        val viewModel = createViewModel()
        val session = createSession("1")

        viewModel.showRenameDialog(session)

        assertEquals(session, viewModel.showRenameDialog.value)
    }

    @Tag("unit")
    @Test
    fun `dismissRenameDialog clears dialog state`() = runTest {
        val viewModel = createViewModel()
        val session = createSession("1")

        viewModel.showRenameDialog(session)
        viewModel.dismissRenameDialog()

        assertNull(viewModel.showRenameDialog.value)
    }

    @Tag("unit")
    @Test
    fun `confirmRenameSession calls repository and clears dialog`() = runTest {
        val viewModel = createViewModel()
        val session = createSession("1")

        viewModel.showRenameDialog(session)
        viewModel.confirmRenameSession("New Name")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.renameSession("1", "New Name") }
        assertNull(viewModel.showRenameDialog.value)
    }

    @Tag("unit")
    @Test
    fun `SessionCreated event emits UI event`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionCreated(createSession("1")))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.SessionCreated)
        }
    }

    @Tag("unit")
    @Test
    fun `SessionKilled event emits UI event`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionKilled("1"))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.SessionKilled)
        }
    }

    @Tag("unit")
    @Test
    fun `SessionRenamed event emits UI event`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionRenamed("1", "New Name"))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.SessionRenamed)
            assertEquals("New Name", (event as SessionsUiEvent.SessionRenamed).newName)
        }
    }

    @Tag("unit")
    @Test
    fun `SessionError event emits UI event`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionError("ERROR", "Error message", null))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is SessionsUiEvent.Error)
            assertEquals("Error message", (event as SessionsUiEvent.Error).message)
        }
    }

    @Tag("unit")
    @Test
    fun `isConnected reflects repository state`() = runTest {
        val viewModel = createViewModel()

        assertTrue(viewModel.isConnected.value)

        isConnectedFlow.value = false

        assertEquals(false, viewModel.isConnected.value)
    }

    // ==========================================================================
    // Channel Single Delivery Tests (verifying fix for SharedFlow -> Channel)
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `UI events are delivered exactly once via Channel`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Collect events in first collector
        val receivedEvents = mutableListOf<SessionsUiEvent>()
        viewModel.uiEvents.test {
            eventsFlow.emit(SessionEvent.SessionCreated(createSession("1")))
            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.add(awaitItem())

            // Emit another event
            eventsFlow.emit(SessionEvent.SessionKilled("1"))
            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.add(awaitItem())
        }

        // Verify exactly 2 events received
        assertEquals(2, receivedEvents.size)
        assertTrue(receivedEvents[0] is SessionsUiEvent.SessionCreated)
        assertTrue(receivedEvents[1] is SessionsUiEvent.SessionKilled)
    }

    @Tag("unit")
    @Test
    fun `multiple rapid events are all delivered via Channel`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            // Emit multiple events rapidly
            repeat(5) { i ->
                eventsFlow.emit(SessionEvent.SessionCreated(createSession("$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // All 5 events should be received
            repeat(5) {
                val event = awaitItem()
                assertTrue(event is SessionsUiEvent.SessionCreated)
            }
        }
    }

    // ==========================================================================
    // Atomic State Update Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `refreshSessions preserves isRefreshing during concurrent session updates`() = runTest {
        val viewModel = createViewModel()

        // Set initial loaded state
        sessionsFlow.value = listOf(createSession("1"))
        testDispatcher.scheduler.advanceUntilIdle()

        // Start refresh
        viewModel.refreshSessions()

        // Simulate concurrent session update while refreshing
        sessionsFlow.value = listOf(createSession("1"), createSession("2"))
        testDispatcher.scheduler.advanceUntilIdle()

        // State should be Loaded (refresh completes)
        val state = viewModel.screenState.value
        assertTrue(state is SessionsScreenState.Loaded)
        assertEquals(2, (state as SessionsScreenState.Loaded).sessions.size)
    }

    @Tag("unit")
    @Test
    fun `loadSessions does not overwrite existing sessions with Loading state`() = runTest {
        // Pre-populate sessions
        sessionsFlow.value = listOf(createSession("1"), createSession("2"))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Should be Loaded, not Loading
        val state = viewModel.screenState.value
        assertTrue(state is SessionsScreenState.Loaded)
        assertEquals(2, (state as SessionsScreenState.Loaded).sessions.size)
    }

    @Tag("unit")
    @Test
    fun `confirmKillSession dismisses dialog immediately for responsiveness`() = runTest {
        val viewModel = createViewModel()
        val session = createSession("1")

        viewModel.showKillDialog(session)
        assertEquals(session, viewModel.showKillDialog.value)

        // Call confirm - dialog should dismiss immediately (before repository call completes)
        viewModel.confirmKillSession()

        // Dialog should be null immediately
        assertNull(viewModel.showKillDialog.value)

        // Repository call happens asynchronously
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { repository.killSession("1") }
    }

    @Tag("unit")
    @Test
    fun `confirmRenameSession dismisses dialog immediately for responsiveness`() = runTest {
        val viewModel = createViewModel()
        val session = createSession("1")

        viewModel.showRenameDialog(session)
        assertEquals(session, viewModel.showRenameDialog.value)

        // Call confirm - dialog should dismiss immediately
        viewModel.confirmRenameSession("New Name")

        // Dialog should be null immediately
        assertNull(viewModel.showRenameDialog.value)

        // Repository call happens asynchronously
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { repository.renameSession("1", "New Name") }
    }

    // ==========================================================================
    // Disconnect Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `disconnect sets disconnected flag BEFORE closing transport`() = runTest {
        // This test verifies the order of operations to prevent reconnection race condition.
        // When transport closes, it triggers ConnectionError which ReconnectionController
        // reacts to. If setDisconnected is called after disconnectGracefully, the flag
        // check races with the reconnection attempt.
        val callOrder = mutableListOf<String>()

        coEvery { keyManager.setDisconnected(true) } answers {
            callOrder.add("setDisconnected")
        }
        coEvery { connectionManager.disconnectGracefully(any()) } answers {
            callOrder.add("disconnectGracefully")
        }

        val viewModel = createViewModel()
        var navigated = false
        viewModel.disconnect { navigated = true }
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify order: setDisconnected must come BEFORE disconnectGracefully
        assertEquals(listOf("setDisconnected", "disconnectGracefully"), callOrder)
        assertTrue(navigated)
    }

    @Tag("unit")
    @Test
    fun `disconnect calls both setDisconnected and disconnectGracefully`() = runTest {
        val viewModel = createViewModel()
        var navigated = false

        viewModel.disconnect { navigated = true }
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { keyManager.setDisconnected(true) }
        coVerify { connectionManager.disconnectGracefully("user_request") }
        assertTrue(navigated)
    }

    private fun createViewModel() = SessionsViewModel(savedStateHandle, credentialRepository, repository, keyManager, connectionManager)

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
