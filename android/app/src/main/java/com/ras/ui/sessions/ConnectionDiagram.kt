package com.ras.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ras.data.connection.ConnectionPath
import com.ras.data.connection.PathType
import com.ras.ui.theme.StatusDisconnected
import com.ras.ui.theme.TerminalGreen
import com.ras.ui.theme.TerminalYellow
import com.ras.ui.theme.TerminalBlue
import com.ras.ui.theme.TerminalCyan

/**
 * Expandable connection diagram showing the path between phone and laptop.
 * Displays connection type, IP addresses, and latency with animated Canvas drawing.
 */
@Composable
fun ConnectionDiagram(
    connectionPath: ConnectionPath?,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row with connection type badge
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionTypeBadge(path = connectionPath)

                Spacer(modifier = Modifier.weight(1f))

                // Expand/collapse icon
                val rotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = tween(200),
                    label = "arrow_rotation"
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Latency indicator (always visible when available)
            connectionPath?.latencyMs?.let { latency ->
                Spacer(modifier = Modifier.height(8.dp))
                LatencyIndicator(latencyMs = latency)
            }

            // Expanded diagram
            AnimatedVisibility(
                visible = isExpanded && connectionPath != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                connectionPath?.let { path ->
                    Spacer(modifier = Modifier.height(16.dp))
                    ConnectionDiagramCanvas(path = path)
                }
            }

            // Hint text when collapsed
            if (!isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap to view connection details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ConnectionTypeBadge(path: ConnectionPath?) {
    val badgeData = when (path?.type) {
        PathType.LAN_DIRECT -> BadgeData(TerminalGreen, Color.Black, "LAN Direct")
        PathType.WEBRTC_DIRECT -> BadgeData(TerminalBlue, Color.Black, "WebRTC Direct")
        PathType.TAILSCALE -> BadgeData(TerminalCyan, Color.Black, "Tailscale")
        PathType.RELAY -> BadgeData(TerminalYellow, Color.Black, "Relay")
        null -> BadgeData(StatusDisconnected, MaterialTheme.colorScheme.onSurface, "Disconnected")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(badgeData.backgroundColor)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = badgeData.label,
            style = MaterialTheme.typography.labelMedium,
            color = badgeData.textColor
        )
    }
}

private data class BadgeData(
    val backgroundColor: Color,
    val textColor: Color,
    val label: String
)

@Composable
private fun LatencyIndicator(latencyMs: Long) {
    val (color, description) = when {
        latencyMs < 50 -> Pair(TerminalGreen, "Excellent")
        latencyMs < 100 -> Pair(TerminalCyan, "Good")
        latencyMs < 200 -> Pair(TerminalYellow, "Fair")
        else -> Pair(StatusDisconnected, "Slow")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Latency dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "${latencyMs}ms",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "($description)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectionDiagramCanvas(path: ConnectionPath) {
    val density = LocalDensity.current
    val connectionColor = getConnectionColor(path.type)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Positions
        val phoneX = canvasWidth * 0.15f
        val phoneY = canvasHeight * 0.65f
        val laptopX = canvasWidth * 0.85f
        val laptopY = canvasHeight * 0.65f
        val phoneRadius = with(density) { 28.dp.toPx() }
        val laptopWidth = with(density) { 56.dp.toPx() }
        val laptopHeight = with(density) { 40.dp.toPx() }

        // Draw connection lines based on path type
        drawConnectionLines(
            path = path,
            startX = phoneX,
            startY = phoneY,
            endX = laptopX,
            endY = laptopY,
            color = connectionColor,
            density = density
        )

        // Draw phone icon (circle with inner details)
        drawPhoneIcon(
            centerX = phoneX,
            centerY = phoneY,
            radius = phoneRadius,
            color = primaryColor,
            density = density
        )

        // Draw laptop icon (rectangle with screen)
        drawLaptopIcon(
            centerX = laptopX,
            centerY = laptopY,
            width = laptopWidth,
            height = laptopHeight,
            color = primaryColor,
            density = density
        )

        // Draw labels
        drawDeviceLabel(
            text = "Phone",
            x = phoneX,
            y = phoneY + phoneRadius + with(density) { 24.dp.toPx() },
            color = onSurfaceColor,
            density = density
        )

        drawDeviceLabel(
            text = "Laptop",
            x = laptopX,
            y = laptopY + laptopHeight / 2 + with(density) { 24.dp.toPx() },
            color = onSurfaceColor,
            density = density
        )

        // Draw IP addresses (if applicable)
        if (path.showLocalIps) {
            drawIpLabel(
                text = "${path.local.ip}:${path.local.port}",
                x = phoneX,
                y = phoneY - phoneRadius - with(density) { 20.dp.toPx() },
                color = onSurfaceVariantColor,
                density = density
            )

            drawIpLabel(
                text = "${path.remote.ip}:${path.remote.port}",
                x = laptopX,
                y = laptopY - laptopHeight / 2 - with(density) { 20.dp.toPx() },
                color = onSurfaceVariantColor,
                density = density
            )
        } else {
            // Show generic connection indicators for Internet-based connections
            drawIpLabel(
                text = "Internet",
                x = canvasWidth / 2,
                y = canvasHeight * 0.25f,
                color = onSurfaceVariantColor,
                density = density
            )
        }

        // Draw path type in the middle
        drawCenterLabel(
            text = path.label,
            x = canvasWidth / 2,
            y = canvasHeight * 0.45f,
            textColor = connectionColor,
            backgroundColor = surfaceColor,
            density = density
        )
    }
}

private fun DrawScope.drawConnectionLines(
    path: ConnectionPath,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    color: Color,
    density: androidx.compose.ui.unit.Density
) {
    val strokeWidth = with(density) { 3.dp.toPx() }

    when (path.type) {
        PathType.LAN_DIRECT -> {
            // Direct straight line
            drawLine(
                color = color,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
        PathType.WEBRTC_DIRECT -> {
            // Arc with arrow indicators
            val yOffset = with(density) { 20.dp.toPx() }
            drawLine(
                color = color,
                start = Offset(startX, startY - yOffset),
                end = Offset(endX, endY - yOffset),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            // Arrow heads
            val arrowX = (startX + endX) / 2
            val arrowY = (startY + endY) / 2 - yOffset
            drawArrowHead(
                x = arrowX,
                y = arrowY,
                color = color,
                pointingRight = true,
                density = density
            )
            drawArrowHead(
                x = arrowX,
                y = arrowY,
                color = color,
                pointingRight = false,
                density = density
            )
        }
        PathType.TAILSCALE -> {
            // Shield-like shape indicating VPN
            val midX = (startX + endX) / 2
            val midY = (startY + endY) / 2
            val vpnOffset = with(density) { 30.dp.toPx() }

            // Draw through "VPN cloud"
            drawLine(
                color = color.copy(alpha = 0.5f),
                start = Offset(startX, startY),
                end = Offset(midX, midY - vpnOffset),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color.copy(alpha = 0.5f),
                start = Offset(midX, midY - vpnOffset),
                end = Offset(endX, endY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )

            // VPN cloud indicator
            drawCircle(
                color = color.copy(alpha = 0.2f),
                radius = with(density) { 24.dp.toPx() },
                center = Offset(midX, midY - vpnOffset)
            )
        }
        PathType.RELAY -> {
            // Zigzag through relay servers
            val segments = 3
            val segmentWidth = (endX - startX) / segments

            for (i in 0 until segments) {
                val x1 = startX + i * segmentWidth
                val x2 = startX + (i + 1) * segmentWidth
                val yOffset = if (i % 2 == 0) 0f else with(density) { -30.dp.toPx() }

                drawLine(
                    color = color.copy(alpha = 0.7f),
                    start = Offset(x1, startY + yOffset),
                    end = Offset(x2, startY + yOffset),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )

                // Relay node indicator
                if (i < segments - 1) {
                    drawCircle(
                        color = color,
                        radius = with(density) { 6.dp.toPx() },
                        center = Offset(x2, startY + yOffset)
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawArrowHead(
    x: Float,
    y: Float,
    color: Color,
    pointingRight: Boolean,
    density: androidx.compose.ui.unit.Density
) {
    val size = with(density) { 8.dp.toPx() }
    val direction = if (pointingRight) 1f else -1f
    val strokeWidth = with(density) { 2.dp.toPx() }

    drawLine(
        color = color,
        start = Offset(x, y),
        end = Offset(x + direction * size, y - size / 2),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(x, y),
        end = Offset(x + direction * size, y + size / 2),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawPhoneIcon(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    density: androidx.compose.ui.unit.Density
) {
    // Outer circle
    drawCircle(
        color = color.copy(alpha = 0.2f),
        radius = radius,
        center = Offset(centerX, centerY)
    )

    // Inner phone shape (rectangle with rounded corners)
    val phoneWidth = radius * 0.6f
    val phoneHeight = radius * 1.0f
    val cornerRadius = with(density) { 4.dp.toPx() }

    drawRoundRect(
        color = color,
        topLeft = Offset(centerX - phoneWidth / 2, centerY - phoneHeight / 2),
        size = Size(phoneWidth, phoneHeight),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        style = Stroke(width = with(density) { 2.dp.toPx() })
    )

    // Screen
    val screenPadding = with(density) { 4.dp.toPx() }
    drawRoundRect(
        color = color.copy(alpha = 0.5f),
        topLeft = Offset(centerX - phoneWidth / 2 + screenPadding, centerY - phoneHeight / 2 + screenPadding * 1.5f),
        size = Size(phoneWidth - screenPadding * 2, phoneHeight - screenPadding * 4),
        cornerRadius = CornerRadius(screenPadding / 2, screenPadding / 2)
    )
}

private fun DrawScope.drawLaptopIcon(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    color: Color,
    density: androidx.compose.ui.unit.Density
) {
    // Outer circle
    drawCircle(
        color = color.copy(alpha = 0.2f),
        radius = width * 0.6f,
        center = Offset(centerX, centerY)
    )

    val strokeWidth = with(density) { 2.dp.toPx() }

    // Laptop screen
    drawRect(
        color = color,
        topLeft = Offset(centerX - width / 2, centerY - height / 2),
        size = Size(width, height * 0.7f),
        style = Stroke(width = strokeWidth)
    )

    // Laptop base
    val baseWidth = width * 1.1f
    val baseHeight = height * 0.25f
    drawRect(
        color = color,
        topLeft = Offset(centerX - baseWidth / 2, centerY + height * 0.2f),
        size = Size(baseWidth, baseHeight),
        style = Stroke(width = strokeWidth)
    )

    // Screen content indicator
    val contentPadding = with(density) { 6.dp.toPx() }
    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(centerX - width / 2 + contentPadding, centerY - height / 2 + contentPadding),
        end = Offset(centerX + width / 2 - contentPadding, centerY - height / 2 + contentPadding),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawDeviceLabel(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    density: androidx.compose.ui.unit.Density
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = with(density) { 12.sp.toPx() }
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }

    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

private fun DrawScope.drawIpLabel(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    density: androidx.compose.ui.unit.Density
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = with(density) { 10.sp.toPx() }
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }

    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

private fun DrawScope.drawCenterLabel(
    text: String,
    x: Float,
    y: Float,
    textColor: Color,
    backgroundColor: Color,
    density: androidx.compose.ui.unit.Density
) {
    val textPaint = android.graphics.Paint().apply {
        this.color = textColor.toArgb()
        textSize = with(density) { 11.sp.toPx() }
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }

    // Measure text for background
    val bounds = android.graphics.Rect()
    textPaint.getTextBounds(text, 0, text.length, bounds)
    val padding = with(density) { 8.dp.toPx() }

    // Draw background
    drawRoundRect(
        color = backgroundColor,
        topLeft = Offset(x - bounds.width() / 2 - padding, y - bounds.height() / 2 - padding / 2),
        size = Size(bounds.width() + padding * 2, bounds.height() + padding),
        cornerRadius = CornerRadius(with(density) { 8.dp.toPx() }, with(density) { 8.dp.toPx() })
    )

    // Draw text
    drawContext.canvas.nativeCanvas.drawText(text, x, y + bounds.height() / 3, textPaint)
}

private fun getConnectionColor(type: PathType): Color {
    return when (type) {
        PathType.LAN_DIRECT -> TerminalGreen
        PathType.WEBRTC_DIRECT -> TerminalBlue
        PathType.TAILSCALE -> TerminalCyan
        PathType.RELAY -> TerminalYellow
    }
}
