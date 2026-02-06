package com.ras.data.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Handles ICE recovery timeout logic.
 *
 * When ICE state transitions to DISCONNECTED (transient), starts a recovery timer.
 * If ICE recovers (CONNECTED/COMPLETED) within the timeout, the timer is cancelled.
 * If the timeout expires or ICE transitions to FAILED (terminal), invokes [onRecoveryFailed].
 *
 * This class is extracted from WebRTCClient for testability - it can be tested
 * without native WebRTC dependencies.
 */
class IceRecoveryHandler(
    private val scope: CoroutineScope,
    private val recoveryTimeoutMs: Long = DEFAULT_RECOVERY_TIMEOUT_MS,
    private val onRecoveryFailed: () -> Unit
) {
    companion object {
        private const val TAG = "IceRecoveryHandler"
        const val DEFAULT_RECOVERY_TIMEOUT_MS = 15_000L
    }

    private var recoveryJob: Job? = null

    /**
     * ICE state transitioned to DISCONNECTED (transient).
     * Starts a recovery timer - if ICE doesn't recover within [recoveryTimeoutMs],
     * invokes [onRecoveryFailed].
     */
    fun onIceDisconnected() {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(recoveryTimeoutMs)
            onRecoveryFailed()
        }
    }

    /**
     * ICE state transitioned to CONNECTED or COMPLETED.
     * Cancels any pending recovery timer.
     */
    fun onIceRecovered() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    /**
     * ICE state transitioned to FAILED (terminal).
     * Cancels any pending recovery timer and immediately invokes [onRecoveryFailed].
     */
    fun onIceFailed() {
        recoveryJob?.cancel()
        recoveryJob = null
        onRecoveryFailed()
    }

    /**
     * Cancel any pending recovery timer.
     * Call this when closing the connection.
     */
    fun cancel() {
        recoveryJob?.cancel()
        recoveryJob = null
    }
}
