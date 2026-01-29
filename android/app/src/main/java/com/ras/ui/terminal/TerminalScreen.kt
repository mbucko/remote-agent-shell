package com.ras.ui.terminal

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ras.R
import com.ras.data.terminal.TerminalScreenState
import com.ras.data.terminal.TerminalState
import com.ras.data.terminal.TerminalUiEvent
import com.ras.ui.theme.StatusConnected
import com.ras.ui.theme.StatusError
import com.ras.ui.theme.TerminalBackground
import com.ras.util.ClipboardHelper
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val sessionName by viewModel.sessionName.collectAsStateWithLifecycle()
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val quickButtons by viewModel.quickButtons.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val pasteTruncated by viewModel.pasteTruncated.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Resume attachment when screen appears
    LaunchedEffect(Unit) {
        viewModel.onResume()
    }

    // Handle UI events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is TerminalUiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is TerminalUiEvent.ShowOutputSkipped -> {
                    val kb = event.bytesSkipped / 1024
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.terminal_output_skipped, kb)
                    )
                }
                is TerminalUiEvent.NavigateBack -> {
                    onNavigateBack()
                }
            }
        }
    }

    // Handle paste truncation warning
    LaunchedEffect(pasteTruncated) {
        if (pasteTruncated) {
            snackbarHostState.showSnackbar(
                context.getString(R.string.terminal_paste_truncated)
            )
            viewModel.dismissPasteTruncated()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sessionName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Raw mode toggle
                    val isRawMode = (screenState as? TerminalScreenState.Connected)?.isRawMode == true
                    TextButton(onClick = { viewModel.onRawModeToggle() }) {
                        Text(
                            if (isRawMode) stringResource(R.string.terminal_normal_mode)
                            else stringResource(R.string.terminal_raw_mode)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Terminal output area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TerminalBackground)
            ) {
                when (val state = screenState) {
                    is TerminalScreenState.Attaching -> {
                        AttachingContent(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is TerminalScreenState.Connected -> {
                        TerminalRenderer(
                            emulator = viewModel.terminalEmulator,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    is TerminalScreenState.Disconnected -> {
                        DisconnectedContent(
                            reason = state.reason,
                            canReconnect = state.canReconnect,
                            onReconnect = { viewModel.reconnect() },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is TerminalScreenState.Error -> {
                        ErrorContent(
                            message = state.message,
                            onRetry = { viewModel.reconnect() },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                // Output skipped banner
                terminalState.outputSkipped?.let { skipped ->
                    OutputSkippedBanner(
                        bytesSkipped = skipped.bytesSkipped,
                        onDismiss = { viewModel.dismissOutputSkipped() },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }

            // Quick buttons bar
            QuickButtonBar(
                buttons = quickButtons,
                onButtonClick = { viewModel.onQuickButtonClicked(it) },
                onAddClick = { viewModel.openButtonEditor() },
                modifier = Modifier.fillMaxWidth()
            )

            // Input area (depends on mode)
            val isConnected = screenState is TerminalScreenState.Connected
            val isRawMode = (screenState as? TerminalScreenState.Connected)?.isRawMode == true

            if (isRawMode) {
                // Raw mode input with keyboard support
                RawModeInput(
                    onKeyPress = { keyCode, isCtrl -> viewModel.onRawKeyPress(keyCode, isCtrl) },
                    onCharacter = { viewModel.onRawCharacterInput(it) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Line-buffered input
                InputBar(
                    text = inputText,
                    onTextChange = { viewModel.onInputTextChanged(it) },
                    onSend = { viewModel.onSendClicked() },
                    onEnter = { viewModel.onEnterPressed() },
                    onPaste = {
                        ClipboardHelper.extractText(context)?.let { text ->
                            viewModel.onPaste(text)
                        }
                    },
                    enabled = isConnected,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AttachingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.terminal_attaching),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun DisconnectedContent(
    reason: String,
    canReconnect: Boolean,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.terminal_disconnected),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (canReconnect) {
            Button(
                onClick = onReconnect,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.terminal_reconnect))
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun OutputSkippedBanner(
    bytesSkipped: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val kb = bytesSkipped / 1024
            Text(
                text = "~${kb}KB output skipped",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * Raw mode input that shows a keyboard and sends keypresses directly.
 */
@Composable
private fun RawModeInput(
    onKeyPress: (keyCode: Int, isCtrlPressed: Boolean) -> Boolean,
    onCharacter: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputText = remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.terminal_raw_mode_active),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp)
            )

            OutlinedTextField(
                value = inputText.value,
                onValueChange = { newValue ->
                    // Send each new character
                    if (newValue.length > inputText.value.length) {
                        val newChar = newValue.last()
                        onCharacter(newChar)
                    }
                    // Clear input after sending to keep it fresh
                    inputText.value = ""
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            val keyCode = keyEvent.nativeKeyEvent.keyCode
                            val isCtrl = keyEvent.nativeKeyEvent.isCtrlPressed
                            onKeyPress(keyCode, isCtrl)
                        } else {
                            false
                        }
                    },
                placeholder = { Text("Type here...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun TerminalScreenAttachingPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TerminalBackground)
        ) {
            AttachingContent(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TerminalScreenDisconnectedPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TerminalBackground)
        ) {
            DisconnectedContent(
                reason = "Connection lost",
                canReconnect = true,
                onReconnect = {},
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TerminalScreenErrorPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TerminalBackground)
        ) {
            ErrorContent(
                message = "Session not found",
                onRetry = {},
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
