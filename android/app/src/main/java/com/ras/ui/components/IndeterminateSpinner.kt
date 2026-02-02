package com.ras.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

/**
 * Indeterminate spinner that animates regardless of system animation scale.
 *
 * Uses manual time-based animation with delay() which bypasses the system's
 * "Animator duration scale" setting. This ensures the spinner always animates,
 * even when animations are disabled for battery saving or accessibility.
 */
@Composable
fun IndeterminateSpinner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 3.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val timeMs = remember { mutableFloatStateOf(0f) }

    // Manual animation loop that ignores system animation scale
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(16L) // ~60fps
            timeMs.floatValue += 16f
        }
    }

    val rotationPeriodMs = 1500f
    val sweepPeriodMs = 2000f
    val minSweep = 30f
    val maxSweep = 300f

    val rotationDegrees = (timeMs.floatValue % rotationPeriodMs) / rotationPeriodMs * 360f
    val sweepProgress = (timeMs.floatValue % sweepPeriodMs) / sweepPeriodMs
    val sweepWave = sin(sweepProgress * 2 * PI).toFloat()
    val sweepNormalized = (sweepWave + 1f) / 2f
    val sweepDegrees = minSweep + (maxSweep - minSweep) * sweepNormalized

    Canvas(modifier = modifier) {
        val strokeWidthPx = strokeWidth.toPx()
        val diameter = size.minDimension - strokeWidthPx
        val topLeft = Offset(strokeWidthPx / 2, strokeWidthPx / 2)
        val arcSize = Size(diameter, diameter)
        val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

        if (trackColor != Color.Transparent) {
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
        }

        val centerAngle = rotationDegrees - 90f
        val startAngle = centerAngle - sweepDegrees / 2f
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweepDegrees,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
    }
}
