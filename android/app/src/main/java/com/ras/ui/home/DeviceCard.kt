package com.ras.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ras.data.model.DeviceType

/**
 * Card displaying paired device information.
 */
@Composable
fun DeviceCard(
    deviceName: String,
    deviceType: DeviceType,
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    onOpenSessions: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Device info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Device icon
                Icon(
                    imageVector = deviceType.toIcon(),
                    contentDescription = deviceType.name,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Device name and status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = connectionState.toDisplayText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(connectionState.toColor())
                )
            }

            Spacer(modifier = Modifier.padding(vertical = 12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = when (connectionState) {
                        ConnectionState.CONNECTED -> onOpenSessions
                        ConnectionState.CONNECTING -> ({})  // No-op while connecting
                        ConnectionState.DISCONNECTED -> onConnect
                    },
                    modifier = Modifier.weight(1f),
                    enabled = connectionState != ConnectionState.CONNECTING
                ) {
                    Text(
                        text = when (connectionState) {
                            ConnectionState.CONNECTED -> "Open Sessions"
                            ConnectionState.CONNECTING -> "Connecting..."
                            ConnectionState.DISCONNECTED -> "Connect"
                        }
                    )
                }

                OutlinedButton(
                    onClick = onUnpair
                ) {
                    Text("Unpair")
                }
            }
        }
    }
}

/**
 * Convert DeviceType to Material Icon.
 */
private fun DeviceType.toIcon(): ImageVector = when (this) {
    DeviceType.LAPTOP -> Icons.Filled.Laptop
    DeviceType.DESKTOP -> Icons.Filled.Computer
    DeviceType.SERVER -> Icons.Filled.Dns
    DeviceType.UNKNOWN -> Icons.Filled.Computer
}

/**
 * Convert ConnectionState to display text.
 */
private fun ConnectionState.toDisplayText(): String = when (this) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting..."
    ConnectionState.DISCONNECTED -> "Disconnected"
}

/**
 * Convert ConnectionState to status dot color.
 */
private fun ConnectionState.toColor(): Color = when (this) {
    ConnectionState.CONNECTED -> Color(0xFF3FB950)   // Green
    ConnectionState.CONNECTING -> Color(0xFFD29922)  // Orange
    ConnectionState.DISCONNECTED -> Color(0xFF8B949E) // Gray
}
