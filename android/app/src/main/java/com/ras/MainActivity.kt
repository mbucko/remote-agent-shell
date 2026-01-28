package com.ras

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ras.data.sessions.SessionRepository
import com.ras.notifications.NotificationHandler
import com.ras.ui.navigation.NavGraph
import com.ras.ui.navigation.Routes
import com.ras.ui.theme.RASTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var notificationHandler: NotificationHandler

    @Inject
    lateinit var sessionRepository: SessionRepository

    // Flow to communicate deep link navigation to composable
    private val _deepLinkNavigation = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val deepLinkNavigation = _deepLinkNavigation.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle notification tap if app was launched from notification
        handleNotificationIntent(intent)

        setContent {
            RASTheme {
                MainContent(
                    deepLinkNavigation = deepLinkNavigation,
                    onSessionNotFound = { showSessionNotFoundToast() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification tap when app is already running
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(NotificationHandler.EXTRA_SESSION_ID)
        val fromNotification = intent?.getBooleanExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, false) ?: false

        if (sessionId != null && fromNotification) {
            lifecycleScope.launch {
                // Check if session exists
                val sessions = sessionRepository.sessions.value
                val sessionExists = sessions.any { it.id == sessionId }

                if (sessionExists) {
                    // Navigate to session terminal
                    _deepLinkNavigation.emit(sessionId)
                    // Dismiss the notification
                    notificationHandler.dismissNotification(sessionId)
                } else {
                    // Show toast and navigate to sessions list
                    showSessionNotFoundToast()
                    _deepLinkNavigation.emit("") // Empty signals navigate to sessions
                }
            }

            // Clear the extras to prevent re-handling on rotation
            intent?.removeExtra(NotificationHandler.EXTRA_SESSION_ID)
            intent?.removeExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION)
        }
    }

    private fun showSessionNotFoundToast() {
        Toast.makeText(this, "Session not found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MainContent(
    deepLinkNavigation: kotlinx.coroutines.flow.SharedFlow<String>,
    onSessionNotFound: () -> Unit
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
                // Navigate to sessions list
                navController.navigate(Routes.Sessions.route) {
                    launchSingleTop = true
                }
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavGraph(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
