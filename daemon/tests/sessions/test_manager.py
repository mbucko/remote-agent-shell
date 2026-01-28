"""Tests for session manager module."""

from dataclasses import dataclass
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import betterproto
import pytest

from ras.proto.ras.ras import SessionStatus
from ras.sessions.agents import AgentInfo
from ras.sessions.manager import SessionData, SessionManager
from ras.sessions.persistence import SessionPersistence


def which_event(event):
    """Get the event type from a SessionEvent."""
    result = betterproto.which_one_of(event, "event")
    return result[0] if result else None


@pytest.fixture
def mock_persistence(tmp_path: Path) -> AsyncMock:
    """Create mock persistence."""
    persistence = AsyncMock(spec=SessionPersistence)
    persistence.load.return_value = []
    persistence.get_recent_directories.return_value = []
    return persistence


@pytest.fixture
def mock_tmux() -> AsyncMock:
    """Create mock tmux executor."""
    tmux = AsyncMock()
    tmux.list_sessions.return_value = []
    tmux.create_session.return_value = "$0"
    return tmux


@pytest.fixture
def mock_agents() -> AsyncMock:
    """Create mock agent detector."""
    agents = AsyncMock()
    agents.get_available.return_value = {
        "claude": AgentInfo(
            name="Claude Code",
            binary="claude",
            path="/usr/bin/claude",
            available=True,
        )
    }
    agents.get_all.return_value = MagicMock(agents=[])
    return agents


@pytest.fixture
def mock_directories() -> AsyncMock:
    """Create mock directory browser."""
    directories = AsyncMock()
    directories.list.return_value = MagicMock(entries=[], recent=[])
    return directories


@pytest.fixture
def mock_emitter() -> AsyncMock:
    """Create mock event emitter."""
    return AsyncMock()


@pytest.fixture
def session_manager(
    mock_persistence,
    mock_tmux,
    mock_agents,
    mock_directories,
    mock_emitter,
    tmp_path: Path,
) -> SessionManager:
    """Create session manager with mocks."""
    config = {
        "sessions": {"max_sessions": 20},
        "directories": {
            "root": str(tmp_path),
            "whitelist": [],
            "blacklist": [],
        },
    }
    return SessionManager(
        persistence=mock_persistence,
        tmux=mock_tmux,
        agents=mock_agents,
        directories=mock_directories,
        event_emitter=mock_emitter,
        config=config,
    )


class TestInitialize:
    """Tests for initialize method."""

    @pytest.mark.asyncio
    async def test_loads_persisted_sessions(
        self, session_manager, mock_persistence, mock_tmux
    ):
        """Loads sessions from persistence."""

        @dataclass
        class MockTmuxSession:
            name: str

        mock_tmux.list_sessions.return_value = [MockTmuxSession(name="ras-claude-test")]
        mock_persistence.load.return_value = []

        await session_manager.initialize()

        mock_persistence.load.assert_called_once()

    @pytest.mark.asyncio
    async def test_adopts_orphan_sessions(
        self, session_manager, mock_persistence, mock_tmux
    ):
        """Adopts orphaned tmux sessions."""

        @dataclass
        class MockTmuxSession:
            name: str

        mock_tmux.list_sessions.return_value = [
            MockTmuxSession(name="ras-aider-orphan")
        ]
        mock_persistence.load.return_value = []

        await session_manager.initialize()

        # Should have adopted the orphan
        result = await session_manager.list_sessions()
        assert len(result.list.sessions) == 1
        assert result.list.sessions[0].tmux_name == "ras-aider-orphan"


class TestCreateSession:
    """Tests for create_session method."""

    @pytest.mark.asyncio
    async def test_creates_session_successfully(
        self, session_manager, mock_tmux, tmp_path
    ):
        """Successfully creates a session."""
        await session_manager.initialize()

        result = await session_manager.create_session(
            directory=str(tmp_path),
            agent="claude",
        )

        assert which_event(result) == "created"
        assert result.created.session.agent == "claude"
        assert result.created.session.status == SessionStatus.ACTIVE
        mock_tmux.create_session.assert_called_once()

    @pytest.mark.asyncio
    async def test_rejects_nonexistent_directory(self, session_manager):
        """Rejects nonexistent directory."""
        await session_manager.initialize()

        result = await session_manager.create_session(
            directory="/nonexistent/path",
            agent="claude",
        )

        assert which_event(result) == "error"
        assert result.error.error_code == "DIR_NOT_FOUND"

    @pytest.mark.asyncio
    async def test_rejects_unknown_agent(self, session_manager, tmp_path, mock_agents):
        """Rejects unknown agent."""
        mock_agents.get_available.return_value = {}
        await session_manager.initialize()

        result = await session_manager.create_session(
            directory=str(tmp_path),
            agent="unknown",
        )

        assert which_event(result) == "error"
        assert result.error.error_code == "AGENT_NOT_FOUND"

    @pytest.mark.asyncio
    async def test_rejects_when_max_sessions_reached(
        self, session_manager, tmp_path, mock_persistence
    ):
        """Rejects when max sessions reached."""
        # Set max_sessions to 1
        session_manager._config["sessions"]["max_sessions"] = 1

        # Add one existing session
        session_manager._sessions["existing1234"] = SessionData(
            id="existing12345",
            tmux_name="ras-claude-existing",
            display_name="claude-existing",
            directory="/tmp",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700000000,
        )

        await session_manager.initialize()

        result = await session_manager.create_session(
            directory=str(tmp_path),
            agent="claude",
        )

        assert which_event(result) == "error"
        assert result.error.error_code == "MAX_SESSIONS_REACHED"

    @pytest.mark.asyncio
    async def test_rate_limits_requests(self, session_manager, tmp_path):
        """Rate limits excessive requests."""
        session_manager._config["rate_limit_max"] = 2
        await session_manager.initialize()

        # First two should succeed
        await session_manager.create_session(str(tmp_path), "claude", phone_id="phone1")
        await session_manager.create_session(str(tmp_path), "claude", phone_id="phone1")

        # Third should be rate limited
        result = await session_manager.create_session(
            str(tmp_path), "claude", phone_id="phone1"
        )

        assert which_event(result) == "error"
        assert result.error.error_code == "RATE_LIMITED"


class TestKillSession:
    """Tests for kill_session method."""

    @pytest.mark.asyncio
    async def test_kills_session_successfully(self, session_manager, mock_tmux):
        """Successfully kills a session."""
        await session_manager.initialize()

        # Add a session
        session_manager._sessions["testID123456"] = SessionData(
            id="testID123456",
            tmux_name="ras-claude-test",
            display_name="claude-test",
            directory="/tmp",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700000000,
        )

        result = await session_manager.kill_session("testID123456")

        assert which_event(result) == "killed"
        assert result.killed.session_id == "testID123456"
        assert "testID123456" not in session_manager._sessions

    @pytest.mark.asyncio
    async def test_rejects_unknown_session(self, session_manager):
        """Rejects unknown session ID."""
        await session_manager.initialize()

        result = await session_manager.kill_session("unknownID1234")

        assert which_event(result) == "error"
        assert result.error.error_code == "SESSION_NOT_FOUND"


class TestRenameSession:
    """Tests for rename_session method."""

    @pytest.mark.asyncio
    async def test_renames_session_successfully(self, session_manager):
        """Successfully renames a session."""
        await session_manager.initialize()

        session_manager._sessions["testID123456"] = SessionData(
            id="testID123456",
            tmux_name="ras-claude-test",
            display_name="claude-test",
            directory="/tmp",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700000000,
        )

        result = await session_manager.rename_session("testID123456", "New Name")

        assert which_event(result) == "renamed"
        assert result.renamed.new_name == "new-name"  # Sanitized

    @pytest.mark.asyncio
    async def test_rejects_duplicate_name(self, session_manager):
        """Rejects duplicate display name."""
        await session_manager.initialize()

        session_manager._sessions["session1ABCD"] = SessionData(
            id="session1ABCD",
            tmux_name="ras-claude-one",
            display_name="existing-name",
            directory="/tmp",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700000000,
        )
        session_manager._sessions["session2EFGH"] = SessionData(
            id="session2EFGH",
            tmux_name="ras-claude-two",
            display_name="other-name",
            directory="/tmp",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700000000,
        )

        result = await session_manager.rename_session("session2EFGH", "Existing Name")

        assert which_event(result) == "error"
        assert result.error.error_code == "SESSION_EXISTS"


class TestListSessions:
    """Tests for list_sessions method."""

    @pytest.mark.asyncio
    async def test_returns_sessions_sorted_by_activity(self, session_manager):
        """Returns sessions sorted by last activity (most recent first)."""
        await session_manager.initialize()

        session_manager._sessions["old_sessionAB"] = SessionData(
            id="old_sessionAB",
            tmux_name="ras-claude-old",
            display_name="old",
            directory="/tmp",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700000000,
        )
        session_manager._sessions["new_sessionCD"] = SessionData(
            id="new_sessionCD",
            tmux_name="ras-claude-new",
            display_name="new",
            directory="/tmp",
            agent="claude",
            created_at=1700001000,
            last_activity_at=1700002000,  # More recent
        )

        result = await session_manager.list_sessions()

        assert len(result.list.sessions) == 2
        assert result.list.sessions[0].id == "new_sessionCD"
        assert result.list.sessions[1].id == "old_sessionAB"


class TestGetAgents:
    """Tests for get_agents method."""

    @pytest.mark.asyncio
    async def test_returns_agents_list(self, session_manager, mock_agents):
        """Returns agents list from detector."""
        await session_manager.initialize()

        result = await session_manager.get_agents()

        assert which_event(result) == "agents"
        mock_agents.get_all.assert_called()


class TestGetDirectories:
    """Tests for get_directories method."""

    @pytest.mark.asyncio
    async def test_returns_directory_listing(self, session_manager, mock_directories):
        """Returns directory listing."""
        await session_manager.initialize()

        result = await session_manager.get_directories()

        assert which_event(result) == "directories"
        mock_directories.list.assert_called()


class TestEventEmission:
    """Tests for event emission."""

    @pytest.mark.asyncio
    async def test_emits_created_event(self, session_manager, mock_emitter, tmp_path):
        """Emits event when session created."""
        await session_manager.initialize()

        await session_manager.create_session(str(tmp_path), "claude")

        mock_emitter.emit.assert_called()
        event = mock_emitter.emit.call_args[0][0]
        assert which_event(event) == "created"

    @pytest.mark.asyncio
    async def test_emits_killed_event(self, session_manager, mock_emitter):
        """Emits event when session killed."""
        await session_manager.initialize()

        session_manager._sessions["testID123456"] = SessionData(
            id="testID123456",
            tmux_name="ras-claude-test",
            display_name="test",
            directory="/tmp",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700000000,
        )

        await session_manager.kill_session("testID123456")

        mock_emitter.emit.assert_called()
        event = mock_emitter.emit.call_args[0][0]
        assert which_event(event) == "killed"
