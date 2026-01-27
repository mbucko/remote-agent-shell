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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    // One-time UI events
    private val _uiEvents = MutableSharedFlow<CreateSessionUiEvent>(extraBufferCapacity = 64)
    val uiEvents: SharedFlow<CreateSessionUiEvent> = _uiEvents.asSharedFlow()

    init {
        observeRepositoryEvents()
        loadInitialData()
    }

    private fun observeRepositoryEvents() {
        viewModelScope.launch {
            sessionRepository.events.collect { event ->
                when (event) {
                    is SessionEvent.SessionCreated -> {
                        _createState.value = CreateSessionState.Created(event.session)
                        _uiEvents.emit(CreateSessionUiEvent.SessionCreated(event.session.displayText))
                    }
                    is SessionEvent.SessionError -> {
                        _createState.value = CreateSessionState.Failed(event.code, event.message)
                        _uiEvents.emit(CreateSessionUiEvent.Error(event.message))
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
            // Load agents
            try {
                sessionRepository.getAgents()
                // Wait for agents to be loaded via the flow
                val agents = sessionRepository.agents.first { it.isNotEmpty() || !sessionRepository.isConnected.value }
                _agentsState.value = AgentsListState.Loaded(agents)
            } catch (e: Exception) {
                _agentsState.value = AgentsListState.Error(e.message ?: "Failed to load agents")
            }

            // Load root directories
            loadDirectories("")
        }
    }

    /**
     * Start the directory selection step.
     */
    fun startDirectorySelection() {
        _createState.value = CreateSessionState.SelectingDirectory
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
        _currentPath.value = path
        _directoryState.value = DirectoryBrowserState.Loading

        viewModelScope.launch {
            try {
                sessionRepository.getDirectories(path)
                // Directory data will arrive via DirectoriesLoaded event
            } catch (e: Exception) {
                _directoryState.value = DirectoryBrowserState.Error(
                    e.message ?: "Failed to load directories"
                )
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
                _uiEvents.emit(CreateSessionUiEvent.Error("Invalid directory path"))
            }
            return
        }
        _selectedDirectory.value = directory
        _createState.value = CreateSessionState.DirectorySelected(directory)
    }

    /**
     * Select a recent directory.
     *
     * @param directory The directory path to select (must be valid absolute path)
     */
    fun selectRecentDirectory(directory: String) {
        if (!DirectoryPathValidator.isValid(directory)) {
            viewModelScope.launch {
                _uiEvents.emit(CreateSessionUiEvent.Error("Invalid directory path"))
            }
            return
        }
        _selectedDirectory.value = directory
        _createState.value = CreateSessionState.DirectorySelected(directory)
    }

    /**
     * Proceed to agent selection.
     */
    fun proceedToAgentSelection() {
        _createState.value = CreateSessionState.SelectingAgent
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
                _uiEvents.emit(CreateSessionUiEvent.Error("Invalid agent name"))
            }
            return
        }

        // Validate agent exists and is available
        val agentsList = (_agentsState.value as? AgentsListState.Loaded)?.agents ?: emptyList()
        val agentInfo = agentsList.find { it.binary == agent }
        if (agentInfo == null) {
            viewModelScope.launch {
                _uiEvents.emit(CreateSessionUiEvent.Error("Agent not found"))
            }
            return
        }
        if (!agentInfo.available) {
            viewModelScope.launch {
                _uiEvents.emit(CreateSessionUiEvent.Error("Agent is not available"))
            }
            return
        }

        _selectedAgent.value = agent
    }

    /**
     * Create the session with selected directory and agent.
     * This method is guarded against concurrent calls.
     */
    fun createSession() {
        // Guard against concurrent calls
        if (_createState.value is CreateSessionState.Creating) {
            return
        }

        val directory = _selectedDirectory.value ?: return
        val agent = _selectedAgent.value ?: return

        _createState.value = CreateSessionState.Creating(directory, agent)

        viewModelScope.launch {
            try {
                sessionRepository.createSession(directory, agent)
                // Success will be handled via SessionCreated event
            } catch (e: Exception) {
                _createState.value = CreateSessionState.Failed(
                    "UNKNOWN",
                    e.message ?: "Failed to create session"
                )
                _uiEvents.emit(CreateSessionUiEvent.Error(e.message ?: "Failed to create session"))
            }
        }
    }

    /**
     * Reset the wizard to initial state.
     */
    fun reset() {
        _createState.value = CreateSessionState.Idle
        _selectedDirectory.value = null
        _selectedAgent.value = null
        _currentPath.value = ""
        pathHistory.clear()
    }

    /**
     * Go back one step in the wizard.
     */
    fun goBackStep() {
        when (_createState.value) {
            is CreateSessionState.DirectorySelected -> {
                _createState.value = CreateSessionState.SelectingDirectory
            }
            is CreateSessionState.SelectingAgent -> {
                _createState.value = CreateSessionState.DirectorySelected(
                    _selectedDirectory.value ?: ""
                )
            }
            is CreateSessionState.Failed -> {
                // Go back to agent selection
                _createState.value = CreateSessionState.SelectingAgent
            }
            else -> { /* Can't go back from other states */ }
        }
    }

    /**
     * Refresh agents list.
     */
    fun refreshAgents() {
        _agentsState.value = AgentsListState.Loading
        viewModelScope.launch {
            try {
                sessionRepository.refreshAgents()
            } catch (e: Exception) {
                _agentsState.value = AgentsListState.Error(e.message ?: "Failed to refresh agents")
            }
        }
    }

    /**
     * Update directory browser with data from daemon.
     * Called when DirectoriesListEvent is received.
     */
    fun updateDirectories(
        parent: String,
        entries: List<DirectoryEntryInfo>,
        recentDirs: List<String>
    ) {
        _directoryState.value = DirectoryBrowserState.Loaded(
            parent = parent,
            entries = entries,
            recentDirectories = recentDirs
        )
        if (recentDirs.isNotEmpty()) {
            _recentDirectories.value = recentDirs
        }
    }

    /**
     * Update agents list with data from daemon.
     * Called when AgentsListEvent is received.
     */
    fun updateAgents(agents: List<AgentInfo>) {
        _agentsState.value = AgentsListState.Loaded(agents)
    }
}

/**
 * One-time UI events emitted by CreateSessionViewModel.
 */
sealed class CreateSessionUiEvent {
    data class SessionCreated(val name: String) : CreateSessionUiEvent()
    data class Error(val message: String) : CreateSessionUiEvent()
}
