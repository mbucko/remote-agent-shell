package com.ras.ui.terminal

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.connection.ConnectionLifecycleState
import com.ras.data.sessions.SessionRepository
import com.ras.data.settings.ModifierKeySettings
import com.ras.data.settings.SettingsQuickButton
import com.ras.data.settings.SettingsRepository
import com.ras.data.terminal.KeyMapper
import com.ras.data.terminal.AppAction
import com.ras.data.terminal.ModifierKey
import com.ras.data.terminal.ModifierMode
import com.ras.data.terminal.ModifierState
import com.ras.data.terminal.getAppShortcut
import com.ras.data.terminal.QuickButton
import com.ras.data.terminal.TerminalEvent
import com.ras.data.terminal.TerminalRepository
import com.ras.data.terminal.TerminalScreenState
import com.ras.data.terminal.TerminalState
import com.ras.data.terminal.TerminalUiEvent
import com.ras.data.terminal.toggle
import com.ras.proto.KeyType
import com.ras.proto.clipboard.ClipboardMessage
import com.ras.proto.clipboard.ImageChunk
import com.ras.proto.clipboard.ImageFormat
import com.ras.proto.clipboard.ImageStart
import com.ras.ui.navigation.NavArgs
import com.ras.util.ClipboardImage
import com.ras.util.ClipboardService
import com.google.protobuf.ByteString
import java.util.UUID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Terminal screen.
 *
 * Manages:
 * - Terminal attachment lifecycle
 * - Input handling (text, special keys, raw mode)
 * - Quick button configuration
 * - UI state
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TerminalRepository,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val modifierKeySettings: ModifierKeySettings,
    private val clipboardService: ClipboardService
) : ViewModel() {

    private val TAG = "TerminalViewModel"

    private val sessionId: String = savedStateHandle.get<String>(NavArgs.SESSION_ID)
        ?: throw IllegalArgumentException("sessionId is required")

    // Terminal state from repository
    val terminalState: StateFlow<TerminalState> = repository.state

    // Terminal emulator for ANSI escape sequence processing
    val terminalEmulator = RemoteTerminalEmulator(
        columns = 80,
        rows = 24,
        onTitleChanged = { _ ->
            // Could update session name if desired
        },
        onBell = {
            // Could play a sound or vibrate
        }
    )

    // Connection state
    val isConnected: StateFlow<Boolean> = repository.isConnected

    // Screen state (derived from terminal state)
    private val _screenState = MutableStateFlow<TerminalScreenState>(TerminalScreenState.Attaching)
    val screenState: StateFlow<TerminalScreenState> = _screenState.asStateFlow()

    // Session name for display
    private val _sessionName = MutableStateFlow("Session")
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    // Quick buttons - observe from settings and convert to QuickButton
    val quickButtons: StateFlow<List<QuickButton>> = settingsRepository.quickButtons
        .map { settingsButtons ->
            settingsButtons.map { it.toQuickButton() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Modifier key state (sticky/locked modifiers for quick buttons)
    private val _modifierState = MutableStateFlow(ModifierState())
    val modifierState: StateFlow<ModifierState> = _modifierState.asStateFlow()

    // Modifier key visibility settings
    val showCtrlKey: StateFlow<Boolean> = modifierKeySettings.showCtrlKey
    val showShiftKey: StateFlow<Boolean> = modifierKeySettings.showShiftKey
    val showAltKey: StateFlow<Boolean> = modifierKeySettings.showAltKey
    val showMetaKey: StateFlow<Boolean> = modifierKeySettings.showMetaKey

    // Input text (for line-buffered mode)
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // Paste truncation warning
    private val _pasteTruncated = MutableStateFlow(false)
    val pasteTruncated: StateFlow<Boolean> = _pasteTruncated.asStateFlow()

    // Button editor visibility
    private val _showButtonEditor = MutableStateFlow(false)
    val showButtonEditor: StateFlow<Boolean> = _showButtonEditor.asStateFlow()

    // Terminal font size (persisted)
    private val _fontSize = MutableStateFlow(settingsRepository.getTerminalFontSize())
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    // One-time UI events
    private val _uiEvents = MutableSharedFlow<TerminalUiEvent>(extraBufferCapacity = 64)
    val uiEvents: SharedFlow<TerminalUiEvent> = _uiEvents.asSharedFlow()

    // Whether we've done the initial attach (deferred until dimensions are known)
    private var hasInitiallyAttached = false

    init {
        loadSessionName()
        observeTerminalState()
        observeTerminalEvents()
        observeTerminalOutput()
        observeConnectionForReattach()
        // Don't attach here - wait for onTerminalSizeChanged() to provide real dimensions
    }

    private fun observeConnectionForReattach() {
        repository.connectionState
            .onEach { state ->
                if (state == ConnectionLifecycleState.CONNECTED) {
                    val termState = terminalState.value
                    if (termState.sessionId != null && !termState.isAttached && !termState.isAttaching) {
                        Log.d(TAG, "Connection ready, re-attaching to ${termState.sessionId}")
                        viewModelScope.launch {
                            try {
                                repository.clearError()
                                repository.attach(sessionId, termState.lastSequence)
                                if (currentCols > 0 && currentRows > 0) {
                                    repository.resize(currentCols, currentRows)
                                }
                            } catch (e: Exception) {
                                _uiEvents.emit(TerminalUiEvent.ShowError(
                                    "Failed to reconnect: ${e.message}"))
                            }
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun loadSessionName() {
        // Look up session info to get display name
        val session = sessionRepository.sessions.value.find { it.id == sessionId }
        if (session != null) {
            _sessionName.value = session.displayText
        } else {
            // Fallback to session ID, but observe for updates
            _sessionName.value = sessionId
            sessionRepository.sessions
                .onEach { sessions ->
                    sessions.find { it.id == sessionId }?.let {
                        _sessionName.value = it.displayText
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    private fun observeTerminalOutput() {
        repository.output
            .onEach { bytes ->
                // Feed raw bytes to the terminal emulator
                // It handles ANSI escape sequences and updates the screen buffer
                terminalEmulator.append(bytes)
            }
            .launchIn(viewModelScope)
    }

    private fun observeTerminalState() {
        repository.state
            .onEach { state ->
                updateScreenState(state)
            }
            .launchIn(viewModelScope)
    }

    private fun updateScreenState(state: TerminalState) {
        _screenState.value = when {
            state.isAttaching -> TerminalScreenState.Attaching

            state.error != null -> TerminalScreenState.Error(
                code = state.error.code,
                message = state.error.message
            )

            state.isAttached -> TerminalScreenState.Connected(
                sessionId = state.sessionId ?: "",
                cols = state.cols,
                rows = state.rows,
                isRawMode = state.isRawMode
            )

            else -> TerminalScreenState.Disconnected(
                reason = "Not connected",
                canReconnect = true
            )
        }
    }

    private fun observeTerminalEvents() {
        repository.events
            .onEach { event -> handleTerminalEvent(event) }
            .launchIn(viewModelScope)
    }

    private fun handleTerminalEvent(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.Error -> {
                viewModelScope.launch {
                    _uiEvents.emit(TerminalUiEvent.ShowError(event.message))
                }
            }
            is TerminalEvent.OutputSkipped -> {
                viewModelScope.launch {
                    _uiEvents.emit(TerminalUiEvent.ShowOutputSkipped(event.bytesSkipped))
                }
            }
            is TerminalEvent.Detached -> {
                if (event.reason != "user_request") {
                    viewModelScope.launch {
                        _uiEvents.emit(TerminalUiEvent.ShowError("Disconnected: ${event.reason}"))
                    }
                }
            }
            is TerminalEvent.Snapshot -> {
                // Reset emulator so snapshot replaces any stale content.
                // Fires on both fresh attach and reconnect (buffer evicted).
                if (terminalState.value.isAttached) {
                    Log.d(TAG, "Snapshot received - resetting emulator")
                    terminalEmulator.reset()
                }
            }
            else -> { /* Handled via state */ }
        }
    }

    // ==========================================================================
    // Lifecycle
    // ==========================================================================

    private fun attach() {
        viewModelScope.launch {
            try {
                // Reset terminal emulator to clear any stale state
                terminalEmulator.reset()
                // Request buffered content from start so user sees current screen immediately
                repository.attach(sessionId, 0)
                // Send resize with real phone dimensions (already known from TerminalRenderer)
                if (currentCols > 0 && currentRows > 0) {
                    repository.resize(currentCols, currentRows)
                }
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to attach: ${e.message}"))
            }
        }
    }

    /**
     * Called when screen resumes - reattach if needed.
     */
    fun onResume() {
        val state = terminalState.value
        if (state.sessionId != null && !state.isAttached && !state.isAttaching) {
            viewModelScope.launch {
                try {
                    repository.attach(sessionId, state.lastSequence)
                } catch (e: Exception) {
                    _uiEvents.emit(TerminalUiEvent.ShowError("Failed to reconnect: ${e.message}"))
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        // Use GlobalScope for cleanup that should complete even after ViewModel is destroyed.
        // This is the standard Android pattern for cleanup that:
        // 1. Should not block the main thread (runBlocking would cause ANR)
        // 2. Should outlive the ViewModel's scope (viewModelScope is cancelled here)
        // The daemon will also timeout abandoned sessions, so this is best-effort.
        GlobalScope.launch(Dispatchers.IO) {
            try {
                repository.detach()
            } catch (e: Exception) {
                // Log but don't crash - cleanup should be best-effort
                android.util.Log.w("TerminalViewModel", "Error during detach in onCleared", e)
            }
        }
    }

    // ==========================================================================
    // Input Actions
    // ==========================================================================

    /**
     * Update the input text field.
     */
    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    /**
     * Send the current input text and clear the field.
     */
    fun onSendClicked() {
        val text = _inputText.value
        if (text.isEmpty()) return

        viewModelScope.launch {
            try {
                repository.sendLine(text)
                _inputText.value = ""
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Send just Enter key (newline) to the terminal.
     */
    fun onEnterPressed() {
        viewModelScope.launch {
            try {
                repository.sendInput("\r")
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send enter: ${e.message}"))
            }
        }
    }

    /**
     * Send input to the terminal.
     */
    fun sendInput(input: String) {
        if (input.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.sendInput(input)
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Handle quick button click.
     * Sends key with current modifier state, then clears sticky modifiers.
     * Intercepts app shortcuts (like ⌘+V for paste) before sending to terminal.
     */
    fun onQuickButtonClicked(button: QuickButton) {
        // Capture state and clear modifiers synchronously before any async work
        // This prevents race conditions when rapidly pressing modifier + key combos
        val state = _modifierState.value
        val modifiers = state.bitmask

        // Check for app shortcuts before async work (e.g., ⌘+V for paste)
        if (button.character != null && button.character.length == 1) {
            val appAction = state.getAppShortcut(button.character[0])
            if (appAction != null) {
                handleAppAction(appAction)
                clearStickyModifiers()
                return
            }
        }

        // Prepare character input synchronously if needed
        val modifiedChar = if (button.character != null) {
            applyModifiersToCharacter(button.character, state)
        } else {
            null
        }

        // Clear sticky modifiers immediately (before async send)
        clearStickyModifiers()

        // Only the network send is async
        viewModelScope.launch {
            try {
                when {
                    button.keyType != null -> repository.sendSpecialKey(button.keyType, modifiers)
                    modifiedChar != null -> repository.sendInput(modifiedChar)
                }
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Apply modifier keys to a character string.
     *
     * - Shift: uppercase the character
     * - Ctrl: convert to control character (e.g., Ctrl+y = 0x19)
     * - Alt: prefix with ESC
     * - Meta: prefix with ESC (same as Alt on most terminals)
     */
    private fun applyModifiersToCharacter(char: String, state: ModifierState): String {
        if (!state.hasActiveModifiers || char.isEmpty()) return char

        var result = char

        // Shift: uppercase single letters
        if (state.shift != ModifierMode.OFF && char.length == 1) {
            result = result.uppercase()
        }

        // Ctrl: convert single letter to control character
        if (state.ctrl != ModifierMode.OFF && char.length == 1) {
            val c = result[0]
            if (c in 'A'..'Z' || c in 'a'..'z') {
                // Control character = letter & 0x1F
                val controlChar = (c.uppercaseChar().code and 0x1F).toChar()
                result = controlChar.toString()
            }
        }

        // Alt/Meta: prefix with ESC
        if (state.alt != ModifierMode.OFF || state.meta != ModifierMode.OFF) {
            result = "\u001b$result"
        }

        return result
    }

    /**
     * Handle modifier button tap.
     * Toggles modifier: OFF -> STICKY, STICKY/LOCKED -> OFF
     */
    fun onModifierTap(modifier: ModifierKey) {
        _modifierState.update { state ->
            when (modifier) {
                ModifierKey.CTRL -> state.copy(ctrl = state.ctrl.toggle())
                ModifierKey.ALT -> state.copy(alt = state.alt.toggle())
                ModifierKey.SHIFT -> state.copy(shift = state.shift.toggle())
                ModifierKey.META -> state.copy(meta = state.meta.toggle())
            }
        }
    }

    /**
     * Handle modifier button long-press.
     * Locks the modifier until tapped again.
     */
    fun onModifierLongPress(modifier: ModifierKey) {
        _modifierState.update { state ->
            when (modifier) {
                ModifierKey.CTRL -> state.copy(ctrl = ModifierMode.LOCKED)
                ModifierKey.ALT -> state.copy(alt = ModifierMode.LOCKED)
                ModifierKey.SHIFT -> state.copy(shift = ModifierMode.LOCKED)
                ModifierKey.META -> state.copy(meta = ModifierMode.LOCKED)
            }
        }
    }

    /**
     * Clear sticky (non-locked) modifiers after key press.
     */
    private fun clearStickyModifiers() {
        _modifierState.update { state ->
            state.copy(
                ctrl = if (state.ctrl == ModifierMode.STICKY) ModifierMode.OFF else state.ctrl,
                alt = if (state.alt == ModifierMode.STICKY) ModifierMode.OFF else state.alt,
                shift = if (state.shift == ModifierMode.STICKY) ModifierMode.OFF else state.shift,
                meta = if (state.meta == ModifierMode.STICKY) ModifierMode.OFF else state.meta
            )
        }
    }

    /**
     * Handle app-level actions (shortcuts intercepted by the terminal emulator).
     */
    private fun handleAppAction(action: AppAction) {
        when (action) {
            AppAction.PASTE_HOST -> {
                // Send KEY_PASTE_HOST to daemon - it will paste from host clipboard
                viewModelScope.launch {
                    try {
                        repository.sendSpecialKey(KeyType.KEY_PASTE_HOST)
                    } catch (e: Exception) {
                        _uiEvents.emit(TerminalUiEvent.ShowError("Failed to paste: ${e.message}"))
                    }
                }
            }
            AppAction.COPY -> {
                // Future: implement when we have text selection
                Log.d(TAG, "Copy action - not implemented yet (no text selection)")
            }
        }
    }

    /**
     * Toggle raw mode.
     */
    fun onRawModeToggle() {
        repository.toggleRawMode()
    }

    // Current terminal dimensions
    private var currentCols = 80
    private var currentRows = 24

    /**
     * Resize the terminal to match phone screen dimensions.
     *
     * @param cols Number of columns
     * @param rows Number of rows
     */
    fun onTerminalSizeChanged(cols: Int, rows: Int) {
        if (cols == currentCols && rows == currentRows) return
        if (cols < 10 || rows < 5) return // Ignore invalid sizes

        currentCols = cols
        currentRows = rows

        // Resize local emulator
        terminalEmulator.resize(cols, rows)

        // First size report: trigger initial attach with correct dimensions
        if (!hasInitiallyAttached) {
            hasInitiallyAttached = true
            attach()
            return // attach() will send resize after attaching
        }

        // Send resize to daemon (only if attached)
        if (terminalState.value.isAttached) {
            viewModelScope.launch {
                try {
                    repository.resize(cols, rows)
                } catch (e: Exception) {
                    android.util.Log.w("TerminalViewModel", "Failed to resize: ${e.message}")
                }
            }
        }
    }

    /**
     * Update terminal font size and persist to settings.
     */
    fun onFontSizeChanged(size: Float) {
        _fontSize.value = size
        settingsRepository.setTerminalFontSize(size)
    }

    /**
     * Handle raw key press (in raw mode).
     *
     * @return true if the key was handled
     */
    fun onRawKeyPress(keyCode: Int, isCtrlPressed: Boolean): Boolean {
        if (!terminalState.value.isRawMode) return false

        val bytes = KeyMapper.keyEventToBytes(keyCode, isCtrlPressed)
        if (bytes != null) {
            viewModelScope.launch {
                try {
                    repository.sendInput(bytes)
                } catch (e: Exception) {
                    _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send key: ${e.message}"))
                }
            }
            return true
        }
        return false
    }

    /**
     * Handle raw character input (in raw mode).
     * Intercepts app shortcuts before sending to terminal.
     */
    fun onRawCharacterInput(char: Char) {
        if (!terminalState.value.isRawMode) return

        // Check for app shortcuts with current modifier state
        val state = _modifierState.value
        val appAction = state.getAppShortcut(char)
        if (appAction != null) {
            handleAppAction(appAction)
            clearStickyModifiers()
            return
        }

        // Apply modifiers and prepare input synchronously
        val input = if (state.hasActiveModifiers) {
            applyModifiersToCharacter(char.toString(), state)
        } else {
            char.toString()
        }

        // Clear modifiers before async send (consistent with app shortcut path)
        clearStickyModifiers()

        viewModelScope.launch {
            try {
                repository.sendInput(input)
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Handle paste button click.
     *
     * Extracts content from clipboard (text or image) and handles paste operation.
     * For text: sends directly to terminal as input.
     * For images: uses chunked clipboard protocol to transfer to daemon.
     * Shows error if clipboard is empty or unavailable.
     */
    fun onPasteClicked() {
        Log.d(TAG, "onPasteClicked called")
        viewModelScope.launch {
            // Check for image first (more specific)
            val image = clipboardService.extractImage()
            if (image != null) {
                Log.d(TAG, "Clipboard contains image: ${image.data.size} bytes, format=${image.format}")
                onPasteImage(image)
                return@launch
            }

            // Fall back to text
            val text = clipboardService.extractText()
            Log.d(TAG, "Clipboard text: ${text?.take(50) ?: "null"}")
            if (text == null) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Clipboard is empty"))
                return@launch
            }
            onPaste(text)
        }
    }

    /**
     * Handle image paste from clipboard.
     *
     * Sends image to daemon using chunked transfer protocol:
     * 1. Send ImageStart with metadata
     * 2. Send ImageChunk for each 64KB chunk
     * 3. Daemon reassembles and pastes to system clipboard
     */
    private suspend fun onPasteImage(image: ClipboardImage) {
        val transferId = UUID.randomUUID().toString()
        val chunkSize = ClipboardService.IMAGE_CHUNK_SIZE
        val totalChunks = (image.data.size + chunkSize - 1) / chunkSize

        Log.d(TAG, "Starting image transfer: id=$transferId, size=${image.data.size}, chunks=$totalChunks")

        try {
            // Send ImageStart
            val format = when (image.format) {
                ClipboardImage.ImageFormat.JPEG -> ImageFormat.IMAGE_FORMAT_JPEG
                ClipboardImage.ImageFormat.PNG -> ImageFormat.IMAGE_FORMAT_PNG
            }

            val startMessage = ClipboardMessage.newBuilder()
                .setImageStart(
                    ImageStart.newBuilder()
                        .setTransferId(transferId)
                        .setTotalSize(image.data.size.toLong())
                        .setFormat(format)
                        .setTotalChunks(totalChunks)
                        .build()
                )
                .build()

            repository.sendClipboardMessage(startMessage)
            Log.d(TAG, "Sent ImageStart")

            // Send chunks - WebRTC backpressure handles flow control
            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, image.data.size)
                val chunkData = image.data.copyOfRange(start, end)

                val chunkMessage = ClipboardMessage.newBuilder()
                    .setImageChunk(
                        ImageChunk.newBuilder()
                            .setTransferId(transferId)
                            .setIndex(i)
                            .setData(ByteString.copyFrom(chunkData))
                            .build()
                    )
                    .build()

                repository.sendClipboardMessage(chunkMessage)
                Log.d(TAG, "Sent chunk ${i + 1}/$totalChunks (${chunkData.size} bytes)")
            }

            Log.d(TAG, "Image transfer complete")
        } catch (e: Exception) {
            Log.e(TAG, "Image paste failed", e)
            _uiEvents.emit(TerminalUiEvent.ShowError("Failed to paste image: ${e.message}"))
        }
    }

    /**
     * Handle image selected from photo picker.
     *
     * Reads the image from the URI, converts to PNG if needed,
     * and sends via the chunked clipboard protocol.
     */
    fun onImageSelected(uri: Uri) {
        Log.d(TAG, "Image selected: $uri")
        viewModelScope.launch {
            try {
                val clipboardImage = clipboardService.readImageFromUri(uri)
                if (clipboardImage == null) {
                    _uiEvents.emit(TerminalUiEvent.ShowError("Failed to read image or image too large"))
                    return@launch
                }

                onPasteImage(clipboardImage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process selected image", e)
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to process image: ${e.message}"))
            }
        }
    }

    /**
     * Handle paste from clipboard.
     *
     * In raw mode, sends content directly to terminal.
     * In normal mode, appends content to input field.
     *
     * Content is validated and truncated to 64KB if necessary,
     * using UTF-8 safe truncation to avoid splitting multi-byte characters.
     *
     * @param text Text content from clipboard
     */
    fun onPaste(text: String) {
        Log.d(TAG, "onPaste called, text length: ${text.length}")
        if (text.isEmpty()) return

        viewModelScope.launch {
            // Check if truncation will occur
            val willTruncate = clipboardService.wouldTruncate(text)
            if (willTruncate) {
                _pasteTruncated.value = true
            }

            // Always send directly to terminal when paste button is pressed
            val bytes = clipboardService.prepareForTerminal(text) ?: return@launch
            Log.d(TAG, "Sending ${bytes.size} bytes to terminal")
            try {
                repository.sendInput(bytes)
                Log.d(TAG, "sendInput completed")
            } catch (e: Exception) {
                Log.e(TAG, "sendInput failed", e)
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to paste: ${e.message}"))
            }
        }
    }

    /**
     * Clear the paste truncation warning.
     */
    fun dismissPasteTruncated() {
        _pasteTruncated.value = false
    }

    /**
     * Send approval response (Y).
     */
    fun approve() {
        viewModelScope.launch {
            try {
                repository.sendInput("y")
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Send rejection response (N).
     */
    fun reject() {
        viewModelScope.launch {
            try {
                repository.sendInput("n")
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    /**
     * Send cancel (Ctrl+C).
     */
    fun cancel() {
        viewModelScope.launch {
            try {
                repository.sendSpecialKey(KeyType.KEY_CTRL_C)
            } catch (e: Exception) {
                _uiEvents.emit(TerminalUiEvent.ShowError("Failed to send: ${e.message}"))
            }
        }
    }

    // ==========================================================================
    // Error Handling
    // ==========================================================================

    fun clearError() {
        repository.clearError()
    }

    fun dismissOutputSkipped() {
        repository.clearOutputSkipped()
    }

    fun reconnect() {
        attach()
    }
}

/**
 * Convert a SettingsQuickButton to a QuickButton for use in the terminal.
 */
private fun SettingsQuickButton.toQuickButton(): QuickButton {
    val keyType = SettingsQuickButton.getKeyType(id)
    return if (keyType != null) {
        QuickButton(id = id, label = label, keyType = keyType)
    } else {
        QuickButton(id = id, label = label, character = keySequence)
    }
}
