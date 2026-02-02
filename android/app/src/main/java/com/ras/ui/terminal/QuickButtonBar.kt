package com.ras.ui.terminal

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ras.data.terminal.DEFAULT_QUICK_BUTTONS
import com.ras.data.terminal.ModifierKey
import com.ras.data.terminal.ModifierMode
import com.ras.data.terminal.ModifierState
import com.ras.data.terminal.QuickButton
import com.ras.proto.KeyType

/**
 * Bar of quick action buttons with optional modifier keys.
 *
 * Displays:
 * - Modifier buttons (Ctrl, Shift, Alt, Meta) with tap/long-press for sticky/locked state
 * - Configurable buttons like Y, N, Ctrl+C for quick terminal input
 *
 * Uses FlowRow to wrap buttons to multiple rows when needed.
 * Button configuration is done through Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickButtonBar(
    buttons: List<QuickButton>,
    modifierState: ModifierState,
    onButtonClick: (QuickButton) -> Unit,
    onModifierTap: (ModifierKey) -> Unit,
    onModifierLongPress: (ModifierKey) -> Unit,
    modifier: Modifier = Modifier,
    showCtrl: Boolean = true,
    showShift: Boolean = true,
    showAlt: Boolean = false,
    showMeta: Boolean = false
) {
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Modifier buttons (universal symbols that work on Mac/Win/Linux)
            if (showCtrl) {
                ModifierButton(
                    label = "Ctrl",
                    mode = modifierState.ctrl,
                    onTap = { onModifierTap(ModifierKey.CTRL) },
                    onLongPress = { onModifierLongPress(ModifierKey.CTRL) }
                )
            }
            if (showShift) {
                ModifierButton(
                    label = "⇧",
                    mode = modifierState.shift,
                    onTap = { onModifierTap(ModifierKey.SHIFT) },
                    onLongPress = { onModifierLongPress(ModifierKey.SHIFT) }
                )
            }
            if (showAlt) {
                ModifierButton(
                    label = "⌥",  // Option symbol (Alt on Windows/Linux)
                    mode = modifierState.alt,
                    onTap = { onModifierTap(ModifierKey.ALT) },
                    onLongPress = { onModifierLongPress(ModifierKey.ALT) }
                )
            }
            if (showMeta) {
                ModifierButton(
                    label = "⌘",  // Command symbol (Super/Win on Windows/Linux)
                    mode = modifierState.meta,
                    onTap = { onModifierTap(ModifierKey.META) },
                    onLongPress = { onModifierLongPress(ModifierKey.META) }
                )
            }

            // Regular quick buttons
            buttons.forEach { button ->
                QuickActionButton(
                    button = button,
                    onClick = { onButtonClick(button) }
                )
            }
        }
    }
}

/**
 * Modifier button with tap (sticky) and long-press (lock) support.
 *
 * Visual states:
 * - OFF: Tertiary container color (distinct from action buttons)
 * - STICKY: Primary container color (active for next key only)
 * - LOCKED: Primary color (stays active until toggled off)
 */
@Composable
fun ModifierButton(
    label: String,
    mode: ModifierMode,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Use tertiary color when OFF to distinguish from action buttons
    val backgroundColor = when (mode) {
        ModifierMode.OFF -> MaterialTheme.colorScheme.tertiaryContainer
        ModifierMode.STICKY -> MaterialTheme.colorScheme.primaryContainer
        ModifierMode.LOCKED -> MaterialTheme.colorScheme.primary
    }

    val contentColor = when (mode) {
        ModifierMode.OFF -> MaterialTheme.colorScheme.onTertiaryContainer
        ModifierMode.STICKY -> MaterialTheme.colorScheme.onPrimaryContainer
        ModifierMode.LOCKED -> MaterialTheme.colorScheme.onPrimary
    }

    // Use Surface with pointerInput for both tap and long-press
    // (Button's onClick conflicts with detectTapGestures)
    Surface(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        shape = RoundedCornerShape(4.dp),
        color = backgroundColor
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}

@Composable
private fun QuickActionButton(
    button: QuickButton,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Text(
            text = button.label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun QuickButtonBarPreview() {
    MaterialTheme {
        QuickButtonBar(
            buttons = DEFAULT_QUICK_BUTTONS,
            modifierState = ModifierState(),
            onButtonClick = {},
            onModifierTap = {},
            onModifierLongPress = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun QuickButtonBarWithStickyModifierPreview() {
    MaterialTheme {
        QuickButtonBar(
            buttons = DEFAULT_QUICK_BUTTONS,
            modifierState = ModifierState(shift = ModifierMode.STICKY),
            onButtonClick = {},
            onModifierTap = {},
            onModifierLongPress = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun QuickButtonBarWithLockedModifierPreview() {
    MaterialTheme {
        QuickButtonBar(
            buttons = DEFAULT_QUICK_BUTTONS,
            modifierState = ModifierState(ctrl = ModifierMode.LOCKED),
            onButtonClick = {},
            onModifierTap = {},
            onModifierLongPress = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun QuickButtonBarManyButtonsPreview() {
    MaterialTheme {
        QuickButtonBar(
            buttons = listOf(
                QuickButton("y", "Yes", character = "y"),
                QuickButton("n", "No", character = "n"),
                QuickButton("ctrl_c", "Ctrl+C", keyType = KeyType.KEY_CTRL_C),
                QuickButton("up", "↑", keyType = KeyType.KEY_UP),
                QuickButton("down", "↓", keyType = KeyType.KEY_DOWN),
                QuickButton("backspace", "⌫", keyType = KeyType.KEY_BACKSPACE),
                QuickButton("tab", "Tab", keyType = KeyType.KEY_TAB)
            ),
            modifierState = ModifierState(),
            onButtonClick = {},
            onModifierTap = {},
            onModifierLongPress = {},
            showAlt = true,
            showMeta = true
        )
    }
}
