package com.ras.ui.navigation

/**
 * Navigation routes for the app.
 */
sealed class Routes(val route: String) {
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
}

/**
 * Navigation arguments
 */
object NavArgs {
    const val SESSION_ID = "sessionId"
}
