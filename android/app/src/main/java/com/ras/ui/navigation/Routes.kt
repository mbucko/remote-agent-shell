package com.ras.ui.navigation

/**
 * Navigation routes for the app.
 */
sealed class Routes(val route: String) {
    /** Startup screen - checks credentials and auto-reconnects */
    data object Startup : Routes("startup")

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

    /** Disconnected home screen - shown when user manually disconnects */
    data object Disconnected : Routes("disconnected")

    /** Create new session wizard */
    data object CreateSession : Routes("create_session")
}

/**
 * Navigation arguments
 */
object NavArgs {
    const val SESSION_ID = "sessionId"
}
