package com.ras.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionsScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Sessions screen.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _screenState = MutableStateFlow<SessionsScreenState>(SessionsScreenState.Loading)
    val screenState: StateFlow<SessionsScreenState> = _screenState.asStateFlow()

    // Dialog states
    private val _showKillDialog = MutableStateFlow<SessionInfo?>(null)
    val showKillDialog: StateFlow<SessionInfo?> = _showKillDialog.asStateFlow()

    private val _showRenameDialog = MutableStateFlow<SessionInfo?>(null)
    val showRenameDialog: StateFlow<SessionInfo?> = _showRenameDialog.asStateFlow()

    // One-time UI events
    private val _uiEvents = MutableSharedFlow<SessionsUiEvent>(extraBufferCapacity = 64)
    val uiEvents: SharedFlow<SessionsUiEvent> = _uiEvents.asSharedFlow()

    // Connection status
    val isConnected: StateFlow<Boolean> = sessionRepository.isConnected

    init {
        observeSessions()
        observeRepositoryEvents()
        loadSessions()
    }

    private fun observeSessions() {
        viewModelScope.launch {
            sessionRepository.sessions.collect { sessions ->
                val currentState = _screenState.value
                val isRefreshing = (currentState as? SessionsScreenState.Loaded)?.isRefreshing ?: false
                _screenState.value = SessionsScreenState.Loaded(
                    sessions = sessions.sortedByDescending { it.lastActivityAt },
                    isRefreshing = isRefreshing
                )
            }
        }
    }

    private fun observeRepositoryEvents() {
        viewModelScope.launch {
            sessionRepository.events.collect { event ->
                when (event) {
                    is SessionEvent.SessionCreated -> {
                        _uiEvents.emit(SessionsUiEvent.SessionCreated(event.session.displayText))
                    }
                    is SessionEvent.SessionKilled -> {
                        _uiEvents.emit(SessionsUiEvent.SessionKilled)
                    }
                    is SessionEvent.SessionRenamed -> {
                        _uiEvents.emit(SessionsUiEvent.SessionRenamed(event.newName))
                    }
                    is SessionEvent.SessionError -> {
                        _uiEvents.emit(SessionsUiEvent.Error(event.message))
                    }
                    is SessionEvent.SessionActivity -> {
                        // Activity updates are reflected in the session list automatically
                    }
                }
            }
        }
    }

    private fun loadSessions() {
        viewModelScope.launch {
            _screenState.value = SessionsScreenState.Loading
            try {
                sessionRepository.listSessions()
            } catch (e: Exception) {
                _screenState.value = SessionsScreenState.Error(
                    e.message ?: "Failed to load sessions"
                )
            }
        }
    }

    /**
     * Refresh the session list.
     */
    fun refreshSessions() {
        viewModelScope.launch {
            val currentState = _screenState.value
            if (currentState is SessionsScreenState.Loaded) {
                _screenState.value = currentState.copy(isRefreshing = true)
            }
            try {
                sessionRepository.listSessions()
            } finally {
                val state = _screenState.value
                if (state is SessionsScreenState.Loaded) {
                    _screenState.value = state.copy(isRefreshing = false)
                }
            }
        }
    }

    /**
     * Show the kill confirmation dialog for a session.
     */
    fun showKillDialog(session: SessionInfo) {
        _showKillDialog.value = session
    }

    /**
     * Dismiss the kill confirmation dialog.
     */
    fun dismissKillDialog() {
        _showKillDialog.value = null
    }

    /**
     * Confirm killing a session.
     */
    fun confirmKillSession() {
        val session = _showKillDialog.value ?: return
        viewModelScope.launch {
            try {
                sessionRepository.killSession(session.id)
            } catch (e: Exception) {
                _uiEvents.emit(SessionsUiEvent.Error("Failed to kill session: ${e.message}"))
            }
        }
        _showKillDialog.value = null
    }

    /**
     * Show the rename dialog for a session.
     */
    fun showRenameDialog(session: SessionInfo) {
        _showRenameDialog.value = session
    }

    /**
     * Dismiss the rename dialog.
     */
    fun dismissRenameDialog() {
        _showRenameDialog.value = null
    }

    /**
     * Confirm renaming a session.
     */
    fun confirmRenameSession(newName: String) {
        val session = _showRenameDialog.value ?: return
        viewModelScope.launch {
            try {
                sessionRepository.renameSession(session.id, newName)
            } catch (e: Exception) {
                _uiEvents.emit(SessionsUiEvent.Error("Failed to rename session: ${e.message}"))
            }
        }
        _showRenameDialog.value = null
    }
}

/**
 * One-time UI events emitted by SessionsViewModel.
 */
sealed class SessionsUiEvent {
    data class SessionCreated(val name: String) : SessionsUiEvent()
    data object SessionKilled : SessionsUiEvent()
    data class SessionRenamed(val newName: String) : SessionsUiEvent()
    data class Error(val message: String) : SessionsUiEvent()
}
