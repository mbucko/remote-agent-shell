package com.ras.ui.terminal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ras.R

/**
 * Input bar for line-buffered terminal input.
 *
 * Contains:
 * - Paste button (to paste from clipboard)
 * - Text field for input
 * - Send button (enabled when input is not empty)
 */
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Paste button
            IconButton(
                onClick = onPaste,
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = stringResource(R.string.terminal_paste)
                )
            }

            // Text field
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.terminal_input_hint)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (text.isNotEmpty()) onSend() }
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send button
            IconButton(
                onClick = onSend,
                enabled = enabled && text.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.terminal_send)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun InputBarEmptyPreview() {
    MaterialTheme {
        InputBar(
            text = "",
            onTextChange = {},
            onSend = {},
            onPaste = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun InputBarWithTextPreview() {
    MaterialTheme {
        InputBar(
            text = "ls -la",
            onTextChange = {},
            onSend = {},
            onPaste = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun InputBarDisabledPreview() {
    MaterialTheme {
        InputBar(
            text = "",
            onTextChange = {},
            onSend = {},
            onPaste = {},
            enabled = false
        )
    }
}
