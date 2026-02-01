package com.ras.ui.connecting

import com.ras.data.connection.ConnectionLog
import com.ras.domain.startup.ReconnectionResult

/**
 * UI state for ConnectingScreen.
 */
sealed class ConnectingState {
    /** Actively connecting, showing progress */
    data class Connecting(
        val log: ConnectionLog = ConnectionLog()
    ) : ConnectingState()

    /** Connection failed, showing error and retry option */
    data class Failed(
        val reason: ReconnectionResult.Failure,
        val log: ConnectionLog
    ) : ConnectingState()
}

/**
 * One-time UI events for ConnectingScreen.
 */
sealed class ConnectingUiEvent {
    data object NavigateToSessions : ConnectingUiEvent()
    data object NavigateBack : ConnectingUiEvent()
}
