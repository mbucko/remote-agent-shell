package com.ras

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.ras.notifications.NotificationHandler
import com.ras.ui.navigation.NavGraph
import com.ras.ui.navigation.Routes
import com.ras.ui.theme.RASTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main activity for the RAS app.
 *
 * Handles:
 * - App startup and navigation setup
 * - Deep link handling from notification taps
 * - Notification permission requests (Android 13+)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var notificationHandler: NotificationHandler

    // Channel to communicate deep link navigation to composable
    // Using Channel for guaranteed single delivery of navigation events
    private val _deepLinkNavigation = Channel<String>(Channel.BUFFERED)
    private val deepLinkNavigation = _deepLinkNavigation.receiveAsFlow()

    // Track if we've already handled the current intent to prevent double handling
    private var handledIntentHashCode: Int? = null

    // Permission request launcher for Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, notifications will now work
        } else {
            // Permission denied - notifications will silently fail
            // No need to show error, user made a choice
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        // Handle notification tap if app was launched from notification
        handleNotificationIntent(intent)

        setContent {
            RASTheme {
                MainContent(deepLinkNavigation = deepLinkNavigation)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Reset handled intent to allow new intent processing
        handledIntentHashCode = null
        // Handle notification tap when app is already running
        handleNotificationIntent(intent)
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+.
     * This is required to show notifications on Android 13 and above.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Handle intent from notification tap.
     *
     * Navigates to the appropriate screen based on the intent extras.
     * Does NOT validate if session exists - let the destination handle that.
     * This avoids race conditions when sessions haven't loaded yet on cold start.
     */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return

        // Prevent double handling of the same intent
        val intentHash = System.identityHashCode(intent)
        if (handledIntentHashCode == intentHash) {
            return
        }

        val fromNotification = intent.getBooleanExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, false)
        if (!fromNotification) {
            return
        }

        val sessionId = intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID)

        // Mark as handled before processing
        handledIntentHashCode = intentHash

        // Clear extras immediately to prevent re-processing on configuration change
        intent.removeExtra(NotificationHandler.EXTRA_SESSION_ID)
        intent.removeExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION)

        lifecycleScope.launch {
            if (sessionId.isNullOrBlank()) {
                // Group summary was tapped - navigate to sessions list
                _deepLinkNavigation.send(NAVIGATE_TO_SESSIONS)
            } else {
                // Specific session notification was tapped - navigate to terminal
                // TerminalViewModel will handle if session doesn't exist
                _deepLinkNavigation.send(sessionId)
                // Dismiss the notification
                notificationHandler.dismissNotification(sessionId)
            }
        }
    }

    companion object {
        // Special value to signal navigation to sessions list
        internal const val NAVIGATE_TO_SESSIONS = ""
    }
}

@Composable
private fun MainContent(
    deepLinkNavigation: kotlinx.coroutines.flow.Flow<String>
) {
    val navController = rememberNavController()

    // Handle deep link navigation
    LaunchedEffect(Unit) {
        deepLinkNavigation.collect { sessionId ->
            if (sessionId.isNotEmpty()) {
                // Navigate to terminal
                navController.navigate(Routes.Terminal.createRoute(sessionId)) {
                    // Don't add to back stack if already on terminal
                    launchSingleTop = true
                }
            } else {
                // Navigate to sessions list (from group summary or session not found)
                navController.navigate(Routes.Sessions.route) {
                    launchSingleTop = true
                }
            }
        }
    }

    // No Scaffold here - let individual screens handle their own edge-to-edge padding
    NavGraph(
        navController = navController,
        modifier = Modifier.fillMaxSize()
    )
}
