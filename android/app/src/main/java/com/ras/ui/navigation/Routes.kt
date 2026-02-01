package com.ras.ui.navigation

/**
 * Navigation routes for the app.
 */
sealed class Routes(val route: String) {
    /** Home screen - app entry point showing device info */
    data object Home : Routes("home")

    /** Connecting screen - shows connection progress */
    data object Connecting : Routes("connecting")

    /** QR scanning and pairing screen */
    data object Pairing : Routes("pairing")

    /** List of tmux sessions */
    data object Sessions : Routes("sessions")

    /** Terminal view for a specific session */
    data object Terminal : Routes("terminal/{sessionId}") {
        fun createRoute(sessionId: String): String = "terminal/$sessionId"
    }

    /** App settings */
    data object Settings : Routes("settings")

    /** Create new session wizard */
    data object CreateSession : Routes("create_session")
}

/**
 * Navigation arguments
 */
object NavArgs {
    const val SESSION_ID = "sessionId"
}
