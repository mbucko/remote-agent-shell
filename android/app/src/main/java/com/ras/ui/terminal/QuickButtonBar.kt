package com.ras.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ras.R
import com.ras.data.terminal.DEFAULT_QUICK_BUTTONS
import com.ras.data.terminal.QuickButton
import com.ras.proto.KeyType

/**
 * Horizontal scrollable bar of quick action buttons.
 *
 * Displays configurable buttons like Y, N, Ctrl+C for quick terminal input.
 * Has an "Add" button at the end to open the button editor.
 */
@Composable
fun QuickButtonBar(
    buttons: List<QuickButton>,
    onButtonClick: (QuickButton) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            buttons.forEach { button ->
                QuickActionButton(
                    button = button,
                    onClick = { onButtonClick(button) }
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Add button
            IconButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.terminal_add_button)
                )
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    button: QuickButton,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = button.label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun QuickButtonBarPreview() {
    MaterialTheme {
        QuickButtonBar(
            buttons = DEFAULT_QUICK_BUTTONS,
            onButtonClick = {},
            onAddClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun QuickButtonBarManyButtonsPreview() {
    MaterialTheme {
        QuickButtonBar(
            buttons = listOf(
                QuickButton("y", "Y", character = "y"),
                QuickButton("n", "N", character = "n"),
                QuickButton("ctrl_c", "Ctrl+C", keyType = KeyType.KEY_CTRL_C),
                QuickButton("tab", "Tab", keyType = KeyType.KEY_TAB),
                QuickButton("up", "↑", keyType = KeyType.KEY_UP),
                QuickButton("down", "↓", keyType = KeyType.KEY_DOWN),
                QuickButton("esc", "Esc", keyType = KeyType.KEY_ESCAPE)
            ),
            onButtonClick = {},
            onAddClick = {}
        )
    }
}
