"""Contract tests for session detection.

These tests verify that the daemon correctly detects and adopts
tmux sessions on startup. This catches bugs where the session
manager is not properly initialized or wired up.

Bug this prevents:
- Session manager not detecting existing tmux sessions
- Session manager not being initialized in daemon
- Session list showing 0 sessions when tmux sessions exist
"""

import asyncio
import os
import tempfile
import uuid

import pytest

from ras.sessions.manager import SessionManager
from ras.sessions.persistence import SessionPersistence
from ras.sessions.agents import AgentDetector
from ras.sessions.directories import DirectoryBrowser
from ras.tmux import TmuxService


pytestmark = pytest.mark.integration


@pytest.fixture
async def isolated_tmux():
    """Fixture providing an isolated tmux server."""
    socket_path = os.path.join(
        tempfile.gettempdir(),
        f"ras_test_tmux_{uuid.uuid4().hex[:8]}.sock"
    )

    service = TmuxService(socket_path=socket_path)

    yield service

    await service.kill_server()
    if os.path.exists(socket_path):
        os.remove(socket_path)


@pytest.fixture
def persistence_path(tmp_path):
    """Fixture providing a temporary persistence file path."""
    return tmp_path / "sessions.json"


class TestSessionDetectionContract:
    """Contract: Session manager MUST detect existing tmux sessions on startup."""

    @pytest.mark.asyncio
    async def test_detects_tmux_session(self, isolated_tmux, persistence_path):
        """Session manager detects existing tmux sessions.

        This is the primary adoption mechanism for orphaned sessions.
        All tmux sessions are adopted (agent parsed from first part of name).
        """
        # Create a session BEFORE session manager starts
        await isolated_tmux.create_session("claude-myproject")

        # Verify session exists in tmux
        sessions = await isolated_tmux.list_sessions()
        assert len(sessions) == 1
        assert sessions[0].name == "claude-myproject"

        # Create session manager (simulates daemon startup)
        persistence = SessionPersistence(path=persistence_path)
        agents = AgentDetector()
        directories = DirectoryBrowser()

        manager = SessionManager(
            persistence=persistence,
            tmux=isolated_tmux,
            agents=agents,
            directories=directories,
        )

        # Initialize - this should detect and adopt the session
        await manager.initialize()

        # CRITICAL: Session manager MUST have detected the session
        assert len(manager._sessions) == 1, (
            "Session manager did not detect existing tmux session. "
            "This indicates _initialize_managers() is not working correctly."
        )

        # Verify session details
        session = list(manager._sessions.values())[0]
        assert session.tmux_name == "claude-myproject"
        assert session.agent == "claude"  # Parsed from <agent>-<project>

    @pytest.mark.asyncio
    async def test_detects_multiple_sessions(self, isolated_tmux, persistence_path):
        """Session manager detects all tmux sessions."""
        # Create multiple sessions
        await isolated_tmux.create_session("claude-project1")
        await isolated_tmux.create_session("aider-project2")
        await isolated_tmux.create_session("cursor-project3")

        # Create session manager
        persistence = SessionPersistence(path=persistence_path)
        agents = AgentDetector()
        directories = DirectoryBrowser()

        manager = SessionManager(
            persistence=persistence,
            tmux=isolated_tmux,
            agents=agents,
            directories=directories,
        )

        await manager.initialize()

        # All sessions should be detected
        assert len(manager._sessions) == 3

    @pytest.mark.asyncio
    async def test_list_sessions_returns_detected_sessions(self, isolated_tmux, persistence_path):
        """list_sessions() returns sessions detected at startup.

        This is what the phone receives in InitialState.
        """
        # Create a session
        await isolated_tmux.create_session("claude-testproject")

        # Initialize session manager
        persistence = SessionPersistence(path=persistence_path)
        agents = AgentDetector()
        directories = DirectoryBrowser()

        manager = SessionManager(
            persistence=persistence,
            tmux=isolated_tmux,
            agents=agents,
            directories=directories,
        )

        await manager.initialize()

        # Call list_sessions - this is what daemon._send_initial_state uses
        result = await manager.list_sessions()

        # CRITICAL: result.list must contain the session
        assert result.list is not None, "list_sessions() returned None"
        assert len(result.list.sessions) == 1, (
            f"Expected 1 session, got {len(result.list.sessions)}. "
            "InitialState will show 0 sessions to the phone."
        )

        session = result.list.sessions[0]
        assert session.tmux_name == "claude-testproject"

    @pytest.mark.asyncio
    async def test_adopts_all_tmux_sessions(self, isolated_tmux, persistence_path):
        """All tmux sessions are adopted.

        The daemon adopts all tmux sessions, parsing agent from the
        first part of the session name (e.g., "claude-project" -> agent="claude").
        """
        # Create multiple sessions with different naming patterns
        await isolated_tmux.create_session("myproject")
        await isolated_tmux.create_session("work")
        await isolated_tmux.create_session("claude-managed")

        persistence = SessionPersistence(path=persistence_path)
        agents = AgentDetector()
        directories = DirectoryBrowser()

        manager = SessionManager(
            persistence=persistence,
            tmux=isolated_tmux,
            agents=agents,
            directories=directories,
        )

        await manager.initialize()

        # All sessions should be detected
        assert len(manager._sessions) == 3
        tmux_names = {s.tmux_name for s in manager._sessions.values()}
        assert tmux_names == {"myproject", "work", "claude-managed"}

    @pytest.mark.asyncio
    async def test_reconciles_with_persisted_sessions(self, isolated_tmux, persistence_path):
        """Session manager reconciles persisted sessions with tmux.

        If a persisted session no longer exists in tmux, it should be removed.
        If a tmux session exists but not persisted, it should be adopted.
        """
        # First, create a session and persist it
        await isolated_tmux.create_session("claude-persisted")

        persistence = SessionPersistence(path=persistence_path)
        agents = AgentDetector()
        directories = DirectoryBrowser()

        manager = SessionManager(
            persistence=persistence,
            tmux=isolated_tmux,
            agents=agents,
            directories=directories,
        )

        await manager.initialize()
        assert len(manager._sessions) == 1

        # Now kill the tmux session (simulates external kill)
        await isolated_tmux.kill_session("claude-persisted")

        # Create a new session
        await isolated_tmux.create_session("aider-newproject")

        # Create a new manager (simulates daemon restart)
        manager2 = SessionManager(
            persistence=persistence,  # Same persistence file
            tmux=isolated_tmux,
            agents=agents,
            directories=directories,
        )

        await manager2.initialize()

        # Should have only the new session
        assert len(manager2._sessions) == 1
        session = list(manager2._sessions.values())[0]
        assert session.tmux_name == "aider-newproject"


class TestSessionManagerInitializationContract:
    """Contract: SessionManager.initialize() MUST be called for detection to work."""

    @pytest.mark.asyncio
    async def test_sessions_empty_before_initialize(self, isolated_tmux, persistence_path):
        """Sessions dict is empty before initialize() is called."""
        await isolated_tmux.create_session("claude-test")

        persistence = SessionPersistence(path=persistence_path)
        manager = SessionManager(
            persistence=persistence,
            tmux=isolated_tmux,
            agents=AgentDetector(),
            directories=DirectoryBrowser(),
        )

        # Before initialize
        assert len(manager._sessions) == 0

        # After initialize
        await manager.initialize()
        assert len(manager._sessions) == 1

    @pytest.mark.asyncio
    async def test_list_sessions_works_after_initialize(self, isolated_tmux, persistence_path):
        """list_sessions() works correctly after initialize()."""
        await isolated_tmux.create_session("claude-test")

        persistence = SessionPersistence(path=persistence_path)
        manager = SessionManager(
            persistence=persistence,
            tmux=isolated_tmux,
            agents=AgentDetector(),
            directories=DirectoryBrowser(),
        )

        await manager.initialize()

        result = await manager.list_sessions()
        assert result.list is not None
        assert len(result.list.sessions) == 1
