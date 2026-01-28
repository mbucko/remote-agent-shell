package com.ras.ui.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.ui.navigation.NavArgs
import com.ras.util.ClipboardHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    // Dependencies will be injected in Phase 10c
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>(NavArgs.SESSION_ID)
        ?: throw IllegalArgumentException("sessionId is required")

    private val _sessionName = MutableStateFlow("Session")
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private val _isRawMode = MutableStateFlow(false)
    val isRawMode: StateFlow<Boolean> = _isRawMode.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _pasteTruncated = MutableStateFlow(false)
    val pasteTruncated: StateFlow<Boolean> = _pasteTruncated.asStateFlow()

    init {
        attachToSession()
    }

    private fun attachToSession() {
        viewModelScope.launch {
            // TODO: Attach to session via repository in Phase 10c
            _sessionName.value = "my-project"
        }
    }

    /**
     * Send raw bytes to the terminal.
     * TODO: Implement in Phase 10c
     */
    private fun sendBytes(data: ByteArray) {
        // Will be implemented in Phase 10c
    }

    /**
     * Send input to the terminal.
     * TODO: Implement in Phase 10c
     */
    fun sendInput(input: String) {
        // Will be implemented in Phase 10c
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

            if (_isRawMode.value) {
                // Raw mode: encode, validate, and send directly
                val bytes = ClipboardHelper.prepareForTerminal(text) ?: return@launch
                sendBytes(bytes)
            } else {
                // Normal mode: append to input field
                // For input field, we just append the text (truncation only matters on send)
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
     * Update input text field.
     */
    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    /**
     * Toggle between raw mode and normal mode.
     */
    fun onRawModeToggle() {
        _isRawMode.update { !it }
    }

    /**
     * Send approval response.
     * TODO: Implement in Phase 10c
     */
    fun approve() {
        sendInput("y")
    }

    /**
     * Send rejection response.
     * TODO: Implement in Phase 10c
     */
    fun reject() {
        sendInput("n")
    }

    /**
     * Send cancel (Escape).
     * TODO: Implement in Phase 10c
     */
    fun cancel() {
        // Send Escape key
    }
}
