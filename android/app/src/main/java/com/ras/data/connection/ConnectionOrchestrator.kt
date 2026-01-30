package com.ras.data.connection

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates connection attempts across multiple strategies.
 *
 * Tries strategies in priority order (lower priority value = tried first).
 * Reports progress for UI feedback. Falls back to next strategy on failure.
 *
 * Usage:
 * ```
 * orchestrator.connect(context) { progress ->
 *     // Update UI with progress
 * }
 * ```
 */
@Singleton
class ConnectionOrchestrator @Inject constructor(
    private val strategies: Set<@JvmSuppressWildcards ConnectionStrategy>
) {
    companion object {
        private const val TAG = "ConnectionOrchestrator"
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _currentTransport = MutableStateFlow<Transport?>(null)
    val currentTransport: StateFlow<Transport?> = _currentTransport.asStateFlow()

    private var connectionJob: Job? = null

    /**
     * Attempt to connect using available strategies.
     *
     * Strategies are tried in priority order. Detection happens first
     * (can be parallel), then connection attempts are sequential.
     *
     * @param context Connection context with daemon info
     * @param onProgress Callback for progress updates
     * @return The connected transport, or null if all strategies failed
     */
    suspend fun connect(
        context: ConnectionContext,
        onProgress: (ConnectionProgress) -> Unit
    ): Transport? = coroutineScope {
        // Cancel any existing connection attempt
        connectionJob?.cancel()

        val sortedStrategies = strategies.sortedBy { it.priority }
        Log.i(TAG, "Starting connection with ${sortedStrategies.size} strategies: " +
                sortedStrategies.joinToString { "${it.name} (p${it.priority})" })

        _state.value = ConnectionState.DETECTING
        val failedAttempts = mutableListOf<FailedAttempt>()

        // Phase 1: Detection (could be parallel, but sequential is simpler for progress)
        val availableStrategies = mutableListOf<Pair<ConnectionStrategy, String?>>()

        for (strategy in sortedStrategies) {
            onProgress(ConnectionProgress.Detecting(strategy.name))
            Log.d(TAG, "Detecting ${strategy.name}...")

            try {
                when (val result = strategy.detect()) {
                    is DetectionResult.Available -> {
                        Log.i(TAG, "${strategy.name} available: ${result.info ?: "ready"}")
                        onProgress(ConnectionProgress.StrategyAvailable(strategy.name, result.info))
                        availableStrategies.add(strategy to result.info)
                    }
                    is DetectionResult.Unavailable -> {
                        Log.i(TAG, "${strategy.name} unavailable: ${result.reason}")
                        onProgress(ConnectionProgress.StrategyUnavailable(strategy.name, result.reason))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${strategy.name} detection failed", e)
                onProgress(ConnectionProgress.StrategyUnavailable(strategy.name, e.message ?: "Detection error"))
            }
        }

        if (availableStrategies.isEmpty()) {
            Log.e(TAG, "No strategies available!")
            _state.value = ConnectionState.FAILED
            onProgress(ConnectionProgress.AllFailed(emptyList()))
            return@coroutineScope null
        }

        // Phase 2: Connection attempts (sequential by priority)
        _state.value = ConnectionState.CONNECTING

        for ((index, pair) in availableStrategies.withIndex()) {
            val (strategy, _) = pair
            val isLast = index == availableStrategies.size - 1
            val startTime = System.currentTimeMillis()

            Log.i(TAG, "Trying ${strategy.name}...")

            try {
                val result = strategy.connect(context) { step ->
                    onProgress(ConnectionProgress.Connecting(
                        strategyName = strategy.name,
                        step = step.step,
                        detail = step.detail,
                        progress = step.progress
                    ))
                }

                when (result) {
                    is ConnectionResult.Success -> {
                        Log.i(TAG, "Connected via ${strategy.name}!")
                        _state.value = ConnectionState.CONNECTED
                        _currentTransport.value = result.transport
                        onProgress(ConnectionProgress.Connected(strategy.name, result.transport))
                        return@coroutineScope result.transport
                    }
                    is ConnectionResult.Failed -> {
                        val duration = System.currentTimeMillis() - startTime
                        Log.w(TAG, "${strategy.name} failed after ${duration}ms: ${result.error}")
                        failedAttempts.add(FailedAttempt(strategy.name, result.error, duration))
                        onProgress(ConnectionProgress.StrategyFailed(
                            strategyName = strategy.name,
                            error = result.error,
                            willTryNext = !isLast
                        ))
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Connection cancelled during ${strategy.name}")
                _state.value = ConnectionState.CANCELLED
                onProgress(ConnectionProgress.Cancelled)
                throw e
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "${strategy.name} threw exception", e)
                failedAttempts.add(FailedAttempt(strategy.name, e.message ?: "Unknown error", duration))
                onProgress(ConnectionProgress.StrategyFailed(
                    strategyName = strategy.name,
                    error = e.message ?: "Unknown error",
                    willTryNext = !isLast
                ))
            }
        }

        // All strategies failed
        Log.e(TAG, "All ${failedAttempts.size} strategies failed")
        _state.value = ConnectionState.FAILED
        onProgress(ConnectionProgress.AllFailed(failedAttempts))
        return@coroutineScope null
    }

    /**
     * Disconnect and close the current transport.
     */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        _currentTransport.value?.close()
        _currentTransport.value = null
        _state.value = ConnectionState.IDLE
    }

    /**
     * Cancel an ongoing connection attempt.
     */
    fun cancel() {
        connectionJob?.cancel()
        connectionJob = null
        _state.value = ConnectionState.CANCELLED
    }
}
