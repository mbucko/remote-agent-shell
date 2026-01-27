package com.ras.ntfy

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates IP change handling: receives notifications from NtfySubscriber,
 * triggers reconnection, and notifies UI on success.
 *
 * @param ntfySubscriber The subscriber to listen for IP changes
 * @param onReconnect Callback to trigger reconnection, returns true on success
 * @param onReconnectSuccess Callback to notify UI of successful reconnection
 * @param dispatcher Coroutine dispatcher for background work
 * @param mainDispatcher Coroutine dispatcher for UI callbacks (Main thread)
 */
class IpChangeHandler(
    private val ntfySubscriber: NtfySubscriber,
    private val onReconnect: suspend (ip: String, port: Int) -> Boolean,
    private val onReconnectSuccess: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    companion object {
        private const val TAG = "IpChangeHandler"
    }

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * Start listening for IP changes.
     */
    fun start() {
        scope = CoroutineScope(dispatcher + SupervisorJob())
        job = scope?.launch {
            ntfySubscriber.ipChanges.collect { data ->
                Log.i(TAG, "Processing IP change")
                Log.d(TAG, "Reconnecting to ${data.ip}:${data.port}")

                try {
                    val success = onReconnect(data.ip, data.port)
                    if (success) {
                        Log.i(TAG, "Reconnection successful")
                        ntfySubscriber.resetReconnectCounter()
                        withContext(mainDispatcher) {
                            onReconnectSuccess()
                        }
                    } else {
                        Log.w(TAG, "Reconnection failed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnection error: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop listening for IP changes.
     */
    fun stop() {
        job?.cancel()
        scope?.cancel()
        scope = null
    }
}
