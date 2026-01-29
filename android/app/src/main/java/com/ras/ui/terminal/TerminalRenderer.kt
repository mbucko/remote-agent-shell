package com.ras.ui.terminal

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TextStyle
import kotlinx.coroutines.delay

/**
 * Compose component that renders a terminal buffer using Canvas.
 *
 * Uses Termux's TerminalBuffer for the data model and renders it
 * efficiently using Android Canvas drawing.
 */
@Composable
fun TerminalRenderer(
    emulator: RemoteTerminalEmulator,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    onSizeChanged: ((cols: Int, rows: Int) -> Unit)? = null
) {
    // Observe screen version to trigger recomposition
    val screenVersion = emulator.screenVersion

    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.sp.toPx() }

    // Calculate cell dimensions based on font
    val paint = remember {
        android.graphics.Paint().apply {
            textSize = fontSizePx
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }

    val cellWidth = remember(fontSizePx) { paint.measureText("M") }
    val cellHeight = remember(fontSizePx) { paint.fontMetrics.let { it.descent - it.ascent } }
    val baselineOffset = remember(fontSizePx) { -paint.fontMetrics.ascent }

    // Cursor blink state
    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            cursorVisible = !cursorVisible
        }
    }

    val verticalScrollState = rememberScrollState()

    Box(
        modifier = modifier
            .background(TerminalColors.background)
            .verticalScroll(verticalScrollState)
            .padding(4.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { /* Handle tap if needed */ }
                }
        ) {
            val screen = emulator.getScreen()
            val rows = emulator.getRows()
            val cols = emulator.getColumns()

            // Get cursor position
            val cursorRow = emulator.getCursorRow()
            val cursorCol = emulator.getCursorCol()
            val showCursor = emulator.isCursorVisible() && cursorVisible

            // Draw each row
            for (row in 0 until rows) {
                drawTerminalRow(
                    screen = screen,
                    row = row,
                    cols = cols,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    baselineOffset = baselineOffset,
                    paint = paint,
                    cursorCol = if (row == cursorRow && showCursor) cursorCol else -1
                )
            }
        }
    }

    // Auto-scroll to bottom when content changes
    LaunchedEffect(screenVersion) {
        verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
    }
}

private fun DrawScope.drawTerminalRow(
    screen: TerminalBuffer,
    row: Int,
    cols: Int,
    cellWidth: Float,
    cellHeight: Float,
    baselineOffset: Float,
    paint: android.graphics.Paint,
    cursorCol: Int
) {
    val y = row * cellHeight

    // Get the row's line for character access
    val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))

    for (col in 0 until cols) {
        val x = col * cellWidth

        // Get cell style
        val cellStyle = screen.getStyleAt(row, col)

        // Get character from TerminalRow
        val charIndex = line.findStartOfColumn(col)
        val cellChar = if (charIndex < line.mText.size && charIndex >= 0) {
            line.mText[charIndex].code
        } else {
            32 // space
        }

        // Decode style
        val fgColor = TextStyle.decodeForeColor(cellStyle)
        val bgColor = TextStyle.decodeBackColor(cellStyle)
        val effect = TextStyle.decodeEffect(cellStyle)

        val isBold = (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
        val isItalic = (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0
        val isUnderline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
        val isInverse = (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0

        // Handle inverse video
        val actualFg = if (isInverse) bgColor else fgColor
        val actualBg = if (isInverse) fgColor else bgColor

        // Draw background if not default
        val bgAndroidColor = getTerminalColor(actualBg, true)
        if (bgAndroidColor != TerminalColors.backgroundInt || col == cursorCol) {
            val rectColor = if (col == cursorCol) {
                TerminalColors.cursorInt
            } else {
                bgAndroidColor
            }
            drawContext.canvas.nativeCanvas.drawRect(
                x, y, x + cellWidth, y + cellHeight,
                android.graphics.Paint().apply { color = rectColor }
            )
        }

        // Draw character if not space/null
        if (cellChar > 32) {
            val fgAndroidColor = if (col == cursorCol) {
                TerminalColors.backgroundInt
            } else {
                getTerminalColor(actualFg, false)
            }

            paint.color = fgAndroidColor
            paint.isFakeBoldText = isBold
            paint.textSkewX = if (isItalic) -0.25f else 0f

            drawContext.canvas.nativeCanvas.drawText(
                Character.toChars(cellChar).concatToString(),
                x,
                y + baselineOffset,
                paint
            )

            // Draw underline
            if (isUnderline) {
                drawContext.canvas.nativeCanvas.drawLine(
                    x, y + cellHeight - 2,
                    x + cellWidth, y + cellHeight - 2,
                    paint
                )
            }
        }
    }
}

/**
 * Convert terminal color index to Android color.
 */
private fun getTerminalColor(colorIndex: Int, isBackground: Boolean): Int {
    return when {
        // Default colors
        colorIndex == TextStyle.COLOR_INDEX_FOREGROUND -> TerminalColors.foregroundInt
        colorIndex == TextStyle.COLOR_INDEX_BACKGROUND -> TerminalColors.backgroundInt
        colorIndex == TextStyle.COLOR_INDEX_CURSOR -> TerminalColors.cursorInt

        // Standard 16 colors (0-15)
        colorIndex < 16 -> STANDARD_COLORS[colorIndex]

        // 216 color cube (16-231)
        colorIndex < 232 -> {
            val index = colorIndex - 16
            val r = (index / 36) * 51
            val g = ((index % 36) / 6) * 51
            val b = (index % 6) * 51
            android.graphics.Color.rgb(r, g, b)
        }

        // Grayscale (232-255)
        colorIndex < 256 -> {
            val gray = (colorIndex - 232) * 10 + 8
            android.graphics.Color.rgb(gray, gray, gray)
        }

        // True color (encoded in upper bits)
        else -> colorIndex
    }
}

/**
 * Standard terminal colors (ANSI 16 colors).
 */
private val STANDARD_COLORS = intArrayOf(
    // Normal colors (0-7)
    0xFF000000.toInt(), // Black
    0xFFCD0000.toInt(), // Red
    0xFF00CD00.toInt(), // Green
    0xFFCDCD00.toInt(), // Yellow
    0xFF0000EE.toInt(), // Blue
    0xFFCD00CD.toInt(), // Magenta
    0xFF00CDCD.toInt(), // Cyan
    0xFFE5E5E5.toInt(), // White

    // Bright colors (8-15)
    0xFF7F7F7F.toInt(), // Bright Black (Gray)
    0xFFFF0000.toInt(), // Bright Red
    0xFF00FF00.toInt(), // Bright Green
    0xFFFFFF00.toInt(), // Bright Yellow
    0xFF5C5CFF.toInt(), // Bright Blue
    0xFFFF00FF.toInt(), // Bright Magenta
    0xFF00FFFF.toInt(), // Bright Cyan
    0xFFFFFFFF.toInt(), // Bright White
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
