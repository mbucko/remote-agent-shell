package com.ras.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.connection.ConnectionManager
import com.ras.data.keystore.KeyManager
import com.ras.data.sessions.AgentInfo
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionRepository
import com.ras.data.settings.DaemonInfo
import com.ras.data.settings.NotificationSettings
import com.ras.data.settings.NotificationType
import com.ras.data.settings.SettingsDefaults
import com.ras.data.settings.SettingsQuickButton
import com.ras.data.settings.SettingsRepository
import com.ras.data.settings.SettingsSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 *
 * Manages:
 * - Default agent selection
 * - Quick button configuration
 * - Notification preferences
 * - Connection status display
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val connectionManager: ConnectionManager,
    private val keyManager: KeyManager
) : ViewModel() {

    // ==========================================================================
    // UI State
    // ==========================================================================

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Connection state passthrough
    val isConnected: StateFlow<Boolean> = connectionManager.isConnected

    // One-time UI events
    private val _uiEvents = MutableSharedFlow<SettingsUiEvent>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<SettingsUiEvent> = _uiEvents.asSharedFlow()

    // Track last connection time for daemon info
    private var lastConnectedTime: Long? = null

    init {
        // Combine all settings into UI state
        combine(
            settingsRepository.defaultAgent,
            settingsRepository.quickButtons,
            settingsRepository.notificationSettings,
            connectionManager.isConnected
        ) { defaultAgent, quickButtons, notifications, connected ->
            // Update lastConnectedTime when connected
            if (connected) {
                lastConnectedTime = System.currentTimeMillis()
            }

            SettingsUiState(
                defaultAgent = defaultAgent,
                quickButtons = quickButtons,
                notifications = notifications,
                daemonInfo = DaemonInfo(
                    connected = connected,
                    // Note: Daemon version/IP require protocol extension to fetch from daemon
                    // For now, these will be null until ConnectionManager exposes this data
                    version = null,
                    ipAddress = null,
                    lastSeen = lastConnectedTime
                ),
                availableAgents = _uiState.value.availableAgents,
                agentListLoading = _uiState.value.agentListLoading,
                agentListError = _uiState.value.agentListError
            )
        }.onEach { state ->
            _uiState.value = state
        }.launchIn(viewModelScope)

        // Listen for agent list updates
        sessionRepository.events.onEach { event ->
            when (event) {
                is SessionEvent.AgentsLoaded -> {
                    _uiState.update { state ->
                        state.copy(
                            availableAgents = event.agents,
                            agentListLoading = false,
                            agentListError = null
                        )
                    }
                    // Validate default agent still exists
                    val valid = settingsRepository.validateDefaultAgent(event.agents.map { it.name })
                    if (!valid) {
                        _uiEvents.emit(SettingsUiEvent.ShowMessage("Default agent no longer available"))
                    }
                }
                is SessionEvent.SessionError -> {
                    if (_uiState.value.agentListLoading) {
                        _uiState.update { state ->
                            state.copy(
                                agentListLoading = false,
                                agentListError = "Failed to load agents"
                            )
                        }
                    }
                }
                else -> { /* ignore other events */ }
            }
        }.launchIn(viewModelScope)

        // Load agent list on init if connected
        if (connectionManager.isConnected.value) {
            loadAgentList()
        }
    }

    // ==========================================================================
    // Agent Selection
    // ==========================================================================

    /**
     * Set the default agent for new sessions.
     * Pass null to select "Always ask".
     */
    fun setDefaultAgent(agent: String?) {
        settingsRepository.setDefaultAgent(agent)
    }

    /**
     * Refresh the agent list from the daemon.
     */
    fun refreshAgentList() {
        loadAgentList()
    }

    private fun loadAgentList() {
        _uiState.update { it.copy(agentListLoading = true, agentListError = null) }

        viewModelScope.launch {
            try {
                sessionRepository.getAgents()
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        agentListLoading = false,
                        agentListError = "Failed to load agents: ${e.message}"
                    )
                }
            }
        }
    }

    // ==========================================================================
    // Quick Buttons
    // ==========================================================================

    /**
     * Set the full list of enabled quick buttons.
     */
    fun setQuickButtons(buttons: List<SettingsQuickButton>) {
        settingsRepository.setEnabledQuickButtons(buttons)
    }

    /**
     * Toggle a quick button on or off.
     */
    fun toggleQuickButton(button: SettingsQuickButton, enabled: Boolean) {
        if (enabled) {
            settingsRepository.enableQuickButton(button)
        } else {
            settingsRepository.disableQuickButton(button)
        }
    }

    /**
     * Reorder quick buttons by moving one from oldIndex to newIndex.
     */
    fun reorderQuickButtons(from: Int, to: Int) {
        settingsRepository.reorderQuickButtons(from, to)
    }

    // ==========================================================================
    // Notifications
    // ==========================================================================

    /**
     * Set notification enabled state for a specific type.
     */
    fun setNotificationEnabled(type: NotificationType, enabled: Boolean) {
        settingsRepository.setNotificationEnabled(type, enabled)
    }

    /**
     * Set all notification settings at once.
     */
    fun setNotificationSettings(settings: NotificationSettings) {
        settingsRepository.setNotificationSettings(settings)
    }

    // ==========================================================================
    // Reset
    // ==========================================================================

    /**
     * Reset a specific section to defaults.
     */
    fun resetSection(section: SettingsSection) {
        settingsRepository.resetSection(section)
        viewModelScope.launch {
            _uiEvents.emit(SettingsUiEvent.ShowMessage("${section.name.lowercase().replaceFirstChar { it.uppercase() }} settings reset"))
        }
    }

    // ==========================================================================
    // Connection
    // ==========================================================================

    /**
     * Disconnect from the daemon and set disconnected flag.
     */
    fun disconnect() {
        viewModelScope.launch {
            connectionManager.disconnect()
            keyManager.setDisconnected(true)
        }
    }
}

/**
 * UI state for the Settings screen.
 */
data class SettingsUiState(
    // Sessions
    val defaultAgent: String? = null,
    val availableAgents: List<AgentInfo> = emptyList(),
    val agentListLoading: Boolean = false,
    val agentListError: String? = null,

    // Terminal
    val quickButtons: List<SettingsQuickButton> = SettingsDefaults.QUICK_BUTTONS,

    // Notifications
    val notifications: NotificationSettings = SettingsDefaults.NOTIFICATIONS,

    // Connection
    val daemonInfo: DaemonInfo = DaemonInfo(false, null, null, null)
)

/**
 * One-time UI events for the Settings screen.
 */
sealed class SettingsUiEvent {
    data class ShowMessage(val message: String) : SettingsUiEvent()
    data object NavigateToAgentPicker : SettingsUiEvent()
}
