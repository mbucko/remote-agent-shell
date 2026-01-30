package com.ras.data.connection

/**
 * Progress updates from the ConnectionOrchestrator.
 *
 * Used for UI feedback showing what's happening during connection.
 */
sealed class ConnectionProgress {
    /**
     * Starting to detect if a strategy is available.
     */
    data class Detecting(val strategyName: String) : ConnectionProgress()

    /**
     * Strategy was detected as available.
     */
    data class StrategyAvailable(
        val strategyName: String,
        val info: String? = null
    ) : ConnectionProgress()

    /**
     * Strategy was detected as unavailable.
     */
    data class StrategyUnavailable(
        val strategyName: String,
        val reason: String
    ) : ConnectionProgress()

    /**
     * Attempting to connect with a strategy.
     */
    data class Connecting(
        val strategyName: String,
        val step: String,
        val detail: String? = null,
        val progress: Float? = null
    ) : ConnectionProgress()

    /**
     * Strategy connection attempt failed, may try next.
     */
    data class StrategyFailed(
        val strategyName: String,
        val error: String,
        val willTryNext: Boolean
    ) : ConnectionProgress()

    /**
     * Successfully connected!
     */
    data class Connected(
        val strategyName: String,
        val transport: Transport
    ) : ConnectionProgress()

    /**
     * All strategies failed.
     */
    data class AllFailed(
        val attempts: List<FailedAttempt>
    ) : ConnectionProgress()

    /**
     * Connection was cancelled.
     */
    object Cancelled : ConnectionProgress()
}

/**
 * Record of a failed connection attempt.
 */
data class FailedAttempt(
    val strategyName: String,
    val error: String,
    val durationMs: Long
)

/**
 * Overall connection state for UI.
 */
enum class ConnectionState {
    IDLE,
    DETECTING,
    CONNECTING,
    CONNECTED,
    FAILED,
    CANCELLED
}
