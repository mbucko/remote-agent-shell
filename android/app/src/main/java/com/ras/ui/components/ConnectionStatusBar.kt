package com.ras.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ras.ui.theme.StatusConnected
import com.ras.ui.theme.StatusConnecting
import com.ras.ui.theme.StatusDisconnected
import com.ras.ui.theme.StatusError
import com.ras.util.ConnectionState

/**
 * Status bar showing the current connection state.
 */
@Composable
fun ConnectionStatusBar(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (statusColor, statusText) = when (state) {
        is ConnectionState.Disconnected -> StatusDisconnected to "Disconnected"
        is ConnectionState.Scanning -> StatusConnecting to "Scanning..."
        is ConnectionState.Connecting -> StatusConnecting to "Connecting..."
        is ConnectionState.Authenticating -> StatusConnecting to "Authenticating..."
        is ConnectionState.Connected -> StatusConnected to "Connected"
        is ConnectionState.Reconnecting -> StatusConnecting to "Reconnecting (${state.attempt})..."
        is ConnectionState.Error -> StatusError to "Error: ${state.reason.name}"
    }

    val animatedColor by animateColorAsState(
        targetValue = statusColor,
        label = "status_color"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(animatedColor)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
