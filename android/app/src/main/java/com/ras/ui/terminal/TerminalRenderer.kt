package com.ras.ui.terminal

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose component that renders a terminal buffer.
 *
 * Uses Termux's TerminalBuffer for the data model and renders it
 * using Compose Text with AnnotatedString for styling.
 */
@Composable
fun TerminalRenderer(
    emulator: RemoteTerminalEmulator,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    onFontSizeChanged: ((Float) -> Unit)? = null,
    onSizeChanged: ((cols: Int, rows: Int) -> Unit)? = null
) {
    // Observe screen version to trigger recomposition
    val screenVersion = emulator.screenVersion

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.sp.toPx() }

    // Calculate character dimensions for terminal sizing
    val paint = remember {
        android.graphics.Paint().apply {
            textSize = fontSizePx
            typeface = Typeface.MONOSPACE
        }
    }
    val charWidth = remember(fontSizePx) { paint.measureText("M") }
    val charHeight = remember(fontSizePx) { fontSizePx * 1.2f }

    // Track size for resize callback
    var lastCols by remember { mutableIntStateOf(0) }
    var lastRows by remember { mutableIntStateOf(0) }

    // Pinch zoom state for font size adjustment
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        if (onFontSizeChanged != null) {
            val newSize = (fontSize * zoomChange).coerceIn(8f, 24f)
            if (newSize != fontSize) {
                onFontSizeChanged(newSize)
            }
        }
    }

    // Extract text from terminal buffer
    val terminalText = remember(screenVersion) {
        extractTerminalText(emulator)
    }

    Box(
        modifier = modifier
            .background(TerminalColors.background)
            .transformable(state = transformableState)
            .onGloballyPositioned { coordinates ->
                val width = coordinates.size.width
                val height = coordinates.size.height

                // Calculate terminal dimensions based on available space
                val cols = ((width - 16.dp.value * density.density) / charWidth).toInt().coerceAtLeast(20)
                val rows = ((height - 16.dp.value * density.density) / charHeight).toInt().coerceAtLeast(5)

                if (cols != lastCols || rows != lastRows) {
                    lastCols = cols
                    lastRows = rows
                    onSizeChanged?.invoke(cols, rows)
                }
            }
            .verticalScroll(verticalScrollState)
            .horizontalScroll(horizontalScrollState)
            .padding(8.dp)
    ) {
        SelectionContainer {
            Text(
                text = terminalText,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.2).sp,
                // Don't constrain width - let it expand for horizontal scrolling
                softWrap = false
            )
        }
    }

    // Auto-scroll to bottom when content changes
    LaunchedEffect(screenVersion) {
        verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
    }
}

/**
 * Extract styled text from the terminal emulator.
 * Uses transcriptText for correct text, then applies styles per-row.
 */
private fun extractTerminalText(emulator: RemoteTerminalEmulator): AnnotatedString {
    val screen = emulator.getScreen()
    val rows = emulator.getRows()
    val cols = emulator.getColumns()

    return buildAnnotatedString {
        // Iterate through all rows including scrollback
        val firstRow = -screen.activeTranscriptRows

        for (rowIndex in firstRow until rows) {
            if (rowIndex > firstRow) {
                append('\n')
            }

            // Get text for this row using Termux's getSelectedText
            val rowText = screen.getSelectedText(0, rowIndex, cols, rowIndex) ?: ""

            // Apply styles character by character
            for (col in 0 until minOf(rowText.length, cols)) {
                val char = rowText[col]

                // Get style for this cell
                val cellStyle = screen.getStyleAt(rowIndex, col)
                val fgColor = com.termux.terminal.TextStyle.decodeForeColor(cellStyle)
                val effect = com.termux.terminal.TextStyle.decodeEffect(cellStyle)

                val isBold = (effect and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
                val isUnderline = (effect and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
                val isInverse = (effect and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0

                val color = if (isInverse) {
                    TerminalColors.background
                } else {
                    getTerminalColor(fgColor)
                }

                val spanStyle = SpanStyle(
                    color = color,
                    fontWeight = if (isBold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    textDecoration = if (isUnderline) androidx.compose.ui.text.style.TextDecoration.Underline else androidx.compose.ui.text.style.TextDecoration.None
                )

                pushStyle(spanStyle)
                append(char)
                pop()
            }
        }
    }
}

/**
 * Convert terminal color index to Compose Color.
 */
private fun getTerminalColor(colorIndex: Int): Color {
    return when {
        colorIndex == com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND -> TerminalColors.foreground
        colorIndex == com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND -> TerminalColors.background

        // Negative values are true colors (RGB encoded by Termux)
        colorIndex < 0 -> {
            val rgb = colorIndex and 0x00FFFFFF
            Color(rgb or 0xFF000000.toInt())
        }

        // Standard 16 colors (0-15)
        colorIndex < 16 -> STANDARD_COLORS[colorIndex]

        // 216 color cube (16-231)
        colorIndex < 232 -> {
            val index = colorIndex - 16
            val r = (index / 36) * 51
            val g = ((index % 36) / 6) * 51
            val b = (index % 6) * 51
            Color(r, g, b)
        }

        // Grayscale (232-255)
        colorIndex < 256 -> {
            val gray = (colorIndex - 232) * 10 + 8
            Color(gray, gray, gray)
        }

        // True color
        else -> Color(colorIndex or 0xFF000000.toInt())
    }
}

/**
 * Standard terminal colors (ANSI 16 colors).
 */
private val STANDARD_COLORS = listOf(
    Color(0xFF000000), // Black
    Color(0xFFCD0000), // Red
    Color(0xFF00CD00), // Green
    Color(0xFFCDCD00), // Yellow
    Color(0xFF0000EE), // Blue
    Color(0xFFCD00CD), // Magenta
    Color(0xFF00CDCD), // Cyan
    Color(0xFFE5E5E5), // White
    Color(0xFF7F7F7F), // Bright Black
    Color(0xFFFF0000), // Bright Red
    Color(0xFF00FF00), // Bright Green
    Color(0xFFFFFF00), // Bright Yellow
    Color(0xFF5C5CFF), // Bright Blue
    Color(0xFFFF00FF), // Bright Magenta
    Color(0xFF00FFFF), // Bright Cyan
    Color(0xFFFFFFFF), // Bright White
)

/**
 * Terminal color constants matching the dark theme.
 */
object TerminalColors {
    val background = Color(0xFF1E1E1E)
    val foreground = Color(0xFFD4D4D4)
    val cursor = Color(0xFFFFFFFF)

    val backgroundInt = 0xFF1E1E1E.toInt()
    val foregroundInt = 0xFFD4D4D4.toInt()
    val cursorInt = 0xFFFFFFFF.toInt()
}
