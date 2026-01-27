package com.ras.sessions

import com.ras.data.sessions.SessionErrorCodes
import com.ras.data.sessions.SessionIdValidator
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionStateTest {

    @Test
    fun `SessionStatus fromProto converts correctly`() {
        assertEquals(SessionStatus.UNKNOWN, SessionStatus.fromProto(0))
        assertEquals(SessionStatus.ACTIVE, SessionStatus.fromProto(1))
        assertEquals(SessionStatus.CREATING, SessionStatus.fromProto(2))
        assertEquals(SessionStatus.KILLING, SessionStatus.fromProto(3))
        assertEquals(SessionStatus.UNKNOWN, SessionStatus.fromProto(999))
    }

    @Test
    fun `SessionStatus toProto converts correctly`() {
        assertEquals(0, SessionStatus.UNKNOWN.toProto())
        assertEquals(1, SessionStatus.ACTIVE.toProto())
        assertEquals(2, SessionStatus.CREATING.toProto())
        assertEquals(3, SessionStatus.KILLING.toProto())
    }

    @Test
    fun `SessionInfo displayText uses displayName when present`() {
        val session = createSession(displayName = "My Session")
        assertEquals("My Session", session.displayText)
    }

    @Test
    fun `SessionInfo displayText falls back to tmuxName when displayName empty`() {
        val session = createSession(displayName = "")
        assertEquals("ras-claude-project", session.displayText)
    }

    @Test
    fun `SessionInfo directoryBasename extracts last path component`() {
        val session = createSession(directory = "/home/user/repos/my-project")
        assertEquals("my-project", session.directoryBasename)
    }

    @Test
    fun `SessionInfo directoryBasename handles root path`() {
        val session = createSession(directory = "/")
        assertEquals("", session.directoryBasename)
    }

    @Test
    fun `SessionErrorCodes getDisplayMessage returns correct messages`() {
        assertEquals(
            "Directory does not exist",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.DIR_NOT_FOUND, "default")
        )
        assertEquals(
            "Directory is not accessible",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.DIR_NOT_ALLOWED, "default")
        )
        assertEquals(
            "Agent is not installed",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.AGENT_NOT_FOUND, "default")
        )
        assertEquals(
            "Session not found",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.SESSION_NOT_FOUND, "default")
        )
        assertEquals(
            "A session with this name already exists",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.SESSION_EXISTS, "default")
        )
        assertEquals(
            "Failed to create tmux session",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.TMUX_ERROR, "default")
        )
        assertEquals(
            "Failed to kill session",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.KILL_FAILED, "default")
        )
        assertEquals(
            "Maximum number of sessions reached",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.MAX_SESSIONS_REACHED, "default")
        )
        assertEquals(
            "Invalid session name",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.INVALID_NAME, "default")
        )
        assertEquals(
            "Too many requests, please wait",
            SessionErrorCodes.getDisplayMessage(SessionErrorCodes.RATE_LIMITED, "default")
        )
    }

    @Test
    fun `SessionErrorCodes getDisplayMessage returns default for unknown code`() {
        assertEquals(
            "Unknown error occurred",
            SessionErrorCodes.getDisplayMessage("UNKNOWN_CODE", "Unknown error occurred")
        )
    }

    // ==================== SessionIdValidator Tests ====================

    @Test
    fun `SessionIdValidator accepts valid 12 char alphanumeric IDs`() {
        assertTrue(SessionIdValidator.isValid("abc123def456"))
        assertTrue(SessionIdValidator.isValid("AbCdEf123456"))
        assertTrue(SessionIdValidator.isValid("ABCDEFGHIJKL"))
        assertTrue(SessionIdValidator.isValid("123456789012"))
        assertTrue(SessionIdValidator.isValid("aaaaaaaaaaaa"))
    }

    @Test
    fun `SessionIdValidator rejects null`() {
        assertFalse(SessionIdValidator.isValid(null))
    }

    @Test
    fun `SessionIdValidator rejects wrong length`() {
        assertFalse(SessionIdValidator.isValid(""))
        assertFalse(SessionIdValidator.isValid("abc123"))
        assertFalse(SessionIdValidator.isValid("abc123def4567"))
        assertFalse(SessionIdValidator.isValid("a"))
        assertFalse(SessionIdValidator.isValid("abc123def456789"))
    }

    @Test
    fun `SessionIdValidator rejects special characters`() {
        assertFalse(SessionIdValidator.isValid("abc-123-def4"))
        assertFalse(SessionIdValidator.isValid("abc_123_def4"))
        assertFalse(SessionIdValidator.isValid("abc.123.def4"))
        assertFalse(SessionIdValidator.isValid("abc 123 def4"))
        assertFalse(SessionIdValidator.isValid("abc!@#def456"))
    }

    @Test
    fun `SessionIdValidator rejects path traversal`() {
        assertFalse(SessionIdValidator.isValid("..abc123def4"))
        assertFalse(SessionIdValidator.isValid("abc..def4567"))
        assertFalse(SessionIdValidator.isValid("abcdef4567.."))
    }

    @Test
    fun `SessionIdValidator rejects slashes`() {
        assertFalse(SessionIdValidator.isValid("abc/def/ghij"))
        assertFalse(SessionIdValidator.isValid("abc\\def\\ghij"))
    }

    @Test
    fun `SessionIdValidator rejects null bytes`() {
        assertFalse(SessionIdValidator.isValid("abc\u0000def45678"))
    }

    @Test
    fun `SessionIdValidator requireValid returns valid ID`() {
        assertEquals("abc123def456", SessionIdValidator.requireValid("abc123def456"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SessionIdValidator requireValid throws for invalid ID`() {
        SessionIdValidator.requireValid("invalid")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SessionIdValidator requireValid throws for null`() {
        SessionIdValidator.requireValid(null)
    }

    private fun createSession(
        id: String = "abc123",
        tmuxName: String = "ras-claude-project",
        displayName: String = "claude-project",
        directory: String = "/home/user/repos/project",
        agent: String = "claude",
        status: SessionStatus = SessionStatus.ACTIVE
    ) = SessionInfo(
        id = id,
        tmuxName = tmuxName,
        displayName = displayName,
        directory = directory,
        agent = agent,
        createdAt = Instant.now(),
        lastActivityAt = Instant.now(),
        status = status
    )
}
