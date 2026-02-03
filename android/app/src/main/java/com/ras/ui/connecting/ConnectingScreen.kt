package com.ras.ui.connecting

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

/**
 * Screen that shows connection progress and handles connection flow.
 *
 * Extracted from StartupScreen to be navigated to from HomeScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectingScreen(
    onNavigateToSessions: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ConnectingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Handle navigation events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ConnectingUiEvent.NavigateToSessions -> onNavigateToSessions()
                is ConnectingUiEvent.NavigateBack -> onNavigateBack()
                is ConnectingUiEvent.DeviceUnpaired -> onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connecting") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (val currentState = state) {
                is ConnectingState.Connecting -> {
                    ConnectingContent(log = currentState.log)
                }
                is ConnectingState.Failed -> {
                    ConnectionFailedContent(
                        reason = currentState.reason,
                        log = currentState.log,
                        onRetry = { viewModel.retry() }
                    )
                }
            }
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
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        // Header with spinner
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IndeterminateSpinner(modifier = Modifier.size(20.dp))
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

/**
 * Simple indeterminate spinner that ignores system animation scale.
 */
@Composable
private fun IndeterminateSpinner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 3.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val timeMs = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(16L)
            timeMs.floatValue += 16f
        }
    }

    val rotationPeriodMs = 1500f
    val sweepPeriodMs = 2000f
    val minSweep = 30f
    val maxSweep = 300f

    val rotationDegrees = (timeMs.floatValue % rotationPeriodMs) / rotationPeriodMs * 360f
    val sweepProgress = (timeMs.floatValue % sweepPeriodMs) / sweepPeriodMs
    val sweepWave = sin(sweepProgress * 2 * PI).toFloat()
    val sweepNormalized = (sweepWave + 1f) / 2f
    val sweepDegrees = minSweep + (maxSweep - minSweep) * sweepNormalized

    Canvas(modifier = modifier) {
        val strokeWidthPx = strokeWidth.toPx()
        val diameter = size.minDimension - strokeWidthPx
        val topLeft = androidx.compose.ui.geometry.Offset(strokeWidthPx / 2, strokeWidthPx / 2)
        val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
        val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

        if (trackColor != Color.Transparent) {
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
        }

        val centerAngle = rotationDegrees - 90f
        val startAngle = centerAngle - sweepDegrees / 2f
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweepDegrees,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
    }
}

@Composable
private fun InProgressIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "  * ",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary
        )
        IndeterminateSpinner(
            modifier = Modifier.size(10.dp),
            color = MaterialTheme.colorScheme.tertiary,
            strokeWidth = 1.dp,
            trackColor = Color.Transparent
        )
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
    isExchanging: Boolean,
    isFinalFailure: Boolean = false
) {
    val directStep = exchangeSteps.find { it.startsWith("CAPABILITIES →") && !it.contains("ntfy") }
    val ntfyStep = exchangeSteps.find { it.contains("ntfy") }
    val tailscaleStep = exchangeSteps.find { it.startsWith("TAILSCALE") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Column {
            CapabilityRow(
                label = "Tailscale",
                value = when {
                    localCapabilities?.tailscaleIp != null -> localCapabilities.tailscaleIp
                    tailscaleStep?.contains("not detected") == true -> "not detected"
                    tailscaleStep != null -> "detecting..."
                    else -> "pending"
                },
                isComplete = localCapabilities?.tailscaleIp != null,
                isSkipped = tailscaleStep?.contains("not detected") == true
            )

            Spacer(modifier = Modifier.height(4.dp))

            CapabilityRow(
                label = "Direct",
                value = when {
                    directStep?.contains("✓") == true -> {
                        if (daemonCapabilities?.tailscaleIp != null) {
                            "Tailscale ${daemonCapabilities.tailscaleIp}:${daemonCapabilities.tailscalePort}"
                        } else if (daemonCapabilities != null) {
                            "WebRTC only"
                        } else {
                            "connected"
                        }
                    }
                    directStep?.contains("unreachable") == true -> "unreachable"
                    directStep != null -> "probing..."
                    isExchanging -> "pending"
                    else -> "pending"
                },
                isComplete = directStep?.contains("✓") == true,
                isSkipped = directStep?.contains("unreachable") == true
            )

            Spacer(modifier = Modifier.height(4.dp))

            CapabilityRow(
                label = "ntfy",
                value = when {
                    ntfyStep?.contains("✓") == true -> {
                        if (daemonCapabilities?.tailscaleIp != null && directStep?.contains("✓") != true) {
                            "Tailscale ${daemonCapabilities.tailscaleIp}:${daemonCapabilities.tailscalePort}"
                        } else if (daemonCapabilities != null && directStep?.contains("✓") != true) {
                            "WebRTC only"
                        } else {
                            "received"
                        }
                    }
                    ntfyStep?.contains("waiting") == true -> "waiting..."
                    ntfyStep?.contains("sending") == true -> "sending..."
                    ntfyStep?.contains("connected") == true -> "connected"
                    ntfyStep?.contains("connecting") == true -> "connecting..."
                    exchangeError != null && isFinalFailure -> "failed"
                    directStep?.contains("unreachable") == true -> "fallback"
                    else -> "standby"
                },
                isComplete = ntfyStep?.contains("✓") == true,
                isError = exchangeError != null && isFinalFailure && directStep?.contains("✓") != true,
                isInProgress = ntfyStep != null && !ntfyStep.contains("✓") && exchangeError == null
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    value: String,
    isComplete: Boolean,
    isError: Boolean = false,
    isSkipped: Boolean = false,
    isInProgress: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                isError -> "x"
                isSkipped -> "~"
                isComplete -> "+"
                isInProgress -> "*"
                else -> "-"
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                isComplete -> MaterialTheme.colorScheme.primary
                isInProgress -> MaterialTheme.colorScheme.tertiary
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
            StrategyStatus.UNAVAILABLE -> "-" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
        completedAttempts.forEach { attempt ->
            AttemptCard(attempt = attempt, isCurrent = false)
            Spacer(modifier = Modifier.height(8.dp))
        }

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

            attempt.steps.forEach { step ->
                Text(
                    text = "  ${step.step}" + (step.detail?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                step.subDetails?.forEach { subDetail ->
                    Text(
                        text = "      · $subDetail",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

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
                    InProgressIndicator()
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
            if (log.localCapabilities != null || log.daemonCapabilities != null) {
                SectionHeader(title = "CAPABILITY DISCOVERY")
                CapabilitySection(
                    localCapabilities = log.localCapabilities,
                    daemonCapabilities = log.daemonCapabilities,
                    exchangeError = log.capabilityExchangeError,
                    exchangeSteps = log.capabilityExchangeSteps,
                    isExchanging = false,
                    isFinalFailure = true
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (log.strategies.isNotEmpty()) {
                SectionHeader(title = "STRATEGIES TRIED")
                StrategiesSection(strategies = log.strategies)
                Spacer(modifier = Modifier.height(16.dp))
            }

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
