"""Tests for SessionRegistry."""

import pytest
from unittest.mock import MagicMock

from ras.session_registry import SessionRegistry


class MockAttachedSession:
    """Mock attached session for testing."""

    def __init__(self, session_id: str):
        self.session_id = session_id


class TestSessionRegistry:
    """Test SessionRegistry operations."""

    def test_empty_registry(self):
        """New registry has no sessions."""
        registry = SessionRegistry()
        assert registry.list_all() == []
        assert len(registry) == 0

    def test_add_session(self):
        """Can add a session to registry."""
        registry = SessionRegistry()
        session = MockAttachedSession("$0")

        registry.add(session)

        assert len(registry) == 1
        assert registry.get("$0") is session

    def test_add_multiple_sessions(self):
        """Can add multiple sessions."""
        registry = SessionRegistry()
        session1 = MockAttachedSession("$0")
        session2 = MockAttachedSession("$1")

        registry.add(session1)
        registry.add(session2)

        assert len(registry) == 2
        assert registry.get("$0") is session1
        assert registry.get("$1") is session2

    def test_get_nonexistent_returns_none(self):
        """Getting nonexistent session returns None."""
        registry = SessionRegistry()

        assert registry.get("$99") is None

    def test_remove_session(self):
        """Can remove a session from registry."""
        registry = SessionRegistry()
        session = MockAttachedSession("$0")
        registry.add(session)

        removed = registry.remove("$0")

        assert removed is session
        assert registry.get("$0") is None
        assert len(registry) == 0

    def test_remove_nonexistent_returns_none(self):
        """Removing nonexistent session returns None."""
        registry = SessionRegistry()

        result = registry.remove("$99")

        assert result is None

    def test_list_all_returns_all_sessions(self):
        """list_all() returns list of all sessions."""
        registry = SessionRegistry()
        session1 = MockAttachedSession("$0")
        session2 = MockAttachedSession("$1")
        registry.add(session1)
        registry.add(session2)

        sessions = registry.list_all()

        assert len(sessions) == 2
        assert session1 in sessions
        assert session2 in sessions

    def test_contains_check(self):
        """Can check if session exists using 'in'."""
        registry = SessionRegistry()
        session = MockAttachedSession("$0")
        registry.add(session)

        assert "$0" in registry
        assert "$1" not in registry

    def test_replace_session(self):
        """Adding session with same ID replaces existing."""
        registry = SessionRegistry()
        session1 = MockAttachedSession("$0")
        session2 = MockAttachedSession("$0")

        registry.add(session1)
        registry.add(session2)

        assert len(registry) == 1
        assert registry.get("$0") is session2
