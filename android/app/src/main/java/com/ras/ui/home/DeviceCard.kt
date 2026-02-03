package com.ras.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.ras.R
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Card displaying device information with connection status.
 * Shows red border and warning icon for unpaired devices.
 */
@Composable
fun DeviceCard(
    device: DeviceInfo,
    isActive: Boolean,
    onClick: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUnpaired = device.status != DeviceStatus.PAIRED

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isUnpaired) { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnpaired) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (isUnpaired) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.error)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Device info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Device icon with warning overlay if unpaired
                Box {
                    Icon(
                        imageVector = device.type.toIcon(),
                        contentDescription = device.type.name,
                        modifier = Modifier.size(32.dp),
                        tint = if (isUnpaired) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )

                    if (isUnpaired) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Unpaired",
                            modifier = Modifier
                                .size(16.dp)
                                .align(Alignment.BottomEnd),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Device name and status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (isUnpaired) {
                        // Show unpaired status
                        Text(
                            text = when (device.status) {
                                DeviceStatus.UNPAIRED_BY_DAEMON -> "Unpaired by host"
                                DeviceStatus.UNPAIRED_BY_USER -> "Unpaired"
                                else -> "Unknown status"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        // Show connection status
                        Text(
                            text = if (device.connectionState == ConnectionState.CONNECTED) {
                                val countText = pluralStringResource(
                                    R.plurals.sessions_count,
                                    device.sessionCount,
                                    device.sessionCount
                                )
                                "Connected · $countText"
                            } else {
                                device.connectionState.toDisplayText()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Show last connected time if available
                    if (!isUnpaired && device.lastConnectedAt != null) {
                        Text(
                            text = "Last: ${formatTimestamp(device.lastConnectedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                // Status dot (only for paired devices)
                if (!isUnpaired) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(device.connectionState.toColor())
                    )
                }
            }

            Spacer(modifier = Modifier.padding(vertical = 12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isUnpaired) {
                    // Unpaired device: show remove button
                    Button(
                        onClick = onUnpair,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Remove")
                    }
                } else {
                    // Paired device: show connect/sessions button
                    Button(
                        onClick = onClick,
                        modifier = Modifier.weight(1f),
                        enabled = device.connectionState != ConnectionState.CONNECTING
                    ) {
                        Text(
                            text = when (device.connectionState) {
                                ConnectionState.CONNECTED -> "Open Sessions"
                                ConnectionState.CONNECTING -> "Connecting..."
                                ConnectionState.DISCONNECTED -> "Connect"
                            }
                        )
                    }

                    // Unpair button
                    OutlinedButton(
                        onClick = onUnpair
                    ) {
                        Text("Unpair")
                    }
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
 * Uses theme colors for dark mode support.
 */
@Composable
private fun ConnectionState.toColor(): Color = when (this) {
    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
    ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
}

/**
 * Format timestamp for display (e.g., "2 hours ago").
 */
private fun formatTimestamp(instant: Instant): String {
    val now = Instant.now()
    val duration = java.time.Duration.between(instant, now)

    return when {
        duration.toMinutes() < 1 -> "just now"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
        duration.toHours() < 24 -> "${duration.toHours()}h ago"
        duration.toDays() < 7 -> "${duration.toDays()}d ago"
        else -> {
            val formatter = DateTimeFormatter.ofPattern("MMM d")
            instant.atZone(ZoneId.systemDefault()).format(formatter)
        }
    }
}
