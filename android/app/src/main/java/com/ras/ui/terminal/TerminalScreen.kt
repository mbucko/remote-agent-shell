package com.ras.ui.terminal

import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
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
import com.ras.ui.components.ConnectionStatusOverlay
import com.ras.ui.theme.StatusConnected
import com.ras.ui.theme.StatusError
import com.ras.ui.theme.TerminalBackground
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Font size constants
private const val FONT_SIZE_MIN = 8f
private const val FONT_SIZE_MAX = 24f
private const val FONT_SIZE_DEFAULT = 12f
private const val FONT_SIZE_STEP = 2f

// Font size control button dimensions
private val FONT_BUTTON_SIZE = 32.dp
private val FONT_ICON_SIZE = 18.dp
private val FONT_BUTTON_SPACING = 12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    @Suppress("UNUSED_PARAMETER") sessionId: String, // Used by NavGraph for route matching
    onNavigateBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val sessionName by viewModel.sessionName.collectAsStateWithLifecycle()
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val quickButtons by viewModel.quickButtons.collectAsStateWithLifecycle()
    val modifierState by viewModel.modifierState.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val pasteTruncated by viewModel.pasteTruncated.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()

    // Modifier key visibility settings
    val showCtrlKey by viewModel.showCtrlKey.collectAsStateWithLifecycle()
    val showShiftKey by viewModel.showShiftKey.collectAsStateWithLifecycle()
    val showAltKey by viewModel.showAltKey.collectAsStateWithLifecycle()
    val showMetaKey by viewModel.showMetaKey.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Trigger for scroll-to-bottom button
    var scrollToBottomTrigger by remember { mutableIntStateOf(0) }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onImageSelected(it) }
    }

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
            // Compact custom header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = sessionName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                )

                // Terminal controls: scroll to bottom, font size
                TerminalControls(
                    onScrollToBottom = { scrollToBottomTrigger++ },
                    onDecrease = {
                        viewModel.onFontSizeChanged((fontSize - FONT_SIZE_STEP).coerceAtLeast(FONT_SIZE_MIN))
                    },
                    onIncrease = {
                        viewModel.onFontSizeChanged((fontSize + FONT_SIZE_STEP).coerceAtMost(FONT_SIZE_MAX))
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Raw mode toggle - fixed width to prevent button shifting
                val isRawMode = (screenState as? TerminalScreenState.Connected)?.isRawMode == true
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .clickable { viewModel.onRawModeToggle() }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isRawMode) stringResource(R.string.terminal_normal_mode)
                               else stringResource(R.string.terminal_raw_mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Terminal output area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
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
                            modifier = Modifier.fillMaxSize(),
                            fontSize = fontSize,
                            scrollToBottomTrigger = scrollToBottomTrigger,
                            onSizeChanged = { cols, rows ->
                                viewModel.onTerminalSizeChanged(cols, rows)
                            }
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

            // Connection status overlay - YouTube-style thin bar
            // Shows only on disconnect/reconnect, auto-hides after connected
            ConnectionStatusOverlay(
                isConnected = isConnected,
                modifier = Modifier.fillMaxWidth()
            )

            // Quick buttons bar with modifier keys
            QuickButtonBar(
                buttons = quickButtons,
                modifierState = modifierState,
                onButtonClick = { viewModel.onQuickButtonClicked(it) },
                onModifierTap = { viewModel.onModifierTap(it) },
                onModifierLongPress = { viewModel.onModifierLongPress(it) },
                modifier = Modifier.fillMaxWidth(),
                showCtrl = showCtrlKey,
                showShift = showShiftKey,
                showAlt = showAltKey,
                showMeta = showMetaKey
            )

            // Input area (depends on mode)
            val isScreenConnected = screenState is TerminalScreenState.Connected
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
                    onPaste = { viewModel.onPasteClicked() },
                    onPickImage = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = isScreenConnected,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Terminal control buttons: scroll to bottom, font size decrease/increase.
 * Uses less rounded buttons for a more compact look.
 */
@Composable
private fun TerminalControls(
    onScrollToBottom: () -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonShape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(FONT_BUTTON_SPACING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = onScrollToBottom,
            modifier = Modifier.size(FONT_BUTTON_SIZE),
            shape = buttonShape
        ) {
            Icon(
                Icons.Default.KeyboardDoubleArrowDown,
                contentDescription = "Scroll to bottom",
                modifier = Modifier.size(FONT_ICON_SIZE)
            )
        }

        FilledTonalIconButton(
            onClick = onDecrease,
            modifier = Modifier.size(FONT_BUTTON_SIZE),
            shape = buttonShape
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "Decrease font size",
                modifier = Modifier.size(FONT_ICON_SIZE)
            )
        }

        FilledTonalIconButton(
            onClick = onIncrease,
            modifier = Modifier.size(FONT_BUTTON_SIZE),
            shape = buttonShape
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Increase font size",
                modifier = Modifier.size(FONT_ICON_SIZE)
            )
        }
    }
}

@Composable
private fun AttachingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.terminal_attaching),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
 * Raw mode input - minimal UI with invisible text field for keyboard capture.
 */
@Composable
private fun RawModeInput(
    onKeyPress: (keyCode: Int, isCtrlPressed: Boolean) -> Boolean,
    onCharacter: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Keep a space so backspace has something to delete
    val inputText = remember { mutableStateOf(" ") }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Invisible text field to capture keyboard input
    Box(modifier = modifier) {
        BasicTextField(
            value = inputText.value,
            onValueChange = { newValue ->
                val oldLen = inputText.value.length
                val newLen = newValue.length

                if (newLen > oldLen) {
                    // New character typed
                    val newChar = newValue.last()
                    onCharacter(newChar)
                } else if (newLen < oldLen) {
                    // Backspace pressed - send DEL character
                    onCharacter('\u007F')
                }
                // Always reset to single space for next input
                inputText.value = " "
            },
            modifier = Modifier
                .fillMaxWidth()
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
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            // Make text field invisible - just captures keyboard
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent)
        )
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
