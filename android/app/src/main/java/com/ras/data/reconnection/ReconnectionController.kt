package com.ras.data.reconnection

import android.util.Log
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.di.IoDispatcher
import com.ras.domain.startup.ReconnectionResult
import com.ras.lifecycle.AppLifecycleObserver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller for auto-connect on app foreground.
 *
 * This handles ONE specific case: when the app returns to foreground and the
 * connection was lost while in background (e.g., OS killed it), auto-reconnect
 * IF the user hasn't manually disconnected.
 *
 * Auto-connect is triggered when:
 * - App comes to foreground AND connection is lost AND user didn't manually disconnect
 *
 * Auto-connect is NOT triggered when:
 * - Already connected
 * - Already reconnecting (prevents duplicates)
 * - No credentials stored
 * - User manually disconnected (they must explicitly reconnect)
 *
 * NOTE: This does NOT auto-reconnect on connection errors. When user clicks
 * disconnect, that's final. They must explicitly tap a device to reconnect.
 *
 * Usage:
 * ```
 * // In Application.onCreate():
 * reconnectionController.initialize()
 * ```
 */
@Singleton
class ReconnectionController @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val reconnectionService: ReconnectionService,
    private val credentialRepository: CredentialRepository,
    private val keyManager: KeyManager,
    private val appLifecycleObserver: AppLifecycleObserver,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ReconnectionController"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in ReconnectionController", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)

    // Mutex to prevent race conditions in reconnection attempts
    private val reconnectMutex = Mutex()

    private val _isReconnecting = MutableStateFlow(false)

    /**
     * StateFlow indicating whether a reconnection attempt is in progress.
     * Use this to prevent duplicate reconnection attempts or show UI state.
     */
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _reconnectionResult = MutableSharedFlow<ReconnectionResult>()

    /**
     * SharedFlow of reconnection results.
     * Subscribe to receive notifications when reconnection completes.
     */
    val reconnectionResult: SharedFlow<ReconnectionResult> = _reconnectionResult.asSharedFlow()

    /**
     * Initialize the controller and start observing foreground events.
     *
     * Must be called once during app initialization (e.g., in Application.onCreate).
     */
    fun initialize() {
        // Auto-connect when app returns to foreground (if connection was lost in background)
        scope.launch {
            appLifecycleObserver.appInForeground
                .drop(1) // Skip initial value - StartupViewModel handles initial connection
                .filter { it } // Only when coming to foreground
                .collect {
                    Log.d(TAG, "App came to foreground, checking if auto-connect needed")
                    attemptReconnectIfNeeded()
                }
        }
        // NOTE: We intentionally do NOT listen to connectionErrors here.
        // When user disconnects, that's final - they must explicitly reconnect.
    }

    /**
     * Attempt to reconnect if all conditions are met.
     *
     * Guards against:
     * - Already reconnecting (mutex provides synchronization)
     * - Already connected
     * - No credentials
     * - User manually disconnected
     *
     * Uses tryLock() for fail-fast mutual exclusion - if another coroutine is
     * already reconnecting, this returns immediately instead of queueing up.
     *
     * @return true if reconnection succeeded, false otherwise
     */
    suspend fun attemptReconnectIfNeeded(): Boolean {
        // Use tryLock to fail fast if another reconnection is in progress
        // This prevents queueing up reconnection attempts
        if (!reconnectMutex.tryLock()) {
            Log.d(TAG, "Skipping reconnection: another attempt in progress")
            return false
        }

        try {
            // Guard: already connected
            if (connectionManager.isConnected.value) {
                Log.d(TAG, "Skipping reconnection: already connected")
                return false
            }

            // Guard: no credentials
            if (credentialRepository.getSelectedDevice() == null) {
                Log.d(TAG, "Skipping reconnection: no credentials")
                return false
            }

            // Guard: user manually disconnected
            if (keyManager.isDisconnectedOnce()) {
                Log.d(TAG, "Skipping reconnection: user manually disconnected")
                return false
            }

            return attemptReconnect()
        } finally {
            reconnectMutex.unlock()
        }
    }

    private suspend fun attemptReconnect(): Boolean {
        _isReconnecting.value = true
        try {
            Log.i(TAG, "Starting automatic reconnection")
            val result = reconnectionService.reconnect()
            _reconnectionResult.emit(result)

            return when (result) {
                is ReconnectionResult.Success -> {
                    Log.i(TAG, "Automatic reconnection succeeded")
                    true
                }
                is ReconnectionResult.Failure -> {
                    Log.w(TAG, "Automatic reconnection failed: $result")
                    false
                }
            }
        } finally {
            _isReconnecting.value = false
        }
    }
}
