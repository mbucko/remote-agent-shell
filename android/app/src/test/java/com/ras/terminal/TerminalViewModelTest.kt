package com.ras.terminal

import app.cash.turbine.test
import com.ras.data.connection.ConnectionLifecycleState
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import com.ras.data.settings.ModifierKeySettings
import com.ras.data.settings.SettingsRepository
import com.ras.data.terminal.TerminalAttachException
import com.ras.data.terminal.TerminalErrorInfo
import com.ras.data.terminal.TerminalEvent
import com.ras.data.terminal.TerminalRepository
import com.ras.data.terminal.TerminalScreenState
import com.ras.data.terminal.TerminalState
import com.ras.data.terminal.TerminalUiEvent
import com.ras.ui.terminal.TerminalViewModel
import com.ras.util.ClipboardService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TerminalRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var modifierKeySettings: ModifierKeySettings
    private lateinit var clipboardService: ClipboardService

    private lateinit var terminalStateFlow: MutableStateFlow<TerminalState>
    private lateinit var terminalOutputFlow: MutableSharedFlow<ByteArray>
    private lateinit var terminalEventsFlow: MutableSharedFlow<TerminalEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var connectionStateFlow: MutableStateFlow<ConnectionLifecycleState>
    private lateinit var sessionsFlow: MutableStateFlow<List<SessionInfo>>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        terminalStateFlow = MutableStateFlow(TerminalState())
        terminalOutputFlow = MutableSharedFlow()
        terminalEventsFlow = MutableSharedFlow()
        isConnectedFlow = MutableStateFlow(true)
        connectionStateFlow = MutableStateFlow(ConnectionLifecycleState.CONNECTED)
        sessionsFlow = MutableStateFlow(listOf(
            SessionInfo(
                id = "abc123def456",
                tmuxName = "test-session",
                displayName = "test-session",
                directory = "/home/user",
                agent = "claude",
                createdAt = Instant.now(),
                lastActivityAt = Instant.now(),
                status = SessionStatus.ACTIVE
            )
        ))

        repository = mockk(relaxed = true) {
            every { state } returns terminalStateFlow
            every { output } returns terminalOutputFlow
            every { events } returns terminalEventsFlow
            every { isConnected } returns isConnectedFlow
            every { connectionState } returns connectionStateFlow
        }

        sessionRepository = mockk(relaxed = true) {
            every { sessions } returns sessionsFlow
        }

        settingsRepository = mockk(relaxed = true) {
            every { quickButtons } returns MutableStateFlow(emptyList())
            every { getTerminalFontSize() } returns 14f
        }

        modifierKeySettings = mockk(relaxed = true) {
            every { showCtrlKey } returns MutableStateFlow(true)
            every { showShiftKey } returns MutableStateFlow(true)
            every { showAltKey } returns MutableStateFlow(true)
            every { showMetaKey } returns MutableStateFlow(true)
        }

        clipboardService = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TerminalViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf("sessionId" to "abc123def456")
        )
        return TerminalViewModel(
            savedStateHandle = savedStateHandle,
            repository = repository,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            modifierKeySettings = modifierKeySettings,
            clipboardService = clipboardService
        )
    }

    // ==========================================================================
    // Category E: ScreenState Mapping After Connection Drop
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `ScreenState shows Disconnected after connection drop`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate attached state
        terminalStateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = true,
            isAttaching = false
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.screenState.value is TerminalScreenState.Connected)

        // Connection drops: isAttached=false, no error
        terminalStateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = false,
            isAttaching = false,
            error = null
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Should show Disconnected, NOT Error
        val screenState = viewModel.screenState.value
        assertTrue(screenState is TerminalScreenState.Disconnected,
            "Expected Disconnected but got $screenState")
        assertTrue((screenState as TerminalScreenState.Disconnected).canReconnect)
    }

    @Tag("unit")
    @Test
    fun `ScreenState shows Attaching during re-attach`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate re-attaching state
        terminalStateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = false,
            isAttaching = true
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Should show Attaching
        assertTrue(viewModel.screenState.value is TerminalScreenState.Attaching)
    }

    // ==========================================================================
    // Category F: observeConnectionForReattach()
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `auto re-attach on connection restore`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate: was attached, connection dropped (repository cleared isAttached)
        terminalStateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = false,
            isAttaching = false,
            lastSequence = 42
        )
        isConnectedFlow.value = false
        connectionStateFlow.value = ConnectionLifecycleState.DISCONNECTED
        testDispatcher.scheduler.advanceUntilIdle()

        // Connection restores
        isConnectedFlow.value = true
        connectionStateFlow.value = ConnectionLifecycleState.CONNECTED
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify re-attach was called with lastSequence
        coVerify {
            repository.clearError()
            repository.attach("abc123def456", 42)
        }
    }

    @Tag("unit")
    @Test
    fun `no auto re-attach if already attached`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate: still attached (connection flap didn't clear it)
        terminalStateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = true,
            isAttaching = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Reset verify counter after initial attach() in init
        io.mockk.clearMocks(repository, answers = false)

        // Connection briefly drops and restores
        isConnectedFlow.value = false
        connectionStateFlow.value = ConnectionLifecycleState.DISCONNECTED
        testDispatcher.scheduler.advanceUntilIdle()
        isConnectedFlow.value = true
        connectionStateFlow.value = ConnectionLifecycleState.CONNECTED
        testDispatcher.scheduler.advanceUntilIdle()

        // attach() should NOT be called again (still attached)
        coVerify(exactly = 0) { repository.attach(any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `no auto re-attach if no sessionId`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Reset verify counter after initial attach() in init
        io.mockk.clearMocks(repository, answers = false)

        // Simulate: never attached (no sessionId)
        terminalStateFlow.value = TerminalState(
            sessionId = null,
            isAttached = false,
            isAttaching = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Connection drops and restores
        isConnectedFlow.value = false
        connectionStateFlow.value = ConnectionLifecycleState.DISCONNECTED
        testDispatcher.scheduler.advanceUntilIdle()
        isConnectedFlow.value = true
        connectionStateFlow.value = ConnectionLifecycleState.CONNECTED
        testDispatcher.scheduler.advanceUntilIdle()

        // attach() should NOT be called by connection observer
        coVerify(exactly = 0) { repository.attach(any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `no auto re-attach if already attaching`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate: already attaching
        terminalStateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = false,
            isAttaching = true
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Reset verify counter
        io.mockk.clearMocks(repository, answers = false)

        // Connection restores
        isConnectedFlow.value = false
        connectionStateFlow.value = ConnectionLifecycleState.DISCONNECTED
        testDispatcher.scheduler.advanceUntilIdle()
        isConnectedFlow.value = true
        connectionStateFlow.value = ConnectionLifecycleState.CONNECTED
        testDispatcher.scheduler.advanceUntilIdle()

        // attach() should NOT be called (already attaching)
        coVerify(exactly = 0) { repository.attach(any(), any()) }
    }

    @Tag("unit")
    @Test
    fun `auto re-attach failure emits ShowError`() = runTest {
        // Let initial attach in init succeed (default relaxed mock returns Unit)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Now make attach throw for the reconnect attempt
        coEvery { repository.attach(any(), any()) } throws
            TerminalAttachException("PIPE_ERROR", "Terminal pipe broken")

        // Simulate: was attached, connection dropped
        terminalStateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = false,
            isAttaching = false
        )
        isConnectedFlow.value = false
        connectionStateFlow.value = ConnectionLifecycleState.DISCONNECTED
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            // Connection restores - triggers re-attach which fails
            isConnectedFlow.value = true
            connectionStateFlow.value = ConnectionLifecycleState.CONNECTED
            testDispatcher.scheduler.advanceUntilIdle()

            // Should emit ShowError for reconnect failure
            val event = awaitItem() as TerminalUiEvent.ShowError
            assertTrue(event.message.contains("reconnect"),
                "Expected reconnect error but got: ${event.message}")
        }
    }
}
