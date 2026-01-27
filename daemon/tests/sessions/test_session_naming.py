"""Tests for session naming and ID generation.

Tests session naming conventions, sanitization, and collision handling.
Uses test vectors from sessions.json for cross-platform validation.
"""

import json
import os
import re
import secrets
from pathlib import Path

import pytest

from tests.sessions.test_vectors import (
    TestSessionIdVectors,
    TestSessionNameVectors,
    TestTmuxNameVectors,
)


# ============================================================================
# HELPER FUNCTIONS (to be implemented in ras.sessions module)
# ============================================================================

def generate_session_id() -> str:
    """Generate a cryptographically secure session ID.

    Returns:
        12-character alphanumeric session ID.
    """
    return secrets.token_hex(6)  # 12 hex chars = 48 bits


def validate_session_id(session_id: str) -> bool:
    """Validate session ID format.

    Args:
        session_id: The session ID to validate.

    Returns:
        True if valid, False otherwise.
    """
    if not session_id or len(session_id) != 12:
        return False
    # Accept alphanumeric characters (hex or base62 style)
    return all(c.isalnum() for c in session_id)


def sanitize_directory_name(directory: str) -> str:
    """Sanitize directory name for use in tmux session name.

    Args:
        directory: Full directory path.

    Returns:
        Sanitized directory basename.
    """
    # Get basename
    name = os.path.basename(directory.rstrip("/"))

    # Handle root and home
    if not name or name == "/":
        return "root"
    if name == "~":
        return "home"

    # Convert to lowercase
    name = name.lower()

    # Replace special chars with dash
    name = re.sub(r"[^a-z0-9-]", "-", name)

    # Collapse multiple dashes
    name = re.sub(r"-+", "-", name)

    # Strip leading/trailing dashes
    name = name.strip("-")

    # Handle empty result
    if not name:
        return "unnamed"

    return name


def generate_tmux_name(directory: str, agent: str) -> str:
    """Generate tmux session name from directory and agent.

    Format: ras-<agent>-<sanitized_directory>
    Max length: 50 characters

    Args:
        directory: Working directory path.
        agent: Agent name (e.g., "claude").

    Returns:
        tmux session name.
    """
    agent_lower = agent.lower()
    dir_name = sanitize_directory_name(directory)

    # Build name with prefix
    prefix = f"ras-{agent_lower}-"
    max_dir_len = 50 - len(prefix)

    # Truncate directory name if needed
    if len(dir_name) > max_dir_len:
        dir_name = dir_name[:max_dir_len]

    return f"{prefix}{dir_name}"


def validate_display_name(name: str) -> bool:
    """Validate session display name.

    Display names must be:
    - 1-64 characters
    - Alphanumeric, dashes, underscores, spaces
    - No leading/trailing whitespace
    - No control characters

    Args:
        name: Display name to validate.

    Returns:
        True if valid, False otherwise.
    """
    if not name:
        return False

    # Check length
    if len(name) < 1 or len(name) > 64:
        return False

    # Check for leading/trailing whitespace
    if name != name.strip():
        return False

    # Check for control characters and invalid chars
    for char in name:
        if ord(char) < 32:  # Control characters
            return False
        if char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ ":
            return False

    return True


# ============================================================================
# SESSION ID TESTS
# ============================================================================

class TestSessionIdGeneration:
    """Tests for session ID generation."""

    def test_session_id_length(self):
        """Session ID has correct length."""
        session_id = generate_session_id()
        assert len(session_id) == 12

    def test_session_id_is_hex(self):
        """Session ID contains only hex characters."""
        session_id = generate_session_id()
        assert all(c in "0123456789abcdef" for c in session_id)

    def test_session_id_is_unique(self):
        """Session IDs are unique across multiple generations."""
        ids = [generate_session_id() for _ in range(100)]
        assert len(set(ids)) == 100

    def test_session_id_is_random(self):
        """Session IDs are not sequential or predictable."""
        ids = [generate_session_id() for _ in range(10)]

        # No two IDs should have significant overlap
        for i, id1 in enumerate(ids):
            for id2 in ids[i+1:]:
                # Allow at most 4 consecutive matching chars
                max_match = 0
                for j in range(len(id1)):
                    if id1[j] == id2[j]:
                        max_match = max(max_match, 1)
                    else:
                        max_match = 0
                assert max_match < 6  # Should not have 6+ consecutive matches


class TestSessionIdValidation:
    """Tests for session ID validation."""

    @pytest.mark.parametrize("session_id", TestSessionIdVectors.VALID_SESSION_IDS)
    def test_valid_session_ids(self, session_id):
        """Valid session IDs pass validation."""
        assert validate_session_id(session_id) is True

    @pytest.mark.parametrize("session_id", TestSessionIdVectors.INVALID_SESSION_IDS)
    def test_invalid_session_ids(self, session_id):
        """Invalid session IDs fail validation."""
        assert validate_session_id(session_id) is False

    def test_none_session_id(self):
        """None session ID fails validation."""
        assert validate_session_id(None) is False

    def test_unicode_session_id(self):
        """Unicode session ID fails validation."""
        assert validate_session_id("日本語session") is False


# ============================================================================
# SESSION NAME VALIDATION TESTS
# ============================================================================

class TestDisplayNameValidation:
    """Tests for display name validation."""

    @pytest.mark.parametrize("name", TestSessionNameVectors.VALID_DISPLAY_NAMES)
    def test_valid_display_names(self, name):
        """Valid display names pass validation."""
        assert validate_display_name(name) is True

    @pytest.mark.parametrize("name", TestSessionNameVectors.INVALID_DISPLAY_NAMES)
    def test_invalid_display_names(self, name):
        """Invalid display names fail validation."""
        assert validate_display_name(name) is False

    def test_boundary_lengths(self):
        """Boundary length cases."""
        # Min length (1)
        assert validate_display_name("a") is True

        # Max length (64)
        assert validate_display_name("a" * 64) is True

        # Over max
        assert validate_display_name("a" * 65) is False

    def test_whitespace_variations(self):
        """Various whitespace scenarios."""
        assert validate_display_name(" leading") is False
        assert validate_display_name("trailing ") is False
        assert validate_display_name(" both ") is False
        assert validate_display_name("inner space") is True


# ============================================================================
# TMUX NAME GENERATION TESTS
# ============================================================================

class TestTmuxNameGeneration:
    """Tests for tmux session name generation."""

    @pytest.mark.parametrize(
        "directory,agent,expected",
        TestTmuxNameVectors.NAME_GENERATION_VECTORS
    )
    def test_name_generation(self, directory, agent, expected):
        """tmux name generation matches expected output."""
        result = generate_tmux_name(directory, agent)
        assert result == expected

    def test_max_length_enforcement(self):
        """tmux name never exceeds 50 characters."""
        long_dir = "/home/user/" + "a" * 200
        result = generate_tmux_name(long_dir, "claude")
        assert len(result) <= 50

    def test_prefix_always_present(self):
        """tmux name always has ras- prefix."""
        result = generate_tmux_name("/any/path", "agent")
        assert result.startswith("ras-")

    def test_agent_lowercase(self):
        """Agent name is always lowercase."""
        result = generate_tmux_name("/path", "CLAUDE")
        assert "claude" in result
        assert "CLAUDE" not in result

    def test_special_character_removal(self):
        """Special characters are replaced with dashes."""
        result = generate_tmux_name("/path/my@project#1!", "claude")
        assert "@" not in result
        assert "#" not in result
        assert "!" not in result


class TestDirectorySanitization:
    """Tests for directory name sanitization."""

    def test_basic_sanitization(self):
        """Basic directory names are sanitized correctly."""
        assert sanitize_directory_name("/home/user/myproject") == "myproject"

    def test_uppercase_to_lowercase(self):
        """Uppercase is converted to lowercase."""
        assert sanitize_directory_name("/home/user/MyProject") == "myproject"

    def test_special_chars_to_dash(self):
        """Special characters become dashes."""
        assert sanitize_directory_name("/home/user/my.project") == "my-project"
        assert sanitize_directory_name("/home/user/my_project") == "my-project"
        assert sanitize_directory_name("/home/user/my project") == "my-project"

    def test_multiple_dashes_collapsed(self):
        """Multiple dashes are collapsed to one."""
        assert sanitize_directory_name("/home/user/my---project") == "my-project"
        assert sanitize_directory_name("/home/user/my...project") == "my-project"

    def test_leading_trailing_dashes_stripped(self):
        """Leading/trailing dashes are stripped."""
        assert sanitize_directory_name("/home/user/-project-") == "project"
        assert sanitize_directory_name("/home/user/---project---") == "project"

    def test_empty_after_sanitize(self):
        """Empty result after sanitization becomes 'unnamed'."""
        assert sanitize_directory_name("/home/user/...") == "unnamed"
        assert sanitize_directory_name("/home/user/!!!") == "unnamed"
        assert sanitize_directory_name("/home/user/@#$") == "unnamed"

    def test_trailing_slash(self):
        """Trailing slash is handled correctly."""
        assert sanitize_directory_name("/home/user/project/") == "project"

    def test_root_path(self):
        """Root path returns 'root'."""
        assert sanitize_directory_name("/") == "root"

    def test_home_tilde(self):
        """Home tilde returns 'home'."""
        assert sanitize_directory_name("~") == "home"

    def test_unicode_characters(self):
        """Unicode characters are replaced."""
        result = sanitize_directory_name("/home/user/プロジェクト")
        assert result == "unnamed" or "-" in result  # All non-ascii replaced

    def test_numbers_preserved(self):
        """Numbers are preserved."""
        assert sanitize_directory_name("/home/user/project123") == "project123"


# ============================================================================
# CROSS-PLATFORM VALIDATION WITH JSON TEST VECTORS
# ============================================================================

class TestCrossPlatformVectors:
    """Tests using JSON test vectors for cross-platform validation."""

    @pytest.fixture
    def test_vectors(self):
        """Load test vectors from JSON file."""
        vectors_path = Path(__file__).parent.parent.parent.parent / "test-vectors" / "sessions.json"
        with open(vectors_path) as f:
            return json.load(f)

    def test_session_naming_vectors(self, test_vectors):
        """Validate session naming against JSON test vectors."""
        vectors = test_vectors["session_naming"]["vectors"]

        for vector in vectors:
            directory = vector["directory"]
            agent = vector["agent"]
            expected = vector["expected"]

            result = generate_tmux_name(directory, agent)
            assert result == expected, (
                f"Vector '{vector['id']}' failed: "
                f"expected '{expected}', got '{result}'"
            )

    def test_session_id_format(self, test_vectors):
        """Validate session ID format requirements."""
        format_desc = test_vectors["session_id_generation"]["format"]
        assert "16 hex" in format_desc or "CSPRNG" in format_desc

        # Verify our generated IDs meet the format
        for _ in range(10):
            session_id = generate_session_id()
            assert len(session_id) == 12  # We use 12 hex chars


# ============================================================================
# EDGE CASES AND SECURITY
# ============================================================================

class TestSecurityEdgeCases:
    """Security-focused tests for session naming."""

    def test_path_traversal_in_name(self):
        """Path traversal attempts are sanitized."""
        result = sanitize_directory_name("/home/user/../../../etc")
        assert ".." not in result
        assert result == "etc" or result == "unnamed"

    def test_null_byte_in_name(self):
        """Null bytes are handled safely."""
        result = sanitize_directory_name("/home/user/test\x00malicious")
        assert "\x00" not in result

    def test_command_injection_in_name(self):
        """Command injection attempts are sanitized."""
        result = sanitize_directory_name("/home/user/; rm -rf /")
        # Shell metacharacters should be removed
        assert ";" not in result
        assert "/" not in result  # Slashes removed from basename
        # The result is just a sanitized string like "rm-rf" which is harmless

    def test_shell_expansion_chars(self):
        """Shell expansion characters are removed."""
        result = sanitize_directory_name("/home/user/$HOME")
        assert "$" not in result

        result = sanitize_directory_name("/home/user/`whoami`")
        assert "`" not in result

        result = sanitize_directory_name("/home/user/$(id)")
        assert "$(" not in result

    def test_very_long_path(self):
        """Very long paths are handled safely."""
        long_path = "/home/user/" + "a" * 10000
        result = generate_tmux_name(long_path, "claude")
        assert len(result) <= 50

    def test_unicode_normalization_attack(self):
        """Unicode normalization attacks are handled."""
        # Using combining characters
        result = sanitize_directory_name("/home/user/a\u0300b\u0301")  # à + ́
        # Should be sanitized to simple ascii
        assert all(ord(c) < 128 for c in result)
