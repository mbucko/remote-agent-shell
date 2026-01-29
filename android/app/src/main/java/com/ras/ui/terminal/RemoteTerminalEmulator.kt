package com.ras.ui.terminal

import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient

/**
 * A terminal emulator wrapper for remote terminal data.
 *
 * Uses Termux's TerminalEmulator for ANSI escape sequence processing,
 * but doesn't spawn a local subprocess. Instead, data is fed from
 * a remote source (WebRTC data channel).
 *
 * Architecture:
 * - Remote daemon sends output bytes via WebRTC
 * - This class processes escape codes using TerminalEmulator
 * - Compose UI observes screenVersion and renders the buffer
 */
class RemoteTerminalEmulator(
    columns: Int = 80,
    rows: Int = 24,
    private val onTitleChanged: ((String) -> Unit)? = null,
    private val onBell: (() -> Unit)? = null
) {
    // Mutable state that Compose observes to trigger recomposition
    var screenVersion by mutableStateOf(0L)
        private set

    private val terminalOutput = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            // This is called when the terminal emulator wants to send data
            // back to the "subprocess". For remote terminals, we'd send this
            // back via WebRTC. Currently unused as we handle input separately.
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            newTitle?.let { onTitleChanged?.invoke(it) }
        }

        override fun onCopyTextToClipboard(text: String) {
            // Handle clipboard copy if needed
        }

        override fun onPasteTextFromClipboard() {
            // Handle clipboard paste if needed
        }

        override fun onBell() {
            onBell?.invoke()
        }

        override fun onColorsChanged() {
            notifyScreenChanged()
        }
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {
            notifyScreenChanged()
        }

        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) {}
        override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) {}
        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) {}
        override fun onBell(session: com.termux.terminal.TerminalSession) {}
        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: com.termux.terminal.TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private var emulator: TerminalEmulator = TerminalEmulator(
        terminalOutput,
        columns,
        rows,
        12, // cell width pixels (approximate, used for scrollback)
        24, // cell height pixels
        null, // transcript rows (use default)
        sessionClient
    )

    /**
     * Get the underlying terminal emulator for rendering.
     */
    fun getEmulator(): TerminalEmulator = emulator

    /**
     * Get the current screen buffer.
     */
    fun getScreen() = emulator.screen

    /**
     * Process incoming terminal data (from remote daemon).
     * This handles ANSI escape sequences and updates the screen buffer.
     */
    fun append(data: ByteArray) {
        emulator.append(data, data.size)
        notifyScreenChanged()
    }

    /**
     * Process incoming terminal data as a string.
     */
    fun append(text: String) {
        append(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Resize the terminal.
     */
    fun resize(columns: Int, rows: Int, fontWidth: Int = 12, fontHeight: Int = 24) {
        if (columns > 0 && rows > 0) {
            emulator.resize(columns, rows, fontWidth, fontHeight)
            notifyScreenChanged()
        }
    }

    /**
     * Reset the terminal to initial state.
     */
    fun reset() {
        emulator.reset()
        notifyScreenChanged()
    }

    /**
     * Get the cursor column position (0-indexed).
     */
    fun getCursorCol(): Int = emulator.cursorCol

    /**
     * Get the cursor row position (0-indexed).
     */
    fun getCursorRow(): Int = emulator.cursorRow

    /**
     * Check if cursor should be visible.
     */
    fun isCursorVisible(): Boolean = emulator.isCursorEnabled

    /**
     * Get the number of columns.
     */
    fun getColumns(): Int = emulator.mColumns

    /**
     * Get the number of rows.
     */
    fun getRows(): Int = emulator.mRows

    private fun notifyScreenChanged() {
        screenVersion++
    }
}
