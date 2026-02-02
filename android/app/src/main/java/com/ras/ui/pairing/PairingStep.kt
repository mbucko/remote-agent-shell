package com.ras.ui.pairing

/**
 * Represents the status of a pairing step.
 */
enum class StepStatus {
    /** Pending - gray empty circle */
    PENDING,
    
    /** In progress - orange spinner */
    IN_PROGRESS,
    
    /** Completed - green checkmark */
    COMPLETED,
    
    /** Unavailable/skipped - gray dash */
    UNAVAILABLE
}

/**
 * Represents a single step in the pairing process.
 *
 * @property label Display label for the step
 * @property status Current status of the step
 * @property durationMs Duration in milliseconds (null if not completed)
 */
data class PairingStep(
    val label: String,
    val status: StepStatus,
    val durationMs: Long? = null
) {
    /**
     * Returns a formatted duration string (e.g., "12ms", "203ms", "2.1s", "30.0s")
     */
    fun formattedDuration(): String? {
        return durationMs?.let { formatDuration(it) }
    }
    
    companion object {
        fun formatDuration(durationMs: Long): String {
            return when {
                durationMs < 1000 -> "${durationMs}ms"
                durationMs < 10000 -> "${(durationMs / 100) / 10.0}s"
                else -> "${durationMs / 1000}s"
            }
        }
    }
}

/**
 * Represents the overall progress of the pairing process.
 *
 * @property steps List of all steps with their current status
 * @property currentMessage Message to display at the top
 * @property isComplete True when all steps are completed
 */
data class PairingProgress(
    val steps: List<PairingStep>,
    val currentMessage: String,
    val isComplete: Boolean
)
