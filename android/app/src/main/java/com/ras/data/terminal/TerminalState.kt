package com.ras.data.terminal

import com.ras.data.sessions.SessionIdValidator
import com.ras.proto.KeyType
import com.ras.proto.NotificationType

/**
 * Terminal attachment state.
 */
data class TerminalState(
    val sessionId: String? = null,
    val isAttached: Boolean = false,
    val isAttaching: Boolean = false,
    val isRawMode: Boolean = false,
    val cols: Int = 80,
    val rows: Int = 24,
    val bufferStartSeq: Long = 0,
    val lastSequence: Long = 0,
    val error: TerminalErrorInfo? = null,
    val outputSkipped: OutputSkippedInfo? = null
) {
    /**
     * True if we have a valid terminal session we can interact with.
     */
    val canSendInput: Boolean
        get() = isAttached && sessionId != null && error == null
}

/**
 * Information about skipped output during reconnection.
 */
data class OutputSkippedInfo(
    val fromSequence: Long,
    val toSequence: Long,
    val bytesSkipped: Int
) {
    /**
     * Human-readable description of skipped data.
     */
    val displayText: String
        get() = when {
            bytesSkipped >= 1024 * 1024 -> "~${bytesSkipped / (1024 * 1024)}MB output skipped"
            bytesSkipped >= 1024 -> "~${bytesSkipped / 1024}KB output skipped"
            else -> "~$bytesSkipped bytes output skipped"
        }
}

/**
 * Terminal error information.
 */
data class TerminalErrorInfo(
    val code: String,
    val message: String,
    val sessionId: String?
)

/**
 * Terminal error codes from the daemon.
 */
object TerminalErrorCodes {
    const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
    const val SESSION_KILLING = "SESSION_KILLING"
    const val NOT_ATTACHED = "NOT_ATTACHED"
    const val ALREADY_ATTACHED = "ALREADY_ATTACHED"
    const val INVALID_SEQUENCE = "INVALID_SEQUENCE"
    const val PIPE_ERROR = "PIPE_ERROR"
    const val PIPE_SETUP_FAILED = "PIPE_SETUP_FAILED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INPUT_TOO_LARGE = "INPUT_TOO_LARGE"
    const val INVALID_SESSION_ID = "INVALID_SESSION_ID"

    /**
     * Get user-friendly message for error code.
     */
    fun getDisplayMessage(code: String, defaultMessage: String): String = when (code) {
        SESSION_NOT_FOUND -> "Session not found"
        SESSION_KILLING -> "Session is being terminated"
        NOT_ATTACHED -> "Not attached to terminal"
        ALREADY_ATTACHED -> "Already attached to this session"
        INVALID_SEQUENCE -> "Reconnection sequence not available"
        PIPE_ERROR -> "Terminal communication error"
        PIPE_SETUP_FAILED -> "Failed to setup terminal"
        RATE_LIMITED -> "Too many inputs, please slow down"
        INPUT_TOO_LARGE -> "Input too large"
        INVALID_SESSION_ID -> "Invalid session ID"
        else -> defaultMessage
    }
}

/**
 * Quick action button configuration.
 */
data class QuickButton(
    val id: String,
    val label: String,
    val keyType: KeyType? = null,
    val character: String? = null,
    val isDefault: Boolean = false
) {
    init {
        require(keyType != null || character != null) {
            "QuickButton must have either keyType or character"
        }
        require(id.isNotEmpty()) { "QuickButton id cannot be empty" }
        require(label.isNotEmpty()) { "QuickButton label cannot be empty" }
    }
}

/**
 * Default quick buttons shown on first use.
 */
val DEFAULT_QUICK_BUTTONS = listOf(
    QuickButton(id = "y", label = "Y", character = "y", isDefault = true),
    QuickButton(id = "n", label = "N", character = "n", isDefault = true),
    QuickButton(id = "ctrl_c", label = "Ctrl+C", keyType = KeyType.KEY_CTRL_C, isDefault = true)
)

/**
 * All available quick buttons for customization.
 */
val AVAILABLE_QUICK_BUTTONS = listOf(
    // Default buttons
    QuickButton(id = "y", label = "Y", character = "y"),
    QuickButton(id = "n", label = "N", character = "n"),
    QuickButton(id = "ctrl_c", label = "Ctrl+C", keyType = KeyType.KEY_CTRL_C),
    // Navigation
    QuickButton(id = "tab", label = "Tab", keyType = KeyType.KEY_TAB),
    QuickButton(id = "esc", label = "Esc", keyType = KeyType.KEY_ESCAPE),
    QuickButton(id = "enter", label = "Enter", keyType = KeyType.KEY_ENTER),
    // Arrow keys
    QuickButton(id = "up", label = "↑", keyType = KeyType.KEY_UP),
    QuickButton(id = "down", label = "↓", keyType = KeyType.KEY_DOWN),
    QuickButton(id = "left", label = "←", keyType = KeyType.KEY_LEFT),
    QuickButton(id = "right", label = "→", keyType = KeyType.KEY_RIGHT),
    // Control characters
    QuickButton(id = "ctrl_d", label = "Ctrl+D", keyType = KeyType.KEY_CTRL_D),
    QuickButton(id = "ctrl_z", label = "Ctrl+Z", keyType = KeyType.KEY_CTRL_Z),
    // Page navigation
    QuickButton(id = "home", label = "Home", keyType = KeyType.KEY_HOME),
    QuickButton(id = "end", label = "End", keyType = KeyType.KEY_END),
    QuickButton(id = "pgup", label = "PgUp", keyType = KeyType.KEY_PAGE_UP),
    QuickButton(id = "pgdn", label = "PgDn", keyType = KeyType.KEY_PAGE_DOWN)
)

/**
 * Events emitted from TerminalRepository.
 */
sealed class TerminalEvent {
    /**
     * Successfully attached to a terminal session.
     */
    data class Attached(
        val sessionId: String,
        val cols: Int,
        val rows: Int,
        val bufferStartSeq: Long,
        val currentSeq: Long
    ) : TerminalEvent()

    /**
     * Detached from terminal session.
     */
    data class Detached(
        val sessionId: String,
        val reason: String
    ) : TerminalEvent()

    /**
     * Terminal output received.
     */
    data class Output(
        val sessionId: String,
        val data: ByteArray,
        val sequence: Long,
        val partial: Boolean
    ) : TerminalEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Output) return false
            return sessionId == other.sessionId &&
                data.contentEquals(other.data) &&
                sequence == other.sequence &&
                partial == other.partial
        }

        override fun hashCode(): Int {
            var result = sessionId.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + sequence.hashCode()
            result = 31 * result + partial.hashCode()
            return result
        }
    }

    /**
     * Output was skipped due to buffer overflow.
     */
    data class OutputSkipped(
        val sessionId: String,
        val fromSequence: Long,
        val toSequence: Long,
        val bytesSkipped: Int
    ) : TerminalEvent()

    /**
     * Terminal error occurred.
     */
    data class Error(
        val sessionId: String?,
        val code: String,
        val message: String
    ) : TerminalEvent()

    /**
     * Notification received from daemon.
     */
    data class Notification(
        val sessionId: String,
        val type: NotificationType,
        val title: String,
        val body: String,
        val snippet: String,
        val timestamp: Long
    ) : TerminalEvent()
}

/**
 * Screen state for terminal UI.
 */
sealed class TerminalScreenState {
    /**
     * Loading state while attaching.
     */
    data object Attaching : TerminalScreenState()

    /**
     * Connected and ready to send/receive.
     */
    data class Connected(
        val sessionId: String,
        val cols: Int,
        val rows: Int,
        val isRawMode: Boolean
    ) : TerminalScreenState()

    /**
     * Disconnected from terminal.
     */
    data class Disconnected(
        val reason: String,
        val canReconnect: Boolean
    ) : TerminalScreenState()

    /**
     * Error state.
     */
    data class Error(
        val code: String,
        val message: String
    ) : TerminalScreenState()
}

/**
 * UI events for one-time actions (snackbars, navigation).
 */
sealed class TerminalUiEvent {
    data class ShowError(val message: String) : TerminalUiEvent()
    data class ShowOutputSkipped(val bytesSkipped: Int) : TerminalUiEvent()
    data object NavigateBack : TerminalUiEvent()
}

/**
 * Input validation for terminal commands.
 * Reuses SessionIdValidator from sessions package.
 */
object TerminalInputValidator {
    private const val MAX_INPUT_SIZE = 65536 // 64KB

    /**
     * Validate input data size.
     */
    fun isValidInputSize(data: ByteArray): Boolean = data.size <= MAX_INPUT_SIZE

    /**
     * Validate session ID using the shared validator.
     */
    fun isValidSessionId(sessionId: String?): Boolean = SessionIdValidator.isValid(sessionId)

    /**
     * Require valid session ID or throw.
     */
    fun requireValidSessionId(sessionId: String?): String = SessionIdValidator.requireValid(sessionId)
}
