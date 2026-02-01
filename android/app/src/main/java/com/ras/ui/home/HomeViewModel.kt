package com.ras.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.connection.ConnectionManager
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceType
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
    private val keyManager: KeyManager,
    private val connectionManager: ConnectionManager,
    private val settingsRepository: SettingsRepository
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
    }

    private fun loadDeviceInfo() {
        viewModelScope.launch {
            val hasMasterSecret = keyManager.hasMasterSecret()

            if (!hasMasterSecret) {
                _state.value = HomeState.NoPairedDevice
                return@launch
            }

            val deviceName = keyManager.getDeviceName() ?: "Unknown Device"
            val deviceType = keyManager.getDeviceType()
            val isConnected = connectionManager.isConnected.value

            _state.value = HomeState.HasDevice(
                name = deviceName,
                type = deviceType,
                connectionState = if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
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
                        connectionState = if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
                    )

                    // Navigate to sessions when connected
                    if (isConnected) {
                        _events.emit(HomeUiEvent.NavigateToSessions)
                    }
                }
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
     * Called when user taps Unpair button.
     */
    fun unpair() {
        viewModelScope.launch {
            // Disconnect if connected
            connectionManager.disconnectGracefully("unpair")

            // Clear credentials
            keyManager.clearCredentials()

            // Update state
            _state.value = HomeState.NoPairedDevice
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
