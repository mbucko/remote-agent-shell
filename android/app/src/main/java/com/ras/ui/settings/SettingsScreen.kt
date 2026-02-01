package com.ras.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ras.BuildConfig
import com.ras.R
import com.ras.data.sessions.AgentInfo
import com.ras.data.settings.NotificationType
import com.ras.data.settings.SettingsQuickButton
import com.ras.data.settings.SettingsSection
import com.ras.ui.theme.StatusConnected
import com.ras.ui.theme.StatusDisconnected
import com.ras.ui.theme.StatusError
import kotlinx.coroutines.flow.collectLatest

/**
 * Shared button shape for consistent styling across settings.
 */
private val SettingsButtonShape = RoundedCornerShape(6.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onDisconnect: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle one-time UI events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is SettingsUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is SettingsUiEvent.NavigateToAgentPicker -> {
                    // Handled by dropdown
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Sessions Section
            SectionHeader(
                text = stringResource(R.string.settings_sessions),
                onReset = { viewModel.resetSection(SettingsSection.SESSIONS) }
            )
            SessionsCard(
                defaultAgent = uiState.defaultAgent,
                availableAgents = uiState.availableAgents,
                isLoading = uiState.agentListLoading,
                error = uiState.agentListError,
                onAgentSelected = { viewModel.setDefaultAgent(it) },
                onRetry = { viewModel.refreshAgentList() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Terminal Section
            SectionHeader(
                text = stringResource(R.string.settings_terminal),
                onReset = { viewModel.resetSection(SettingsSection.TERMINAL) }
            )
            QuickButtonsCard(
                enabledButtons = uiState.quickButtons,
                onToggle = { button, enabled -> viewModel.toggleQuickButton(button, enabled) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Notifications Section
            SectionHeader(
                text = stringResource(R.string.settings_notifications),
                onReset = { viewModel.resetSection(SettingsSection.NOTIFICATIONS) }
            )
            NotificationsCard(
                approvalEnabled = uiState.notifications.approvalEnabled,
                completionEnabled = uiState.notifications.completionEnabled,
                errorEnabled = uiState.notifications.errorEnabled,
                onApprovalChange = { viewModel.setNotificationEnabled(NotificationType.APPROVAL, it) },
                onCompletionChange = { viewModel.setNotificationEnabled(NotificationType.COMPLETION, it) },
                onErrorChange = { viewModel.setNotificationEnabled(NotificationType.ERROR, it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Connection Section (no reset)
            SectionHeader(text = stringResource(R.string.settings_connection))
            ConnectionCard(
                isConnected = isConnected,
                daemonVersion = uiState.daemonInfo.version,
                ipAddress = uiState.daemonInfo.ipAddress,
                onDisconnect = {
                    viewModel.disconnect()
                    onDisconnect()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // About Section (no reset)
            SectionHeader(text = stringResource(R.string.settings_about))
            AboutCard()
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    onReset: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (onReset != null) {
            TextButton(onClick = onReset) {
                Text(
                    text = stringResource(R.string.settings_reset),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

// ==========================================================================
// Sessions Section
// ==========================================================================

@Composable
private fun SessionsCard(
    defaultAgent: String?,
    availableAgents: List<AgentInfo>,
    isLoading: Boolean,
    error: String?,
    onAgentSelected: (String?) -> Unit,
    onRetry: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_default_agent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            when {
                isLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_loading),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                error != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusError,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.settings_retry)
                            )
                        }
                    }
                }
                availableAgents.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.settings_no_agents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { expanded = true }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = defaultAgent ?: stringResource(R.string.settings_always_ask),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null
                            )
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            // Always ask option
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_always_ask)) },
                                onClick = {
                                    onAgentSelected(null)
                                    expanded = false
                                },
                                leadingIcon = if (defaultAgent == null) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )

                            // Agent options
                            availableAgents.forEach { agent ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = agent.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    onClick = {
                                        onAgentSelected(agent.name)
                                        expanded = false
                                    },
                                    leadingIcon = if (defaultAgent == agent.name) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================================================
// Terminal Section - Quick Buttons
// ==========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickButtonsCard(
    enabledButtons: List<SettingsQuickButton>,
    onToggle: (SettingsQuickButton, Boolean) -> Unit
) {
    val allButtons = SettingsQuickButton.ALL
    val disabledButtons = allButtons.filter { it !in enabledButtons }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_quick_buttons),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.settings_quick_buttons_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            if (enabledButtons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_enabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    enabledButtons.forEach { button ->
                        QuickButtonChip(
                            button = button,
                            enabled = true,
                            onClick = { onToggle(button, false) }
                        )
                    }
                }
            }

            if (disabledButtons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_disabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    disabledButtons.forEach { button ->
                        QuickButtonChip(
                            button = button,
                            enabled = false,
                            onClick = { onToggle(button, true) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickButtonChip(
    button: SettingsQuickButton,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = Modifier
            .clip(SettingsButtonShape)
            .border(1.dp, borderColor, SettingsButtonShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = button.label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor
        )
    }
}

// ==========================================================================
// Notifications Section
// ==========================================================================

@Composable
private fun NotificationsCard(
    approvalEnabled: Boolean,
    completionEnabled: Boolean,
    errorEnabled: Boolean,
    onApprovalChange: (Boolean) -> Unit,
    onCompletionChange: (Boolean) -> Unit,
    onErrorChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            NotificationRow(
                title = stringResource(R.string.settings_notify_approvals),
                subtitle = stringResource(R.string.settings_notify_approvals_desc),
                checked = approvalEnabled,
                onCheckedChange = onApprovalChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            NotificationRow(
                title = stringResource(R.string.settings_notify_completed),
                subtitle = stringResource(R.string.settings_notify_completed_desc),
                checked = completionEnabled,
                onCheckedChange = onCompletionChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            NotificationRow(
                title = stringResource(R.string.settings_notify_errors),
                subtitle = stringResource(R.string.settings_notify_errors_desc),
                checked = errorEnabled,
                onCheckedChange = onErrorChange
            )
        }
    }
}

@Composable
private fun NotificationRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

// ==========================================================================
// Connection Section
// ==========================================================================

@Composable
private fun ConnectionCard(
    isConnected: Boolean,
    daemonVersion: String?,
    ipAddress: String?,
    onDisconnect: () -> Unit
) {
    var showDisconnectDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Circle,
                    contentDescription = null,
                    tint = if (isConnected) StatusConnected else StatusDisconnected,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(12.dp)
                )
                Text(
                    text = if (isConnected) stringResource(R.string.settings_connected)
                    else stringResource(R.string.settings_disconnected),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (daemonVersion != null) {
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(
                    label = stringResource(R.string.settings_daemon_version),
                    value = "v$daemonVersion"
                )
            }

            if (ipAddress != null) {
                Spacer(modifier = Modifier.height(4.dp))
                InfoRow(
                    label = stringResource(R.string.settings_ip_address),
                    value = ipAddress
                )
            }

            if (isConnected) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showDisconnectDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusError
                    ),
                    shape = SettingsButtonShape
                ) {
                    Text(stringResource(R.string.settings_disconnect))
                }
            }
        }
    }

    // Disconnect confirmation dialog
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.settings_disconnect)) },
            text = { Text(stringResource(R.string.settings_disconnect_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectDialog = false
                        onDisconnect()
                    }
                ) {
                    Text(
                        stringResource(R.string.settings_disconnect),
                        color = StatusError
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.terminal_cancel))
                }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// ==========================================================================
// About Section
// ==========================================================================

@Composable
private fun AboutCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoRow(
                label = stringResource(R.string.settings_version_label),
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mbucko/remote-agent-shell"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = SettingsButtonShape
            ) {
                Text(stringResource(R.string.settings_github))
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mbucko/remote-agent-shell/issues"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = SettingsButtonShape
            ) {
                Text(stringResource(R.string.settings_feedback))
            }
        }
    }
}
