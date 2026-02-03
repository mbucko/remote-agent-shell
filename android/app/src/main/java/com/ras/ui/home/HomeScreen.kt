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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ras.R
import com.ras.data.model.DeviceStatus
import kotlinx.coroutines.flow.collectLatest

/**
 * Home screen - app entry point.
 *
 * Displays list of paired devices with connection status.
 * Provides access to pairing, connecting, and settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToConnecting: (String) -> Unit,
    onNavigateToPairing: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSessions: (String) -> Unit,
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
                is HomeUiEvent.NavigateToConnecting -> onNavigateToConnecting(event.deviceId)
                is HomeUiEvent.NavigateToPairing -> onNavigateToPairing()
                is HomeUiEvent.NavigateToSettings -> onNavigateToSettings()
                is HomeUiEvent.NavigateToSessions -> onNavigateToSessions(event.deviceId)
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.pair() }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Pair Device"
                )
            }
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
                is HomeState.NoDevices -> EmptyStateContent(
                    onPair = { viewModel.pair() }
                )
                is HomeState.HasDevices -> HasDevicesContent(
                    devices = currentState.devices,
                    activeDeviceId = currentState.activeDeviceId,
                    autoConnectEnabled = autoConnectEnabled,
                    onDeviceClick = { viewModel.onDeviceClicked(it) },
                    onUnpair = { viewModel.unpairDevice(it) },
                    onRemove = { viewModel.removeDevice(it) },
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
            text = "No devices paired yet",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tap + to scan a QR code and pair with your computer",
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
private fun HasDevicesContent(
    devices: List<DeviceInfo>,
    activeDeviceId: String?,
    autoConnectEnabled: Boolean,
    onDeviceClick: (String) -> Unit,
    onUnpair: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAutoConnectChanged: (Boolean) -> Unit
) {
    var deviceToUnpair by remember { mutableStateOf<DeviceInfo?>(null) }
    var deviceToRemove by remember { mutableStateOf<DeviceInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Device list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(devices, key = { it.deviceId }) { device ->
                DeviceCard(
                    device = device,
                    isActive = device.deviceId == activeDeviceId,
                    onClick = { onDeviceClick(device.deviceId) },
                    onUnpair = {
                        if (device.status == DeviceStatus.PAIRED) {
                            deviceToUnpair = device
                        } else {
                            deviceToRemove = device
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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

    // Unpair confirmation dialog
    deviceToUnpair?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToUnpair = null },
            title = { Text("Unpair ${device.name}") },
            text = {
                Text(
                    "This will unpair this device. To reconnect, you'll need to scan the QR code again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUnpair(device.deviceId)
                        deviceToUnpair = null
                    }
                ) {
                    Text("Unpair")
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnpair = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Remove confirmation dialog
    deviceToRemove?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRemove = null },
            title = { Text("Remove ${device.name}") },
            text = {
                Text(
                    "This device is already unpaired. Remove it from the list?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemove(device.deviceId)
                        deviceToRemove = null
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRemove = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
