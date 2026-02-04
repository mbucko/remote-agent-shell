package com.ras.data.connection

import android.util.Log
import com.ras.data.credentials.CredentialRepository
import com.ras.util.GlobalConnectionTimer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private val strategies: Set<@JvmSuppressWildcards ConnectionStrategy>,
    private val credentialRepository: CredentialRepository
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
     * Flow:
     * 1. Discovery: Detect local capabilities (Tailscale IP, etc.)
     * 2. Exchange: Get daemon's current capabilities via signaling
     * 3. Detection: Check which strategies are available
     * 4. Connection: Try strategies in priority order
     *
     * @param context Connection context with daemon info
     * @param onProgress Callback for progress updates
     * @return The connected transport, or null if all strategies failed
     */
    suspend fun connect(
        context: ConnectionContext,
        onProgress: (ConnectionProgress) -> Unit
    ): Transport? = coroutineScope {
        // Guard: Don't start a new connection if we already have one
        val existingTransport = _currentTransport.value
        if (existingTransport != null && existingTransport.isConnected) {
            Log.w(TAG, "connect() called but already have a connected transport - skipping")
            return@coroutineScope existingTransport
        }

        // Guard: Don't start if already in CONNECTING state
        if (_state.value == ConnectionState.CONNECTING) {
            Log.w(TAG, "connect() called but already connecting - skipping")
            return@coroutineScope null
        }

        // Cancel any existing connection attempt
        connectionJob?.cancel()

        // Start global connection timer
        GlobalConnectionTimer.start("connection")

        val sortedStrategies = strategies.sortedBy { it.priority }
        Log.i(TAG, "Starting connection with ${sortedStrategies.size} strategies: " +
                sortedStrategies.joinToString { "${it.name} (p${it.priority})" })

        // =================================================================
        // Phase 0: Parallel Capability Discovery
        // =================================================================
        // Start all probes in parallel for faster connection establishment
        GlobalConnectionTimer.logMark("discovery_start")
        onProgress(ConnectionProgress.DiscoveryStarted)

        // Start Tailscale detection in parallel (fast, ~10ms)
        onProgress(ConnectionProgress.TailscaleDetecting)
        val tailscaleDeferred: Deferred<TailscaleInfo?> = async {
            TailscaleDetector.detect(context.androidContext)
        }

        // Exchange capabilities - this now races direct vs ntfy internally
        onProgress(ConnectionProgress.ExchangingCapabilities)

        // Get Tailscale result (should be ready quickly)
        val localTailscale = tailscaleDeferred.await()
        onProgress(ConnectionProgress.LocalCapabilities(
            tailscaleIp = localTailscale?.ip,
            tailscaleInterface = localTailscale?.interfaceName,
            supportsWebRTC = true
        ))
        Log.i(TAG, "Local capabilities: tailscale=${localTailscale?.ip ?: "none"}")

        // Build our capabilities with Tailscale info
        val ourCapabilities = ConnectionCapabilities(
            tailscaleIp = localTailscale?.ip,
            tailscalePort = 9876, // Default port
            supportsWebRTC = true,
            supportsTurn = false,
            protocolVersion = 1
        )

        // Exchange capabilities with daemon - but SKIP if we don't have local Tailscale
        // because we can't use TailscaleStrategy anyway, and WebRTC doesn't need daemon's
        // Tailscale info. This saves ~4 seconds when Tailscale isn't available.
        val daemonCapabilities = if (localTailscale != null) {
            try {
                context.signaling.exchangeCapabilities(ourCapabilities, onProgress)
            } catch (e: CancellationException) {
                throw e  // Never swallow CancellationException - it breaks coroutine cancellation
            } catch (e: Exception) {
                Log.w(TAG, "Capability exchange error: ${e.message}")
                null
            }
        } else {
            Log.i(TAG, "Skipping capability exchange - no local Tailscale available")
            onProgress(ConnectionProgress.CapabilityExchangeSkipped("No local Tailscale"))
            null
        }

        // Update context with daemon's Tailscale info (dynamic, not from stored credentials)
        GlobalConnectionTimer.logMark("capability_exchange_done")
        val enrichedContext = if (daemonCapabilities != null) {
            onProgress(ConnectionProgress.DaemonCapabilities(
                tailscaleIp = daemonCapabilities.tailscaleIp,
                tailscalePort = daemonCapabilities.tailscalePort,
                supportsWebRTC = daemonCapabilities.supportsWebRTC,
                protocolVersion = daemonCapabilities.protocolVersion
            ))
            Log.i(TAG, "Daemon capabilities: tailscale=${daemonCapabilities.tailscaleIp}:${daemonCapabilities.tailscalePort}")

            // Persist daemon's Tailscale IP for future fast reconnection
            if (!daemonCapabilities.tailscaleIp.isNullOrEmpty()) {
                try {
                    credentialRepository.updateTailscaleInfo(
                        context.deviceId,
                        daemonCapabilities.tailscaleIp,
                        daemonCapabilities.tailscalePort
                    )
                    Log.i(TAG, "Cached daemon Tailscale IP from capability exchange: ${daemonCapabilities.tailscaleIp}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cache Tailscale IP: ${e.message}")
                }
            }

            // Enrich context with fresh daemon Tailscale info and local Tailscale status
            context.copy(
                daemonTailscaleIp = daemonCapabilities.tailscaleIp,
                daemonTailscalePort = daemonCapabilities.tailscalePort,
                localTailscaleAvailable = true
            )
        } else if (localTailscale == null) {
            // No local Tailscale - skip capability exchange entirely
            context.copy(localTailscaleAvailable = false)
        } else {
            onProgress(ConnectionProgress.CapabilityExchangeFailed("Could not reach daemon"))
            Log.w(TAG, "Capability exchange failed, proceeding with stored credentials")
            // Still set localTailscaleAvailable
            context.copy(localTailscaleAvailable = true)
        }

        // =================================================================
        // Phase 1: Strategy Detection
        // =================================================================
        GlobalConnectionTimer.logMark("strategy_detection_start")
        _state.value = ConnectionState.DETECTING
        val failedAttempts = mutableListOf<FailedAttempt>()
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

        // =================================================================
        // Phase 2: Connection Attempts (sequential by priority)
        // =================================================================
        GlobalConnectionTimer.logMark("connection_attempts_start")
        _state.value = ConnectionState.CONNECTING

        for ((index, pair) in availableStrategies.withIndex()) {
            val (strategy, _) = pair
            val isLast = index == availableStrategies.size - 1
            val startTime = System.currentTimeMillis()

            Log.i(TAG, "Trying ${strategy.name}...")

            try {
                val result = strategy.connect(enrichedContext) { step ->
                    onProgress(ConnectionProgress.Connecting(
                        strategyName = strategy.name,
                        step = step.step,
                        detail = step.detail,
                        progress = step.progress
                    ))
                }

                when (result) {
                    is ConnectionResult.Success -> {
                        val duration = System.currentTimeMillis() - startTime
                        GlobalConnectionTimer.logMark("connected")
                        GlobalConnectionTimer.logSummary()
                        Log.i(TAG, "Connected via ${strategy.name} in ${duration}ms!")
                        _state.value = ConnectionState.CONNECTED
                        _currentTransport.value = result.transport
                        onProgress(ConnectionProgress.Connected(strategy.name, result.transport, duration))

                        // Extract and cache Tailscale IP from WebRTC ICE candidates
                        // This enables fast Tailscale Direct on subsequent connections
                        extractAndCacheTailscaleIp(
                            result.transport,
                            enrichedContext.deviceId,
                            enrichedContext.localTailscaleAvailable
                        )

                        return@coroutineScope result.transport
                    }
                    is ConnectionResult.Failed -> {
                        val duration = System.currentTimeMillis() - startTime
                        Log.w(TAG, "${strategy.name} failed after ${duration}ms: ${result.error}")
                        failedAttempts.add(FailedAttempt(strategy.name, result.error, duration))
                        onProgress(ConnectionProgress.StrategyFailed(
                            strategyName = strategy.name,
                            error = result.error,
                            durationMs = duration,
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
                    durationMs = duration,
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

    /**
     * Extract Tailscale IP from WebRTC ICE candidates and cache it for future connections.
     *
     * This enables fast Tailscale Direct connections on subsequent reconnects.
     * Only caches if:
     * - Transport is WebRTC (has ICE candidates)
     * - Local Tailscale is available (we can route to Tailscale IPs)
     * - Remote candidate is in Tailscale IP range
     *
     * @param transport The connected transport
     * @param deviceId The device ID to update credentials for
     * @param localTailscaleAvailable Whether local Tailscale is detected
     */
    private suspend fun extractAndCacheTailscaleIp(
        transport: Transport,
        deviceId: String,
        localTailscaleAvailable: Boolean
    ) {
        // Only extract from WebRTC transport (has ICE candidates)
        if (transport !is WebRTCTransport) {
            Log.d(TAG, "Not extracting Tailscale IP - not WebRTC transport")
            return
        }

        // Only cache if local Tailscale is available
        // (otherwise we can't use the cached IP anyway)
        if (!localTailscaleAvailable) {
            Log.d(TAG, "Not caching Tailscale IP - local Tailscale not available")
            return
        }

        try {
            val path = transport.client.getActivePath()
            if (path == null) {
                Log.d(TAG, "No active path available from WebRTC stats")
                return
            }

            // Check if remote candidate is a Tailscale IP
            if (!path.remote.isTailscaleIp()) {
                Log.d(TAG, "Remote candidate is not Tailscale IP: ${path.remote.ip}")
                return
            }

            // Cache the daemon's Tailscale IP for future fast reconnection
            // Note: ICE ports are ephemeral, so we use the default Tailscale port (9876)
            // The actual listening port is fixed, not the ICE candidate port
            val tailscalePort = 9876
            Log.i(TAG, "Caching daemon Tailscale IP from ICE: ${path.remote.ip}:$tailscalePort")
            credentialRepository.updateTailscaleInfo(
                deviceId,
                path.remote.ip,
                tailscalePort
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract/cache Tailscale IP from ICE: ${e.message}")
        }
    }
}
