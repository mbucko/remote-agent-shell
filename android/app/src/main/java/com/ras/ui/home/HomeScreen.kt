package com.ras.ui.home

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ras.R
import kotlinx.coroutines.flow.collectLatest

/**
 * Home screen - app entry point.
 *
 * Displays paired device info, connection status, and provides access
 * to pairing, connecting, and settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToConnecting: () -> Unit,
    onNavigateToPairing: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSessions: () -> Unit,
    showDisconnectedMessage: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val autoConnectEnabled by viewModel.autoConnectEnabled.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is HomeUiEvent.NavigateToConnecting -> onNavigateToConnecting()
                is HomeUiEvent.NavigateToPairing -> onNavigateToPairing()
                is HomeUiEvent.NavigateToSettings -> onNavigateToSettings()
                is HomeUiEvent.NavigateToSessions -> onNavigateToSessions()
                is HomeUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Show disconnected message if returning from manual disconnect
    LaunchedEffect(showDisconnectedMessage) {
        if (showDisconnectedMessage) {
            viewModel.showDisconnectedMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RemoteAgentShell") },
                actions = {
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val currentState = state) {
                is HomeState.Loading -> LoadingContent()
                is HomeState.NoPairedDevice -> EmptyStateContent(
                    onPair = { viewModel.pair() }
                )
                is HomeState.HasDevice -> HasDeviceContent(
                    deviceName = currentState.name,
                    deviceType = currentState.type,
                    connectionState = currentState.connectionState,
                    autoConnectEnabled = autoConnectEnabled,
                    onConnect = { viewModel.connect() },
                    onOpenSessions = { viewModel.openSessions() },
                    onUnpair = { viewModel.unpair() },
                    onAutoConnectChanged = { viewModel.setAutoConnect(it) }
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyStateContent(
    onPair: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App logo
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "App Logo",
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "No device paired yet",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Scan a QR code to get started",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onPair,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("Pair Device")
        }
    }
}

@Composable
private fun HasDeviceContent(
    deviceName: String,
    deviceType: com.ras.data.model.DeviceType,
    connectionState: ConnectionState,
    autoConnectEnabled: Boolean,
    onConnect: () -> Unit,
    onOpenSessions: () -> Unit,
    onUnpair: () -> Unit,
    onAutoConnectChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App logo
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "App Logo",
            modifier = Modifier.size(100.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Device card
        DeviceCard(
            deviceName = deviceName,
            deviceType = deviceType,
            connectionState = connectionState,
            onConnect = onConnect,
            onOpenSessions = onOpenSessions,
            onUnpair = onUnpair
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Auto-connect toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Auto-connect on launch",
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = autoConnectEnabled,
                onCheckedChange = onAutoConnectChanged
            )
        }
    }
}
