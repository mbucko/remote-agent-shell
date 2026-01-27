package com.ras.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ras.BuildConfig
import com.ras.R
import com.ras.ui.theme.StatusConnected
import com.ras.ui.theme.StatusDisconnected
import com.ras.ui.theme.StatusError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onDisconnect: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val peerId by viewModel.peerId.collectAsStateWithLifecycle()

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
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Connection Section
            SectionHeader(text = stringResource(R.string.settings_connection))
            ConnectionCard(
                isConnected = isConnected,
                peerId = peerId,
                onDisconnect = {
                    viewModel.disconnect()
                    onDisconnect()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Notifications Section
            SectionHeader(text = stringResource(R.string.settings_notifications))
            NotificationsCard()

            Spacer(modifier = Modifier.height(24.dp))

            // About Section
            SectionHeader(text = stringResource(R.string.settings_about))
            AboutCard()
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ConnectionCard(
    isConnected: Boolean,
    peerId: String?,
    onDisconnect: () -> Unit
) {
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
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = if (isConnected) stringResource(R.string.settings_connected)
                    else stringResource(R.string.settings_disconnected),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (isConnected && peerId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Peer: $peerId",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isConnected) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusError
                    )
                ) {
                    Text(stringResource(R.string.settings_disconnect))
                }
            }
        }
    }
}

@Composable
private fun NotificationsCard() {
    var ntfyServer by remember { mutableStateOf("https://ntfy.sh") }
    var notifyApprovals by remember { mutableStateOf(true) }
    var notifyCompleted by remember { mutableStateOf(true) }
    var notifyErrors by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = ntfyServer,
                onValueChange = { ntfyServer = it },
                label = { Text(stringResource(R.string.settings_ntfy_server)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            CheckboxRow(
                checked = notifyApprovals,
                onCheckedChange = { notifyApprovals = it },
                text = stringResource(R.string.settings_notify_approvals)
            )

            CheckboxRow(
                checked = notifyCompleted,
                onCheckedChange = { notifyCompleted = it },
                text = stringResource(R.string.settings_notify_completed)
            )

            CheckboxRow(
                checked = notifyErrors,
                onCheckedChange = { notifyErrors = it },
                text = stringResource(R.string.settings_notify_errors)
            )
        }
    }
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text)
    }
}

@Composable
private fun AboutCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { /* TODO: Open GitHub */ }
            ) {
                Text(stringResource(R.string.settings_github))
            }
        }
    }
}
