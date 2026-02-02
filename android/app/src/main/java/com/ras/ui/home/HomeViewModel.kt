package com.ras.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.sessions.SessionRepository
import com.ras.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for HomeScreen.
 *
 * Manages device info loading, connection state, and navigation events.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val connectionManager: ConnectionManager,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HomeUiEvent>()
    val events: SharedFlow<HomeUiEvent> = _events.asSharedFlow()

    val autoConnectEnabled: StateFlow<Boolean> = settingsRepository.autoConnectEnabled

    private var hasCheckedAutoConnect = false

    init {
        loadDeviceInfo()
        observeConnectionState()
        observeSessionCount()
        observeUnpairNotifications()
    }

    private fun loadDeviceInfo() {
        viewModelScope.launch {
            val hasCredentials = credentialRepository.hasCredentials()

            if (!hasCredentials) {
                _state.value = HomeState.NoPairedDevice
                return@launch
            }

            val deviceName = credentialRepository.getDeviceName() ?: "Unknown Device"
            val deviceType = credentialRepository.getDeviceType()
            val isConnected = connectionManager.isConnected.value

            val sessions = sessionRepository.sessions.value
            _state.value = HomeState.HasDevice(
                name = deviceName,
                type = deviceType,
                connectionState = if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                sessionCount = if (isConnected) sessions.size else 0
            )

            // Auto-connect if enabled and we have a device
            if (!hasCheckedAutoConnect) {
                hasCheckedAutoConnect = true
                if (settingsRepository.getAutoConnectEnabled()) {
                    _events.emit(HomeUiEvent.NavigateToConnecting)
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.isConnected.collect { isConnected ->
                val currentState = _state.value
                if (currentState is HomeState.HasDevice) {
                    _state.value = currentState.copy(
                        connectionState = if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                        sessionCount = if (isConnected) currentState.sessionCount else 0
                    )

                    // Navigate to sessions when connected
                    if (isConnected) {
                        _events.emit(HomeUiEvent.NavigateToSessions)
                    }
                }
            }
        }
    }

    private fun observeSessionCount() {
        viewModelScope.launch {
            sessionRepository.sessions.collect { sessions ->
                val currentState = _state.value
                if (currentState is HomeState.HasDevice && currentState.connectionState == ConnectionState.CONNECTED) {
                    _state.value = currentState.copy(sessionCount = sessions.size)
                }
            }
        }
    }

    /**
     * Observe unpair notifications from daemon.
     * When daemon unpairs this device, clear credentials and update UI.
     */
    private fun observeUnpairNotifications() {
        viewModelScope.launch {
            connectionManager.unpairedByDaemon.collect { notification ->
                // Clear credentials immediately
                credentialRepository.clearCredentials()

                // Show notification to user
                _events.emit(HomeUiEvent.ShowSnackbar("Unpaired by host: ${notification.reason}"))

                // Update state to show no paired device
                _state.value = HomeState.NoPairedDevice
            }
        }
    }

    /**
     * Called when user taps Connect button.
     */
    fun connect() {
        viewModelScope.launch {
            _events.emit(HomeUiEvent.NavigateToConnecting)
        }
    }

    /**
     * Called when user taps Open Sessions button (when already connected).
     */
    fun openSessions() {
        viewModelScope.launch {
            _events.emit(HomeUiEvent.NavigateToSessions)
        }
    }

    /**
     * Called when user taps Unpair button.
     * Sends unpair request to daemon if connected, then clears local credentials.
     */
    fun unpair() {
        viewModelScope.launch {
            try {
                // If connected, send unpair request to daemon
                if (connectionManager.isConnected.value) {
                    val credentials = credentialRepository.getCredentials()
                    if (credentials != null) {
                        connectionManager.sendUnpairRequest(credentials.deviceId)
                    }
                }

                // Clear credentials locally (regardless of daemon response)
                credentialRepository.clearCredentials()

                // Disconnect if still connected
                connectionManager.disconnectGracefully("unpair")

                // Update state
                _state.value = HomeState.NoPairedDevice

                // Show success message
                _events.emit(HomeUiEvent.ShowSnackbar("Device unpaired"))
            } catch (e: Exception) {
                // Log error but continue with unpair (best effort to notify daemon)
                android.util.Log.e("HomeViewModel", "Unpair failed", e)
                _events.emit(HomeUiEvent.ShowSnackbar("Unpair completed (daemon notification failed)"))
            }
        }
    }

    /**
     * Called when user taps Pair Device button.
     */
    fun pair() {
        viewModelScope.launch {
            _events.emit(HomeUiEvent.NavigateToPairing)
        }
    }

    /**
     * Called when user taps Settings button.
     */
    fun openSettings() {
        viewModelScope.launch {
            _events.emit(HomeUiEvent.NavigateToSettings)
        }
    }

    /**
     * Called when user toggles auto-connect.
     */
    fun setAutoConnect(enabled: Boolean) {
        settingsRepository.setAutoConnectEnabled(enabled)
    }

    /**
     * Called when returning from manual disconnect.
     */
    fun showDisconnectedMessage() {
        viewModelScope.launch {
            _events.emit(HomeUiEvent.ShowSnackbar("Disconnected"))
        }
    }

    /**
     * Refresh device info from storage.
     */
    fun refresh() {
        hasCheckedAutoConnect = true // Don't auto-connect on refresh
        loadDeviceInfo()
    }
}
