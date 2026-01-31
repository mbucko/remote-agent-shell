package com.ras.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ras.ui.theme.StatusConnected
import com.ras.ui.theme.StatusConnecting
import com.ras.ui.theme.StatusError
import kotlinx.coroutines.delay

/**
 * Connection overlay state for the status bar.
 *
 * Industry standard pattern: Sealed class for exhaustive state handling.
 */
@Stable
sealed class OverlayState {
    /** Bar is hidden */
    data object Hidden : OverlayState()

    /** Disconnected - show red bar */
    data object Disconnected : OverlayState()

    /** Reconnecting - show yellow bar with attempt count */
    data class Reconnecting(val attempt: Int = 1) : OverlayState()

    /** Just connected - show green bar briefly */
    data object Connected : OverlayState()
}

/**
 * Thin connection status overlay bar - YouTube-style.
 *
 * Behavior:
 * - Hidden when connected normally
 * - Slides up when disconnected (red)
 * - Shows reconnecting state (yellow)
 * - Briefly shows connected (green) then auto-hides after 3 seconds
 *
 * Design:
 * - Very thin (24dp) to minimize screen space usage
 * - Full width with centered content
 * - Slides up/down for smooth transitions
 */
@Composable
fun ConnectionStatusOverlay(
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    autoHideDelayMs: Long = 3000L
) {
    var overlayState by remember { mutableStateOf<OverlayState>(OverlayState.Hidden) }
    var wasConnected by remember { mutableStateOf(isConnected) }

    // Track connection state changes
    LaunchedEffect(isConnected) {
        if (isConnected && !wasConnected) {
            // Just reconnected - show green briefly
            overlayState = OverlayState.Connected
            delay(autoHideDelayMs)
            overlayState = OverlayState.Hidden
        } else if (!isConnected && wasConnected) {
            // Just disconnected - show red
            overlayState = OverlayState.Disconnected
        } else if (!isConnected) {
            // Still disconnected (initial state or continued)
            overlayState = OverlayState.Disconnected
        }
        wasConnected = isConnected
    }

    ConnectionStatusOverlayContent(
        state = overlayState,
        modifier = modifier
    )
}

/**
 * Stateless content component for the overlay.
 * Separating state from presentation follows Compose best practices.
 */
@Composable
fun ConnectionStatusOverlayContent(
    state: OverlayState,
    modifier: Modifier = Modifier
) {
    val isVisible = state != OverlayState.Hidden

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(durationMillis = 200)
        ),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(durationMillis = 200)
        ),
        modifier = modifier
    ) {
        val (backgroundColor, dotColor, text) = when (state) {
            is OverlayState.Hidden -> Triple(Color.Transparent, Color.Transparent, "")
            is OverlayState.Disconnected -> Triple(
                StatusError.copy(alpha = 0.15f),
                StatusError,
                "Disconnected"
            )
            is OverlayState.Reconnecting -> Triple(
                StatusConnecting.copy(alpha = 0.15f),
                StatusConnecting,
                if (state.attempt > 1) "Reconnecting (${state.attempt})..." else "Reconnecting..."
            )
            is OverlayState.Connected -> Triple(
                StatusConnected.copy(alpha = 0.15f),
                StatusConnected,
                "Connected"
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Status text
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = dotColor
                )
            }
        }
    }
}

// ============ Previews ============

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ConnectionStatusOverlayDisconnectedPreview() {
    ConnectionStatusOverlayContent(
        state = OverlayState.Disconnected
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ConnectionStatusOverlayReconnectingPreview() {
    ConnectionStatusOverlayContent(
        state = OverlayState.Reconnecting(attempt = 2)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ConnectionStatusOverlayConnectedPreview() {
    ConnectionStatusOverlayContent(
        state = OverlayState.Connected
    )
}
