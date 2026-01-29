package com.ras.ui.startup

import com.ras.domain.startup.ReconnectionResult

/**
 * UI state for the startup screen.
 */
sealed class StartupState {
    /**
     * Initial state - checking for stored credentials.
     */
    object Loading : StartupState()

    /**
     * Credentials found, attempting to reconnect to daemon.
     */
    object Connecting : StartupState()

    /**
     * No credentials stored - navigate to pairing screen.
     */
    object NavigateToPairing : StartupState()

    /**
     * Successfully reconnected - navigate to sessions screen.
     */
    object NavigateToSessions : StartupState()

    /**
     * Reconnection failed - show retry options.
     */
    data class ConnectionFailed(
        val reason: ReconnectionResult.Failure
    ) : StartupState()
}
