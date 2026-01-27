package com.ras.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Small green dot indicator that appears briefly after successful reconnection.
 *
 * The indicator fades in, stays visible for 2 seconds, then fades out.
 * It can be retriggered while visible.
 *
 * @param showReconnected Set to true to trigger the indicator animation
 * @param modifier Modifier for positioning (typically Alignment.TopEnd)
 */
@Composable
fun ConnectionIndicator(
    showReconnected: Boolean,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    var triggerKey by remember { mutableIntStateOf(0) }

    // Track changes to showReconnected
    LaunchedEffect(showReconnected, triggerKey) {
        if (showReconnected) {
            visible = true
            delay(2000)  // Show for 2 seconds
            visible = false
        }
    }

    // Allow re-triggering
    LaunchedEffect(showReconnected) {
        if (showReconnected) {
            triggerKey++
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "indicator_alpha"
    )

    if (alpha > 0f) {
        Box(
            modifier = modifier
                .padding(8.dp)
                .alpha(alpha)
                .semantics {
                    contentDescription = "Connection indicator"
                }
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(Color(0xFF4CAF50), CircleShape)
                    .align(Alignment.TopEnd)
            )
        }
    }
}
