package com.ras.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ras.domain.startup.ReconnectionResult

/**
 * Startup screen that handles automatic reconnection flow.
 * Shows loading/connecting state and handles navigation.
 */
@Composable
fun StartupScreen(
    onNavigateToPairing: () -> Unit,
    onNavigateToSessions: () -> Unit,
    viewModel: StartupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Handle navigation
    LaunchedEffect(state) {
        when (state) {
            is StartupState.NavigateToPairing -> onNavigateToPairing()
            is StartupState.NavigateToSessions -> onNavigateToSessions()
            else -> { /* Stay on this screen */ }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (val currentState = state) {
            is StartupState.Loading -> {
                LoadingContent(message = "Checking credentials...")
            }
            is StartupState.Connecting -> {
                ConnectingContent(progress = currentState.progress)
            }
            is StartupState.ConnectionFailed -> {
                ConnectionFailedContent(
                    reason = currentState.reason,
                    onRetry = { viewModel.retry() },
                    onRePair = { viewModel.rePair() }
                )
            }
            is StartupState.NavigateToPairing,
            is StartupState.NavigateToSessions -> {
                // Will navigate away, show loading in the meantime
                LoadingContent(message = "")
            }
        }
    }
}

@Composable
private fun LoadingContent(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        if (message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectingContent(
    progress: ConnectionProgressInfo,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Progress indicator
        if (progress.progress != null) {
            CircularProgressIndicator(progress = { progress.progress })
        } else {
            CircularProgressIndicator()
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Strategy name (if available)
        if (progress.strategyName.isNotEmpty()) {
            Text(
                text = progress.strategyName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Step description
        Text(
            text = progress.step,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Detail (if available)
        if (progress.detail != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = progress.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ConnectionFailedContent(
    reason: ReconnectionResult.Failure,
    onRetry: () -> Unit,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Connection Failed",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = getFailureMessage(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier.width(200.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text("Retry")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onRePair,
            modifier = Modifier.width(200.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text("Re-pair Device")
        }
    }
}

private fun getFailureMessage(reason: ReconnectionResult.Failure): String {
    return when (reason) {
        is ReconnectionResult.Failure.NoCredentials -> "No credentials stored."
        is ReconnectionResult.Failure.DaemonUnreachable -> "Could not reach daemon. Make sure the daemon is running."
        is ReconnectionResult.Failure.AuthenticationFailed -> "Authentication failed. You may need to re-pair."
        is ReconnectionResult.Failure.DeviceNotFound -> "Device not found on daemon. Please re-pair your device."
        is ReconnectionResult.Failure.NetworkError -> "Network error. Check your connection."
        is ReconnectionResult.Failure.Unknown -> reason.message
    }
}
