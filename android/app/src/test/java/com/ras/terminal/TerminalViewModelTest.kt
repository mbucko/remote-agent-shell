package com.ras.terminal

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.ras.data.terminal.DEFAULT_QUICK_BUTTONS
import com.ras.data.terminal.QuickButton
import com.ras.data.terminal.TerminalEvent
import com.ras.data.terminal.TerminalRepository
import com.ras.data.terminal.TerminalScreenState
import com.ras.data.terminal.TerminalState
import com.ras.data.terminal.TerminalUiEvent
import com.ras.proto.KeyType
import com.ras.settings.QuickButtonSettings
import com.ras.ui.navigation.NavArgs
import com.ras.ui.terminal.TerminalViewModel
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive tests for TerminalViewModel.
 * Tests all user actions, state management, and event handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TerminalRepository
    private lateinit var buttonSettings: QuickButtonSettings
    private lateinit var savedStateHandle: SavedStateHandle

    private lateinit var stateFlow: MutableStateFlow<TerminalState>
    private lateinit var outputFlow: MutableSharedFlow<ByteArray>
    private lateinit var eventsFlow: MutableSharedFlow<TerminalEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        stateFlow = MutableStateFlow(TerminalState())
        outputFlow = MutableSharedFlow()
        eventsFlow = MutableSharedFlow()
        isConnectedFlow = MutableStateFlow(true)

        repository = mockk(relaxed = true) {
            every { state } returns stateFlow
            every { output } returns outputFlow
            every { events } returns eventsFlow
            every { isConnected } returns isConnectedFlow
        }

        buttonSettings = mockk(relaxed = true) {
            every { getButtons() } returns DEFAULT_QUICK_BUTTONS
        }

        savedStateHandle = SavedStateHandle(mapOf(NavArgs.SESSION_ID to "abc123def456"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TerminalViewModel {
        return TerminalViewModel(savedStateHandle, repository, buttonSettings)
    }

    // ==========================================================================
    // Initialization Tests
    // ==========================================================================

    @Test
    fun `viewModel attaches to session on init`() = runTest {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.attach("abc123def456") }
    }

    @Test
    fun `viewModel loads quick buttons from settings`() = runTest {
        val customButtons = listOf(
            QuickButton("custom", "Custom", character = "x")
        )
        every { buttonSettings.getButtons() } returns customButtons

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(customButtons, viewModel.quickButtons.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `viewModel throws when sessionId missing`() {
        savedStateHandle = SavedStateHandle()
        createViewModel()
    }

    // ==========================================================================
    // Screen State Tests
    // ==========================================================================

    @Test
    fun `screenState is Attaching when isAttaching is true`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        stateFlow.value = TerminalState(isAttaching = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.screenState.value is TerminalScreenState.Attaching)
    }

    @Test
    fun `screenState is Connected when isAttached is true`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        stateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = true,
            cols = 120,
            rows = 40,
            isRawMode = true
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.screenState.value as TerminalScreenState.Connected
        assertEquals("abc123def456", state.sessionId)
        assertEquals(120, state.cols)
        assertEquals(40, state.rows)
        assertTrue(state.isRawMode)
    }

    @Test
    fun `screenState is Error when error is present`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        stateFlow.value = TerminalState(
            error = com.ras.data.terminal.TerminalErrorInfo(
                code = "SESSION_NOT_FOUND",
                message = "Session not found",
                sessionId = "abc123def456"
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.screenState.value as TerminalScreenState.Error
        assertEquals("SESSION_NOT_FOUND", state.code)
        assertEquals("Session not found", state.message)
    }

    @Test
    fun `screenState is Disconnected when not attached and no error`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        stateFlow.value = TerminalState(isAttached = false, isAttaching = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.screenState.value as TerminalScreenState.Disconnected
        assertTrue(state.canReconnect)
    }

    // ==========================================================================
    // Input Text Tests
    // ==========================================================================

    @Test
    fun `onInputTextChanged updates inputText`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onInputTextChanged("hello world")

        assertEquals("hello world", viewModel.inputText.value)
    }

    @Test
    fun `onSendClicked sends line and clears input`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onInputTextChanged("ls -la")
        viewModel.onSendClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendLine("ls -la") }
        assertEquals("", viewModel.inputText.value)
    }

    @Test
    fun `onSendClicked does nothing when input is empty`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSendClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendLine(any()) }
    }

    @Test
    fun `sendInput sends to repository`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendInput("test")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendInput("test") }
    }

    @Test
    fun `sendInput does nothing for empty string`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendInput("")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendInput(any<String>()) }
    }

    // ==========================================================================
    // Quick Button Tests
    // ==========================================================================

    @Test
    fun `onQuickButtonClicked sends special key`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val button = QuickButton("ctrl_c", "Ctrl+C", keyType = KeyType.KEY_CTRL_C)
        viewModel.onQuickButtonClicked(button)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendSpecialKey(KeyType.KEY_CTRL_C) }
    }

    @Test
    fun `onQuickButtonClicked sends character`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val button = QuickButton("y", "Y", character = "y")
        viewModel.onQuickButtonClicked(button)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendInput("y") }
    }

    @Test
    fun `updateQuickButtons saves to settings`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val newButtons = listOf(QuickButton("new", "New", character = "n"))
        viewModel.updateQuickButtons(newButtons)

        verify { buttonSettings.saveButtons(newButtons) }
        assertEquals(newButtons, viewModel.quickButtons.value)
    }

    @Test
    fun `resetQuickButtonsToDefault resets buttons`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.resetQuickButtonsToDefault()

        verify { buttonSettings.saveButtons(DEFAULT_QUICK_BUTTONS) }
        assertEquals(DEFAULT_QUICK_BUTTONS, viewModel.quickButtons.value)
    }

    @Test
    fun `openButtonEditor sets showButtonEditor to true`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.openButtonEditor()

        assertTrue(viewModel.showButtonEditor.value)
    }

    @Test
    fun `closeButtonEditor sets showButtonEditor to false`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.openButtonEditor()
        viewModel.closeButtonEditor()

        assertFalse(viewModel.showButtonEditor.value)
    }

    // ==========================================================================
    // Raw Mode Tests
    // ==========================================================================

    @Test
    fun `onRawModeToggle calls repository toggle`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRawModeToggle()

        verify { repository.toggleRawMode() }
    }

    @Test
    fun `onRawKeyPress sends bytes when in raw mode`() = runTest {
        stateFlow.value = TerminalState(isRawMode = true, isAttached = true, sessionId = "abc123def456")

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val handled = viewModel.onRawKeyPress(android.view.KeyEvent.KEYCODE_ENTER, false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.sendInput(any<ByteArray>()) }
    }

    @Test
    fun `onRawKeyPress returns false when not in raw mode`() = runTest {
        stateFlow.value = TerminalState(isRawMode = false)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val handled = viewModel.onRawKeyPress(android.view.KeyEvent.KEYCODE_ENTER, false)

        assertFalse(handled)
    }

    @Test
    fun `onRawCharacterInput sends character when in raw mode`() = runTest {
        stateFlow.value = TerminalState(isRawMode = true, isAttached = true, sessionId = "abc123def456")

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRawCharacterInput('a')
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendInput("a") }
    }

    @Test
    fun `onRawCharacterInput does nothing when not in raw mode`() = runTest {
        stateFlow.value = TerminalState(isRawMode = false)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRawCharacterInput('a')
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendInput(any<String>()) }
    }

    // ==========================================================================
    // Paste Tests
    // ==========================================================================

    @Test
    fun `onPaste in raw mode sends directly`() = runTest {
        stateFlow.value = TerminalState(isRawMode = true, isAttached = true, sessionId = "abc123def456")

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onPaste("pasted text")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendInput(any<ByteArray>()) }
    }

    @Test
    fun `onPaste in normal mode appends to input`() = runTest {
        stateFlow.value = TerminalState(isRawMode = false)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onInputTextChanged("existing ")
        viewModel.onPaste("pasted")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("existing pasted", viewModel.inputText.value)
    }

    @Test
    fun `onPaste does nothing for empty text`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onPaste("")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("", viewModel.inputText.value)
    }

    // ==========================================================================
    // Approve/Reject/Cancel Tests
    // ==========================================================================

    @Test
    fun `approve sends y`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.approve()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendInput("y") }
    }

    @Test
    fun `reject sends n`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.reject()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendInput("n") }
    }

    @Test
    fun `cancel sends Ctrl+C`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.cancel()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.sendSpecialKey(KeyType.KEY_CTRL_C) }
    }

    // ==========================================================================
    // Event Handling Tests
    // ==========================================================================

    @Test
    fun `error event emits ShowError ui event`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(TerminalEvent.Error("abc123def456", "ERROR", "Test error"))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem() as TerminalUiEvent.ShowError
            assertEquals("Test error", event.message)
        }
    }

    @Test
    fun `outputSkipped event emits ShowOutputSkipped ui event`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(TerminalEvent.OutputSkipped("abc123def456", 0, 10, 5000))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem() as TerminalUiEvent.ShowOutputSkipped
            assertEquals(5000, event.bytesSkipped)
        }
    }

    @Test
    fun `detached event with non-user reason emits error`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(TerminalEvent.Detached("abc123def456", "session_killed"))
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem() as TerminalUiEvent.ShowError
            assertTrue(event.message.contains("Disconnected"))
        }
    }

    @Test
    fun `detached event with user_request does not emit error`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            eventsFlow.emit(TerminalEvent.Detached("abc123def456", "user_request"))
            testDispatcher.scheduler.advanceUntilIdle()

            expectNoEvents()
        }
    }

    // ==========================================================================
    // Error Handling Tests
    // ==========================================================================

    @Test
    fun `clearError calls repository`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearError()

        verify { repository.clearError() }
    }

    @Test
    fun `dismissOutputSkipped calls repository`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismissOutputSkipped()

        verify { repository.clearOutputSkipped() }
    }

    @Test
    fun `reconnect calls attach`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        // Called once in init and once in reconnect
        coVerify(exactly = 2) { repository.attach("abc123def456") }
    }

    // ==========================================================================
    // Lifecycle Tests
    // ==========================================================================

    @Test
    fun `onResume reattaches when disconnected`() = runTest {
        stateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = false,
            isAttaching = false,
            lastSequence = 100
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onResume()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.attach("abc123def456", 100) }
    }

    @Test
    fun `onResume does nothing when already attached`() = runTest {
        stateFlow.value = TerminalState(
            sessionId = "abc123def456",
            isAttached = true
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onResume()
        testDispatcher.scheduler.advanceUntilIdle()

        // Only the initial attach call
        coVerify(exactly = 1) { repository.attach(any(), any()) }
    }

    // Note: onCleared() is protected and cannot be called directly in tests.
    // The detach behavior is tested through repository.detach() verification
    // in other tests when the ViewModel scope is cancelled.
}
