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
 * This class manages the state transitions through the pairing steps,
 * tracking start and completion times for each step.
 */
@Singleton
class PairingProgressTracker @Inject constructor(
    private val clock: Clock
) {
    
    companion object {
        // Step indices for direct connection path (6 steps: 0-5)
        private const val INDEX_QR_SCANNED = 0
        private const val INDEX_CREATING_CONNECTION = 1
        private const val INDEX_REACHING_HOST = 2
        private const val INDEX_SECURE_CHANNEL = 3
        private const val INDEX_AUTHENTICATING = 4
        private const val INDEX_COMPLETE = 5
        
        // Step indices for relay connection path (7 steps: 0-6)
        // INDEX_QR_SCANNED = 0 (shared)
        // INDEX_CREATING_CONNECTION = 1 (shared)
        private const val INDEX_DIRECT_CONNECTION = 2
        private const val INDEX_RELAY_CONNECTION = 3
        // Relay path: secure channel = 4, authenticating = 5, complete = 6
        
        // Step labels
        private const val LABEL_QR_SCANNED = "QR code scanned"
        private const val LABEL_CREATING_CONNECTION = "Creating connection"
        private const val LABEL_REACHING_HOST = "Reaching host"
        private const val LABEL_DIRECT_CONNECTION = "Direct connection"
        private const val LABEL_RELAY_CONNECTION = "Relay connection"
        private const val LABEL_SECURE_CHANNEL = "Secure channel open"
        private const val LABEL_AUTHENTICATING = "Authenticating"
        private const val LABEL_COMPLETE = "Complete"
        
        // Messages for each state
        private const val MSG_SCANNING_QR = "Scanning QR code..."
        private const val MSG_CREATING_CONNECTION = "Creating connection..."
        private const val MSG_REACHING_HOST = "Reaching host..."
        private const val MSG_SETTING_UP_RELAY = "Setting up relay..."
        private const val MSG_WAITING_FOR_HOST = "Waiting for host response..."
        private const val MSG_SECURE_CHANNEL = "Secure channel open..."
        private const val MSG_AUTHENTICATING = "Authenticating..."
        private const val MSG_PAIRED_SUCCESS = "Paired successfully!"
    }
    
    private var stepStartTime: Long = 0
    private val stepDurations = ConcurrentHashMap<Int, Long>()
    private var currentStepIndex: Int = -1
    private var isDirectPath: Boolean = true
    private var directFailed: Boolean = false
    private var started: Boolean = false
    
    private val _progress = MutableStateFlow<PairingProgress>(createInitialProgress())
    val progress: StateFlow<PairingProgress> = _progress.asStateFlow()
    
    /**
     * Resets the tracker to initial state.
     */
    fun reset() {
        stepStartTime = 0
        stepDurations.clear()
        currentStepIndex = -1
        isDirectPath = true
        directFailed = false
        started = false
        _progress.value = createInitialProgress()
    }
    
    /**
     * Called when QR code is successfully parsed.
     * Completes step 0 and moves to step 1.
     * Must be called after start() and before any other transition.
     */
    fun onQrParsed() {
        require(currentStepIndex == -1) { "onQrParsed() must be called first, before any other transition. Current index: $currentStepIndex" }
        require(started) { "start() must be called before onQrParsed()" }
        
        val duration = clock.currentTimeMillis() - stepStartTime
        stepDurations[INDEX_QR_SCANNED] = duration
        currentStepIndex = INDEX_CREATING_CONNECTION
        stepStartTime = clock.currentTimeMillis()
        
        _progress.update { current ->
            createProgressFromState(
                steps = createSteps(step1Status = StepStatus.IN_PROGRESS),
                message = MSG_CREATING_CONNECTION
            )
        }
    }
    
    /**
     * Called when starting to create WebRTC connection (offer creation).
     * Step 1 is in progress.
     */
    fun onCreatingConnection() {
        if (currentStepIndex < INDEX_CREATING_CONNECTION) {
            stepStartTime = clock.currentTimeMillis()
            currentStepIndex = INDEX_CREATING_CONNECTION
        }
    }
    
    /**
     * Called when WebRTC offer is created and signaling begins.
     * Completes step 1 and moves to step 2.
     * Must be called after onQrParsed() or onCreatingConnection().
     */
    fun onConnectionCreated() {
        require(currentStepIndex >= INDEX_CREATING_CONNECTION) { 
            "onConnectionCreated() must be called after onQrParsed(). Current index: $currentStepIndex" 
        }
        
        val duration = clock.currentTimeMillis() - stepStartTime
        stepDurations[INDEX_CREATING_CONNECTION] = duration
        currentStepIndex = INDEX_REACHING_HOST
        stepStartTime = clock.currentTimeMillis()
        
        _progress.update { current ->
            createProgressFromState(
                steps = createSteps(
                    step1Status = StepStatus.COMPLETED,
                    step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                    step2Status = StepStatus.IN_PROGRESS
                ),
                message = MSG_REACHING_HOST
            )
        }
    }
    
    /**
     * Called when direct signaling is in progress.
     * Step 2 (reaching host) is in progress.
     */
    fun onDirectSignaling() {
        _progress.update { current ->
            createProgressFromState(
                steps = createSteps(
                    step1Status = StepStatus.COMPLETED,
                    step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                    step2Status = StepStatus.IN_PROGRESS
                ),
                message = MSG_REACHING_HOST
            )
        }
    }
    
    /**
     * Called when direct connection fails and relay fallback begins.
     * Marks step 2 as unavailable and starts step 3 (relay).
     */
    fun onDirectFailed() {
        directFailed = true
        isDirectPath = false
        val duration = clock.currentTimeMillis() - stepStartTime
        stepDurations[INDEX_REACHING_HOST] = duration
        stepDurations[INDEX_DIRECT_CONNECTION] = 0  // Will be updated when relay completes
        currentStepIndex = INDEX_AUTHENTICATING
        stepStartTime = clock.currentTimeMillis()
        
        _progress.update { current ->
            createProgressFromState(
                steps = createStepsWithRelay(
                    step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                    directDuration = stepDurations[INDEX_REACHING_HOST],
                    step4Status = StepStatus.IN_PROGRESS
                ),
                message = MSG_SETTING_UP_RELAY
            )
        }
    }
    
    /**
     * Called when ntfy subscription is in progress.
     */
    fun onNtfySubscribing() {
        _progress.update { current ->
            createProgressFromState(
                steps = createStepsWithRelay(
                    step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                    directDuration = stepDurations[INDEX_REACHING_HOST],
                    step4Status = StepStatus.IN_PROGRESS
                ),
                message = MSG_SETTING_UP_RELAY
            )
        }
    }
    
    /**
     * Called when waiting for host response via ntfy.
     */
    fun onNtfyWaitingForAnswer() {
        _progress.update { current ->
            createProgressFromState(
                steps = createStepsWithRelay(
                    step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                    directDuration = stepDurations[INDEX_REACHING_HOST],
                    step4Duration = clock.currentTimeMillis() - stepStartTime,
                    step4Status = StepStatus.IN_PROGRESS
                ),
                message = MSG_WAITING_FOR_HOST
            )
        }
    }
    
    /**
     * Called when SDP exchange succeeds (direct or relay).
     * Completes step 2 (direct) or step 4 (relay) and moves to secure channel.
     */
    fun onSignalingComplete(usedNtfy: Boolean) {
        require(currentStepIndex >= INDEX_REACHING_HOST) { "onSignalingComplete() must be called after reaching host phase. Expected index >= $INDEX_REACHING_HOST, found: $currentStepIndex" }
        
        if (usedNtfy) {
            val duration = clock.currentTimeMillis() - stepStartTime
            stepDurations[INDEX_SECURE_CHANNEL] = duration
            currentStepIndex = INDEX_COMPLETE
        } else {
            val duration = clock.currentTimeMillis() - stepStartTime
            stepDurations[INDEX_REACHING_HOST] = duration
            currentStepIndex = INDEX_SECURE_CHANNEL
        }
        stepStartTime = clock.currentTimeMillis()
        
        _progress.update { current ->
            if (usedNtfy) {
                createProgressFromState(
                    steps = createStepsWithRelay(
                        step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                        directDuration = stepDurations[INDEX_REACHING_HOST],
                        step4Duration = stepDurations[INDEX_SECURE_CHANNEL],
                        step4Status = StepStatus.COMPLETED,
                        step5Status = StepStatus.IN_PROGRESS
                    ),
                    message = MSG_SECURE_CHANNEL
                )
            } else {
                createProgressFromState(
                    steps = createSteps(
                        step1Status = StepStatus.COMPLETED,
                        step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                        step2Status = StepStatus.COMPLETED,
                        step2Duration = stepDurations[INDEX_REACHING_HOST],
                        step3Status = StepStatus.IN_PROGRESS
                    ),
                    message = MSG_SECURE_CHANNEL
                )
            }
        }
    }
    
    /**
     * Called when data channel opens.
     * Completes step 3 (direct) or step 5 (relay) and moves to authenticating.
     */
    fun onDataChannelOpen() {
        require(currentStepIndex >= INDEX_SECURE_CHANNEL) { "onDataChannelOpen() must be called after secure channel is established. Expected index >= $INDEX_SECURE_CHANNEL, found: $currentStepIndex" }
        
        if (isDirectPath) {
            val duration = clock.currentTimeMillis() - stepStartTime
            stepDurations[INDEX_DIRECT_CONNECTION] = duration
            currentStepIndex = INDEX_AUTHENTICATING
        } else {
            val duration = clock.currentTimeMillis() - stepStartTime
            stepDurations[INDEX_AUTHENTICATING] = duration
            currentStepIndex = INDEX_COMPLETE
        }
        stepStartTime = clock.currentTimeMillis()
        
        _progress.update { current ->
            if (isDirectPath) {
                createProgressFromState(
                    steps = createSteps(
                        step1Status = StepStatus.COMPLETED,
                        step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                        step2Status = StepStatus.COMPLETED,
                        step2Duration = stepDurations[INDEX_REACHING_HOST],
                        step3Status = StepStatus.COMPLETED,
                        step3Duration = stepDurations[INDEX_DIRECT_CONNECTION],
                        step4Status = StepStatus.IN_PROGRESS
                    ),
                    message = MSG_AUTHENTICATING
                )
            } else {
                createProgressFromState(
                    steps = createStepsWithRelay(
                        step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                        directDuration = stepDurations[INDEX_REACHING_HOST],
                        step4Duration = stepDurations[INDEX_SECURE_CHANNEL],
                        step5Duration = stepDurations[INDEX_AUTHENTICATING],
                        step5Status = StepStatus.COMPLETED,
                        step6Status = StepStatus.IN_PROGRESS
                    ),
                    message = MSG_AUTHENTICATING
                )
            }
        }
    }
    
    /**
     * Called when authentication succeeds.
     * Completes all steps.
     */
    fun onAuthenticated() {
        require(currentStepIndex >= INDEX_AUTHENTICATING) { "onAuthenticated() must be called during authenticating phase. Expected index >= $INDEX_AUTHENTICATING, found: $currentStepIndex" }
        
        val duration = clock.currentTimeMillis() - stepStartTime
        if (isDirectPath) {
            stepDurations[INDEX_SECURE_CHANNEL] = duration
        } else {
            stepDurations[INDEX_COMPLETE] = duration
        }
        
        _progress.update { current ->
            if (isDirectPath) {
                createProgressFromState(
                    steps = createSteps(
                        step1Status = StepStatus.COMPLETED,
                        step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                        step2Status = StepStatus.COMPLETED,
                        step2Duration = stepDurations[INDEX_REACHING_HOST],
                        step3Status = StepStatus.COMPLETED,
                        step3Duration = stepDurations[INDEX_DIRECT_CONNECTION],
                        step4Status = StepStatus.COMPLETED,
                        step4Duration = stepDurations[INDEX_SECURE_CHANNEL],
                        step5Status = StepStatus.COMPLETED
                    ),
                    message = MSG_PAIRED_SUCCESS,
                    isComplete = true
                )
            } else {
                createProgressFromState(
                    steps = createStepsWithRelay(
                        step1Duration = stepDurations[INDEX_CREATING_CONNECTION],
                        directDuration = stepDurations[INDEX_REACHING_HOST],
                        step4Duration = stepDurations[INDEX_SECURE_CHANNEL],
                        step4Status = StepStatus.COMPLETED,
                        step5Duration = stepDurations[INDEX_AUTHENTICATING],
                        step5Status = StepStatus.COMPLETED,
                        step6Duration = stepDurations[INDEX_COMPLETE],
                        step6Status = StepStatus.COMPLETED,
                        step7Status = StepStatus.COMPLETED
                    ),
                    message = MSG_PAIRED_SUCCESS,
                    isComplete = true
                )
            }
        }
    }
    
    /**
     * Called when pairing fails.
     * Marks the current step as unavailable.
     */
    fun onFailed() {
        require(currentStepIndex >= 0) { "onFailed() called but pairing has not started. Expected index >= 0, found: $currentStepIndex" }
        
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
    
    /**
     * Initialize the tracker when pairing starts.
     */
    fun start() {
        reset()
        started = true
        stepStartTime = clock.currentTimeMillis()
    }
    
    // Helper functions to create step lists
    
    private fun createInitialProgress(): PairingProgress {
        return PairingProgress(
            steps = listOf(
                PairingStep(LABEL_QR_SCANNED, StepStatus.IN_PROGRESS),
                PairingStep(LABEL_CREATING_CONNECTION, StepStatus.PENDING),
                PairingStep(LABEL_REACHING_HOST, StepStatus.PENDING),
                PairingStep(LABEL_SECURE_CHANNEL, StepStatus.PENDING),
                PairingStep(LABEL_AUTHENTICATING, StepStatus.PENDING),
                PairingStep(LABEL_COMPLETE, StepStatus.PENDING)
            ),
            currentMessage = MSG_SCANNING_QR,
            isComplete = false
        )
    }
    
    private fun createProgressFromState(
        steps: List<PairingStep>,
        message: String,
        isComplete: Boolean = false
    ): PairingProgress {
        return PairingProgress(
            steps = steps,
            currentMessage = message,
            isComplete = isComplete
        )
    }
    
    private fun createSteps(
        step0Duration: Long? = null,
        step1Status: StepStatus = StepStatus.PENDING,
        step1Duration: Long? = null,
        step2Status: StepStatus = StepStatus.PENDING,
        step2Duration: Long? = null,
        step3Status: StepStatus = StepStatus.PENDING,
        step3Duration: Long? = null,
        step4Status: StepStatus = StepStatus.PENDING,
        step4Duration: Long? = null,
        step5Status: StepStatus = StepStatus.PENDING
    ): List<PairingStep> {
        return listOf(
            PairingStep(LABEL_QR_SCANNED, StepStatus.COMPLETED, step0Duration ?: stepDurations[INDEX_QR_SCANNED] ?: 0),
            PairingStep(LABEL_CREATING_CONNECTION, step1Status, step1Duration),
            PairingStep(LABEL_REACHING_HOST, step2Status, step2Duration),
            PairingStep(LABEL_SECURE_CHANNEL, step3Status, step3Duration),
            PairingStep(LABEL_AUTHENTICATING, step4Status, step4Duration),
            PairingStep(LABEL_COMPLETE, step5Status)
        )
    }
    
    private fun createStepsWithRelay(
        step0Duration: Long? = null,
        step1Duration: Long? = null,
        directDuration: Long? = null,
        step4Duration: Long? = null,
        step4Status: StepStatus = StepStatus.PENDING,
        step5Duration: Long? = null,
        step5Status: StepStatus = StepStatus.PENDING,
        step6Duration: Long? = null,
        step6Status: StepStatus = StepStatus.PENDING,
        step7Status: StepStatus = StepStatus.PENDING
    ): List<PairingStep> {
        return listOf(
            PairingStep(LABEL_QR_SCANNED, StepStatus.COMPLETED, step0Duration ?: stepDurations[INDEX_QR_SCANNED] ?: 0),
            PairingStep(LABEL_CREATING_CONNECTION, StepStatus.COMPLETED, step1Duration),
            PairingStep(LABEL_DIRECT_CONNECTION, StepStatus.UNAVAILABLE, directDuration),
            PairingStep(LABEL_RELAY_CONNECTION, step4Status, step4Duration),
            PairingStep(LABEL_SECURE_CHANNEL, step5Status, step5Duration),
            PairingStep(LABEL_AUTHENTICATING, step6Status, step6Duration),
            PairingStep(LABEL_COMPLETE, step7Status)
        )
    }
}
