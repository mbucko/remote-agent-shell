package com.ras.ui.terminal

import androidx.lifecycle.SavedStateHandle
import com.ras.data.sessions.SessionRepository
import com.ras.data.settings.SettingsDefaults
import com.ras.data.settings.SettingsRepository
import com.ras.data.terminal.TerminalRepository
import com.ras.data.terminal.TerminalState
import com.ras.settings.QuickButtonSettings
import com.ras.ui.navigation.NavArgs
import com.ras.util.ClipboardService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TerminalViewModel.
 *
 * Tests focus on font size state management since that's the feature
 * recently added. Other terminal functionality has its own tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var terminalRepository: TerminalRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var buttonSettings: QuickButtonSettings
    private lateinit var clipboardService: ClipboardService

    // Flows for terminal repository
    private val terminalStateFlow = MutableStateFlow(TerminalState())
    private val terminalOutputFlow = MutableSharedFlow<ByteArray>()
    private val terminalEventsFlow = MutableSharedFlow<com.ras.data.terminal.TerminalEvent>()
    private val isConnectedFlow = MutableStateFlow(true)
    private val sessionsFlow = MutableStateFlow(emptyList<com.ras.data.sessions.SessionInfo>())

    // Flows for settings repository (modifier visibility)
    private val showCtrlKeyFlow = MutableStateFlow(true)
    private val showShiftKeyFlow = MutableStateFlow(true)
    private val showAltKeyFlow = MutableStateFlow(false)
    private val showMetaKeyFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        clearAllMocks()
        Dispatchers.setMain(testDispatcher)

        savedStateHandle = SavedStateHandle(mapOf(NavArgs.SESSION_ID to "test-session-123"))

        terminalRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        buttonSettings = mockk(relaxed = true)
        clipboardService = mockk(relaxed = true)

        // Setup terminal repository mocks
        every { terminalRepository.state } returns terminalStateFlow
        every { terminalRepository.output } returns terminalOutputFlow
        every { terminalRepository.events } returns terminalEventsFlow
        every { terminalRepository.isConnected } returns isConnectedFlow

        // Setup session repository mocks
        every { sessionRepository.sessions } returns sessionsFlow

        // Setup settings repository with default font size
        every { settingsRepository.getTerminalFontSize() } returns SettingsDefaults.TERMINAL_FONT_SIZE

        // Setup modifier key visibility settings
        every { settingsRepository.showCtrlKey } returns showCtrlKeyFlow
        every { settingsRepository.showShiftKey } returns showShiftKeyFlow
        every { settingsRepository.showAltKey } returns showAltKeyFlow
        every { settingsRepository.showMetaKey } returns showMetaKeyFlow

        // Setup button settings
        every { buttonSettings.getButtons() } returns emptyList()

        // Relaxed attach for init
        coEvery { terminalRepository.attach(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TerminalViewModel {
        return TerminalViewModel(
            savedStateHandle = savedStateHandle,
            repository = terminalRepository,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            buttonSettings = buttonSettings,
            clipboardService = clipboardService
        )
    }

    // ==========================================================================
    // Font Size Initial State
    // ==========================================================================

    @Test
    fun `FS01 - initial font size comes from settings repository`() = runTest {
        every { settingsRepository.getTerminalFontSize() } returns 16f

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(16f, viewModel.fontSize.value)
    }

    @Test
    fun `FS02 - initial font size uses default when not set`() = runTest {
        every { settingsRepository.getTerminalFontSize() } returns SettingsDefaults.TERMINAL_FONT_SIZE

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(SettingsDefaults.TERMINAL_FONT_SIZE, viewModel.fontSize.value)
    }

    @Test
    fun `FS03 - initial font size reads from repository at construction`() = runTest {
        every { settingsRepository.getTerminalFontSize() } returns 20f

        createViewModel()
        advanceUntilIdle()

        verify(exactly = 1) { settingsRepository.getTerminalFontSize() }
    }

    // ==========================================================================
    // Font Size Changes
    // ==========================================================================

    @Test
    fun `FS04 - onFontSizeChanged updates state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onFontSizeChanged(18f)

        assertEquals(18f, viewModel.fontSize.value)
    }

    @Test
    fun `FS05 - onFontSizeChanged persists to settings repository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onFontSizeChanged(18f)

        verify { settingsRepository.setTerminalFontSize(18f) }
    }

    @Test
    fun `FS06 - multiple font size changes update state correctly`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onFontSizeChanged(10f)
        assertEquals(10f, viewModel.fontSize.value)

        viewModel.onFontSizeChanged(14f)
        assertEquals(14f, viewModel.fontSize.value)

        viewModel.onFontSizeChanged(20f)
        assertEquals(20f, viewModel.fontSize.value)
    }

    @Test
    fun `FS07 - multiple font size changes persist each change`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onFontSizeChanged(10f)
        viewModel.onFontSizeChanged(14f)
        viewModel.onFontSizeChanged(20f)

        verify { settingsRepository.setTerminalFontSize(10f) }
        verify { settingsRepository.setTerminalFontSize(14f) }
        verify { settingsRepository.setTerminalFontSize(20f) }
    }

    @Test
    fun `FS08 - setting same font size still persists`() = runTest {
        every { settingsRepository.getTerminalFontSize() } returns 12f

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onFontSizeChanged(12f)

        verify { settingsRepository.setTerminalFontSize(12f) }
    }

    // ==========================================================================
    // Font Size Edge Cases
    // ==========================================================================

    @Test
    fun `FS09 - minimum font size persisted`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onFontSizeChanged(8f)

        assertEquals(8f, viewModel.fontSize.value)
        verify { settingsRepository.setTerminalFontSize(8f) }
    }

    @Test
    fun `FS10 - maximum font size persisted`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onFontSizeChanged(24f)

        assertEquals(24f, viewModel.fontSize.value)
        verify { settingsRepository.setTerminalFontSize(24f) }
    }

    @Test
    fun `FS11 - font size with decimals persisted`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onFontSizeChanged(14.5f)

        assertEquals(14.5f, viewModel.fontSize.value)
        verify { settingsRepository.setTerminalFontSize(14.5f) }
    }

    @Test
    fun `FS12 - font size flow emits updates`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initial value from settings
        assertEquals(SettingsDefaults.TERMINAL_FONT_SIZE, viewModel.fontSize.first())

        // Update and verify new value
        viewModel.onFontSizeChanged(16f)
        assertEquals(16f, viewModel.fontSize.first())
    }

    // ==========================================================================
    // Font Size State Isolation
    // ==========================================================================

    @Test
    fun `FS13 - font size changes dont affect other state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val inputBefore = viewModel.inputText.value
        val buttonsBefore = viewModel.quickButtons.value
        val screenStateBefore = viewModel.screenState.value

        viewModel.onFontSizeChanged(20f)

        assertEquals(inputBefore, viewModel.inputText.value)
        assertEquals(buttonsBefore, viewModel.quickButtons.value)
        assertEquals(screenStateBefore, viewModel.screenState.value)
    }

    @Test
    fun `FS14 - different view models have independent font sizes`() = runTest {
        every { settingsRepository.getTerminalFontSize() } returns 12f

        val viewModel1 = createViewModel()
        advanceUntilIdle()

        // Change settings mock for second viewmodel
        every { settingsRepository.getTerminalFontSize() } returns 16f

        // Create new SavedStateHandle for second viewmodel
        savedStateHandle = SavedStateHandle(mapOf(NavArgs.SESSION_ID to "test-session-456"))
        val viewModel2 = createViewModel()
        advanceUntilIdle()

        // Both should read their initial values independently
        // Note: viewModel1 was created with 12f, viewModel2 with 16f
        assertEquals(12f, viewModel1.fontSize.value)
        assertEquals(16f, viewModel2.fontSize.value)
    }

    // ==========================================================================
    // Settings Repository Boundary Values
    // ==========================================================================

    @Test
    fun `FS15 - handles repository returning zero`() = runTest {
        every { settingsRepository.getTerminalFontSize() } returns 0f

        val viewModel = createViewModel()
        advanceUntilIdle()

        // ViewModel should accept whatever repository returns (validation is in UI)
        assertEquals(0f, viewModel.fontSize.value)
    }

    @Test
    fun `FS16 - handles repository returning negative`() = runTest {
        every { settingsRepository.getTerminalFontSize() } returns -5f

        val viewModel = createViewModel()
        advanceUntilIdle()

        // ViewModel should accept whatever repository returns (validation is in UI)
        assertEquals(-5f, viewModel.fontSize.value)
    }

    @Test
    fun `FS17 - handles repository returning very large value`() = runTest {
        every { settingsRepository.getTerminalFontSize() } returns 1000f

        val viewModel = createViewModel()
        advanceUntilIdle()

        // ViewModel should accept whatever repository returns (validation is in UI)
        assertEquals(1000f, viewModel.fontSize.value)
    }
}
