package com.ras.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ras.ui.components.IndeterminateSpinner
import com.ras.ui.theme.StatusConnected
import com.ras.ui.theme.StatusConnecting
import com.ras.ui.theme.StatusDisconnected

/**
 * Displays a list of pairing steps with their status and timing.
 *
 * @param steps List of pairing steps to display
 * @param modifier Modifier for the container
 */
@Composable
fun PairingStepList(
    steps: List<PairingStep>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        steps.forEach { step ->
            PairingStepRow(step = step)
        }
    }
}

/**
 * Displays a single pairing step row with icon, label, and duration.
 *
 * @param step The pairing step to display
 * @param modifier Modifier for the row
 */
@Composable
private fun PairingStepRow(
    step: PairingStep,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            StepIcon(status = step.status)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = step.label,
                style = MaterialTheme.typography.bodyMedium,
                color = when (step.status) {
                    StepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
                    StepStatus.IN_PROGRESS -> StatusConnecting
                    StepStatus.UNAVAILABLE -> StatusDisconnected
                    StepStatus.PENDING -> StatusDisconnected
                }
            )
        }
        
        step.formattedDuration()?.let { duration ->
            Text(
                text = duration,
                style = MaterialTheme.typography.bodySmall,
                color = when (step.status) {
                    StepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
                    StepStatus.UNAVAILABLE -> StatusDisconnected
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * Displays the appropriate icon for a step status.
 *
 * @param status The step status to display
 */
@Composable
private fun StepIcon(status: StepStatus) {
    when (status) {
        StepStatus.COMPLETED -> {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    tint = StatusConnected,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        StepStatus.IN_PROGRESS -> {
            IndeterminateSpinner(
                modifier = Modifier.size(20.dp),
                color = StatusConnecting,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
        }
        
        StepStatus.UNAVAILABLE -> {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "—",
                    color = StatusDisconnected,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        
        StepStatus.PENDING -> {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(StatusDisconnected.copy(alpha = 0.3f))
            )
        }
    }
}
