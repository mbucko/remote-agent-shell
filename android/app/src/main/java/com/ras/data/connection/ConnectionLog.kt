package com.ras.data.connection

/**
 * Comprehensive log of the connection process for UI display.
 *
 * Accumulates all progress events into a structured view showing:
 * 1. Capability discovery results
 * 2. Available/unavailable strategies
 * 3. Connection attempt history with timing
 * 4. Final result
 */
data class ConnectionLog(
    val phase: ConnectionPhase = ConnectionPhase.INITIALIZING,
    val localCapabilities: LocalCapabilitiesInfo? = null,
    val daemonCapabilities: DaemonCapabilitiesInfo? = null,
    val capabilityExchangeError: String? = null,
    val capabilityExchangeSteps: List<String> = emptyList(),  // Detailed steps for capability exchange
    val strategies: List<StrategyInfo> = emptyList(),
    val currentAttempt: AttemptInfo? = null,
    val completedAttempts: List<AttemptInfo> = emptyList(),
    val result: ConnectionResultInfo? = null,
    val startTimeMs: Long = System.currentTimeMillis()
) {
    /**
     * Apply a progress event to produce updated log.
     */
    fun apply(progress: ConnectionProgress): ConnectionLog {
        return when (progress) {
            // Capability Discovery
            ConnectionProgress.DiscoveryStarted -> copy(
                phase = ConnectionPhase.DISCOVERING_CAPABILITIES
            )
            ConnectionProgress.TailscaleDetecting -> copy(
                capabilityExchangeSteps = capabilityExchangeSteps + "TAILSCALE → detecting..."
            )
            is ConnectionProgress.LocalCapabilities -> copy(
                localCapabilities = LocalCapabilitiesInfo(
                    tailscaleIp = progress.tailscaleIp,
                    tailscaleInterface = progress.tailscaleInterface,
                    supportsWebRTC = progress.supportsWebRTC
                ),
                // Update the Tailscale detection step
                capabilityExchangeSteps = capabilityExchangeSteps.map { step ->
                    if (step.contains("TAILSCALE")) {
                        if (progress.tailscaleIp != null) {
                            "TAILSCALE → ${progress.tailscaleIp} ✓"
                        } else {
                            "TAILSCALE → not detected"
                        }
                    } else step
                }
            )
            ConnectionProgress.ExchangingCapabilities -> copy(
                phase = ConnectionPhase.EXCHANGING_CAPABILITIES
            )

            // Capability exchange detailed events
            is ConnectionProgress.CapabilityTryingDirect -> copy(
                phase = ConnectionPhase.EXCHANGING_CAPABILITIES,
                capabilityExchangeSteps = capabilityExchangeSteps + "CAPABILITIES → ${progress.host}:${progress.port}..."
            )
            is ConnectionProgress.CapabilityDirectTimeout -> copy(
                // Replace the direct probe line (find by host:port pattern)
                capabilityExchangeSteps = capabilityExchangeSteps.map { step ->
                    if (step.contains("${progress.host}:${progress.port}") && !step.contains("unreachable")) {
                        "CAPABILITIES → ${progress.host}:${progress.port}... unreachable"
                    } else step
                }
            )
            is ConnectionProgress.CapabilityDirectSuccess -> copy(
                // Replace the direct probe line to show success
                capabilityExchangeSteps = capabilityExchangeSteps.map { step ->
                    if (step.contains("${progress.host}:${progress.port}") && !step.contains("✓")) {
                        "CAPABILITIES → ${progress.host}:${progress.port} ✓ local"
                    } else step
                }
            )
            is ConnectionProgress.CapabilityNtfySubscribing -> copy(
                capabilityExchangeSteps = capabilityExchangeSteps + "CAPABILITIES → ntfy... connecting"
            )
            is ConnectionProgress.CapabilityNtfySubscribed -> copy(
                // Update the ntfy line (find by "CAPABILITIES → ntfy" prefix)
                capabilityExchangeSteps = capabilityExchangeSteps.map { step ->
                    if (step.startsWith("CAPABILITIES → ntfy")) {
                        "CAPABILITIES → ntfy... connected"
                    } else step
                }
            )
            is ConnectionProgress.CapabilityNtfySending -> copy(
                capabilityExchangeSteps = capabilityExchangeSteps.map { step ->
                    if (step.startsWith("CAPABILITIES → ntfy")) {
                        "CAPABILITIES → ntfy... sending"
                    } else step
                }
            )
            is ConnectionProgress.CapabilityNtfyWaiting -> copy(
                capabilityExchangeSteps = capabilityExchangeSteps.map { step ->
                    if (step.startsWith("CAPABILITIES → ntfy")) {
                        "CAPABILITIES → ntfy... waiting"
                    } else step
                }
            )
            is ConnectionProgress.CapabilityNtfyReceived -> copy(
                capabilityExchangeSteps = capabilityExchangeSteps.map { step ->
                    if (step.startsWith("CAPABILITIES → ntfy")) {
                        "CAPABILITIES ← ntfy ✓"
                    } else step
                }
            )
            is ConnectionProgress.DaemonCapabilities -> copy(
                daemonCapabilities = DaemonCapabilitiesInfo(
                    tailscaleIp = progress.tailscaleIp,
                    tailscalePort = progress.tailscalePort,
                    supportsWebRTC = progress.supportsWebRTC,
                    protocolVersion = progress.protocolVersion
                )
            )
            is ConnectionProgress.CapabilityExchangeFailed -> copy(
                capabilityExchangeError = progress.reason
            )

            // Strategy Detection
            is ConnectionProgress.Detecting -> copy(
                phase = ConnectionPhase.DETECTING_STRATEGIES,
                strategies = strategies.addOrUpdate(
                    StrategyInfo(
                        name = progress.strategyName,
                        status = StrategyStatus.DETECTING
                    )
                )
            )
            is ConnectionProgress.StrategyAvailable -> copy(
                strategies = strategies.addOrUpdate(
                    StrategyInfo(
                        name = progress.strategyName,
                        status = StrategyStatus.AVAILABLE,
                        info = progress.info
                    )
                )
            )
            is ConnectionProgress.StrategyUnavailable -> copy(
                strategies = strategies.addOrUpdate(
                    StrategyInfo(
                        name = progress.strategyName,
                        status = StrategyStatus.UNAVAILABLE,
                        info = progress.reason
                    )
                )
            )

            // Signaling Progress (pass through as connection steps)
            // All signaling events create currentAttempt if it doesn't exist
            is ConnectionProgress.TryingDirectSignaling -> copy(
                phase = ConnectionPhase.CONNECTING,
                currentAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = "WebRTC P2P",
                    startTimeMs = System.currentTimeMillis()
                )).let { attempt ->
                    attempt.copy(
                        steps = attempt.steps + StepInfo(
                            step = "Signaling",
                            detail = "OFFER (SDP) → ${progress.host}:${progress.port}...",
                            status = StepStatus.IN_PROGRESS
                        )
                    )
                }
            )
            is ConnectionProgress.DirectSignalingTimeout -> copy(
                currentAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = "WebRTC P2P",
                    startTimeMs = System.currentTimeMillis()
                )).let { attempt ->
                    // Update the direct signaling step (find by host:port)
                    attempt.copy(
                        steps = attempt.steps.map { step ->
                            if (step.detail?.contains("${progress.host}:${progress.port}") == true &&
                                !step.detail.contains("timeout")) {
                                step.copy(
                                    detail = "OFFER (SDP) → ${progress.host}:${progress.port}... timeout",
                                    status = StepStatus.FAILED
                                )
                            } else step
                        }
                    )
                }
            )
            is ConnectionProgress.NtfySubscribing -> copy(
                currentAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = "WebRTC P2P",
                    startTimeMs = System.currentTimeMillis()
                )).let { attempt ->
                    attempt.copy(
                        steps = attempt.steps + StepInfo(
                            step = "Signaling",
                            detail = "OFFER (SDP) → ntfy.sh/${progress.topic.truncateTopic()}... connecting",
                            status = StepStatus.IN_PROGRESS
                        )
                    )
                }
            )
            is ConnectionProgress.NtfySubscribed -> copy(
                currentAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = "WebRTC P2P",
                    startTimeMs = System.currentTimeMillis()
                )).let { attempt ->
                    // Update the ntfy step (find by truncated topic)
                    attempt.copy(
                        steps = attempt.steps.map { step ->
                            if (step.detail?.contains("ntfy.sh/${progress.topic.truncateTopic()}") == true) {
                                step.copy(detail = "OFFER (SDP) → ntfy.sh/${progress.topic.truncateTopic()}... connected")
                            } else step
                        }
                    )
                }
            )
            is ConnectionProgress.NtfySendingOffer -> copy(
                currentAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = "WebRTC P2P",
                    startTimeMs = System.currentTimeMillis()
                )).let { attempt ->
                    attempt.copy(
                        steps = attempt.steps.map { step ->
                            if (step.detail?.contains("ntfy.sh/${progress.topic.truncateTopic()}") == true) {
                                step.copy(
                                    detail = "OFFER → ntfy.sh/${progress.topic.truncateTopic()}... sending",
                                    subDetails = progress.sdpInfo?.let { sdp ->
                                        buildList {
                                            // SDP media types
                                            if (sdp.mediaTypes.isNotEmpty()) {
                                                add("SDP: ${sdp.mediaTypes.joinToString(", ")}")
                                            }
                                            // ICE candidates with nested IPs
                                            if (sdp.iceCandidates.isNotEmpty()) {
                                                add("ICE candidates:")
                                                sdp.iceCandidates.forEach { candidate ->
                                                    add("    ${candidate.ip}:${candidate.port} (${candidate.label})")
                                                }
                                            }
                                        }
                                    }
                                )
                            } else step
                        }
                    )
                }
            )
            is ConnectionProgress.NtfyWaitingForAnswer -> copy(
                currentAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = "WebRTC P2P",
                    startTimeMs = System.currentTimeMillis()
                )).let { attempt ->
                    attempt.copy(
                        steps = attempt.steps.map { step ->
                            if (step.detail?.contains("ntfy.sh/${progress.topic.truncateTopic()}") == true) {
                                step.copy(detail = "OFFER (SDP) → ntfy.sh/${progress.topic.truncateTopic()}... waiting")
                            } else step
                        }
                    )
                }
            )
            is ConnectionProgress.NtfyReceivedAnswer -> copy(
                currentAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = "WebRTC P2P",
                    startTimeMs = System.currentTimeMillis()
                )).let { attempt ->
                    attempt.copy(
                        steps = attempt.steps.map { step ->
                            if (step.detail?.contains("ntfy.sh/${progress.topic.truncateTopic()}") == true) {
                                step.copy(
                                    detail = "ANSWER (SDP + ${progress.candidateCount} ICE) ← ntfy.sh/${progress.topic.truncateTopic()} ✓",
                                    status = StepStatus.COMPLETED
                                )
                            } else step
                        }
                    )
                }
            )
            is ConnectionProgress.NtfyRetrying -> copy(
                currentAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = "WebRTC P2P",
                    startTimeMs = System.currentTimeMillis()
                )).let { attempt ->
                    attempt.copy(
                        steps = attempt.steps.map { step ->
                            if (step.detail?.contains("ntfy.sh/${progress.topic.truncateTopic()}") == true) {
                                step.copy(
                                    detail = "OFFER (SDP) → ntfy.sh/${progress.topic.truncateTopic()}... retrying (${progress.attempt}/${progress.maxAttempts})",
                                    status = StepStatus.IN_PROGRESS
                                )
                            } else step
                        }
                    )
                }
            )

            // Connection Attempts
            is ConnectionProgress.Connecting -> {
                val attempt = currentAttempt?.copy(
                    steps = currentAttempt.steps + StepInfo(
                        step = progress.step,
                        detail = progress.detail,
                        status = StepStatus.IN_PROGRESS
                    )
                ) ?: AttemptInfo(
                    strategyName = progress.strategyName,
                    startTimeMs = System.currentTimeMillis(),
                    steps = listOf(
                        StepInfo(
                            step = progress.step,
                            detail = progress.detail,
                            status = StepStatus.IN_PROGRESS
                        )
                    )
                )
                copy(
                    phase = ConnectionPhase.CONNECTING,
                    currentAttempt = attempt
                )
            }
            is ConnectionProgress.StrategyFailed -> {
                val finishedAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = progress.strategyName,
                    startTimeMs = System.currentTimeMillis()
                )).copy(
                    success = false,
                    error = progress.error,
                    durationMs = progress.durationMs
                )
                copy(
                    currentAttempt = null,
                    completedAttempts = completedAttempts + finishedAttempt,
                    strategies = strategies.addOrUpdate(
                        StrategyInfo(
                            name = progress.strategyName,
                            status = StrategyStatus.FAILED,
                            info = progress.error
                        )
                    )
                )
            }
            is ConnectionProgress.Connected -> {
                val finishedAttempt = (currentAttempt ?: AttemptInfo(
                    strategyName = progress.strategyName,
                    startTimeMs = System.currentTimeMillis()
                )).copy(
                    success = true,
                    durationMs = progress.durationMs
                )
                copy(
                    phase = ConnectionPhase.CONNECTED,
                    currentAttempt = null,
                    completedAttempts = completedAttempts + finishedAttempt,
                    result = ConnectionResultInfo(
                        success = true,
                        strategyName = progress.strategyName,
                        totalDurationMs = System.currentTimeMillis() - startTimeMs
                    )
                )
            }
            is ConnectionProgress.AllFailed -> copy(
                phase = ConnectionPhase.FAILED,
                result = ConnectionResultInfo(
                    success = false,
                    totalDurationMs = System.currentTimeMillis() - startTimeMs,
                    failedAttempts = progress.attempts
                )
            )
            ConnectionProgress.Cancelled -> copy(
                phase = ConnectionPhase.CANCELLED,
                result = ConnectionResultInfo(
                    success = false,
                    cancelled = true,
                    totalDurationMs = System.currentTimeMillis() - startTimeMs
                )
            )

            // Authentication Phase
            is ConnectionProgress.Authenticating -> copy(
                phase = ConnectionPhase.AUTHENTICATING
            )
            ConnectionProgress.Authenticated -> copy(
                phase = ConnectionPhase.AUTHENTICATED
            )
            is ConnectionProgress.AuthenticationFailed -> copy(
                phase = ConnectionPhase.FAILED,
                result = ConnectionResultInfo(
                    success = false,
                    totalDurationMs = System.currentTimeMillis() - startTimeMs,
                    authenticationError = progress.reason
                )
            )
        }
    }

    private fun List<StrategyInfo>.addOrUpdate(strategy: StrategyInfo): List<StrategyInfo> {
        val existing = indexOfFirst { it.name == strategy.name }
        return if (existing >= 0) {
            toMutableList().apply { set(existing, strategy) }
        } else {
            this + strategy
        }
    }
}

enum class ConnectionPhase {
    INITIALIZING,
    DISCOVERING_CAPABILITIES,
    EXCHANGING_CAPABILITIES,
    DETECTING_STRATEGIES,
    CONNECTING,
    CONNECTED,
    AUTHENTICATING,
    AUTHENTICATED,
    FAILED,
    CANCELLED
}

data class LocalCapabilitiesInfo(
    val tailscaleIp: String?,
    val tailscaleInterface: String?,
    val supportsWebRTC: Boolean
)

data class DaemonCapabilitiesInfo(
    val tailscaleIp: String?,
    val tailscalePort: Int?,
    val supportsWebRTC: Boolean,
    val protocolVersion: Int
)

data class StrategyInfo(
    val name: String,
    val status: StrategyStatus,
    val info: String? = null,
    val priority: Int? = null
)

enum class StrategyStatus {
    DETECTING,
    AVAILABLE,
    UNAVAILABLE,
    CONNECTING,
    FAILED,
    SUCCEEDED
}

data class AttemptInfo(
    val strategyName: String,
    val startTimeMs: Long,
    val steps: List<StepInfo> = emptyList(),
    val success: Boolean = false,
    val error: String? = null,
    val durationMs: Long? = null
)

data class StepInfo(
    val step: String,
    val detail: String? = null,
    val status: StepStatus = StepStatus.PENDING,
    val subDetails: List<String>? = null  // Nested bullet points
)

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

data class ConnectionResultInfo(
    val success: Boolean,
    val strategyName: String? = null,
    val totalDurationMs: Long = 0,
    val cancelled: Boolean = false,
    val failedAttempts: List<FailedAttempt> = emptyList(),
    val authenticationError: String? = null
)

/**
 * Truncate ntfy topic to first 10 chars for matching/display.
 * "ras-cd86d61fbeb6" -> "ras-cd86d6"
 */
private fun String.truncateTopic(): String {
    return if (length > 10) take(10) else this
}
