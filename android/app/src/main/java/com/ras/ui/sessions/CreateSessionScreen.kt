package com.ras.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ras.data.sessions.AgentInfo
import com.ras.data.sessions.AgentsListState
import com.ras.data.sessions.CreateSessionState
import com.ras.data.sessions.DirectoryBrowserState
import com.ras.data.sessions.DirectoryEntryInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSessionScreen(
    onNavigateBack: () -> Unit,
    onSessionCreated: (String) -> Unit,
    viewModel: CreateSessionViewModel = hiltViewModel()
) {
    val createState by viewModel.createState.collectAsStateWithLifecycle()
    val directoryState by viewModel.directoryState.collectAsStateWithLifecycle()
    val agentsState by viewModel.agentsState.collectAsStateWithLifecycle()
    val selectedDirectory by viewModel.selectedDirectory.collectAsStateWithLifecycle()
    val selectedAgent by viewModel.selectedAgent.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val recentDirectories by viewModel.recentDirectories.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // Start the wizard
    LaunchedEffect(Unit) {
        if (createState is CreateSessionState.Idle) {
            viewModel.startDirectorySelection()
        }
    }

    // Handle UI events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is CreateSessionUiEvent.SessionCreated -> {
                    onSessionCreated(event.name)
                }
                is CreateSessionUiEvent.Error -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    // Handle back navigation
    BackHandler {
        when (createState) {
            is CreateSessionState.SelectingDirectory -> {
                if (!viewModel.navigateBack()) {
                    onNavigateBack()
                }
            }
            is CreateSessionState.DirectorySelected,
            is CreateSessionState.SelectingAgent,
            is CreateSessionState.Failed -> {
                viewModel.goBackStep()
            }
            is CreateSessionState.Creating -> {
                // Can't go back while creating
            }
            else -> onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (createState) {
                            is CreateSessionState.SelectingDirectory,
                            is CreateSessionState.DirectorySelected -> "Select Directory"
                            is CreateSessionState.SelectingAgent -> "Select Agent"
                            is CreateSessionState.Creating -> "Creating Session"
                            is CreateSessionState.Created -> "Session Created"
                            is CreateSessionState.Failed -> "Error"
                            else -> "New Session"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (createState) {
                            is CreateSessionState.SelectingDirectory -> {
                                if (!viewModel.navigateBack()) {
                                    onNavigateBack()
                                }
                            }
                            is CreateSessionState.Creating -> {
                                // Can't go back while creating
                            }
                            else -> {
                                viewModel.goBackStep()
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        AnimatedContent(
            targetState = createState,
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn())
                    .togetherWith(slideOutHorizontally { -it } + fadeOut())
            },
            label = "wizard_content"
        ) { state ->
            when (state) {
                is CreateSessionState.Idle,
                is CreateSessionState.SelectingDirectory -> {
                    DirectorySelectionStep(
                        directoryState = directoryState,
                        currentPath = currentPath,
                        recentDirectories = recentDirectories,
                        onDirectoryClick = { viewModel.navigateToDirectory(it) },
                        onDirectorySelect = { viewModel.selectDirectory(it) },
                        onRecentSelect = { viewModel.selectRecentDirectory(it) },
                        modifier = Modifier.padding(paddingValues)
                    )
                }

                is CreateSessionState.DirectorySelected -> {
                    DirectoryConfirmStep(
                        directory = state.directory,
                        onConfirm = { viewModel.proceedToAgentSelection() },
                        onChangeDirectory = { viewModel.startDirectorySelection() },
                        modifier = Modifier.padding(paddingValues)
                    )
                }

                is CreateSessionState.SelectingAgent -> {
                    AgentSelectionStep(
                        agentsState = agentsState,
                        selectedAgent = selectedAgent,
                        onAgentSelect = { viewModel.selectAgent(it) },
                        onRefresh = { viewModel.refreshAgents() },
                        onCreateSession = { viewModel.createSession() },
                        modifier = Modifier.padding(paddingValues)
                    )
                }

                is CreateSessionState.Creating -> {
                    CreatingSessionStep(
                        directory = state.directory,
                        agent = state.agent,
                        modifier = Modifier.padding(paddingValues)
                    )
                }

                is CreateSessionState.Created -> {
                    SessionCreatedStep(
                        sessionName = state.session.displayText,
                        onDone = { onNavigateBack() },
                        modifier = Modifier.padding(paddingValues)
                    )
                }

                is CreateSessionState.Failed -> {
                    ErrorStep(
                        errorCode = state.errorCode,
                        message = state.message,
                        onRetry = { viewModel.createSession() },
                        onBack = { viewModel.goBackStep() },
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectorySelectionStep(
    directoryState: DirectoryBrowserState,
    currentPath: String,
    recentDirectories: List<String>,
    onDirectoryClick: (String) -> Unit,
    onDirectorySelect: (String) -> Unit,
    onRecentSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Current path breadcrumb
        if (currentPath.isNotEmpty()) {
            Text(
                text = currentPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        when (directoryState) {
            is DirectoryBrowserState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is DirectoryBrowserState.Loaded -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Recent directories section (only at root)
                    if (currentPath.isEmpty() && recentDirectories.isNotEmpty()) {
                        item(key = "recent_header") {
                            Text(
                                text = "Recent",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            items = recentDirectories.take(5),
                            key = { directory -> "recent_$directory" }
                        ) { directory ->
                            RecentDirectoryItem(
                                path = directory,
                                onClick = { onRecentSelect(directory) }
                            )
                        }
                        item(key = "browse_header") {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Browse",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // Directory entries
                    items(
                        items = directoryState.entries,
                        key = { entry -> "dir_${entry.path}" }
                    ) { entry ->
                        DirectoryItem(
                            entry = entry,
                            onClick = { onDirectoryClick(entry.path) },
                            onSelect = { onDirectorySelect(entry.path) }
                        )
                    }

                    // Select current directory option
                    if (currentPath.isNotEmpty()) {
                        item(key = "select_current") {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { onDirectorySelect(currentPath) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select this directory")
                            }
                        }
                    }
                }
            }

            is DirectoryBrowserState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = directoryState.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryItem(
    entry: DirectoryEntryInfo,
    onClick: () -> Unit,
    onSelect: () -> Unit
) {
    ListItem(
        headlineContent = { Text(entry.name) },
        supportingContent = { Text(entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onSelect) {
                    Icon(Icons.Default.Check, contentDescription = "Select")
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Browse",
                    modifier = Modifier.clickable(onClick = onClick)
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun RecentDirectoryItem(
    path: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = path.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun DirectoryConfirmStep(
    directory: String,
    onConfirm: () -> Unit,
    onChangeDirectory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Selected Directory",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = directory,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onChangeDirectory) {
            Text("Change Directory")
        }
    }
}

@Composable
private fun AgentSelectionStep(
    agentsState: AgentsListState,
    selectedAgent: String?,
    onAgentSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        when (agentsState) {
            is AgentsListState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is AgentsListState.Loaded -> {
                val availableAgents = agentsState.agents.filter { it.available }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (availableAgents.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "No agents found",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(onClick = onRefresh) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Refresh")
                                    }
                                }
                            }
                        }
                    } else {
                        items(
                            items = availableAgents,
                            key = { agent -> "available_${agent.binary}" }
                        ) { agent ->
                            AgentItem(
                                agent = agent,
                                isSelected = selectedAgent == agent.binary,
                                onSelect = { onAgentSelect(agent.binary) }
                            )
                        }
                    }

                    // Show unavailable agents
                    val unavailableAgents = agentsState.agents.filter { !it.available }
                    if (unavailableAgents.isNotEmpty()) {
                        item(key = "unavailable_header") {
                            Text(
                                text = "Not Installed",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            items = unavailableAgents,
                            key = { agent -> "unavailable_${agent.binary}" }
                        ) { agent ->
                            AgentItem(
                                agent = agent,
                                isSelected = false,
                                onSelect = { },
                                enabled = false
                            )
                        }
                    }
                }

                // Create button
                Button(
                    onClick = onCreateSession,
                    enabled = selectedAgent != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Create Session")
                }
            }

            is AgentsListState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = agentsState.message,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = onRefresh) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentItem(
    agent: AgentInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true
) {
    ListItem(
        headlineContent = {
            Text(
                text = agent.name,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                }
            )
        },
        supportingContent = {
            Text(
                text = agent.binary,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.5f
                )
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                }
            )
        },
        trailingContent = {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.clickable(enabled = enabled, onClick = onSelect)
    )
}

@Composable
private fun CreatingSessionStep(
    directory: String,
    agent: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            CircularProgressIndicator()

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Creating session...",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Starting $agent in",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = directory,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionCreatedStep(
    sessionName: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Session Created",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = sessionName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onDone) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun ErrorStep(
    errorCode: String,
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Failed to Create Session",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )

            if (errorCode.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Error: $errorCode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row {
                TextButton(onClick = onBack) {
                    Text("Back")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}
