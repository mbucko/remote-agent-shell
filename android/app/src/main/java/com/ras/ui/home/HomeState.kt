package com.ras.ui.home

import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import java.time.Instant

/**
 * Connection state for UI display.
 */
enum class ConnectionState {
    DISCONNECTED,  // Gray dot
    CONNECTING,    // Orange dot
    CONNECTED      // Green dot
}

/**
 * Display information for a single device in the list.
 */
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val type: DeviceType,
    val connectionState: ConnectionState,
    val sessionCount: Int = 0,
    val status: DeviceStatus,
    val lastConnectedAt: Instant?,
    val pairedAt: Instant
)

/**
 * UI state for HomeScreen.
 */
sealed class HomeState {
    /** Loading device info from storage */
    data object Loading : HomeState()

    /** No devices paired - show empty state */
    data object NoDevices : HomeState()

    /** Devices are paired - show device list */
    data class HasDevices(
        val devices: List<DeviceInfo>,
        val activeDeviceId: String? = null
    ) : HomeState()
}

/**
 * One-time UI events for HomeScreen.
 */
sealed class HomeUiEvent {
    data class NavigateToConnecting(val deviceId: String) : HomeUiEvent()
    data object NavigateToPairing : HomeUiEvent()
    data object NavigateToSettings : HomeUiEvent()
    data class NavigateToSessions(val deviceId: String) : HomeUiEvent()
    data class ShowSnackbar(val message: String) : HomeUiEvent()
}
