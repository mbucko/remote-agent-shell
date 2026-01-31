package com.ras.ui.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ras.data.connection.AttemptInfo
import com.ras.data.connection.ConnectionLog
import com.ras.data.connection.ConnectionPhase
import com.ras.data.connection.DaemonCapabilitiesInfo
import com.ras.data.connection.LocalCapabilitiesInfo
import com.ras.data.connection.StrategyInfo
import com.ras.data.connection.StrategyStatus
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
                ConnectingContent(log = currentState.log)
            }
            is StartupState.ConnectionFailed -> {
                ConnectionFailedContent(
                    reason = currentState.reason,
                    log = currentState.log,
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
    log: ConnectionLog,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        // Header with spinner
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = getPhaseTitle(log.phase),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Capability Discovery Section
        SectionHeader(title = "CAPABILITY DISCOVERY")
        CapabilitySection(
            localCapabilities = log.localCapabilities,
            daemonCapabilities = log.daemonCapabilities,
            exchangeError = log.capabilityExchangeError,
            exchangeSteps = log.capabilityExchangeSteps,
            isExchanging = log.phase == ConnectionPhase.EXCHANGING_CAPABILITIES
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Strategies Section
        if (log.strategies.isNotEmpty() || log.phase.ordinal >= ConnectionPhase.DETECTING_STRATEGIES.ordinal) {
            SectionHeader(title = "STRATEGIES")
            StrategiesSection(strategies = log.strategies)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Connection Attempts Section
        if (log.completedAttempts.isNotEmpty() || log.currentAttempt != null) {
            SectionHeader(title = "CONNECTION ATTEMPTS")
            AttemptsSection(
                completedAttempts = log.completedAttempts,
                currentAttempt = log.currentAttempt
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun CapabilitySection(
    localCapabilities: LocalCapabilitiesInfo?,
    daemonCapabilities: DaemonCapabilitiesInfo?,
    exchangeError: String?,
    exchangeSteps: List<String>,
    isExchanging: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Column {
            // Local capabilities
            CapabilityRow(
                label = "Local",
                value = localCapabilities?.let { caps ->
                    if (caps.tailscaleIp != null) {
                        "Tailscale ${caps.tailscaleIp}"
                    } else {
                        "WebRTC only"
                    }
                } ?: "Detecting...",
                isComplete = localCapabilities != null
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Daemon capabilities
            CapabilityRow(
                label = "Daemon",
                value = when {
                    exchangeError != null -> "Failed: $exchangeError"
                    daemonCapabilities != null -> {
                        if (daemonCapabilities.tailscaleIp != null) {
                            "Tailscale ${daemonCapabilities.tailscaleIp}:${daemonCapabilities.tailscalePort}"
                        } else {
                            "WebRTC only"
                        }
                    }
                    isExchanging -> "Exchanging..."
                    else -> "Pending"
                },
                isComplete = daemonCapabilities != null,
                isError = exchangeError != null
            )

            // Detailed exchange steps
            if (exchangeSteps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                exchangeSteps.forEach { step ->
                    Text(
                        text = "  $step",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            step.contains("✓") -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    value: String,
    isComplete: Boolean,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isError) "x" else if (isComplete) "+" else "-",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                isComplete -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun StrategiesSection(strategies: List<StrategyInfo>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Column {
            if (strategies.isEmpty()) {
                Text(
                    text = "- Detecting strategies...",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                strategies.forEach { strategy ->
                    StrategyRow(strategy)
                }
            }
        }
    }
}

@Composable
private fun StrategyRow(strategy: StrategyInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (symbol, color) = when (strategy.status) {
            StrategyStatus.DETECTING -> "-" to MaterialTheme.colorScheme.onSurfaceVariant
            StrategyStatus.AVAILABLE -> "+" to MaterialTheme.colorScheme.primary
            StrategyStatus.UNAVAILABLE -> "x" to MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            StrategyStatus.CONNECTING -> "*" to MaterialTheme.colorScheme.tertiary
            StrategyStatus.FAILED -> "x" to MaterialTheme.colorScheme.error
            StrategyStatus.SUCCEEDED -> "+" to MaterialTheme.colorScheme.primary
        }

        Text(
            text = symbol,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = strategy.name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (strategy.info != null) {
            Text(
                text = " - ${strategy.info}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun AttemptsSection(
    completedAttempts: List<AttemptInfo>,
    currentAttempt: AttemptInfo?
) {
    Column {
        // Show completed attempts
        completedAttempts.forEach { attempt ->
            AttemptCard(attempt = attempt, isCurrent = false)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Show current attempt
        currentAttempt?.let { attempt ->
            AttemptCard(attempt = attempt, isCurrent = true)
        }
    }
}

@Composable
private fun AttemptCard(
    attempt: AttemptInfo,
    isCurrent: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = attempt.strategyName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (attempt.durationMs != null) {
                    Text(
                        text = "${attempt.durationMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Steps
            attempt.steps.forEach { step ->
                Text(
                    text = "  ${step.step}" + (step.detail?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Render sub-details as nested bullets
                step.subDetails?.forEach { subDetail ->
                    Text(
                        text = "      · $subDetail",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // Result
            when {
                attempt.success -> {
                    Text(
                        text = "  + Connected!",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                attempt.error != null -> {
                    Text(
                        text = "  x ${attempt.error}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                isCurrent -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "  * ",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}

private fun getPhaseTitle(phase: ConnectionPhase): String {
    return when (phase) {
        ConnectionPhase.INITIALIZING -> "Initializing..."
        ConnectionPhase.DISCOVERING_CAPABILITIES -> "Discovering capabilities..."
        ConnectionPhase.EXCHANGING_CAPABILITIES -> "Exchanging capabilities..."
        ConnectionPhase.DETECTING_STRATEGIES -> "Detecting strategies..."
        ConnectionPhase.CONNECTING -> "Connecting..."
        ConnectionPhase.CONNECTED -> "Transport connected!"
        ConnectionPhase.AUTHENTICATING -> "Authenticating..."
        ConnectionPhase.AUTHENTICATED -> "Authenticated!"
        ConnectionPhase.FAILED -> "Connection failed"
        ConnectionPhase.CANCELLED -> "Cancelled"
    }
}

@Composable
private fun ConnectionFailedContent(
    reason: ReconnectionResult.Failure,
    log: ConnectionLog,
    onRetry: () -> Unit,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Connection Failed",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = getFailureMessage(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Show what was tried
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            // Capability Discovery Section
            if (log.localCapabilities != null || log.daemonCapabilities != null) {
                SectionHeader(title = "CAPABILITY DISCOVERY")
                CapabilitySection(
                    localCapabilities = log.localCapabilities,
                    daemonCapabilities = log.daemonCapabilities,
                    exchangeError = log.capabilityExchangeError,
                    exchangeSteps = log.capabilityExchangeSteps,
                    isExchanging = false
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Strategies Section
            if (log.strategies.isNotEmpty()) {
                SectionHeader(title = "STRATEGIES TRIED")
                StrategiesSection(strategies = log.strategies)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Connection Attempts Section
            if (log.completedAttempts.isNotEmpty()) {
                SectionHeader(title = "FAILED ATTEMPTS")
                AttemptsSection(
                    completedAttempts = log.completedAttempts,
                    currentAttempt = null
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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

        Spacer(modifier = Modifier.height(16.dp))
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
