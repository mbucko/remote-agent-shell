package com.ras

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.ras.data.reconnection.ReconnectionController
import com.ras.lifecycle.AppLifecycleObserver
import com.ras.notifications.NotificationChannels
import com.ras.service.ConnectionServiceController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class RasApplication : Application() {

    @Inject
    lateinit var appLifecycleObserver: AppLifecycleObserver

    @Inject
    lateinit var connectionServiceController: ConnectionServiceController

    @Inject
    lateinit var reconnectionController: ReconnectionController

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Create notification channels
        NotificationChannels.createChannels(this)

        // Register lifecycle observer for foreground/background detection
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        // Initialize controllers
        connectionServiceController.initialize()
        reconnectionController.initialize()

        // Set up global uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
        }

        Log.i(TAG, "Application initialized with lifecycle observer and controllers")
    }

    companion object {
        private const val TAG = "RasApplication"

        /**
         * Global coroutine exception handler for use in coroutine scopes.
         */
        val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught coroutine exception", throwable)
        }
    }
}
