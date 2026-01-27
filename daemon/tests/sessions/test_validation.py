"""Tests for session validation module."""

import os
from dataclasses import dataclass
from pathlib import Path

import pytest

from ras.sessions.validation import (
    generate_session_id,
    validate_agent,
    validate_directory,
    validate_name,
    validate_session_id,
    SESSION_ID_LENGTH,
    SESSION_ID_ALPHABET,
)


class TestGenerateSessionId:
    """Tests for generate_session_id."""

    def test_returns_correct_length(self):
        """Generated ID has correct length."""
        session_id = generate_session_id()
        assert len(session_id) == SESSION_ID_LENGTH

    def test_uses_valid_alphabet(self):
        """Generated ID only uses valid characters."""
        for _ in range(100):
            session_id = generate_session_id()
            for char in session_id:
                assert char in SESSION_ID_ALPHABET

    def test_generates_unique_ids(self):
        """Generated IDs are unique."""
        ids = {generate_session_id() for _ in range(1000)}
        assert len(ids) == 1000


class TestValidateDirectory:
    """Tests for validate_directory."""

    def test_valid_directory(self, tmp_path: Path):
        """Valid directory under root returns None."""
        result = validate_directory(
            str(tmp_path),
            root=str(tmp_path.parent),
            whitelist=[],
            blacklist=[],
        )
        assert result is None

    def test_empty_path_returns_error(self):
        """Empty path returns error."""
        result = validate_directory("", root="/", whitelist=[], blacklist=[])
        assert result is not None
        assert "required" in result.lower()

    def test_nonexistent_directory_returns_error(self):
        """Nonexistent directory returns error."""
        result = validate_directory(
            "/nonexistent/path/that/does/not/exist",
            root="/",
            whitelist=[],
            blacklist=[],
        )
        assert result is not None
        assert "does not exist" in result.lower()

    def test_file_not_directory_returns_error(self, tmp_path: Path):
        """File (not directory) returns error."""
        file = tmp_path / "file.txt"
        file.write_text("test")
        result = validate_directory(
            str(file),
            root=str(tmp_path),
            whitelist=[],
            blacklist=[],
        )
        assert result is not None
        assert "not a directory" in result.lower()

    def test_outside_root_returns_error(self, tmp_path: Path):
        """Directory outside root returns error."""
        result = validate_directory(
            "/etc",
            root=str(tmp_path),
            whitelist=[],
            blacklist=[],
        )
        assert result is not None
        assert "outside root" in result.lower()

    def test_path_traversal_blocked(self, tmp_path: Path):
        """Path traversal attempts are blocked."""
        result = validate_directory(
            f"{tmp_path}/../../../etc",
            root=str(tmp_path),
            whitelist=[],
            blacklist=[],
        )
        # After resolution, /etc is outside tmp_path
        assert result is not None

    def test_not_in_whitelist_returns_error(self, tmp_path: Path):
        """Directory not in whitelist returns error."""
        allowed = tmp_path / "allowed"
        allowed.mkdir()
        other = tmp_path / "other"
        other.mkdir()

        result = validate_directory(
            str(other),
            root=str(tmp_path),
            whitelist=[str(allowed)],
            blacklist=[],
        )
        assert result is not None
        assert "whitelist" in result.lower()

    def test_in_whitelist_allowed(self, tmp_path: Path):
        """Directory in whitelist is allowed."""
        allowed = tmp_path / "allowed"
        allowed.mkdir()

        result = validate_directory(
            str(allowed),
            root=str(tmp_path),
            whitelist=[str(allowed)],
            blacklist=[],
        )
        assert result is None

    def test_subdirectory_of_whitelist_allowed(self, tmp_path: Path):
        """Subdirectory of whitelist entry is allowed."""
        allowed = tmp_path / "allowed"
        allowed.mkdir()
        sub = allowed / "subdir"
        sub.mkdir()

        result = validate_directory(
            str(sub),
            root=str(tmp_path),
            whitelist=[str(allowed)],
            blacklist=[],
        )
        assert result is None

    def test_in_blacklist_returns_error(self, tmp_path: Path):
        """Directory in blacklist returns error."""
        secret = tmp_path / "secret"
        secret.mkdir()

        result = validate_directory(
            str(secret),
            root=str(tmp_path),
            whitelist=[],
            blacklist=[str(secret)],
        )
        assert result is not None
        assert "blacklisted" in result.lower()

    def test_subdirectory_of_blacklist_blocked(self, tmp_path: Path):
        """Subdirectory of blacklist entry is blocked."""
        secret = tmp_path / "secret"
        secret.mkdir()
        sub = secret / "subdir"
        sub.mkdir()

        result = validate_directory(
            str(sub),
            root=str(tmp_path),
            whitelist=[],
            blacklist=[str(secret)],
        )
        assert result is not None
        assert "blacklisted" in result.lower()

    def test_hidden_directory_glob_blacklist(self, tmp_path: Path):
        """Hidden directories matched by glob pattern are blocked."""
        hidden = tmp_path / ".hidden"
        hidden.mkdir()

        result = validate_directory(
            str(hidden),
            root=str(tmp_path),
            whitelist=[],
            blacklist=[f"{tmp_path}/.*"],
        )
        assert result is not None
        assert "blacklisted" in result.lower()

    def test_tilde_expansion(self, tmp_path: Path):
        """Tilde is expanded correctly."""
        result = validate_directory(
            "~",
            root="~",
            whitelist=[],
            blacklist=[],
        )
        # Home directory should exist
        assert result is None


class TestValidateAgent:
    """Tests for validate_agent."""

    def test_valid_agent_returns_none(self):
        """Valid available agent returns None."""

        @dataclass
        class MockAgent:
            available: bool = True

        result = validate_agent("claude", {"claude": MockAgent()})
        assert result is None

    def test_empty_agent_returns_error(self):
        """Empty agent string returns error."""
        result = validate_agent("", {})
        assert result is not None
        assert "required" in result.lower()

    def test_unknown_agent_returns_error(self):
        """Unknown agent returns error."""
        result = validate_agent("unknown", {"claude": object()})
        assert result is not None
        assert "not installed" in result.lower()

    def test_unavailable_agent_returns_error(self):
        """Unavailable agent returns error."""

        @dataclass
        class MockAgent:
            available: bool = False

        result = validate_agent("claude", {"claude": MockAgent()})
        assert result is not None
        assert "not found" in result.lower()


class TestValidateSessionId:
    """Tests for validate_session_id."""

    def test_valid_session_id_returns_none(self):
        """Valid 12-char alphanumeric session ID returns None."""
        result = validate_session_id("abcDEF012345")
        assert result is None

    def test_generated_id_is_valid(self):
        """Generated session IDs pass validation."""
        for _ in range(100):
            session_id = generate_session_id()
            result = validate_session_id(session_id)
            assert result is None

    def test_empty_session_id_returns_error(self):
        """Empty session ID returns error."""
        result = validate_session_id("")
        assert result is not None
        assert "required" in result.lower()

    def test_too_short_returns_error(self):
        """Too short session ID returns error."""
        result = validate_session_id("abc123")
        assert result is not None
        assert "format" in result.lower()

    def test_too_long_returns_error(self):
        """Too long session ID returns error."""
        result = validate_session_id("abcDEF01234567890")
        assert result is not None
        assert "format" in result.lower()

    def test_special_chars_returns_error(self):
        """Special characters return error."""
        result = validate_session_id("abc-DEF_0123")
        assert result is not None
        assert "format" in result.lower()

    def test_path_traversal_blocked(self):
        """Path traversal attempts are blocked."""
        result = validate_session_id("../etc/passwd")
        assert result is not None
        assert "format" in result.lower()

    def test_null_byte_blocked(self):
        """Null bytes are blocked."""
        result = validate_session_id("abc\x00DEF01234")
        assert result is not None
        assert "format" in result.lower()


class TestValidateName:
    """Tests for validate_name."""

    def test_valid_name_returns_none(self):
        """Valid name returns None."""
        result = validate_name("my-project")
        assert result is None

    def test_empty_name_returns_error(self):
        """Empty name returns error."""
        result = validate_name("")
        assert result is not None
        assert "required" in result.lower()

    def test_too_long_returns_error(self):
        """Name exceeding max length returns error."""
        result = validate_name("a" * 101)
        assert result is not None
        assert "too long" in result.lower()

    def test_max_length_allowed(self):
        """Name at max length is allowed."""
        result = validate_name("a" * 100)
        assert result is None

    def test_custom_max_length(self):
        """Custom max length is respected."""
        result = validate_name("a" * 51, max_length=50)
        assert result is not None
        assert "50" in result
