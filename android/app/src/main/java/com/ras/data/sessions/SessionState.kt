package com.ras.data.sessions

import java.time.Instant

/**
 * Domain model for a tmux session.
 */
data class SessionInfo(
    val id: String,
    val tmuxName: String,
    val displayName: String,
    val directory: String,
    val agent: String,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val status: SessionStatus
) {
    /**
     * Friendly display text for the session.
     */
    val displayText: String
        get() = displayName.ifEmpty { tmuxName }

    /**
     * Short directory name (basename).
     */
    val directoryBasename: String
        get() = directory.substringAfterLast('/')
}

/**
 * Session status enum.
 */
enum class SessionStatus {
    UNKNOWN,
    ACTIVE,
    CREATING,
    KILLING;

    companion object {
        fun fromProto(value: Int): SessionStatus = when (value) {
            1 -> ACTIVE
            2 -> CREATING
            3 -> KILLING
            else -> UNKNOWN
        }
    }

    fun toProto(): Int = when (this) {
        UNKNOWN -> 0
        ACTIVE -> 1
        CREATING -> 2
        KILLING -> 3
    }
}

/**
 * Domain model for an AI agent.
 */
data class AgentInfo(
    val name: String,
    val binary: String,
    val path: String,
    val available: Boolean
) {
    /**
     * Short name for display in compact views.
     */
    val shortName: String
        get() = binary.replaceFirstChar { it.uppercase() }
}

/**
 * Domain model for a directory entry.
 */
data class DirectoryEntryInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean
)

/**
 * State for session list screen.
 */
sealed class SessionsScreenState {
    data object Loading : SessionsScreenState()
    data class Loaded(
        val sessions: List<SessionInfo>,
        val isRefreshing: Boolean = false
    ) : SessionsScreenState()
    data class Error(val message: String) : SessionsScreenState()
}

/**
 * State for create session wizard.
 */
sealed class CreateSessionState {
    data object Idle : CreateSessionState()
    data object SelectingDirectory : CreateSessionState()
    data class DirectorySelected(val directory: String) : CreateSessionState()
    data object SelectingAgent : CreateSessionState()
    data class Creating(val directory: String, val agent: String) : CreateSessionState()
    data class Created(val session: SessionInfo) : CreateSessionState()
    data class Failed(val errorCode: String, val message: String) : CreateSessionState()
}

/**
 * State for directory browser.
 */
sealed class DirectoryBrowserState {
    data object Loading : DirectoryBrowserState()
    data class Loaded(
        val parent: String,
        val entries: List<DirectoryEntryInfo>,
        val recentDirectories: List<String> = emptyList()
    ) : DirectoryBrowserState()
    data class Error(val message: String) : DirectoryBrowserState()
}

/**
 * State for agents list.
 */
sealed class AgentsListState {
    data object Loading : AgentsListState()
    data class Loaded(val agents: List<AgentInfo>) : AgentsListState()
    data class Error(val message: String) : AgentsListState()
}

/**
 * Session error codes from the daemon.
 */
object SessionErrorCodes {
    const val DIR_NOT_FOUND = "DIR_NOT_FOUND"
    const val DIR_NOT_ALLOWED = "DIR_NOT_ALLOWED"
    const val AGENT_NOT_FOUND = "AGENT_NOT_FOUND"
    const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
    const val SESSION_EXISTS = "SESSION_EXISTS"
    const val TMUX_ERROR = "TMUX_ERROR"
    const val KILL_FAILED = "KILL_FAILED"
    const val MAX_SESSIONS_REACHED = "MAX_SESSIONS_REACHED"
    const val INVALID_NAME = "INVALID_NAME"
    const val RATE_LIMITED = "RATE_LIMITED"

    /**
     * Get user-friendly message for error code.
     */
    fun getDisplayMessage(code: String, defaultMessage: String): String = when (code) {
        DIR_NOT_FOUND -> "Directory does not exist"
        DIR_NOT_ALLOWED -> "Directory is not accessible"
        AGENT_NOT_FOUND -> "Agent is not installed"
        SESSION_NOT_FOUND -> "Session not found"
        SESSION_EXISTS -> "A session with this name already exists"
        TMUX_ERROR -> "Failed to create tmux session"
        KILL_FAILED -> "Failed to kill session"
        MAX_SESSIONS_REACHED -> "Maximum number of sessions reached"
        INVALID_NAME -> "Invalid session name"
        RATE_LIMITED -> "Too many requests, please wait"
        else -> defaultMessage
    }
}

/**
 * Events emitted from SessionRepository.
 */
sealed class SessionEvent {
    data class SessionCreated(val session: SessionInfo) : SessionEvent()
    data class SessionKilled(val sessionId: String) : SessionEvent()
    data class SessionRenamed(val sessionId: String, val newName: String) : SessionEvent()
    data class SessionActivity(val sessionId: String, val timestamp: Instant) : SessionEvent()
    data class SessionError(val code: String, val message: String, val sessionId: String?) : SessionEvent()
}

/**
 * Actions that can be performed on a session.
 */
enum class SessionAction {
    OPEN,
    KILL,
    RENAME
}

/**
 * Session ID validation utilities.
 *
 * Session IDs are:
 * - Exactly 12 alphanumeric characters (a-zA-Z0-9)
 * - Must not contain path traversal sequences (..), slashes, or null bytes
 */
object SessionIdValidator {
    private val SESSION_ID_PATTERN = Regex("^[a-zA-Z0-9]{12}$")

    /**
     * Validate a session ID format.
     *
     * @param sessionId The session ID to validate
     * @return true if valid, false otherwise
     */
    fun isValid(sessionId: String?): Boolean {
        if (sessionId == null) return false
        if (sessionId.length != 12) return false

        // Reject dangerous sequences
        if (sessionId.contains("..")) return false
        if (sessionId.contains("/")) return false
        if (sessionId.contains("\\")) return false
        if (sessionId.contains("\u0000")) return false

        return SESSION_ID_PATTERN.matches(sessionId)
    }

    /**
     * Validate and return the session ID, or throw if invalid.
     *
     * @param sessionId The session ID to validate
     * @return The validated session ID
     * @throws IllegalArgumentException if the session ID is invalid
     */
    fun requireValid(sessionId: String?): String {
        require(isValid(sessionId)) {
            "Invalid session ID: must be exactly 12 alphanumeric characters"
        }
        return sessionId!!
    }
}
