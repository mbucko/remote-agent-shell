package com.ras.ui.startup

import com.ras.data.connection.ConnectionLog
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
     * @param log Full connection log for comprehensive UI feedback
     */
    data class Connecting(
        val log: ConnectionLog = ConnectionLog()
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
     * User previously disconnected - navigate to disconnected screen.
     */
    object NavigateToDisconnected : StartupState()

    /**
     * Reconnection failed - show retry options.
     * @param log Final connection log showing what was tried
     */
    data class ConnectionFailed(
        val reason: ReconnectionResult.Failure,
        val log: ConnectionLog = ConnectionLog()
    ) : StartupState()
}

// Keep old ConnectionProgressInfo for backward compatibility with simple progress display
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
                ConnectionProgress.DiscoveryStarted -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "Detecting local capabilities"
                )
                ConnectionProgress.TailscaleDetecting -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "TAILSCALE → detecting..."
                )
                is ConnectionProgress.LocalCapabilities -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = if (progress.tailscaleIp != null)
                        "TAILSCALE → ${progress.tailscaleIp} ✓"
                    else
                        "TAILSCALE → not detected"
                )
                ConnectionProgress.ExchangingCapabilities -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "Exchanging capabilities with daemon"
                )
                is ConnectionProgress.DaemonCapabilities -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = if (progress.tailscaleIp != null)
                        "Daemon Tailscale: ${progress.tailscaleIp}"
                    else
                        "Daemon: WebRTC only"
                )
                is ConnectionProgress.CapabilityExchangeFailed -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "Capability exchange failed: ${progress.reason}"
                )

                // Capability exchange detailed events
                is ConnectionProgress.CapabilityTryingDirect -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "CAPABILITIES → ${progress.host}:${progress.port}..."
                )
                is ConnectionProgress.CapabilityDirectTimeout -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "CAPABILITIES → ${progress.host}:${progress.port}... unreachable"
                )
                is ConnectionProgress.CapabilityDirectSuccess -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "CAPABILITIES → ${progress.host}:${progress.port} ✓ local"
                )
                is ConnectionProgress.CapabilityNtfySubscribing -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "CAPABILITIES → ntfy.sh/${progress.topic.truncateTopic()}... connecting"
                )
                is ConnectionProgress.CapabilityNtfySubscribed -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "CAPABILITIES → ntfy.sh/${progress.topic.truncateTopic()}... connected"
                )
                is ConnectionProgress.CapabilityNtfySending -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "CAPABILITIES → ntfy.sh/${progress.topic.truncateTopic()}... sending"
                )
                is ConnectionProgress.CapabilityNtfyWaiting -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "CAPABILITIES → ntfy.sh/${progress.topic.truncateTopic()}... waiting"
                )
                is ConnectionProgress.CapabilityNtfyReceived -> ConnectionProgressInfo(
                    step = "Discovery",
                    detail = "CAPABILITIES ← ntfy.sh/${progress.topic.truncateTopic()} ✓"
                )
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

                // Signaling events
                is ConnectionProgress.TryingDirectSignaling -> ConnectionProgressInfo(
                    step = "Signaling",
                    detail = "OFFER (SDP) → ${progress.host}:${progress.port}..."
                )
                is ConnectionProgress.DirectSignalingTimeout -> ConnectionProgressInfo(
                    step = "Signaling",
                    detail = "OFFER (SDP) → ${progress.host}:${progress.port}... timeout"
                )
                is ConnectionProgress.NtfySubscribing -> ConnectionProgressInfo(
                    step = "Signaling",
                    detail = "OFFER (SDP) → ntfy.sh/${progress.topic.truncateTopic()}... connecting"
                )
                is ConnectionProgress.NtfySubscribed -> ConnectionProgressInfo(
                    step = "Signaling",
                    detail = "OFFER (SDP) → ntfy.sh/${progress.topic.truncateTopic()}... connected"
                )
                is ConnectionProgress.NtfySendingOffer -> ConnectionProgressInfo(
                    step = "Signaling",
                    detail = "OFFER (SDP) → ntfy.sh/${progress.topic.truncateTopic()}... sending"
                )
                is ConnectionProgress.NtfyWaitingForAnswer -> ConnectionProgressInfo(
                    step = "Signaling",
                    detail = "OFFER (SDP) → ntfy.sh/${progress.topic.truncateTopic()}... waiting"
                )
                is ConnectionProgress.NtfyReceivedAnswer -> ConnectionProgressInfo(
                    step = "Signaling",
                    detail = "ANSWER (SDP + ${progress.candidateCount} ICE) ← ntfy.sh/${progress.topic.truncateTopic()} ✓"
                )
                is ConnectionProgress.NtfyRetrying -> ConnectionProgressInfo(
                    step = "Signaling",
                    detail = "ntfy.sh/${progress.topic.truncateTopic()}... retrying (${progress.attempt}/${progress.maxAttempts})"
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
                    detail = "Connection established in ${progress.durationMs}ms"
                )
                is ConnectionProgress.AllFailed -> ConnectionProgressInfo(
                    step = "All strategies failed",
                    failedStrategies = progress.attempts.map { it.strategyName }
                )
                ConnectionProgress.Cancelled -> ConnectionProgressInfo(
                    step = "Cancelled"
                )
                is ConnectionProgress.Authenticating -> ConnectionProgressInfo(
                    step = "Authenticating",
                    detail = progress.step
                )
                ConnectionProgress.Authenticated -> ConnectionProgressInfo(
                    step = "Authenticated",
                    detail = "Successfully authenticated"
                )
                is ConnectionProgress.AuthenticationFailed -> ConnectionProgressInfo(
                    step = "Authentication Failed",
                    detail = progress.reason
                )
            }
        }

        /**
         * Truncate ntfy topic to first 10 chars for display.
         */
        private fun String.truncateTopic(): String {
            return if (length > 10) take(10) else this
        }
    }
}
