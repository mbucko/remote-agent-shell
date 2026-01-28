package com.ras.data.terminal

import android.util.Log
import com.ras.data.connection.ConnectionManager
import com.ras.di.IoDispatcher
import com.ras.proto.AttachTerminal
import com.ras.proto.DetachTerminal
import com.ras.proto.KeyType
import com.ras.proto.SpecialKey
import com.ras.proto.TerminalCommand
import com.ras.proto.TerminalInput
import com.ras.proto.TerminalEvent as ProtoTerminalEvent
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for terminal I/O management.
 *
 * Handles:
 * - Attaching/detaching to terminal sessions
 * - Sending terminal input (text, special keys)
 * - Receiving terminal output
 * - Managing terminal state
 */
@Singleton
class TerminalRepository @Inject constructor(
    private val connectionManager: ConnectionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "TerminalRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Terminal state
    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    // Terminal output stream (raw bytes for terminal emulator)
    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 128
    )
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    // Events for one-time notifications
    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TerminalEvent> = _events.asSharedFlow()

    // Connection state passthrough
    val isConnected: StateFlow<Boolean> = connectionManager.isConnected

    init {
        // Subscribe to terminal events from connection manager
        connectionManager.terminalEvents
            .onEach { event -> handleEvent(event) }
            .launchIn(scope)
    }

    // ==========================================================================
    // Event Handling
    // ==========================================================================

    private suspend fun handleEvent(event: ProtoTerminalEvent) {
        when {
            event.hasAttached() -> handleAttached(event.attached)
            event.hasDetached() -> handleDetached(event.detached)
            event.hasOutput() -> handleOutput(event.output)
            event.hasError() -> handleError(event.error)
            event.hasSkipped() -> handleSkipped(event.skipped)
        }
    }

    private suspend fun handleAttached(attached: com.ras.proto.TerminalAttached) {
        Log.d(TAG, "Attached to session: ${attached.sessionId}")
        _state.update { current ->
            current.copy(
                sessionId = attached.sessionId,
                isAttached = true,
                isAttaching = false,
                cols = attached.cols,
                rows = attached.rows,
                bufferStartSeq = attached.bufferStartSeq,
                lastSequence = attached.currentSeq,
                error = null
            )
        }
        _events.emit(
            TerminalEvent.Attached(
                sessionId = attached.sessionId,
                cols = attached.cols,
                rows = attached.rows,
                bufferStartSeq = attached.bufferStartSeq,
                currentSeq = attached.currentSeq
            )
        )
    }

    private suspend fun handleDetached(detached: com.ras.proto.TerminalDetached) {
        Log.d(TAG, "Detached from session: ${detached.sessionId}, reason: ${detached.reason}")
        _state.update { current ->
            current.copy(
                isAttached = false,
                isAttaching = false,
                error = if (detached.reason != "user_request") {
                    TerminalErrorInfo(
                        code = "DETACHED",
                        message = detached.reason,
                        sessionId = detached.sessionId
                    )
                } else null
            )
        }
        _events.emit(
            TerminalEvent.Detached(
                sessionId = detached.sessionId,
                reason = detached.reason
            )
        )
    }

    private suspend fun handleOutput(output: com.ras.proto.TerminalOutput) {
        val data = output.data.toByteArray()
        Log.v(TAG, "Output: ${data.size} bytes, seq=${output.sequence}")

        // Update sequence (thread-safe)
        _state.update { current -> current.copy(lastSequence = output.sequence) }

        // Emit raw output for terminal emulator
        _output.emit(data)

        // Emit event for any listeners
        _events.emit(
            TerminalEvent.Output(
                sessionId = output.sessionId,
                data = data,
                sequence = output.sequence,
                partial = output.partial
            )
        )
    }

    private suspend fun handleError(error: com.ras.proto.TerminalError) {
        Log.e(TAG, "Terminal error: ${error.errorCode} - ${error.message}")
        _state.update { current ->
            current.copy(
                isAttaching = false,
                error = TerminalErrorInfo(
                    code = error.errorCode,
                    message = error.message,
                    sessionId = error.sessionId.ifEmpty { null }
                )
            )
        }
        _events.emit(
            TerminalEvent.Error(
                sessionId = error.sessionId.ifEmpty { null },
                code = error.errorCode,
                message = error.message
            )
        )
    }

    private suspend fun handleSkipped(skipped: com.ras.proto.OutputSkipped) {
        Log.w(TAG, "Output skipped: seq ${skipped.fromSequence}-${skipped.toSequence}, ${skipped.bytesSkipped} bytes")
        _state.update { current ->
            current.copy(
                outputSkipped = OutputSkippedInfo(
                    fromSequence = skipped.fromSequence,
                    toSequence = skipped.toSequence,
                    bytesSkipped = skipped.bytesSkipped
                )
            )
        }
        _events.emit(
            TerminalEvent.OutputSkipped(
                sessionId = skipped.sessionId,
                fromSequence = skipped.fromSequence,
                toSequence = skipped.toSequence,
                bytesSkipped = skipped.bytesSkipped
            )
        )
    }

    // ==========================================================================
    // Commands (Phone -> Daemon)
    // ==========================================================================

    /**
     * Attach to a terminal session.
     *
     * @param sessionId The session ID to attach to
     * @param fromSequence Resume from this sequence (0 = from buffer start)
     * @throws IllegalArgumentException if sessionId is invalid
     */
    suspend fun attach(sessionId: String, fromSequence: Long = 0) {
        TerminalInputValidator.requireValidSessionId(sessionId)

        Log.d(TAG, "Attaching to session: $sessionId from seq $fromSequence")
        _state.update { current ->
            current.copy(
                sessionId = sessionId,
                isAttaching = true,
                error = null
            )
        }

        val command = TerminalCommand.newBuilder()
            .setAttach(
                AttachTerminal.newBuilder()
                    .setSessionId(sessionId)
                    .setFromSequence(fromSequence)
                    .build()
            )
            .build()

        connectionManager.sendTerminalCommand(command)
    }

    /**
     * Detach from the current terminal session.
     */
    suspend fun detach() {
        val sessionId = _state.value.sessionId
        if (sessionId == null) {
            Log.w(TAG, "Cannot detach: no session attached")
            return
        }

        Log.d(TAG, "Detaching from session: $sessionId")

        val command = TerminalCommand.newBuilder()
            .setDetach(
                DetachTerminal.newBuilder()
                    .setSessionId(sessionId)
                    .build()
            )
            .build()

        connectionManager.sendTerminalCommand(command)
    }

    /**
     * Send text input to the terminal.
     *
     * @param data The raw bytes to send
     * @throws IllegalArgumentException if data is too large
     * @throws IllegalStateException if not attached
     */
    suspend fun sendInput(data: ByteArray) {
        require(TerminalInputValidator.isValidInputSize(data)) {
            "Input too large: ${data.size} bytes exceeds maximum"
        }

        val sessionId = requireAttached()

        val command = TerminalCommand.newBuilder()
            .setInput(
                TerminalInput.newBuilder()
                    .setSessionId(sessionId)
                    .setData(ByteString.copyFrom(data))
                    .build()
            )
            .build()

        connectionManager.sendTerminalCommand(command)
    }

    /**
     * Send text input as a string.
     *
     * @param text The text to send (will be UTF-8 encoded)
     */
    suspend fun sendInput(text: String) {
        sendInput(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Send a special key to the terminal.
     *
     * @param keyType The key type to send
     * @param modifiers Modifier bitmask (MOD_CTRL, MOD_ALT, MOD_SHIFT)
     * @throws IllegalStateException if not attached
     */
    suspend fun sendSpecialKey(keyType: KeyType, modifiers: Int = 0) {
        val sessionId = requireAttached()

        val command = TerminalCommand.newBuilder()
            .setInput(
                TerminalInput.newBuilder()
                    .setSessionId(sessionId)
                    .setSpecial(
                        SpecialKey.newBuilder()
                            .setKey(keyType)
                            .setModifiers(modifiers)
                            .build()
                    )
                    .build()
            )
            .build()

        connectionManager.sendTerminalCommand(command)
    }

    /**
     * Send a line of text followed by Enter.
     *
     * @param line The text line to send
     */
    suspend fun sendLine(line: String) {
        sendInput("$line\r")
    }

    private fun requireAttached(): String {
        val state = _state.value
        check(state.isAttached) { "Not attached to terminal" }
        return state.sessionId ?: throw IllegalStateException("Session ID is null")
    }

    // ==========================================================================
    // State Management
    // ==========================================================================

    /**
     * Set raw mode on/off.
     * In raw mode, individual key presses are sent immediately.
     * In normal mode, input is line-buffered.
     */
    fun setRawMode(enabled: Boolean) {
        _state.update { current -> current.copy(isRawMode = enabled) }
    }

    /**
     * Toggle raw mode.
     */
    fun toggleRawMode() {
        _state.update { current -> current.copy(isRawMode = !current.isRawMode) }
    }

    /**
     * Clear the current error.
     */
    fun clearError() {
        _state.update { current -> current.copy(error = null) }
    }

    /**
     * Clear the output skipped notification.
     */
    fun clearOutputSkipped() {
        _state.update { current -> current.copy(outputSkipped = null) }
    }

    /**
     * Reset terminal state (e.g., when navigating away).
     */
    fun reset() {
        _state.update { TerminalState() }
    }
}
