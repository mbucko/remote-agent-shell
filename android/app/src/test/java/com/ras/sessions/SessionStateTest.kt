package com.ras.sessions

import com.ras.data.sessions.AgentInfo
import com.ras.data.sessions.AgentNameValidator
import com.ras.data.sessions.DirectoryPathValidator
import com.ras.data.sessions.DisplayNameValidator
import com.ras.data.sessions.SessionErrorCodes
import com.ras.data.sessions.SessionIdValidator
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

class SessionStateTest {

    @Tag("unit")
    @Test
    fun `SessionStatus fromProto converts correctly`() {
        assertEquals(SessionStatus.UNKNOWN, SessionStatus.fromProto(0))
        assertEquals(SessionStatus.ACTIVE, SessionStatus.fromProto(1))
        assertEquals(SessionStatus.CREATING, SessionStatus.fromProto(2))
        assertEquals(SessionStatus.KILLING, SessionStatus.fromProto(3))
        assertEquals(SessionStatus.UNKNOWN, SessionStatus.fromProto(999))
    }

    @Tag("unit")
    @Test
    fun `SessionStatus toProto converts correctly`() {
        assertEquals(0, SessionStatus.UNKNOWN.toProto())
        assertEquals(1, SessionStatus.ACTIVE.toProto())
        assertEquals(2, SessionStatus.CREATING.toProto())
        assertEquals(3, SessionStatus.KILLING.toProto())
    }

    // ==================== AgentInfo Tests ====================

    @Tag("unit")
    @Test
    fun `AgentInfo shortName capitalizes first char`() {
        val agent = AgentInfo("Claude Code", "claude", "/usr/bin/claude", true)
        assertEquals("Claude", agent.shortName)
    }

    @Tag("unit")
    @Test
    fun `AgentInfo shortName handles empty binary`() {
        val agent = AgentInfo("Unknown", "", "/path", false)
        assertEquals("", agent.shortName)
    }

    @Tag("unit")
    @Test
    fun `AgentInfo shortName handles single char binary`() {
        val agent = AgentInfo("A Agent", "a", "/path", true)
        assertEquals("A", agent.shortName)
    }

    @Tag("unit")
    @Test
    fun `AgentInfo shortName handles already uppercase`() {
        val agent = AgentInfo("AGENT", "AGENT", "/path", true)
        assertEquals("AGENT", agent.shortName)
    }

    @Tag("unit")
    @Test
    fun `AgentInfo shortName handles numeric first char`() {
        val agent = AgentInfo("123 Agent", "123agent", "/path", true)
        assertEquals("123agent", agent.shortName)
    }

    @Tag("unit")
    @Test
    fun `SessionInfo displayText uses displayName when present`() {
        val session = createSession(displayName = "My Session")
        assertEquals("My Session", session.displayText)
    }

    @Tag("unit")
    @Test
    fun `SessionInfo displayText falls back to tmuxName when displayName empty`() {
        val session = createSession(displayName = "")
        assertEquals("ras-claude-project", session.displayText)
    }

    @Tag("unit")
    @Test
    fun `SessionInfo directoryBasename extracts last path component`() {
        val session = createSession(directory = "/home/user/repos/my-project")
        assertEquals("my-project", session.directoryBasename)
    }

    @Tag("unit")
    @Test
    fun `SessionInfo directoryBasename handles root path`() {
        val session = createSession(directory = "/")
        assertEquals("", session.directoryBasename)
    }

    @Tag("unit")
    @Test
    fun `SessionInfo directoryBasename handles trailing slash`() {
        val session = createSession(directory = "/home/user/project/")
        assertEquals("", session.directoryBasename)
    }

    @Tag("unit")
    @Test
    fun `SessionInfo directoryBasename handles single level path`() {
        val session = createSession(directory = "/home")
        assertEquals("home", session.directoryBasename)
    }

    @Tag("unit")
    @Test
    fun `SessionInfo directoryBasename handles empty path`() {
        val session = createSession(directory = "")
        assertEquals("", session.directoryBasename)
    }

    @Tag("unit")
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

    @Tag("unit")
    @Test
    fun `SessionErrorCodes getDisplayMessage returns default for unknown code`() {
        assertEquals(
            "Unknown error occurred",
            SessionErrorCodes.getDisplayMessage("UNKNOWN_CODE", "Unknown error occurred")
        )
    }

    // ==================== SessionIdValidator Tests ====================

    @Tag("unit")
    @Test
    fun `SessionIdValidator accepts valid 12 char alphanumeric IDs`() {
        assertTrue(SessionIdValidator.isValid("abc123def456"))
        assertTrue(SessionIdValidator.isValid("AbCdEf123456"))
        assertTrue(SessionIdValidator.isValid("ABCDEFGHIJKL"))
        assertTrue(SessionIdValidator.isValid("123456789012"))
        assertTrue(SessionIdValidator.isValid("aaaaaaaaaaaa"))
    }

    @Tag("unit")
    @Test
    fun `SessionIdValidator rejects null`() {
        assertFalse(SessionIdValidator.isValid(null))
    }

    @Tag("unit")
    @Test
    fun `SessionIdValidator rejects wrong length`() {
        assertFalse(SessionIdValidator.isValid(""))
        assertFalse(SessionIdValidator.isValid("abc123"))
        assertFalse(SessionIdValidator.isValid("abc123def4567"))
        assertFalse(SessionIdValidator.isValid("a"))
        assertFalse(SessionIdValidator.isValid("abc123def456789"))
    }

    @Tag("unit")
    @Test
    fun `SessionIdValidator rejects special characters`() {
        assertFalse(SessionIdValidator.isValid("abc-123-def4"))
        assertFalse(SessionIdValidator.isValid("abc_123_def4"))
        assertFalse(SessionIdValidator.isValid("abc.123.def4"))
        assertFalse(SessionIdValidator.isValid("abc 123 def4"))
        assertFalse(SessionIdValidator.isValid("abc!@#def456"))
    }

    @Tag("unit")
    @Test
    fun `SessionIdValidator rejects path traversal`() {
        assertFalse(SessionIdValidator.isValid("..abc123def4"))
        assertFalse(SessionIdValidator.isValid("abc..def4567"))
        assertFalse(SessionIdValidator.isValid("abcdef4567.."))
    }

    @Tag("unit")
    @Test
    fun `SessionIdValidator rejects slashes`() {
        assertFalse(SessionIdValidator.isValid("abc/def/ghij"))
        assertFalse(SessionIdValidator.isValid("abc\\def\\ghij"))
    }

    @Tag("unit")
    @Test
    fun `SessionIdValidator rejects null bytes`() {
        assertFalse(SessionIdValidator.isValid("abc\u0000def45678"))
    }

    @Tag("unit")
    @Test
    fun `SessionIdValidator requireValid returns valid ID`() {
        assertEquals("abc123def456", SessionIdValidator.requireValid("abc123def456"))
    }

    @Test
    fun `SessionIdValidator requireValid throws for invalid ID`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionIdValidator.requireValid("invalid")
        }
    }

    @Test
    fun `SessionIdValidator requireValid throws for null`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionIdValidator.requireValid(null)
        }
    }

    // ==================== DisplayNameValidator Tests ====================

    @Tag("unit")
    @Test
    fun `DisplayNameValidator accepts valid names`() {
        assertTrue(DisplayNameValidator.isValid("My Session"))
        assertTrue(DisplayNameValidator.isValid("project-name"))
        assertTrue(DisplayNameValidator.isValid("project_name"))
        assertTrue(DisplayNameValidator.isValid("Project 123"))
        assertTrue(DisplayNameValidator.isValid("a"))
        assertTrue(DisplayNameValidator.isValid("A".repeat(64)))
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator rejects null`() {
        assertFalse(DisplayNameValidator.isValid(null))
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator rejects empty string`() {
        assertFalse(DisplayNameValidator.isValid(""))
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator rejects names exceeding max length`() {
        assertFalse(DisplayNameValidator.isValid("A".repeat(65)))
        assertFalse(DisplayNameValidator.isValid("A".repeat(100)))
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator rejects leading whitespace`() {
        assertFalse(DisplayNameValidator.isValid(" Name"))
        assertFalse(DisplayNameValidator.isValid("  Name"))
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator rejects trailing whitespace`() {
        assertFalse(DisplayNameValidator.isValid("Name "))
        assertFalse(DisplayNameValidator.isValid("Name  "))
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator rejects control characters`() {
        assertFalse(DisplayNameValidator.isValid("Name\u0000"))
        assertFalse(DisplayNameValidator.isValid("Name\t"))
        assertFalse(DisplayNameValidator.isValid("Name\n"))
        assertFalse(DisplayNameValidator.isValid("\u0001Name"))
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator rejects special characters`() {
        assertFalse(DisplayNameValidator.isValid("Name@123"))
        assertFalse(DisplayNameValidator.isValid("Name#123"))
        assertFalse(DisplayNameValidator.isValid("Name!"))
        assertFalse(DisplayNameValidator.isValid("Name/path"))
        assertFalse(DisplayNameValidator.isValid("Name\\path"))
        assertFalse(DisplayNameValidator.isValid("Name.ext"))
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator validate returns specific error for null`() {
        val result = DisplayNameValidator.validate(null)
        assertTrue(result is DisplayNameValidator.ValidationResult.Invalid)
        assertEquals("Name cannot be null", (result as DisplayNameValidator.ValidationResult.Invalid).reason)
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator validate returns specific error for leading whitespace`() {
        val result = DisplayNameValidator.validate(" Name")
        assertTrue(result is DisplayNameValidator.ValidationResult.Invalid)
        assertEquals("Name cannot have leading or trailing spaces", (result as DisplayNameValidator.ValidationResult.Invalid).reason)
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator validate returns specific error for too short`() {
        val result = DisplayNameValidator.validate("")
        assertTrue(result is DisplayNameValidator.ValidationResult.Invalid)
        assertEquals("Name must be at least 1 character", (result as DisplayNameValidator.ValidationResult.Invalid).reason)
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator validate returns specific error for too long`() {
        val result = DisplayNameValidator.validate("A".repeat(65))
        assertTrue(result is DisplayNameValidator.ValidationResult.Invalid)
        assertEquals("Name must be at most 64 characters", (result as DisplayNameValidator.ValidationResult.Invalid).reason)
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator validate returns specific error for control chars`() {
        // Use control character in the middle to avoid triggering whitespace trimming check
        val result = DisplayNameValidator.validate("Na\tme")
        assertTrue(result is DisplayNameValidator.ValidationResult.Invalid)
        assertEquals("Name cannot contain control characters", (result as DisplayNameValidator.ValidationResult.Invalid).reason)
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator validate returns specific error for invalid chars`() {
        val result = DisplayNameValidator.validate("Name@123")
        assertTrue(result is DisplayNameValidator.ValidationResult.Invalid)
        assertEquals("Name can only contain letters, numbers, dashes, underscores, and spaces", (result as DisplayNameValidator.ValidationResult.Invalid).reason)
    }

    @Tag("unit")
    @Test
    fun `DisplayNameValidator requireValid returns valid name`() {
        assertEquals("My Session", DisplayNameValidator.requireValid("My Session"))
    }

    @Test
    fun `DisplayNameValidator requireValid throws for invalid name`() {
        assertThrows(IllegalArgumentException::class.java) {
            DisplayNameValidator.requireValid("Invalid@Name")
        }
    }

    @Test
    fun `DisplayNameValidator requireValid throws for null`() {
        assertThrows(IllegalArgumentException::class.java) {
            DisplayNameValidator.requireValid(null)
        }
    }

    // ==================== DirectoryPathValidator Tests ====================

    @Tag("unit")
    @Test
    fun `DirectoryPathValidator accepts valid absolute paths`() {
        assertTrue(DirectoryPathValidator.isValid("/"))
        assertTrue(DirectoryPathValidator.isValid("/home"))
        assertTrue(DirectoryPathValidator.isValid("/home/user"))
        assertTrue(DirectoryPathValidator.isValid("/home/user/repos/project"))
        assertTrue(DirectoryPathValidator.isValid("/Users/name/Documents"))
    }

    @Tag("unit")
    @Test
    fun `DirectoryPathValidator rejects null`() {
        assertFalse(DirectoryPathValidator.isValid(null))
    }

    @Tag("unit")
    @Test
    fun `DirectoryPathValidator rejects empty string`() {
        assertFalse(DirectoryPathValidator.isValid(""))
    }

    @Tag("unit")
    @Test
    fun `DirectoryPathValidator rejects relative paths`() {
        assertFalse(DirectoryPathValidator.isValid("home/user"))
        assertFalse(DirectoryPathValidator.isValid("./project"))
        assertFalse(DirectoryPathValidator.isValid("project"))
    }

    @Tag("unit")
    @Test
    fun `DirectoryPathValidator rejects path traversal`() {
        assertFalse(DirectoryPathValidator.isValid("/home/user/.."))
        assertFalse(DirectoryPathValidator.isValid("/home/../etc/passwd"))
        assertFalse(DirectoryPathValidator.isValid("/../root"))
        assertFalse(DirectoryPathValidator.isValid("/home/user/../.."))
    }

    @Tag("unit")
    @Test
    fun `DirectoryPathValidator rejects null bytes`() {
        assertFalse(DirectoryPathValidator.isValid("/home/user\u0000"))
        assertFalse(DirectoryPathValidator.isValid("/home\u0000/user"))
    }

    @Tag("unit")
    @Test
    fun `DirectoryPathValidator rejects double slashes`() {
        assertFalse(DirectoryPathValidator.isValid("/home//user"))
        assertFalse(DirectoryPathValidator.isValid("//home/user"))
        assertFalse(DirectoryPathValidator.isValid("/home/user//"))
    }

    @Tag("unit")
    @Test
    fun `DirectoryPathValidator requireValid returns valid path`() {
        assertEquals("/home/user", DirectoryPathValidator.requireValid("/home/user"))
    }

    @Test
    fun `DirectoryPathValidator requireValid throws for relative path`() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryPathValidator.requireValid("home/user")
        }
    }

    @Test
    fun `DirectoryPathValidator requireValid throws for path traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryPathValidator.requireValid("/home/../etc")
        }
    }

    @Test
    fun `DirectoryPathValidator requireValid throws for null`() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryPathValidator.requireValid(null)
        }
    }

    // ==================== AgentNameValidator Tests ====================

    @Tag("unit")
    @Test
    fun `AgentNameValidator accepts valid agent names`() {
        assertTrue(AgentNameValidator.isValid("claude"))
        assertTrue(AgentNameValidator.isValid("aider"))
        assertTrue(AgentNameValidator.isValid("claude-code"))
        assertTrue(AgentNameValidator.isValid("my_agent"))
        assertTrue(AgentNameValidator.isValid("Agent123"))
        assertTrue(AgentNameValidator.isValid("a"))
        assertTrue(AgentNameValidator.isValid("A".repeat(32)))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator rejects null`() {
        assertFalse(AgentNameValidator.isValid(null))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator rejects empty string`() {
        assertFalse(AgentNameValidator.isValid(""))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator rejects names exceeding max length`() {
        assertFalse(AgentNameValidator.isValid("A".repeat(33)))
        assertFalse(AgentNameValidator.isValid("A".repeat(100)))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator rejects spaces`() {
        assertFalse(AgentNameValidator.isValid("my agent"))
        assertFalse(AgentNameValidator.isValid(" agent"))
        assertFalse(AgentNameValidator.isValid("agent "))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator rejects special characters`() {
        assertFalse(AgentNameValidator.isValid("agent@123"))
        assertFalse(AgentNameValidator.isValid("agent#test"))
        assertFalse(AgentNameValidator.isValid("agent!"))
        assertFalse(AgentNameValidator.isValid("agent.exe"))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator rejects path traversal`() {
        assertFalse(AgentNameValidator.isValid(".."))
        assertFalse(AgentNameValidator.isValid("../evil"))
        assertFalse(AgentNameValidator.isValid("agent/.."))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator rejects slashes`() {
        assertFalse(AgentNameValidator.isValid("agent/test"))
        assertFalse(AgentNameValidator.isValid("/usr/bin/agent"))
        assertFalse(AgentNameValidator.isValid("agent\\test"))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator rejects null bytes`() {
        assertFalse(AgentNameValidator.isValid("agent\u0000"))
        assertFalse(AgentNameValidator.isValid("\u0000agent"))
    }

    @Tag("unit")
    @Test
    fun `AgentNameValidator requireValid returns valid name`() {
        assertEquals("claude", AgentNameValidator.requireValid("claude"))
        assertEquals("my-agent_123", AgentNameValidator.requireValid("my-agent_123"))
    }

    @Test
    fun `AgentNameValidator requireValid throws for invalid name`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentNameValidator.requireValid("invalid/agent")
        }
    }

    @Test
    fun `AgentNameValidator requireValid throws for null`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentNameValidator.requireValid(null)
        }
    }

    @Test
    fun `AgentNameValidator requireValid throws for empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentNameValidator.requireValid("")
        }
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
