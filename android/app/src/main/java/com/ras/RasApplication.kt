package com.ras

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler

@HiltAndroidApp
class RasApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Set up global uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
        }
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
