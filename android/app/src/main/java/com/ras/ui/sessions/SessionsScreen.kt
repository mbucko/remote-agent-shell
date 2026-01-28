package com.ras.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ras.R
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionStatus
import com.ras.data.sessions.SessionsScreenState
import com.ras.ui.theme.StatusConnected
import com.ras.ui.theme.StatusDisconnected
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onSessionClick: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCreateSession: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel()
) {
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val showKillDialog by viewModel.showKillDialog.collectAsStateWithLifecycle()
    val showRenameDialog by viewModel.showRenameDialog.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // Handle one-time UI events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is SessionsUiEvent.SessionCreated -> {
                    snackbarHostState.showSnackbar("Session '${event.name}' created")
                }
                is SessionsUiEvent.SessionKilled -> {
                    snackbarHostState.showSnackbar("Session killed")
                }
                is SessionsUiEvent.SessionRenamed -> {
                    snackbarHostState.showSnackbar("Session renamed to '${event.newName}'")
                }
                is SessionsUiEvent.Error -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Circle,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (isConnected) StatusConnected else StatusDisconnected
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isConnected) "Connected" else "Disconnected")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshSessions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onNavigateToCreateSession) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_new))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = screenState) {
                is SessionsScreenState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is SessionsScreenState.Loaded -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (state.sessions.isEmpty()) {
                            EmptySessionsContent(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            SessionsList(
                                sessions = state.sessions,
                                onSessionClick = onSessionClick,
                                onKillClick = { viewModel.showKillDialog(it) },
                                onRenameClick = { viewModel.showRenameDialog(it) }
                            )
                        }
                        // Show loading indicator when refreshing
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp)
                            )
                        }
                    }
                }

                is SessionsScreenState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = { viewModel.refreshSessions() },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }

    // Dialogs
    showKillDialog?.let { session ->
        KillSessionDialog(
            sessionName = session.displayText,
            onConfirm = { viewModel.confirmKillSession() },
            onDismiss = { viewModel.dismissKillDialog() }
        )
    }

    showRenameDialog?.let { session ->
        RenameSessionDialog(
            currentName = session.displayName,
            onConfirm = { newName -> viewModel.confirmRenameSession(newName) },
            onDismiss = { viewModel.dismissRenameDialog() }
        )
    }
}

@Composable
private fun EmptySessionsContent(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.sessions_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap + to create a new session",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun SessionsList(
    sessions: List<SessionInfo>,
    onSessionClick: (String) -> Unit,
    onKillClick: (SessionInfo) -> Unit,
    onRenameClick: (SessionInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }
        items(
            items = sessions,
            key = { it.id }
        ) { session ->
            SessionItem(
                session = session,
                onClick = { onSessionClick(session.id) },
                onKillClick = { onKillClick(session) },
                onRenameClick = { onRenameClick(session) }
            )
        }
        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun SessionItem(
    session: SessionInfo,
    onClick: () -> Unit,
    onKillClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when (session.status) {
                            SessionStatus.ACTIVE -> StatusConnected
                            SessionStatus.CREATING -> MaterialTheme.colorScheme.tertiary
                            SessionStatus.KILLING -> MaterialTheme.colorScheme.error
                            SessionStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
                        },
                        shape = MaterialTheme.shapes.small
                    )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.displayText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${session.agent.replaceFirstChar { it.uppercase() }} - ${session.directoryBasename}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatLastActivity(session.lastActivityAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Menu button
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onRenameClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Kill", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onKillClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun KillSessionDialog(
    sessionName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kill Session?") },
        text = {
            Text("Are you sure you want to kill the session \"$sessionName\"? This will terminate any running processes.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Kill", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RenameSessionDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var isValid by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        isValid = it.isNotBlank() && it.length <= 64
                    },
                    label = { Text("New name") },
                    singleLine = true,
                    isError = !isValid,
                    supportingText = if (!isValid) {
                        { Text("Name must be 1-64 characters") }
                    } else null
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newName.trim()) },
                enabled = isValid && newName.trim() != currentName
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatLastActivity(instant: Instant): String {
    val now = Instant.now()
    val diff = now.epochSecond - instant.epochSecond

    return when {
        diff < 60 -> "Active just now"
        diff < 3600 -> "Active ${diff / 60}m ago"
        diff < 86400 -> "Active ${diff / 3600}h ago"
        else -> {
            val formatter = DateTimeFormatter.ofPattern("MMM d")
            "Active " + instant.atZone(ZoneId.systemDefault()).format(formatter)
        }
    }
}
