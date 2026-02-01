package com.ras.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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
 * Configuration constants for terminal rendering and scroll behavior.
 */
private object TerminalRendererConfig {
    /** Delay before accepting totalRows decrease (buffer reorganization during resize) */
    const val TOTAL_ROWS_DEBOUNCE_MS = 200L

    /** Delay before notifying resize (keyboard animation settling) */
    const val RESIZE_DEBOUNCE_MS = 150L

    /** Delay before scrolling after viewport expand (wait for all debounces to complete) */
    const val VIEWPORT_EXPAND_SCROLL_DELAY_MS = 500L

    /** Consider "at bottom" if within this many items of item 0 */
    const val AT_BOTTOM_THRESHOLD = 3

    /** Minimum terminal columns */
    const val MIN_COLS = 20

    /** Minimum terminal rows */
    const val MIN_ROWS = 5

    /** Column buffer for edge padding */
    const val COL_BUFFER = 2
}

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
    scrollToBottomTrigger: Int = 0,
    onSizeChanged: ((cols: Int, rows: Int) -> Unit)? = null
) {
    // Observe screen version to trigger recomposition
    val screenVersion = emulator.screenVersion

    val screen = emulator.getScreen()
    val terminalRows = emulator.getRows()
    val cols = emulator.getColumns()

    // Total rows including scrollback
    val firstRow = -screen.activeTranscriptRows
    val rawTotalRows = terminalRows - firstRow

    // Stabilize totalRows to prevent bounce during buffer reorganization
    // - Increases: accept immediately (new content arrived)
    // - Decreases: debounce (might be temporary fluctuation during resize)
    var stableTotalRows by remember { mutableIntStateOf(rawTotalRows) }

    LaunchedEffect(rawTotalRows) {
        if (rawTotalRows > stableTotalRows) {
            // New content - accept immediately
            stableTotalRows = rawTotalRows
        } else if (rawTotalRows < stableTotalRows) {
            // Possible fluctuation - wait for stability
            delay(TerminalRendererConfig.TOTAL_ROWS_DEBOUNCE_MS)
            // Accept decrease only if value is still the same (not a fluctuation)
            stableTotalRows = rawTotalRows
        }
    }

    val totalRows = stableTotalRows

    // Use remember instead of rememberLazyListState to prevent scroll position
    // restoration via SaveableStateRegistry - we always want to start at bottom
    val listState = remember { LazyListState() }

    // Track if we've done the initial scroll to bottom
    var hasInitiallyScrolled by remember { mutableStateOf(false) }

    // Track if user is at bottom - updated continuously, captures state BEFORE changes
    var wasAtBottom by remember { mutableStateOf(true) }

    // Update wasAtBottom continuously based on scroll position
    LaunchedEffect(listState.firstVisibleItemIndex) {
        wasAtBottom = listState.firstVisibleItemIndex < TerminalRendererConfig.AT_BOTTOM_THRESHOLD
    }

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

    // Track CONTAINER size in pixels
    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }

    // Track what we've reported to avoid duplicate notifications
    var reportedCols by remember { mutableIntStateOf(0) }
    var reportedRows by remember { mutableIntStateOf(0) }

    // Calculate cols/rows from current container size and font
    val horizontalPadding = with(density) { 16.dp.toPx() }
    val verticalPadding = with(density) { 16.dp.toPx() }
    val calculatedCols = if (containerWidth > 0) {
        val rawCols = ((containerWidth - horizontalPadding) / charWidth).toInt()
        (rawCols - TerminalRendererConfig.COL_BUFFER).coerceAtLeast(TerminalRendererConfig.MIN_COLS)
    } else 0
    val calculatedRows = if (containerHeight > 0) {
        ((containerHeight - verticalPadding) / charHeight).toInt().coerceAtLeast(TerminalRendererConfig.MIN_ROWS)
    } else 0

    // SINGLE debounced resize notification - waits for size to stabilize
    LaunchedEffect(calculatedCols, calculatedRows) {
        if (calculatedCols == 0 || calculatedRows == 0) return@LaunchedEffect
        if (calculatedCols == reportedCols && calculatedRows == reportedRows) return@LaunchedEffect

        // Wait for resize to stabilize (keyboard animation)
        delay(TerminalRendererConfig.RESIZE_DEBOUNCE_MS)

        // Only notify if still different after debounce
        if (calculatedCols != reportedCols || calculatedRows != reportedRows) {
            reportedCols = calculatedCols
            reportedRows = calculatedRows
            onSizeChanged?.invoke(calculatedCols, calculatedRows)
        }
    }

    Box(
        modifier = modifier
            .background(TerminalColors.background)
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                // Just capture size - debounced LaunchedEffect handles notification
                containerWidth = coordinates.size.width
                containerHeight = coordinates.size.height
            }
    ) {
        LazyColumn(
            state = listState,
            reverseLayout = true,  // Bottom-anchored: item 0 at bottom, naturally stays at bottom
            modifier = Modifier
                .alpha(if (hasInitiallyScrolled || totalRows == 0) 1f else 0f)
                .clipToBounds()
                .padding(4.dp)
        ) {
            items(
                count = totalRows,
                // Key must match the actual row being displayed
                key = { index ->
                    // index 0 displays the last row, index totalRows-1 displays the first row
                    firstRow + (totalRows - 1 - index)
                }
            ) { index ->
                // With reverseLayout, we need to reverse the item order
                // index 0 should show the LAST row (most recent), displayed at bottom
                val rowIndex = firstRow + (totalRows - 1 - index)
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

    // With reverseLayout=true, item 0 is at the bottom and the list naturally stays there.
    // We only need to wait for initial layout, then mark as scrolled.
    LaunchedEffect(Unit) {
        while (listState.layoutInfo.totalItemsCount == 0) {
            awaitFrame()
        }
        awaitFrame()
        hasInitiallyScrolled = true
    }

    // Scroll to bottom when viewport expands (keyboard hides) and user was at bottom
    LaunchedEffect(Unit) {
        var lastHeight = 0
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .drop(1)
            .collectLatest { height ->
                val expanded = height > lastHeight
                lastHeight = height

                if (expanded && hasInitiallyScrolled && wasAtBottom) {
                    // Wait for everything to stabilize (resize + totalRows debounces + buffer)
                    delay(TerminalRendererConfig.VIEWPORT_EXPAND_SCROLL_DELAY_MS)
                    if (listState.firstVisibleItemIndex > 0) {
                        listState.scrollToItem(0)
                    }
                }
            }
    }

    // Scroll to bottom when triggered by parent (e.g., scroll-to-bottom button)
    LaunchedEffect(scrollToBottomTrigger) {
        if (scrollToBottomTrigger > 0 && hasInitiallyScrolled) {
            listState.scrollToItem(0)
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
