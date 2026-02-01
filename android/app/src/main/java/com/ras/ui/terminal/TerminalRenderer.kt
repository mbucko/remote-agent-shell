package com.ras.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ras.R
import com.termux.terminal.TerminalBuffer

/**
 * Compose component that renders a terminal buffer.
 *
 * Uses Termux's TerminalBuffer for the data model and renders it
 * using LazyColumn for virtualization - only visible rows are composed.
 *
 * Performance: Instead of extracting all 2000+ scrollback rows on every
 * recomposition, we only extract the ~40 visible rows, reducing work by 50x.
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

    val screen = emulator.getScreen()
    val terminalRows = emulator.getRows()
    val cols = emulator.getColumns()

    // Total rows including scrollback
    val firstRow = -screen.activeTranscriptRows
    val totalRows = terminalRows - firstRow

    val listState = rememberLazyListState()

    // Track if we've done the initial scroll to bottom
    var hasInitiallyScrolled by remember { mutableStateOf(false) }

    // Track whether user is at/near bottom (for font size changes)
    // This is continuously updated based on scroll state, capturing position BEFORE font changes
    var isAtBottom by remember { mutableStateOf(true) }

    val density = LocalDensity.current

    // Use JetBrains Mono - a true monospace font where all characters have equal width
    val jetBrainsMono = remember {
        FontFamily(
            Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
            Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
        )
    }

    // Use Compose TextMeasurer to get accurate character dimensions
    val textMeasurer = rememberTextMeasurer()
    val textStyle = remember(fontSize, jetBrainsMono) {
        TextStyle(
            fontFamily = jetBrainsMono,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.2).sp
        )
    }

    // With a true monospace font, all characters should have the same width
    val charWidth = remember(fontSize, textMeasurer, jetBrainsMono) {
        textMeasurer.measure(text = "M", style = textStyle).size.width.toFloat()
    }
    val charHeight = remember(fontSize) {
        with(density) { (fontSize * 1.2f).sp.toPx() }
    }

    // Track CONTAINER size in pixels - only recalculate when this changes
    var lastContainerWidth by remember { mutableIntStateOf(0) }
    var lastContainerHeight by remember { mutableIntStateOf(0) }
    var reportedCols by remember { mutableIntStateOf(0) }
    var reportedRows by remember { mutableIntStateOf(0) }

    // Recalculate when font size changes (pinch zoom)
    LaunchedEffect(charWidth, charHeight) {
        if (lastContainerWidth > 0 && lastContainerHeight > 0) {
            val horizontalPadding = with(density) { 16.dp.toPx() }
            val rawCols = ((lastContainerWidth - horizontalPadding) / charWidth).toInt()
            val calculatedCols = (rawCols - 2).coerceAtLeast(20)
            val calculatedRows = ((lastContainerHeight - with(density) { 16.dp.toPx() }) / charHeight).toInt().coerceAtLeast(5)

            if (calculatedCols != reportedCols || calculatedRows != reportedRows) {
                reportedCols = calculatedCols
                reportedRows = calculatedRows
                onSizeChanged?.invoke(calculatedCols, calculatedRows)
            }
        }
    }

    Box(
        modifier = modifier
            .background(TerminalColors.background)
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                val width = coordinates.size.width
                val height = coordinates.size.height

                // Only recalculate if container size actually changed (not just recomposition)
                if (width != lastContainerWidth || height != lastContainerHeight) {
                    lastContainerWidth = width
                    lastContainerHeight = height

                    // Calculate terminal dimensions based on available space
                    val horizontalPadding = with(density) { 16.dp.toPx() }
                    val rawCols = ((width - horizontalPadding) / charWidth).toInt()
                    val calculatedCols = (rawCols - 2).coerceAtLeast(20)
                    val calculatedRows = ((height - with(density) { 16.dp.toPx() }) / charHeight).toInt().coerceAtLeast(5)

                    // Only send resize if cols/rows actually changed
                    if (calculatedCols != reportedCols || calculatedRows != reportedRows) {
                        reportedCols = calculatedCols
                        reportedRows = calculatedRows
                        onSizeChanged?.invoke(calculatedCols, calculatedRows)
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .alpha(if (hasInitiallyScrolled) 1f else 0f)
                .clipToBounds()
                .padding(4.dp)
        ) {
            items(
                count = totalRows,
                // Keys: use absolute row index for stable identity
                key = { index -> firstRow + index }
            ) { index ->
                val rowIndex = firstRow + index
                TerminalRow(
                    screen = screen,
                    rowIndex = rowIndex,
                    cols = cols,
                    screenVersion = screenVersion,
                    fontSize = fontSize,
                    fontFamily = jetBrainsMono
                )
            }
        }
    }

    // Track scroll position continuously for font size changes
    // This captures whether user is at bottom BEFORE font size changes
    LaunchedEffect(listState.firstVisibleItemIndex, listState.canScrollForward) {
        if (hasInitiallyScrolled) {
            isAtBottom = !listState.canScrollForward
        }
    }

    // Preserve scroll position when font size changes
    // (row heights change, which can confuse LazyColumn)
    LaunchedEffect(fontSize) {
        if (hasInitiallyScrolled && totalRows > 0) {
            val itemToRestore = listState.firstVisibleItemIndex

            // Wait for layout to settle with new font size
            awaitFrame()

            if (isAtBottom) {
                // User was at bottom - stay at bottom
                listState.scrollToItem(totalRows - 1)
            } else if (itemToRestore > 0) {
                // Restore previous position
                listState.scrollToItem(itemToRestore)
            }
        }
    }

    // Auto-scroll to bottom:
    // - Always on initial load
    // - After that, only if user is already at bottom (don't interrupt reading scrollback)
    LaunchedEffect(screenVersion, totalRows) {
        if (totalRows > 0) {
            val shouldScroll = !hasInitiallyScrolled || !listState.canScrollForward
            if (shouldScroll) {
                listState.scrollToItem(totalRows - 1)
                // Wait for next frame to let scroll position settle before showing
                if (!hasInitiallyScrolled) {
                    awaitFrame()  // Actually waits for next frame render
                    hasInitiallyScrolled = true
                }
            }
        } else if (!hasInitiallyScrolled) {
            // No content yet, but mark as scrolled so we show the empty terminal
            hasInitiallyScrolled = true
        }
    }
}

/**
 * Renders a single terminal row.
 *
 * This composable extracts text for only ONE row, making it O(cols) instead of O(rows * cols).
 * LazyColumn ensures only visible rows (~40) are composed, not all 2000+ scrollback rows.
 */
@Composable
private fun TerminalRow(
    screen: TerminalBuffer,
    rowIndex: Int,
    cols: Int,
    screenVersion: Long,
    fontSize: Float,
    fontFamily: FontFamily
) {
    // Cache extracted text - invalidate when screen content changes
    val rowText = remember(rowIndex, screenVersion, cols) {
        extractRowText(screen, rowIndex, cols)
    }

    Text(
        text = rowText,
        fontFamily = fontFamily,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.2).sp,
        softWrap = false
    )
}

/**
 * Extract styled text for a single terminal row.
 */
private fun extractRowText(
    screen: TerminalBuffer,
    rowIndex: Int,
    cols: Int
): AnnotatedString {
    return buildAnnotatedString {
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
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (isUnderline) TextDecoration.Underline else TextDecoration.None
            )

            pushStyle(spanStyle)
            append(char)
            pop()
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
