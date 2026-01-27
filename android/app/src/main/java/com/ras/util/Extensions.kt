package com.ras.util

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController

/**
 * Navigate only if the lifecycle is in RESUMED state.
 * Prevents crashes from navigation during configuration changes.
 */
fun NavController.navigateIfResumed(route: String, lifecycleOwner: LifecycleOwner) {
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        navigateSafe(route)
    }
}

/**
 * Navigate with safety checks to prevent duplicate navigation.
 */
fun NavController.navigateSafe(route: String) {
    val currentRoute = currentBackStackEntry?.destination?.route
    if (currentRoute != route) {
        navigate(route) {
            launchSingleTop = true
            restoreState = true
        }
    }
}

/**
 * Show a short toast message.
 */
fun Context.showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * Show a long toast message.
 */
fun Context.showLongToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

/**
 * Convert a string to a JavaScript-safe string literal.
 */
fun String.toJsString(): String {
    return buildString {
        append('"')
        for (char in this@toJsString) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
