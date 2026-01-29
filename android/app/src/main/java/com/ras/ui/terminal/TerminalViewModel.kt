package com.ras.ui.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.terminal.DEFAULT_QUICK_BUTTONS
import com.ras.data.terminal.KeyMapper
import com.ras.data.terminal.QuickButton
import com.ras.data.terminal.TerminalEvent
import com.ras.data.terminal.TerminalRepository
import com.ras.data.terminal.TerminalScreenState
import com.ras.data.terminal.TerminalState
import com.ras.data.terminal.TerminalUiEvent
import com.ras.proto.KeyType
import com.ras.settings.QuickButtonSettings
import com.ras.ui.navigation.NavArgs
import com.ras.util.ClipboardHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Terminal screen.
 *
 * Manages:
 * - Terminal attachment lifecycle
 * - Input handling (text, special keys, raw mode)
 * - Quick button configuration
 * - UI state
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TerminalRepository,
    private val buttonSettings: QuickButtonSettings
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>(NavArgs.SESSION_ID)
        ?: throw IllegalArgumentException("sessionId is required")

    // Terminal state from repository
    val terminalState: StateFlow<TerminalState> = repository.state

    // Terminal output for rendering
    val terminalOutput: SharedFlow<ByteArray> = repository.output

    // Connection state
    val isConnected: StateFlow<Boolean> = repository.isConnected

    // Screen state (derived from terminal state)
    private val _screenState = MutableStateFlow<TerminalScreenState>(TerminalScreenState.Attaching)
    val screenState: StateFlow<TerminalScreenState> = _screenState.asStateFlow()

    // Session name for display
    private val _sessionName = MutableStateFlow("Session")
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    // Quick buttons
    private val _quickButtons = MutableStateFlow(buttonSettings.getButtons())
    val quickButtons: StateFlow<List<QuickButton>> = _quickButtons.asStateFlow()

    // Input text (for line-buffered mode)
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // Paste truncation warning
    private val _pasteTruncated = MutableStateFlow(false)
    val pasteTruncated: StateFlow<Boolean> = _pasteTruncated.asStateFlow()

    // Button editor visibility
    private val _showButtonEditor = MutableStateFlow(false)
    val showButtonEditor: StateFlow<Boolean> = _showButtonEditor.asStateFlow()

    // One-time UI events
    private val _uiEvents = MutableSharedFlow<TerminalUiEvent>(extraBufferCapacity = 64)
    val uiEvents: SharedFlow<TerminalUiEvent> = _uiEvents.asSharedFlow()

    init {
        observeTerminalState()
        observeTerminalEvents()
        attach()
    }

    private fun observeTerminalState() {
        repository.state
            .onEach { state ->
                updateScreenState(state)
                // Update session name when attached
                if (state.sessionId != null) {
                    _sessionName.value = state.sessionId
                }
            }
            .launchIn(viewModelScope)
    }

    private fun updateScreenState(state: TerminalState) {
        _screenState.value = when {
            state.isAttaching -> TerminalScreenState.Attaching

            state.error != null -> TerminalScreenState.Error(
                code = state.error.code,
                message = state.error.message
            )

            state.isAttached -> TerminalScreenState.Connected(
                sessionId = state.sessionId ?: "",
                cols = state.cols,
                rows = state.rows,
                isRawMode = state.isRawMode
            )

            else -> TerminalScreenState.Disconnected(
                reason = "Not connected",
                canReconnect = true
            )
        }
    }

    private fun observeTerminalEvents() {
        repository.events
            .onEach { event -> handleTerminalEvent(event) }
            .launchIn(viewModelScope)
    }

    private fun handleTerminalEvent(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.Error -> {
                viewModelScope.launch {
                    _uiEvents.emit(TerminalUiEvent.ShowError(event.message))
                }
            }
            is TerminalEvent.OutputSkipped -> {
                viewModelScope.launch {
                    _uiEvents.emit(TerminalUiEvent.ShowOutputSkipped(event.bytesSkipped))
                }
            }
            is TerminalEvent.Detached -> {
                if (event.reason != "user_request") {
                    viewModelScope.launch {
                        _uiEvents.emit(TerminalUiEvent.ShowError("Disconnected: ${event.reason}"))
                    }
                }
            }
            else -> { /* Handled via state */ }
        }
    }

    // ==========================================================================
    // Lifecycle
    // ==========================================================================

    private fun attach() {
        viewModelScope.launch {
            try {
                repository.attach(sessionId)
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to attach: ${e.message}"))
            }
        }
    }

    /**
     * Called when screen resumes - reattach if needed.
     */
    fun onResume() {
        val state = terminalState.value
        if (state.sessionId != null && !state.isAttached && !state.isAttaching) {
            viewModelScope.launch {
                try {
                    repository.attach(sessionId, state.lastSequence)
                } catch (e: Exception) {
                    _uiEvents.emit(TerminalUiEvent.ShowError("Failed to reconnect: ${e.message}"))
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        // Use GlobalScope for cleanup that should complete even after ViewModel is destroyed.
        // This is the standard Android pattern for cleanup that:
        // 1. Should not block the main thread (runBlocking would cause ANR)
        // 2. Should outlive the ViewModel's scope (viewModelScope is cancelled here)
        // The daemon will also timeout abandoned sessions, so this is best-effort.
        GlobalScope.launch(Dispatchers.IO) {
            try {
                repository.detach()
            } catch (e: Exception) {
                // Log but don't crash - cleanup should be best-effort
                android.util.Log.w("TerminalViewModel", "Error during detach in onCleared", e)
            }
        }
    }

    // ==========================================================================
    // Input Actions
    // ==========================================================================

    /**
     * Update the input text field.
     */
    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    /**
     * Send the current input text and clear the field.
     */
    fun onSendClicked() {
        val text = _inputText.value
        if (text.isEmpty()) return

        viewModelScope.launch {
            try {
                repository.sendLine(text)
                _inputText.value = ""
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Send input to the terminal.
     */
    fun sendInput(input: String) {
        if (input.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.sendInput(input)
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Handle quick button click.
     */
    fun onQuickButtonClicked(button: QuickButton) {
        viewModelScope.launch {
            try {
                when {
                    button.keyType != null -> repository.sendSpecialKey(button.keyType)
                    button.character != null -> repository.sendInput(button.character)
                }
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Toggle raw mode.
     */
    fun onRawModeToggle() {
        repository.toggleRawMode()
    }

    /**
     * Handle raw key press (in raw mode).
     *
     * @return true if the key was handled
     */
    fun onRawKeyPress(keyCode: Int, isCtrlPressed: Boolean): Boolean {
        if (!terminalState.value.isRawMode) return false

        val bytes = KeyMapper.keyEventToBytes(keyCode, isCtrlPressed)
        if (bytes != null) {
            viewModelScope.launch {
                try {
                    repository.sendInput(bytes)
                } catch (e: Exception) {
                    _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send key: ${e.message}"))
                }
            }
            return true
        }
        return false
    }

    /**
     * Handle raw character input (in raw mode).
     */
    fun onRawCharacterInput(char: Char) {
        if (!terminalState.value.isRawMode) return

        viewModelScope.launch {
            try {
                repository.sendInput(char.toString())
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Handle paste from clipboard.
     *
     * In raw mode, sends content directly to terminal.
     * In normal mode, appends content to input field.
     *
     * Content is validated and truncated to 64KB if necessary,
     * using UTF-8 safe truncation to avoid splitting multi-byte characters.
     *
     * @param text Text content from clipboard
     */
    fun onPaste(text: String) {
        if (text.isEmpty()) return

        viewModelScope.launch {
            // Check if truncation will occur
            val willTruncate = ClipboardHelper.wouldTruncate(text)
            if (willTruncate) {
                _pasteTruncated.value = true
            }

            val state = terminalState.value
            if (state.isRawMode) {
                // Raw mode: encode, validate, and send directly
                val bytes = ClipboardHelper.prepareForTerminal(text) ?: return@launch
                try {
                    repository.sendInput(bytes)
                } catch (e: Exception) {
                    _uiEvents.emit(TerminalUiEvent.ShowError("Failed to paste: ${e.message}"))
                }
            } else {
                // Normal mode: append to input field
                _inputText.update { it + text }
            }
        }
    }

    /**
     * Clear the paste truncation warning.
     */
    fun dismissPasteTruncated() {
        _pasteTruncated.value = false
    }

    /**
     * Send approval response (Y).
     */
    fun approve() {
        viewModelScope.launch {
            try {
                repository.sendInput("y")
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Send rejection response (N).
     */
    fun reject() {
        viewModelScope.launch {
            try {
                repository.sendInput("n")
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Send cancel (Ctrl+C).
     */
    fun cancel() {
        viewModelScope.launch {
            try {
                repository.sendSpecialKey(KeyType.KEY_CTRL_C)
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    // ==========================================================================
    // Quick Button Editor
    // ==========================================================================

    fun openButtonEditor() {
        _showButtonEditor.value = true
    }

    fun closeButtonEditor() {
        _showButtonEditor.value = false
    }

    fun updateQuickButtons(buttons: List<QuickButton>) {
        buttonSettings.saveButtons(buttons)
        _quickButtons.value = buttons
        _showButtonEditor.value = false
    }

    fun resetQuickButtonsToDefault() {
        updateQuickButtons(DEFAULT_QUICK_BUTTONS)
    }

    // ==========================================================================
    // Error Handling
    // ==========================================================================

    fun clearError() {
        repository.clearError()
    }

    fun dismissOutputSkipped() {
        repository.clearOutputSkipped()
    }

    fun reconnect() {
        attach()
    }
}
