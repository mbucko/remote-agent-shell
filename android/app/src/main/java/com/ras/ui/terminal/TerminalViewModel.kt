package com.ras.ui.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    // Dependencies will be injected in Phase 6d
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>(NavArgs.SESSION_ID)
        ?: throw IllegalArgumentException("sessionId is required")

    private val _sessionName = MutableStateFlow("Session")
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    init {
        attachToSession()
    }

    private fun attachToSession() {
        viewModelScope.launch {
            // TODO: Attach to session via repository in Phase 6d
            _sessionName.value = "my-project"
        }
    }

    /**
     * Send input to the terminal.
     * TODO: Implement in Phase 6d
     */
    fun sendInput(input: String) {
        // Will be implemented in Phase 6d
    }

    /**
     * Send approval response.
     * TODO: Implement in Phase 6d
     */
    fun approve() {
        sendInput("y")
    }

    /**
     * Send rejection response.
     * TODO: Implement in Phase 6d
     */
    fun reject() {
        sendInput("n")
    }

    /**
     * Send cancel (Escape).
     * TODO: Implement in Phase 6d
     */
    fun cancel() {
        // Send Escape key
    }
}
