package com.ras.pairing

import com.ras.ui.pairing.PairingProgress
import com.ras.ui.pairing.PairingStep
import com.ras.ui.pairing.StepStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the current time in milliseconds.
 * Abstracted for testability.
 */
fun interface Clock {
    fun currentTimeMillis(): Long
}

/**
 * Default clock that uses System.currentTimeMillis().
 */
@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/**
 * Tracks the progress of the pairing process with step-by-step timing.
 *
 * Steps:
 * 0. QR code scanned
 * 1. Exchanging credentials (via ntfy)
 * 2. Complete
 */
@Singleton
class PairingProgressTracker @Inject constructor(
    private val clock: Clock
) {

    companion object {
        private const val INDEX_QR_SCANNED = 0
        private const val INDEX_EXCHANGING = 1
        private const val INDEX_COMPLETE = 2

        private const val LABEL_QR_SCANNED = "QR code scanned"
        private const val LABEL_EXCHANGING = "Exchanging credentials"
        private const val LABEL_COMPLETE = "Complete"

        private const val MSG_SCANNING_QR = "Scanning QR code..."
        private const val MSG_EXCHANGING = "Exchanging credentials..."
        private const val MSG_PAIRED_SUCCESS = "Paired successfully!"
    }

    private var stepStartTime: Long = 0
    private val stepDurations = ConcurrentHashMap<Int, Long>()
    private var currentStepIndex: Int = -1
    private var started: Boolean = false

    private val _progress = MutableStateFlow<PairingProgress>(createInitialProgress())
    val progress: StateFlow<PairingProgress> = _progress.asStateFlow()

    fun reset() {
        stepStartTime = 0
        stepDurations.clear()
        currentStepIndex = -1
        started = false
        _progress.value = createInitialProgress()
    }

    fun start() {
        reset()
        started = true
        stepStartTime = clock.currentTimeMillis()
    }

    /**
     * Called when QR code is successfully parsed.
     * Completes step 0 and moves to step 1.
     */
    fun onQrParsed() {
        require(currentStepIndex == -1) { "onQrParsed() must be called first" }
        require(started) { "start() must be called before onQrParsed()" }

        val duration = clock.currentTimeMillis() - stepStartTime
        stepDurations[INDEX_QR_SCANNED] = duration
        currentStepIndex = INDEX_EXCHANGING
        stepStartTime = clock.currentTimeMillis()

        _progress.update {
            PairingProgress(
                steps = createSteps(
                    exchangingStatus = StepStatus.IN_PROGRESS
                ),
                currentMessage = MSG_EXCHANGING,
                isComplete = false
            )
        }
    }

    /**
     * Called when credential exchange begins (PAIR_REQUEST sent).
     * Step 1 is in progress.
     */
    fun onExchanging() {
        if (currentStepIndex < INDEX_EXCHANGING) {
            stepStartTime = clock.currentTimeMillis()
            currentStepIndex = INDEX_EXCHANGING
        }
    }

    /**
     * Called when pairing completes successfully.
     * Completes all steps.
     */
    fun onComplete() {
        require(currentStepIndex >= INDEX_EXCHANGING) {
            "onComplete() must be called after exchange starts"
        }

        val duration = clock.currentTimeMillis() - stepStartTime
        stepDurations[INDEX_EXCHANGING] = duration

        _progress.update {
            PairingProgress(
                steps = createSteps(
                    exchangingStatus = StepStatus.COMPLETED,
                    exchangingDuration = stepDurations[INDEX_EXCHANGING],
                    completeStatus = StepStatus.COMPLETED
                ),
                currentMessage = MSG_PAIRED_SUCCESS,
                isComplete = true
            )
        }
    }

    /**
     * Called when pairing fails.
     * Marks the current step as unavailable.
     */
    fun onFailed() {
        require(currentStepIndex >= 0) { "onFailed() called but pairing has not started" }

        val duration = clock.currentTimeMillis() - stepStartTime
        stepDurations[currentStepIndex] = duration

        _progress.update { current ->
            val updatedSteps = current.steps.mapIndexed { index, step ->
                if (index == currentStepIndex) {
                    step.copy(status = StepStatus.UNAVAILABLE, durationMs = duration)
                } else {
                    step
                }
            }
            current.copy(steps = updatedSteps)
        }
    }

    private fun createInitialProgress(): PairingProgress {
        return PairingProgress(
            steps = listOf(
                PairingStep(LABEL_QR_SCANNED, StepStatus.IN_PROGRESS),
                PairingStep(LABEL_EXCHANGING, StepStatus.PENDING),
                PairingStep(LABEL_COMPLETE, StepStatus.PENDING)
            ),
            currentMessage = MSG_SCANNING_QR,
            isComplete = false
        )
    }

    private fun createSteps(
        exchangingStatus: StepStatus = StepStatus.PENDING,
        exchangingDuration: Long? = null,
        completeStatus: StepStatus = StepStatus.PENDING
    ): List<PairingStep> {
        return listOf(
            PairingStep(
                LABEL_QR_SCANNED,
                StepStatus.COMPLETED,
                stepDurations[INDEX_QR_SCANNED] ?: 0
            ),
            PairingStep(LABEL_EXCHANGING, exchangingStatus, exchangingDuration),
            PairingStep(LABEL_COMPLETE, completeStatus)
        )
    }
}
