package com.ras.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    onEnter: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.navigationBarsPadding(),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Paste button (smaller)
            IconButton(
                onClick = onPaste,
                enabled = enabled,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = stringResource(R.string.terminal_paste)
                )
            }

            // Text field - compact with minimal padding
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (text.isNotEmpty()) onSend() }
                ),
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.terminal_input_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )

            // Enter button (compact)
            Button(
                onClick = onEnter,
                enabled = enabled,
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                    contentDescription = stringResource(R.string.terminal_enter)
                )
            }

            // Send button (smaller)
            IconButton(
                onClick = onSend,
                enabled = enabled && text.isNotEmpty(),
                modifier = Modifier.size(32.dp)
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
            onEnter = {},
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
            onEnter = {},
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
            onEnter = {},
            onPaste = {},
            enabled = false
        )
    }
}
