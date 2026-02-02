package com.ras.ui.home

import com.ras.data.model.DeviceType

/**
 * Connection state for UI display.
 */
enum class ConnectionState {
    DISCONNECTED,  // Gray dot
    CONNECTING,    // Orange dot
    CONNECTED      // Green dot
}

/**
 * UI state for HomeScreen.
 */
sealed class HomeState {
    /** Loading device info from storage */
    data object Loading : HomeState()

    /** No device paired - show empty state */
    data object NoPairedDevice : HomeState()

    /** Device is paired - show device card */
    data class HasDevice(
        val name: String,
        val type: DeviceType,
        val connectionState: ConnectionState,
        val sessionCount: Int = 0
    ) : HomeState()
}

/**
 * One-time UI events for HomeScreen.
 */
sealed class HomeUiEvent {
    data object NavigateToConnecting : HomeUiEvent()
    data object NavigateToPairing : HomeUiEvent()
    data object NavigateToSettings : HomeUiEvent()
    data object NavigateToSessions : HomeUiEvent()
    data class ShowSnackbar(val message: String) : HomeUiEvent()
}
