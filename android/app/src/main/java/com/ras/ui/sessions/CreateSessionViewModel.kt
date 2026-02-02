package com.ras.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.sessions.AgentInfo
import com.ras.data.sessions.AgentNameValidator
import com.ras.data.sessions.AgentsListState
import com.ras.data.sessions.CreateSessionState
import com.ras.data.sessions.DirectoryBrowserState
import com.ras.data.sessions.DirectoryEntryInfo
import com.ras.data.sessions.DirectoryPathValidator
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Create Session wizard.
 */
@HiltViewModel
class CreateSessionViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    // Wizard state
    private val _createState = MutableStateFlow<CreateSessionState>(CreateSessionState.Idle)
    val createState: StateFlow<CreateSessionState> = _createState.asStateFlow()

    // Directory browser state
    private val _directoryState = MutableStateFlow<DirectoryBrowserState>(DirectoryBrowserState.Loading)
    val directoryState: StateFlow<DirectoryBrowserState> = _directoryState.asStateFlow()

    // Agents state
    private val _agentsState = MutableStateFlow<AgentsListState>(AgentsListState.Loading)
    val agentsState: StateFlow<AgentsListState> = _agentsState.asStateFlow()

    // Current selections
    private val _selectedDirectory = MutableStateFlow<String?>(null)
    val selectedDirectory: StateFlow<String?> = _selectedDirectory.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent: StateFlow<String?> = _selectedAgent.asStateFlow()

    // Current directory path in browser
    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    // Navigation path history for back navigation
    private val pathHistory = mutableListOf<String>()

    // Recent directories
    private val _recentDirectories = MutableStateFlow<List<String>>(emptyList())
    val recentDirectories: StateFlow<List<String>> = _recentDirectories.asStateFlow()

    // One-time UI events using Channel for guaranteed single delivery
    private val _uiEvents = Channel<CreateSessionUiEvent>(Channel.BUFFERED)
    val uiEvents = _uiEvents.receiveAsFlow()

    init {
        observeRepositoryEvents()
        loadInitialData()
    }

    private fun observeRepositoryEvents() {
        viewModelScope.launch {
            sessionRepository.events.collect { event ->
                when (event) {
                    is SessionEvent.SessionCreated -> {
                        _createState.update { CreateSessionState.Created(event.session) }
                        _uiEvents.send(CreateSessionUiEvent.SessionCreated(event.session.id))
                    }
                    is SessionEvent.SessionError -> {
                        // Only update state if we were in Creating state (error relevant to us)
                        _createState.update { current ->
                            if (current is CreateSessionState.Creating) {
                                CreateSessionState.Failed(event.code, event.message)
                            } else {
                                current
                            }
                        }
                        _uiEvents.send(CreateSessionUiEvent.Error(event.message))
                    }
                    is SessionEvent.DirectoriesLoaded -> {
                        updateDirectories(event.parent, event.entries, event.recentDirectories)
                    }
                    is SessionEvent.AgentsLoaded -> {
                        updateAgents(event.agents)
                    }
                    else -> { /* Ignore other events */ }
                }
            }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            // Check if we already have agents from InitialState
            val existingAgents = sessionRepository.agents.value
            if (existingAgents.isNotEmpty()) {
                _agentsState.update { AgentsListState.Loaded(existingAgents) }
            } else {
                // Request agents - result will arrive via AgentsLoaded event
                try {
                    sessionRepository.getAgents()
                } catch (e: Exception) {
                    _agentsState.update { AgentsListState.Error(e.message ?: "Failed to load agents") }
                }
            }

            // Load root directories
            loadDirectories("")
        }
    }

    /**
     * Start the directory selection step.
     */
    fun startDirectorySelection() {
        _createState.update { CreateSessionState.SelectingDirectory }
        pathHistory.clear()
        loadDirectories("")
    }

    /**
     * Navigate into a directory.
     */
    fun navigateToDirectory(path: String) {
        if (_currentPath.value.isNotEmpty()) {
            pathHistory.add(_currentPath.value)
        }
        loadDirectories(path)
    }

    /**
     * Navigate back to parent directory.
     */
    fun navigateBack(): Boolean {
        return if (pathHistory.isNotEmpty()) {
            val previousPath = pathHistory.removeAt(pathHistory.lastIndex)
            loadDirectories(previousPath)
            true
        } else if (_currentPath.value.isNotEmpty()) {
            // Navigate to root
            loadDirectories("")
            true
        } else {
            false
        }
    }

    private fun loadDirectories(path: String) {
        _currentPath.update { path }
        _directoryState.update { DirectoryBrowserState.Loading }

        viewModelScope.launch {
            try {
                sessionRepository.getDirectories(path)
                // Directory data will arrive via DirectoriesLoaded event
            } catch (e: Exception) {
                _directoryState.update {
                    DirectoryBrowserState.Error(e.message ?: "Failed to load directories")
                }
            }
        }
    }

    /**
     * Select a directory for the new session.
     *
     * @param directory The directory path to select (must be valid absolute path)
     */
    fun selectDirectory(directory: String) {
        if (!DirectoryPathValidator.isValid(directory)) {
            viewModelScope.launch {
                _uiEvents.send(CreateSessionUiEvent.Error("Invalid directory path"))
            }
            return
        }
        _selectedDirectory.update { directory }
        _createState.update { CreateSessionState.DirectorySelected(directory) }
    }

    /**
     * Select a recent directory.
     *
     * @param directory The directory path to select (must be valid absolute path)
     */
    fun selectRecentDirectory(directory: String) {
        if (!DirectoryPathValidator.isValid(directory)) {
            viewModelScope.launch {
                _uiEvents.send(CreateSessionUiEvent.Error("Invalid directory path"))
            }
            return
        }
        _selectedDirectory.update { directory }
        _createState.update { CreateSessionState.DirectorySelected(directory) }
    }

    /**
     * Proceed to agent selection.
     */
    fun proceedToAgentSelection() {
        _createState.update { CreateSessionState.SelectingAgent }
    }

    /**
     * Select an agent for the new session.
     *
     * @param agent The agent binary name (must be valid and available)
     */
    fun selectAgent(agent: String) {
        // Validate agent name format
        if (!AgentNameValidator.isValid(agent)) {
            viewModelScope.launch {
                _uiEvents.send(CreateSessionUiEvent.Error("Invalid agent name"))
            }
            return
        }

        // Validate agent exists and is available
        val agentsList = (_agentsState.value as? AgentsListState.Loaded)?.agents ?: emptyList()
        val agentInfo = agentsList.find { it.binary == agent }
        if (agentInfo == null) {
            viewModelScope.launch {
                _uiEvents.send(CreateSessionUiEvent.Error("Agent not found"))
            }
            return
        }
        if (!agentInfo.available) {
            viewModelScope.launch {
                _uiEvents.send(CreateSessionUiEvent.Error("Agent is not available"))
            }
            return
        }

        _selectedAgent.update { agent }
    }

    /**
     * Create the session with selected directory and agent.
     * This method is guarded against concurrent calls using atomic state check.
     */
    fun createSession() {
        val directory = _selectedDirectory.value ?: return
        val agent = _selectedAgent.value ?: return

        // Atomic check-and-set to guard against concurrent calls
        var shouldCreate = false
        _createState.update { currentState ->
            if (currentState is CreateSessionState.Creating) {
                currentState  // Already creating, no-op
            } else {
                shouldCreate = true
                CreateSessionState.Creating(directory, agent)
            }
        }

        if (!shouldCreate) return

        viewModelScope.launch {
            try {
                sessionRepository.createSession(directory, agent)
                // Success will be handled via SessionCreated event
            } catch (e: Exception) {
                _createState.update {
                    CreateSessionState.Failed("UNKNOWN", e.message ?: "Failed to create session")
                }
                _uiEvents.send(CreateSessionUiEvent.Error(e.message ?: "Failed to create session"))
            }
        }
    }

    /**
     * Reset the wizard to initial state.
     */
    fun reset() {
        _createState.update { CreateSessionState.Idle }
        _selectedDirectory.update { null }
        _selectedAgent.update { null }
        _currentPath.update { "" }
        pathHistory.clear()
    }

    /**
     * Go back one step in the wizard.
     */
    fun goBackStep() {
        val directory = _selectedDirectory.value ?: ""
        _createState.update { currentState ->
            when (currentState) {
                is CreateSessionState.DirectorySelected -> CreateSessionState.SelectingDirectory
                is CreateSessionState.SelectingAgent -> CreateSessionState.DirectorySelected(directory)
                is CreateSessionState.Failed -> CreateSessionState.SelectingAgent
                else -> currentState  // Can't go back from other states
            }
        }
    }

    /**
     * Refresh agents list.
     */
    fun refreshAgents() {
        _agentsState.update { AgentsListState.Loading }
        viewModelScope.launch {
            try {
                sessionRepository.refreshAgents()
            } catch (e: Exception) {
                _agentsState.update { AgentsListState.Error(e.message ?: "Failed to refresh agents") }
            }
        }
    }

    /**
     * Update directory browser with data from daemon.
     * Called when DirectoriesListEvent is received.
     */
    private fun updateDirectories(
        parent: String,
        entries: List<DirectoryEntryInfo>,
        recentDirs: List<String>
    ) {
        _directoryState.update {
            DirectoryBrowserState.Loaded(
                parent = parent,
                entries = entries,
                recentDirectories = recentDirs
            )
        }
        if (recentDirs.isNotEmpty()) {
            _recentDirectories.update { recentDirs }
        }
    }

    /**
     * Update agents list with data from daemon.
     * Called when AgentsListEvent is received.
     */
    private fun updateAgents(agents: List<AgentInfo>) {
        _agentsState.update { AgentsListState.Loaded(agents) }
    }
}

/**
 * One-time UI events emitted by CreateSessionViewModel.
 */
sealed class CreateSessionUiEvent {
    data class SessionCreated(val sessionId: String) : CreateSessionUiEvent()
    data class Error(val message: String) : CreateSessionUiEvent()
}
