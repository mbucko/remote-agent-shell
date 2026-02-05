package com.ras.data.terminal

import android.util.Log
import com.ras.data.connection.ConnectionManager
import com.ras.di.AttachTimeoutMs
import com.ras.di.IoDispatcher
import com.ras.notifications.NotificationHandler
import com.ras.proto.AttachTerminal
import com.ras.proto.DetachTerminal
import com.ras.proto.KeyType
import com.ras.proto.SpecialKey
import com.ras.proto.TerminalAttached
import com.ras.proto.TerminalCommand
import com.ras.proto.TerminalInput
import com.ras.proto.TerminalNotification
import com.ras.proto.TerminalResize
import com.ras.proto.TerminalEvent as ProtoTerminalEvent
import com.ras.proto.clipboard.ClipboardMessage
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
    private val notificationHandler: NotificationHandler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @AttachTimeoutMs private val attachTimeoutMs: Long
) {
    companion object {
        private const val TAG = "TerminalRepository"
        const val DEFAULT_ATTACH_TIMEOUT_MS = 10_000L // 10 seconds
    }

    // Exception handler to prevent silent failures in coroutines
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG, "Uncaught exception in TerminalRepository scope", exception)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)

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

        // Invalidate attachment state when connection drops
        connectionManager.isConnected
            .onEach { connected -> if (!connected) onConnectionLost() }
            .launchIn(scope)
    }

    private fun onConnectionLost() {
        val current = _state.value
        if (!current.isAttached && !current.isAttaching) return

        Log.w(TAG, "Connection lost: clearing attachment state for session ${current.sessionId}")

        // Cancel any pending attach (don't let it hang for timeout duration)
        pendingAttach?.cancel()
        pendingAttach = null

        // Clear attachment but keep sessionId + lastSequence for re-attach
        _state.update {
            it.copy(isAttached = false, isAttaching = false)
        }
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
            event.hasNotification() -> handleNotification(event.notification)
        }
    }

    private suspend fun handleAttached(attached: TerminalAttached) {
        Log.d(TAG, "Attached to session: ${attached.sessionId}")

        // Update state
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

        // Complete pending request-response correlation
        // This unblocks the attach() caller who is awaiting the response
        pendingAttach?.complete(attached)

        // Emit event for other listeners
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

        // Update sequence using maxOf to prevent regression from out-of-order packets
        _state.update { current ->
            current.copy(lastSequence = maxOf(current.lastSequence, output.sequence))
        }

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

        val wasAttaching = _state.value.isAttaching

        // Update state
        _state.update { current ->
            current.copy(
                isAttaching = false,
                // NOT_ATTACHED means daemon lost our attachment - clear stale flag
                isAttached = if (error.errorCode == TerminalErrorCodes.NOT_ATTACHED)
                    false else current.isAttached,
                error = TerminalErrorInfo(
                    code = error.errorCode,
                    message = error.message,
                    sessionId = error.sessionId.ifEmpty { null }
                )
            )
        }

        // If we were attaching, complete the pending request-response with error
        // This unblocks the attach() caller with an exception
        if (wasAttaching) {
            pendingAttach?.completeExceptionally(
                TerminalAttachException(error.errorCode, error.message)
            )
        }

        // Emit event for other listeners
        _events.emit(
            TerminalEvent.Error(
                sessionId = error.sessionId.ifEmpty { null },
                code = error.errorCode,
                message = error.message
            )
        )
    }

    private suspend fun handleSkipped(skipped: com.ras.proto.OutputSkipped) {
        // Only show output skipped if there was actually data skipped
        if (skipped.bytesSkipped <= 0) {
            Log.d(TAG, "Ignoring output skipped with 0 bytes")
            return
        }
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

    private suspend fun handleNotification(notification: TerminalNotification) {
        Log.d(TAG, "Notification received: session=${notification.sessionId}, type=${notification.type}")

        // Show system notification
        notificationHandler.showNotification(notification)

        // Emit event for any listeners (e.g., in-app handling)
        _events.emit(
            TerminalEvent.Notification(
                sessionId = notification.sessionId,
                type = notification.type,
                title = notification.title,
                body = notification.body,
                snippet = notification.snippet,
                timestamp = notification.timestamp
            )
        )
    }

    // ==========================================================================
    // Commands (Phone -> Daemon)
    // ==========================================================================

    // Mutex for preventing concurrent attach() calls (coroutine-safe)
    private val attachMutex = Mutex()

    // Pending attach operation - completed when daemon responds with Attached or Error
    // This is the industry-standard request-response correlation pattern
    @Volatile
    private var pendingAttach: CompletableDeferred<TerminalAttached>? = null

    /**
     * Attach to a terminal session.
     *
     * This is a suspending request-response operation that:
     * 1. Sends the attach command to the daemon
     * 2. Waits for the daemon's response (TerminalAttached or TerminalError)
     * 3. Returns when attached or throws on error/timeout
     *
     * Uses CompletableDeferred for proper request-response correlation,
     * avoiding race conditions between sending command and receiving response.
     *
     * @param sessionId The session ID to attach to
     * @param fromSequence Resume from this sequence (0 = from buffer start)
     * @throws IllegalArgumentException if sessionId is invalid
     * @throws IllegalStateException if already attaching or attached
     * @throws TimeoutCancellationException if daemon doesn't respond in time
     * @throws TerminalAttachException if daemon returns an error
     */
    suspend fun attach(sessionId: String, fromSequence: Long = 0) {
        TerminalInputValidator.requireValidSessionId(sessionId)

        // Use mutex to prevent concurrent attach operations
        attachMutex.withLock {
            val current = _state.value

            // Already attached or attaching to the same session - no-op
            if ((current.isAttached || current.isAttaching) && current.sessionId == sessionId) {
                val status = if (current.isAttached) "attached" else "attaching"
                Log.d(TAG, "Already $status to session: $sessionId")
                return
            }

            // Already attached to different session - detach first
            if (current.isAttached && current.sessionId != null) {
                Log.d(TAG, "Switching sessions: detaching from ${current.sessionId} before attaching to $sessionId")
                // Send detach command for previous session
                val detachCommand = TerminalCommand.newBuilder()
                    .setDetach(
                        DetachTerminal.newBuilder()
                            .setSessionId(current.sessionId)
                            .build()
                    )
                    .build()
                try {
                    connectionManager.sendTerminalCommand(detachCommand)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send detach for session switch: ${e.message}")
                    // Continue with attach anyway - daemon will handle it
                }
                // Reset state for new attach
                _state.value = TerminalState()
            }

            // Still attaching to different session - wait or error
            if (current.isAttaching) {
                Log.w(TAG, "Already attaching to session: ${current.sessionId}")
                throw IllegalStateException("Already attaching to session: ${current.sessionId}")
            }

            // Mark as attaching
            _state.value = current.copy(
                sessionId = sessionId,
                isAttaching = true,
                error = null
            )

            Log.d(TAG, "Attaching to session: $sessionId from seq $fromSequence")

            // Create deferred for request-response correlation
            val deferred = CompletableDeferred<TerminalAttached>()
            pendingAttach = deferred

            // Send the command
            val command = TerminalCommand.newBuilder()
                .setAttach(
                    AttachTerminal.newBuilder()
                        .setSessionId(sessionId)
                        .setFromSequence(fromSequence)
                        .build()
                )
                .build()

            try {
                connectionManager.sendTerminalCommand(command)

                // Await response with timeout
                // withTimeout is the proper coroutine-based timeout mechanism
                withTimeout(attachTimeoutMs) {
                    deferred.await()
                }
                Log.d(TAG, "Attach completed successfully for session: $sessionId")
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Attach timeout for session: $sessionId")
                pendingAttach = null
                _state.update { it.copy(
                    isAttaching = false,
                    error = TerminalErrorInfo(
                        code = "ATTACH_TIMEOUT",
                        message = "Connection to terminal timed out",
                        sessionId = sessionId
                    )
                )}
                _events.emit(
                    TerminalEvent.Error(
                        sessionId = sessionId,
                        code = "ATTACH_TIMEOUT",
                        message = "Connection to terminal timed out"
                    )
                )
                throw e
            } catch (e: CancellationException) {
                // Connection dropped while attaching - onConnectionLost handles state
                Log.d(TAG, "Attach cancelled for session: $sessionId (likely connection lost)")
                pendingAttach = null
                _state.update { it.copy(isAttaching = false) }
                throw e  // Re-throw per coroutine convention
            } catch (e: TerminalAttachException) {
                Log.e(TAG, "Attach failed for session: $sessionId - ${e.message}")
                pendingAttach = null
                // State already updated in handleError
                throw e
            } catch (e: Exception) {
                // Handle unexpected errors (e.g., connection lost, no encryption codec)
                Log.e(TAG, "Attach failed unexpectedly for session: $sessionId - ${e.message}")
                pendingAttach = null
                _state.update { it.copy(
                    isAttaching = false,
                    error = TerminalErrorInfo(
                        code = "ATTACH_FAILED",
                        message = e.message ?: "Failed to connect to terminal",
                        sessionId = sessionId
                    )
                )}
                _events.emit(
                    TerminalEvent.Error(
                        sessionId = sessionId,
                        code = "ATTACH_FAILED",
                        message = e.message ?: "Failed to connect to terminal"
                    )
                )
                throw e
            } finally {
                pendingAttach = null
            }
        }
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

    /**
     * Resize the terminal.
     *
     * @param cols Number of columns
     * @param rows Number of rows
     * @throws IllegalStateException if not attached
     */
    suspend fun resize(cols: Int, rows: Int) {
        val sessionId = requireAttached()

        val command = TerminalCommand.newBuilder()
            .setInput(
                TerminalInput.newBuilder()
                    .setSessionId(sessionId)
                    .setResize(
                        TerminalResize.newBuilder()
                            .setCols(cols)
                            .setRows(rows)
                            .build()
                    )
                    .build()
            )
            .build()

        connectionManager.sendTerminalCommand(command)
        Log.d(TAG, "Sent resize request: ${cols}x${rows}")
    }

    /**
     * Send a clipboard message to the daemon.
     *
     * Used for image paste which requires the chunked transfer protocol.
     */
    suspend fun sendClipboardMessage(message: ClipboardMessage) {
        connectionManager.sendClipboardMessage(message)
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

    /**
     * Close the repository and cancel all background work.
     * Call this in tests to properly clean up.
     */
    fun close() {
        scope.cancel()
    }
}
