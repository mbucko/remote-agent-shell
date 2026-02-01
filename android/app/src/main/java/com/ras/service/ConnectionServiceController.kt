package com.ras.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.ras.data.connection.ConnectionManager
import com.ras.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controls the ConnectionService lifecycle based on ConnectionManager state.
 *
 * This controller observes ConnectionManager.isConnected and:
 * - Starts the foreground service when a connection is established
 * - Stops the service when the connection is lost
 *
 * This keeps the ConnectionManager free of Android dependencies (Context),
 * following the separation of concerns principle.
 *
 * Usage:
 * ```
 * // In Application.onCreate():
 * connectionServiceController.initialize()
 * ```
 */
@Singleton
class ConnectionServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ConnectionServiceController"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in ConnectionServiceController", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)
    private var serviceRunning = false

    /**
     * Initialize the controller and start observing connection state.
     *
     * Must be called once during app initialization (e.g., in Application.onCreate).
     */
    fun initialize() {
        scope.launch {
            connectionManager.isConnected.collect { isConnected ->
                if (isConnected && !serviceRunning) {
                    startService()
                } else if (!isConnected && serviceRunning) {
                    stopService()
                }
            }
        }
    }

    private fun startService() {
        val intent = Intent(context, ConnectionService::class.java)
        ContextCompat.startForegroundService(context, intent)
        serviceRunning = true
    }

    private fun stopService() {
        context.stopService(Intent(context, ConnectionService::class.java))
        serviceRunning = false
    }
}
