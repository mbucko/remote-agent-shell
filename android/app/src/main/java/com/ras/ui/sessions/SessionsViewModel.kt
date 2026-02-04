package com.ras.ui.sessions

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionsScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SessionsViewModel"

/**
 * ViewModel for the Sessions screen.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val credentialRepository: CredentialRepository,
    private val sessionRepository: SessionRepository,
    private val keyManager: KeyManager,
    private val connectionManager: ConnectionManager
) : ViewModel() {

    private val deviceId: String = savedStateHandle["deviceId"]
        ?: throw IllegalArgumentException("deviceId is required")

    private val _screenState = MutableStateFlow<SessionsScreenState>(SessionsScreenState.Loaded(emptyList()))
    val screenState: StateFlow<SessionsScreenState> = _screenState.asStateFlow()

    // Dialog states
    private val _showKillDialog = MutableStateFlow<SessionInfo?>(null)
    val showKillDialog: StateFlow<SessionInfo?> = _showKillDialog.asStateFlow()

    private val _showRenameDialog = MutableStateFlow<SessionInfo?>(null)
    val showRenameDialog: StateFlow<SessionInfo?> = _showRenameDialog.asStateFlow()

    // One-time UI events using Channel for guaranteed single delivery
    // Industry standard: Channel prevents event duplication and guarantees consumption
    private val _uiEvents = Channel<SessionsUiEvent>(Channel.BUFFERED)
    val uiEvents = _uiEvents.receiveAsFlow()

    // Connection status
    val isConnected: StateFlow<Boolean> = sessionRepository.isConnected

    // Connection path for diagram visualization
    val connectionPath = connectionManager.connectionPath

    init {
        Log.i(TAG, "SessionsViewModel created for deviceId=$deviceId, isConnected=${sessionRepository.isConnected.value}")
        observeSessions()
        observeRepositoryEvents()
        // Ensure this device is selected before loading sessions
        viewModelScope.launch {
            credentialRepository.setSelectedDevice(deviceId)
            loadSessions()
        }
    }

    private fun observeSessions() {
        viewModelScope.launch {
            Log.i(TAG, "Starting to observe sessions from repository")
            sessionRepository.sessions.collect { sessions ->
                Log.i(TAG, "Received ${sessions.size} sessions from repository")
                // Use atomic update to preserve isRefreshing flag across concurrent updates
                _screenState.update { currentState ->
                    Log.i(TAG, "Current screen state: ${currentState::class.simpleName}")
                    val isRefreshing = (currentState as? SessionsScreenState.Loaded)?.isRefreshing ?: false
                    SessionsScreenState.Loaded(
                        sessions = sessions.sortedByDescending { it.lastActivityAt },
                        isRefreshing = isRefreshing
                    ).also {
                        Log.i(TAG, "Updated screen state to Loaded with ${sessions.size} sessions")
                    }
                }
            }
        }
    }

    private fun observeRepositoryEvents() {
        viewModelScope.launch {
            sessionRepository.events.collect { event ->
                when (event) {
                    is SessionEvent.SessionCreated -> {
                        _uiEvents.send(SessionsUiEvent.SessionCreated(event.session.displayText))
                    }
                    is SessionEvent.SessionKilled -> {
                        _uiEvents.send(SessionsUiEvent.SessionKilled)
                    }
                    is SessionEvent.SessionRenamed -> {
                        _uiEvents.send(SessionsUiEvent.SessionRenamed(event.newName))
                    }
                    is SessionEvent.SessionError -> {
                        _uiEvents.send(SessionsUiEvent.Error(event.message))
                    }
                    is SessionEvent.SessionActivity -> {
                        // Activity updates are reflected in the session list automatically
                    }
                    is SessionEvent.AgentsLoaded -> {
                        // Agents loaded - used by create session screen
                    }
                    is SessionEvent.DirectoriesLoaded -> {
                        // Directories loaded - used by create session screen
                    }
                }
            }
        }
    }

    private fun loadSessions() {
        viewModelScope.launch {
            // Set refreshing state while loading
            _screenState.update { currentState ->
                when (currentState) {
                    is SessionsScreenState.Loaded -> {
                        Log.i(TAG, "loadSessions: setting isRefreshing=true")
                        currentState.copy(isRefreshing = true)
                    }
                    else -> {
                        Log.i(TAG, "loadSessions: state is $currentState, setting to Loaded(empty, refreshing)")
                        SessionsScreenState.Loaded(emptyList(), isRefreshing = true)
                    }
                }
            }

            try {
                sessionRepository.listSessions()
                Log.i(TAG, "loadSessions: listSessions command sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "loadSessions: error - ${e.message}")
                _screenState.update {
                    SessionsScreenState.Error(e.message ?: "Failed to load sessions")
                }
            }
        }
    }

    /**
     * Refresh the session list.
     */
    fun refreshSessions() {
        Log.i(TAG, "refreshSessions() called")
        viewModelScope.launch {
            Log.i(TAG, "refreshSessions: starting refresh")
            // Atomic update to set refreshing = true
            _screenState.update { currentState ->
                if (currentState is SessionsScreenState.Loaded) {
                    currentState.copy(isRefreshing = true)
                } else {
                    currentState
                }
            }
            try {
                Log.i(TAG, "refreshSessions: calling listSessions()")
                sessionRepository.listSessions()
                Log.i(TAG, "refreshSessions: listSessions() completed")
            } catch (e: Exception) {
                Log.e(TAG, "refreshSessions: error", e)
            } finally {
                // Atomic update to set refreshing = false
                _screenState.update { currentState ->
                    if (currentState is SessionsScreenState.Loaded) {
                        currentState.copy(isRefreshing = false)
                    } else {
                        currentState
                    }
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
        _showKillDialog.value = null  // Dismiss dialog immediately for responsiveness
        viewModelScope.launch {
            try {
                sessionRepository.killSession(session.id)
            } catch (e: Exception) {
                _uiEvents.send(SessionsUiEvent.Error("Failed to kill session: ${e.message}"))
            }
        }
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
        _showRenameDialog.value = null  // Dismiss dialog immediately for responsiveness
        viewModelScope.launch {
            try {
                sessionRepository.renameSession(session.id, newName)
            } catch (e: Exception) {
                _uiEvents.send(SessionsUiEvent.Error("Failed to rename session: ${e.message}"))
            }
        }
    }

    /**
     * Disconnect from the daemon and navigate to disconnected screen.
     *
     * Sends a graceful disconnect message to the daemon first, allowing it
     * to clean up immediately (e.g., resize terminal windows).
     */
    fun disconnect(onNavigate: () -> Unit) {
        viewModelScope.launch {
            // Set disconnected flag BEFORE closing transport to prevent reconnection race.
            // When transport closes, it triggers ConnectionError which ReconnectionController
            // reacts to after a 1 second delay. If we set the flag after disconnecting,
            // the flag check races with the reconnection attempt.
            keyManager.setDisconnected(true)
            connectionManager.disconnectGracefully("user_request")
            onNavigate()
        }
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
