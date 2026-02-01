"""Tests for session naming module."""

import pytest

from ras.sessions.naming import generate_session_name, sanitize_name


class TestSanitizeName:
    """Tests for sanitize_name."""

    def test_basic_name_unchanged(self):
        """Basic lowercase alphanumeric name is unchanged."""
        assert sanitize_name("my-project") == "my-project"

    def test_uppercase_to_lowercase(self):
        """Uppercase is converted to lowercase."""
        assert sanitize_name("MyProject") == "myproject"

    def test_special_chars_to_hyphens(self):
        """Special characters are converted to hyphens."""
        assert sanitize_name("my_project.v2 (copy)") == "my-project-v2-copy"

    def test_multiple_hyphens_collapsed(self):
        """Multiple hyphens are collapsed to one."""
        assert sanitize_name("my---project") == "my-project"

    def test_leading_trailing_hyphens_stripped(self):
        """Leading and trailing hyphens are stripped."""
        assert sanitize_name("-my-project-") == "my-project"

    def test_empty_result_becomes_unnamed(self):
        """Empty result after sanitization becomes 'unnamed'."""
        assert sanitize_name("...") == "unnamed"
        assert sanitize_name("") == "unnamed"
        assert sanitize_name("---") == "unnamed"

    def test_numbers_preserved(self):
        """Numbers are preserved."""
        assert sanitize_name("project123") == "project123"

    def test_unicode_to_hyphens(self):
        """Unicode characters are converted to hyphens."""
        assert sanitize_name("projeçt") == "proje-t"


class TestGenerateSessionName:
    """Tests for generate_session_name."""

    def test_basic_generation(self):
        """Basic name generation works."""
        name = generate_session_name("/home/user/my-project", "claude", [])
        assert name == "claude-my-project"

    def test_uses_directory_basename(self):
        """Uses only the directory basename."""
        name = generate_session_name("/very/long/path/to/project", "aider", [])
        assert name == "aider-project"

    def test_sanitizes_directory_name(self):
        """Directory name is sanitized."""
        name = generate_session_name("/home/user/My Project (Copy)", "claude", [])
        assert name == "claude-my-project-copy"

    def test_sanitizes_agent_name(self):
        """Agent name is sanitized."""
        name = generate_session_name("/home/user/project", "Claude Code", [])
        assert name == "claude-code-project"

    def test_collision_appends_number(self):
        """Collision with existing name appends -2."""
        existing = ["claude-my-project"]
        name = generate_session_name("/home/user/my-project", "claude", existing)
        assert name == "claude-my-project-2"

    def test_multiple_collisions(self):
        """Multiple collisions increment counter."""
        existing = ["claude-my-project", "claude-my-project-2"]
        name = generate_session_name("/home/user/my-project", "claude", existing)
        assert name == "claude-my-project-3"

    def test_truncation_respects_max_length(self):
        """Long names are truncated to max_length."""
        long_dir = "/home/user/" + "a" * 100
        name = generate_session_name(long_dir, "claude", [], max_length=50)
        assert len(name) <= 50

    def test_truncation_leaves_room_for_suffix(self):
        """Truncation leaves room for collision suffix."""
        long_dir = "/home/user/" + "a" * 100
        existing = [generate_session_name(long_dir, "claude", [], max_length=50)]
        name = generate_session_name(long_dir, "claude", existing, max_length=50)
        assert len(name) <= 50
        assert name.endswith("-2")

    def test_trailing_slash_handled(self):
        """Trailing slash on directory is handled."""
        name = generate_session_name("/home/user/project/", "claude", [])
        assert name == "claude-project"
