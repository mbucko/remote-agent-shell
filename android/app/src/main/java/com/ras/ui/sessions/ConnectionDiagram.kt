package com.ras.ui.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.ras.data.model.DeviceType
import com.ras.ui.theme.StatusDisconnected
import com.ras.ui.theme.TerminalGreen
import com.ras.ui.theme.TerminalYellow
import com.ras.ui.theme.TerminalBlue
import com.ras.ui.theme.TerminalCyan

/**
 * Connection diagram showing the path between phone and daemon device.
 * Displays connection type, IP addresses, and latency with Canvas drawing.
 * Always expanded - shows full diagram without collapse option.
 */
@Composable
fun ConnectionDiagram(
    connectionPath: ConnectionPath?,
    deviceName: String = "Device",
    deviceType: DeviceType = DeviceType.UNKNOWN,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row with connection type badge on left, latency on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionTypeBadge(path = connectionPath)
                Spacer(modifier = Modifier.weight(1f))
                // Latency indicator on the right
                connectionPath?.latencyMs?.let { latency ->
                    LatencyIndicator(latencyMs = latency)
                }
            }

            // Connection diagram (always visible when path available)
            if (connectionPath != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ConnectionDiagramCanvas(
                    path = connectionPath,
                    deviceName = deviceName,
                    deviceType = deviceType
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
private fun ConnectionDiagramCanvas(
    path: ConnectionPath,
    deviceName: String,
    deviceType: DeviceType
) {
    val density = LocalDensity.current
    val connectionColor = getConnectionColor(path.type)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Positions - devices lower in the canvas
        val phoneX = canvasWidth * 0.15f
        val phoneY = canvasHeight * 0.72f
        val laptopX = canvasWidth * 0.85f
        val laptopY = canvasHeight * 0.72f
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

        // Draw device icon based on type
        when (deviceType) {
            DeviceType.SERVER -> drawServerIcon(
                centerX = laptopX,
                centerY = laptopY,
                width = laptopWidth,
                height = laptopHeight,
                color = primaryColor,
                density = density
            )
            else -> drawLaptopIcon(
                centerX = laptopX,
                centerY = laptopY,
                width = laptopWidth,
                height = laptopHeight,
                color = primaryColor,
                density = density
            )
        }

        // Draw labels at same Y position for alignment
        val labelY = canvasHeight - with(density) { 8.dp.toPx() }
        drawDeviceLabel(
            text = "Phone",
            x = phoneX,
            y = labelY,
            color = onSurfaceColor,
            density = density
        )

        drawDeviceLabel(
            text = deviceName,
            x = laptopX,
            y = labelY,
            color = onSurfaceColor,
            density = density
        )

        // Draw IP addresses (if applicable)
        // Always use getLocalDisplayIp/getRemoteDisplayIp which prefer IPv4 over IPv6
        if (path.showLocalIps) {
            val localDisplay = path.getLocalDisplayIp()
            val remoteDisplay = path.getRemoteDisplayIp()

            drawIpLabel(
                text = "${localDisplay.ip}:${localDisplay.port}",
                x = phoneX,
                y = phoneY - phoneRadius - with(density) { 20.dp.toPx() },
                color = onSurfaceVariantColor,
                density = density,
                backgroundColor = surfaceVariantColor
            )

            drawIpLabel(
                text = "${remoteDisplay.ip}:${remoteDisplay.port}",
                x = laptopX,
                y = laptopY - laptopHeight / 2 - with(density) { 20.dp.toPx() },
                color = onSurfaceVariantColor,
                density = density,
                backgroundColor = surfaceVariantColor
            )
        } else if (path.type == PathType.WEBRTC_DIRECT) {
            // WebRTC Direct: show NAT with public IPs
            val natY = with(density) { 65.dp.toPx() }
            val publicIpY = with(density) { 45.dp.toPx() }

            // NAT boxes
            drawNatBox(
                x = phoneX,
                y = natY,
                color = connectionColor,
                backgroundColor = surfaceVariantColor,
                density = density
            )
            drawNatBox(
                x = laptopX,
                y = natY,
                color = connectionColor,
                backgroundColor = surfaceVariantColor,
                density = density
            )

            // Public IPs above NAT (use public IPs if available, otherwise fall back)
            val localDisplay = path.getLocalDisplayIp()
            val remoteDisplay = path.getRemoteDisplayIp()

            drawIpLabel(
                text = "${localDisplay.ip}:${localDisplay.port}",
                x = phoneX,
                y = publicIpY,
                color = onSurfaceVariantColor,
                density = density,
                backgroundColor = surfaceVariantColor
            )

            drawIpLabel(
                text = "${remoteDisplay.ip}:${remoteDisplay.port}",
                x = laptopX,
                y = publicIpY,
                color = onSurfaceVariantColor,
                density = density,
                backgroundColor = surfaceVariantColor
            )

            // Internet cloud at top
            drawIpLabel(
                text = "☁️",
                x = canvasWidth / 2,
                y = with(density) { 10.dp.toPx() },
                color = onSurfaceVariantColor,
                density = density
            )
            // Internet label below cloud
            drawIpLabel(
                text = "Internet",
                x = canvasWidth / 2,
                y = with(density) { 22.dp.toPx() },
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

        // Draw path type label (at top for Tailscale and WebRTC Direct, middle for others)
        val pathLabelY = when (path.type) {
            PathType.TAILSCALE -> with(density) { 28.dp.toPx() }
            PathType.WEBRTC_DIRECT -> with(density) { 35.dp.toPx() }
            else -> canvasHeight * 0.40f
        }
        drawCenterLabel(
            text = path.label,
            x = canvasWidth / 2,
            y = pathLabelY,
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
            // WebRTC through NAT: lines go up to public IPs, then to Internet cloud
            val topY = with(density) { 28.dp.toPx() }
            val natY = with(density) { 65.dp.toPx() }
            val ipOffset = with(density) { 20.dp.toPx() }

            // Vertical lines from devices up to NAT/public IP level
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(startX, startY - ipOffset),
                end = Offset(startX, natY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(endX, endY - ipOffset),
                end = Offset(endX, natY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )

            // Angled lines from NAT level up to Internet cloud at top
            val internetY = topY
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(startX, natY),
                end = Offset((startX + endX) / 2 - 15.dp.toPx(), internetY + 15.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(endX, natY),
                end = Offset((startX + endX) / 2 + 15.dp.toPx(), internetY + 15.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
        PathType.TAILSCALE -> {
            // VPN lines go up from devices to Tailscale VPN at top
            val topY = with(density) { 28.dp.toPx() }
            val ipOffset = with(density) { 20.dp.toPx() }

            // Lines go from IP level (above devices) up to VPN at top
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(startX, startY - ipOffset),
                end = Offset(startX, topY + 15.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(endX, endY - ipOffset),
                end = Offset(endX, topY + 15.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )

            // Angled lines from vertical up to VPN label at top center
            val vpnLabelY = topY
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(startX, topY + 15.dp.toPx()),
                end = Offset((startX + endX) / 2 - 10.dp.toPx(), vpnLabelY + 10.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(endX, topY + 15.dp.toPx()),
                end = Offset((startX + endX) / 2 + 10.dp.toPx(), vpnLabelY + 10.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
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

private fun DrawScope.drawNatBox(
    x: Float,
    y: Float,
    color: Color,
    backgroundColor: Color,
    density: androidx.compose.ui.unit.Density
) {
    val width = with(density) { 32.dp.toPx() }
    val height = with(density) { 18.dp.toPx() }
    val strokeWidth = with(density) { 1.5f.dp.toPx() }

    // NAT box background (filled) to cover lines behind it
    drawRect(
        color = backgroundColor,
        topLeft = Offset(x - width / 2, y - height / 2),
        size = Size(width, height)
    )

    // NAT box outline
    drawRect(
        color = color.copy(alpha = 0.6f),
        topLeft = Offset(x - width / 2, y - height / 2),
        size = Size(width, height),
        style = Stroke(width = strokeWidth)
    )

    // NAT label inside
    val paint = android.graphics.Paint().apply {
        this.color = color.copy(alpha = 0.8f).toArgb()
        textSize = with(density) { 8.sp.toPx() }
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }
    drawContext.canvas.nativeCanvas.drawText("NAT", x, y + 3.dp.toPx(), paint)
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

private fun DrawScope.drawServerIcon(
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
    val serverHeight = height * 1.2f
    val unitHeight = serverHeight / 3

    // Draw 3 stacked server units (rack server look)
    for (i in 0 until 3) {
        val unitY = centerY - serverHeight / 2 + i * unitHeight
        drawRoundRect(
            color = color,
            topLeft = Offset(centerX - width / 2, unitY),
            size = Size(width, unitHeight - with(density) { 2.dp.toPx() }),
            cornerRadius = CornerRadius(with(density) { 2.dp.toPx() }, with(density) { 2.dp.toPx() }),
            style = Stroke(width = strokeWidth)
        )
        // LED indicator on each unit
        drawCircle(
            color = color.copy(alpha = 0.7f),
            radius = with(density) { 3.dp.toPx() },
            center = Offset(centerX + width / 2 - with(density) { 8.dp.toPx() }, unitY + unitHeight / 2 - with(density) { 1.dp.toPx() })
        )
    }
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
    density: androidx.compose.ui.unit.Density,
    backgroundColor: Color? = null
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = with(density) { 10.sp.toPx() }
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }

    // Draw background if provided
    if (backgroundColor != null) {
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val padding = with(density) { 4.dp.toPx() }
        drawRoundRect(
            color = backgroundColor,
            topLeft = Offset(x - bounds.width() / 2 - padding, y - bounds.height() - padding / 2),
            size = Size(bounds.width() + padding * 2, bounds.height() + padding),
            cornerRadius = CornerRadius(with(density) { 4.dp.toPx() }, with(density) { 4.dp.toPx() })
        )
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
