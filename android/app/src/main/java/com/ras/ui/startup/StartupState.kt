package com.ras.ui.startup

import com.ras.data.connection.ConnectionProgress
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
     * @param progress Detailed progress info for UI feedback
     */
    data class Connecting(
        val progress: ConnectionProgressInfo = ConnectionProgressInfo()
    ) : StartupState()

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

/**
 * Connection progress info for detailed UI feedback.
 */
data class ConnectionProgressInfo(
    val strategyName: String = "",
    val step: String = "Initializing",
    val detail: String? = null,
    val progress: Float? = null,
    val availableStrategies: List<String> = emptyList(),
    val failedStrategies: List<String> = emptyList()
) {
    companion object {
        /**
         * Convert from ConnectionProgress to UI-friendly info.
         */
        fun from(progress: ConnectionProgress): ConnectionProgressInfo {
            return when (progress) {
                is ConnectionProgress.Detecting -> ConnectionProgressInfo(
                    strategyName = progress.strategyName,
                    step = "Detecting",
                    detail = "Checking availability"
                )
                is ConnectionProgress.StrategyAvailable -> ConnectionProgressInfo(
                    strategyName = progress.strategyName,
                    step = "Available",
                    detail = progress.info
                )
                is ConnectionProgress.StrategyUnavailable -> ConnectionProgressInfo(
                    strategyName = progress.strategyName,
                    step = "Unavailable",
                    detail = progress.reason
                )
                is ConnectionProgress.Connecting -> ConnectionProgressInfo(
                    strategyName = progress.strategyName,
                    step = progress.step,
                    detail = progress.detail,
                    progress = progress.progress
                )
                is ConnectionProgress.StrategyFailed -> ConnectionProgressInfo(
                    strategyName = progress.strategyName,
                    step = "Failed",
                    detail = if (progress.willTryNext) "Trying next..." else progress.error
                )
                is ConnectionProgress.Connected -> ConnectionProgressInfo(
                    strategyName = progress.strategyName,
                    step = "Connected",
                    detail = "Connection established"
                )
                is ConnectionProgress.AllFailed -> ConnectionProgressInfo(
                    step = "All strategies failed",
                    failedStrategies = progress.attempts.map { it.strategyName }
                )
                ConnectionProgress.Cancelled -> ConnectionProgressInfo(
                    step = "Cancelled"
                )
            }
        }
    }
}
