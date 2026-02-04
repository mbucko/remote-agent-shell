package com.ras.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceStatus
import com.ras.data.sessions.SessionRepository
import com.ras.data.settings.SettingsRepository
import com.ras.domain.unpair.UnpairDeviceUseCase
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
 * Manages multi-device display, connection state, and navigation events.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val connectionManager: ConnectionManager,
    private val keyManager: KeyManager,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val unpairDeviceUseCase: UnpairDeviceUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HomeUiEvent>()
    val events: SharedFlow<HomeUiEvent> = _events.asSharedFlow()

    val autoConnectEnabled: StateFlow<Boolean> = settingsRepository.autoConnectEnabled

    private var hasCheckedAutoConnect = false

    init {
        loadDevices()
        observeConnectionState()
        observeSessionCount()
        observeUnpairNotifications()
    }

    private fun loadDevices() {
        viewModelScope.launch {
            try {
                val devices = credentialRepository.getAllDevicesFlow()
                devices.collect { pairedDevices ->
                    if (pairedDevices.isEmpty()) {
                        _state.value = HomeState.NoDevices
                        return@collect
                    }

                    val selectedDevice = credentialRepository.getSelectedDevice()
                    val isConnected = connectionManager.isConnected.value
                    val sessions = sessionRepository.sessions.value

                    val deviceInfoList = pairedDevices.map { device ->
                        val isActive = device.deviceId == selectedDevice?.deviceId
                        DeviceInfo(
                            deviceId = device.deviceId,
                            name = device.deviceName,
                            type = device.deviceType,
                            connectionState = when {
                                isActive && isConnected -> ConnectionState.CONNECTED
                                isActive && !isConnected -> ConnectionState.DISCONNECTED
                                else -> ConnectionState.DISCONNECTED
                            },
                            sessionCount = if (isActive && isConnected) sessions.size else 0,
                            status = device.status,
                            lastConnectedAt = device.lastConnectedAt,
                            pairedAt = device.pairedAt
                        )
                    }

                    _state.value = HomeState.HasDevices(
                        devices = deviceInfoList,
                        activeDeviceId = selectedDevice?.deviceId
                    )

                    // Auto-connect if enabled and we have a selected device
                    // BUT NOT if user manually disconnected (they must explicitly tap to reconnect)
                    if (!hasCheckedAutoConnect && selectedDevice != null) {
                        hasCheckedAutoConnect = true
                        val userDisconnected = keyManager.isDisconnectedOnce()
                        if (settingsRepository.getAutoConnectEnabled() && !userDisconnected) {
                            _events.emit(HomeUiEvent.NavigateToConnecting(selectedDevice.deviceId))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load devices", e)
                _state.value = HomeState.NoDevices
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.isConnected.collect { isConnected ->
                val currentState = _state.value
                if (currentState is HomeState.HasDevices) {
                    val selectedDevice = credentialRepository.getSelectedDevice()

                    // Update connection state for active device
                    val updatedDevices = currentState.devices.map { device ->
                        if (device.deviceId == selectedDevice?.deviceId) {
                            device.copy(
                                connectionState = if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                                sessionCount = if (isConnected) device.sessionCount else 0
                            )
                        } else {
                            device
                        }
                    }

                    _state.value = currentState.copy(devices = updatedDevices)

                    // Navigate to sessions when connected
                    if (isConnected && selectedDevice != null) {
                        _events.emit(HomeUiEvent.NavigateToSessions(selectedDevice.deviceId))
                    }
                }
            }
        }
    }

    private fun observeSessionCount() {
        viewModelScope.launch {
            sessionRepository.sessions.collect { sessions ->
                val currentState = _state.value
                if (currentState is HomeState.HasDevices) {
                    val selectedDevice = credentialRepository.getSelectedDevice()
                    val isConnected = connectionManager.isConnected.value

                    if (isConnected && selectedDevice != null) {
                        val updatedDevices = currentState.devices.map { device ->
                            if (device.deviceId == selectedDevice.deviceId) {
                                device.copy(sessionCount = sessions.size)
                            } else {
                                device
                            }
                        }
                        _state.value = currentState.copy(devices = updatedDevices)
                    }
                }
            }
        }
    }

    /**
     * Observe unpair notifications from daemon.
     * When daemon unpairs this device, update device status to show unpaired.
     */
    private fun observeUnpairNotifications() {
        viewModelScope.launch {
            connectionManager.unpairedByDaemon.collect { notification ->
                try {
                    Log.i(TAG, "Received unpair notification for device: ${notification.deviceId}")

                    // Mark the specific device from notification as unpaired by daemon
                    credentialRepository.updateDeviceStatus(
                        notification.deviceId,
                        DeviceStatus.UNPAIRED_BY_DAEMON
                    )

                    // Show notification to user
                    _events.emit(HomeUiEvent.ShowSnackbar("Unpaired by host: ${notification.reason}"))

                    // Disconnect from daemon (this device is no longer paired)
                    connectionManager.disconnectGracefully("unpaired_by_daemon")

                    // Refresh device list to show unpaired status
                    loadDevices()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle daemon unpair notification", e)
                    _events.emit(HomeUiEvent.ShowSnackbar("Unpair failed: ${e.message}"))
                }
            }
        }
    }

    /**
     * Called when user taps on a device to connect or open sessions.
     */
    fun onDeviceClicked(deviceId: String) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is HomeState.HasDevices) return@launch

            val device = currentState.devices.find { it.deviceId == deviceId } ?: return@launch

            // If device is unpaired, show snackbar
            if (device.status != DeviceStatus.PAIRED) {
                _events.emit(HomeUiEvent.ShowSnackbar("Device is unpaired. Please remove and re-pair."))
                return@launch
            }

            // If already connected, go to sessions
            if (device.connectionState == ConnectionState.CONNECTED) {
                _events.emit(HomeUiEvent.NavigateToSessions(deviceId))
            } else {
                // Select this device and connect
                credentialRepository.setSelectedDevice(deviceId)
                _events.emit(HomeUiEvent.NavigateToConnecting(deviceId))
            }
        }
    }

    /**
     * Called when user taps Connect button.
     * @deprecated Use onDeviceClicked instead
     */
    fun connect() {
        viewModelScope.launch {
            val selectedDevice = credentialRepository.getSelectedDevice()
            if (selectedDevice != null) {
                _events.emit(HomeUiEvent.NavigateToConnecting(selectedDevice.deviceId))
            }
        }
    }

    /**
     * Called when user taps Open Sessions button (when already connected).
     * @deprecated Use onDeviceClicked instead
     */
    fun openSessions() {
        viewModelScope.launch {
            val selectedDevice = credentialRepository.getSelectedDevice()
            if (selectedDevice != null) {
                _events.emit(HomeUiEvent.NavigateToSessions(selectedDevice.deviceId))
            }
        }
    }

    /**
     * Called when user wants to unpair a specific device.
     * Removes device immediately from list (user-initiated unpair).
     */
    fun unpairDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                // Use case handles unpair + disconnect
                unpairDeviceUseCase(deviceId)

                // Remove from list immediately (don't show as unpaired for user-initiated unpair)
                credentialRepository.removeDevice(deviceId)

                // Show success message
                _events.emit(HomeUiEvent.ShowSnackbar("Device unpaired"))

                // Refresh device list
                loadDevices()
            } catch (e: Exception) {
                Log.e(TAG, "Unpair failed", e)
                _events.emit(HomeUiEvent.ShowSnackbar("Unpair failed: ${e.message}"))
            }
        }
    }

    /**
     * Called when user wants to remove an unpaired device.
     */
    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                credentialRepository.removeDevice(deviceId)
                _events.emit(HomeUiEvent.ShowSnackbar("Device removed"))
                loadDevices()
            } catch (e: Exception) {
                Log.e(TAG, "Remove failed", e)
                _events.emit(HomeUiEvent.ShowSnackbar("Remove failed: ${e.message}"))
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
        loadDevices()
    }
}
