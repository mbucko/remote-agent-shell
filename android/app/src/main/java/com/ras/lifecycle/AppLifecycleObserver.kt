package com.ras.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes app-level lifecycle events using ProcessLifecycleOwner.
 *
 * This is a lightweight observer that only tracks foreground/background state.
 * It does NOT trigger reconnection directly - that responsibility belongs to
 * ReconnectionController which observes this state.
 *
 * Usage:
 * ```
 * // In Application.onCreate():
 * ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
 * ```
 */
@Singleton
class AppLifecycleObserver @Inject constructor() : DefaultLifecycleObserver {

    private val _appInForeground = MutableStateFlow(true)

    /**
     * StateFlow indicating whether the app is in foreground.
     *
     * - true: App is visible to user (onStart called)
     * - false: App is in background (onStop called)
     *
     * Initial value is true because apps start in foreground.
     */
    val appInForeground: StateFlow<Boolean> = _appInForeground.asStateFlow()

    /**
     * Called when the app becomes visible (any Activity starts).
     * This is called after onResume of the first visible Activity.
     */
    override fun onStart(owner: LifecycleOwner) {
        _appInForeground.value = true
    }

    /**
     * Called when the app is no longer visible (all Activities stopped).
     * This is called after onStop of the last visible Activity.
     */
    override fun onStop(owner: LifecycleOwner) {
        _appInForeground.value = false
    }
}
