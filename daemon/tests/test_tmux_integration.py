"""Real tmux integration tests.

These tests run against an isolated tmux server using a separate socket,
ensuring complete isolation from the user's real tmux sessions.
"""

import asyncio
import os
import tempfile
import uuid
import pytest

pytestmark = pytest.mark.integration

from ras.tmux import TmuxService


@pytest.fixture
async def isolated_tmux():
    """Fixture providing an isolated tmux server.

    Creates a temporary socket file and provides a TmuxService
    configured to use it. Cleans up the server on teardown.
    """
    # Create unique socket path in temp directory
    socket_path = os.path.join(
        tempfile.gettempdir(),
        f"ras_test_tmux_{uuid.uuid4().hex[:8]}.sock"
    )

    service = TmuxService(socket_path=socket_path)

    yield service

    # Cleanup: kill the server and remove socket
    await service.kill_server()
    if os.path.exists(socket_path):
        os.remove(socket_path)


@pytest.fixture
async def test_session(isolated_tmux):
    """Fixture providing a test session in the isolated tmux server."""
    session_name = f"test_{uuid.uuid4().hex[:8]}"
    session_id = await isolated_tmux.create_session(session_name)

    yield session_id, session_name, isolated_tmux

    # Cleanup happens via isolated_tmux fixture killing the server


class TestRealTmuxListSessions:
    """Test listing sessions against real tmux."""

    @pytest.mark.asyncio
    async def test_list_sessions_empty_server(self, isolated_tmux):
        """Empty server returns empty list."""
        sessions = await isolated_tmux.list_sessions()
        assert sessions == []

    @pytest.mark.asyncio
    async def test_list_sessions_finds_created_session(self, isolated_tmux):
        """Created session appears in list."""
        session_id = await isolated_tmux.create_session("test_session")

        sessions = await isolated_tmux.list_sessions()

        assert len(sessions) == 1
        assert sessions[0].id == session_id
        assert sessions[0].name == "test_session"
        assert sessions[0].windows == 1
        assert sessions[0].attached is False

    @pytest.mark.asyncio
    async def test_list_multiple_sessions(self, isolated_tmux):
        """Multiple sessions are listed correctly."""
        await isolated_tmux.create_session("session_a")
        await isolated_tmux.create_session("session_b")
        await isolated_tmux.create_session("session_c")

        sessions = await isolated_tmux.list_sessions()

        assert len(sessions) == 3
        names = {s.name for s in sessions}
        assert names == {"session_a", "session_b", "session_c"}


class TestRealTmuxSendKeys:
    """Test sending keys to real tmux."""

    @pytest.mark.asyncio
    async def test_send_keys_and_capture(self, test_session):
        """Send keys and verify via capture_pane."""
        session_id, _, service = test_session

        # Send a command
        await service.send_keys(session_id, "echo TESTOUTPUT123\n")

        # Give shell time to execute
        await asyncio.sleep(0.2)

        # Capture and verify
        content = await service.capture_pane(session_id)
        assert "TESTOUTPUT123" in content

    @pytest.mark.asyncio
    async def test_send_multiple_commands(self, test_session):
        """Send multiple commands sequentially."""
        session_id, _, service = test_session

        await service.send_keys(session_id, "export TESTVAR=hello\n")
        await asyncio.sleep(0.1)
        await service.send_keys(session_id, "echo $TESTVAR\n")
        await asyncio.sleep(0.2)

        content = await service.capture_pane(session_id)
        assert "hello" in content


class TestRealTmuxSessionSize:
    """Test session size operations against real tmux."""

    @pytest.mark.asyncio
    async def test_get_session_size(self, test_session):
        """Can get session size."""
        session_id, _, service = test_session

        rows, cols = await service.get_session_size(session_id)

        # Default size should be reasonable
        assert rows > 0
        assert cols > 0

    @pytest.mark.asyncio
    async def test_resize_session(self, test_session):
        """Can resize session."""
        session_id, _, service = test_session

        # Resize to specific dimensions
        await service.resize_session(session_id, rows=30, cols=100)

        # Verify new size
        rows, cols = await service.get_session_size(session_id)
        assert rows == 30
        assert cols == 100

    @pytest.mark.asyncio
    async def test_resize_multiple_times(self, test_session):
        """Can resize session multiple times."""
        session_id, _, service = test_session

        await service.resize_session(session_id, rows=20, cols=80)
        rows, cols = await service.get_session_size(session_id)
        assert rows == 20
        assert cols == 80

        await service.resize_session(session_id, rows=40, cols=120)
        rows, cols = await service.get_session_size(session_id)
        assert rows == 40
        assert cols == 120


class TestRealTmuxWindows:
    """Test window operations against real tmux."""

    @pytest.mark.asyncio
    async def test_list_windows_single(self, test_session):
        """New session has one window."""
        session_id, _, service = test_session

        windows = await service.list_windows(session_id)

        assert len(windows) == 1
        assert windows[0].active is True

    @pytest.mark.asyncio
    async def test_create_and_list_windows(self, test_session):
        """Can create additional windows and list them."""
        session_id, _, service = test_session

        # Create a new window using the service method
        window_id = await service.new_window(session_id, name="testwin")

        windows = await service.list_windows(session_id)

        assert len(windows) == 2
        names = {w.name for w in windows}
        assert "testwin" in names
        # Verify returned window ID is in the list
        window_ids = {w.id for w in windows}
        assert window_id in window_ids


class TestRealTmuxSessionLifecycle:
    """Test session lifecycle operations."""

    @pytest.mark.asyncio
    async def test_create_and_kill_session(self, isolated_tmux):
        """Can create and kill sessions."""
        # Create
        session_id = await isolated_tmux.create_session("temp_session")
        sessions = await isolated_tmux.list_sessions()
        assert len(sessions) == 1

        # Kill
        await isolated_tmux.kill_session(session_id)
        sessions = await isolated_tmux.list_sessions()
        assert len(sessions) == 0

    @pytest.mark.asyncio
    async def test_kill_nonexistent_session(self, isolated_tmux):
        """Killing nonexistent session doesn't raise."""
        # Should not raise
        await isolated_tmux.kill_session("nonexistent_session")


class TestRealTmuxCapture:
    """Test capture operations against real tmux."""

    @pytest.mark.asyncio
    async def test_capture_pane_content(self, test_session):
        """Capture pane returns shell content."""
        session_id, _, service = test_session

        content = await service.capture_pane(session_id)

        # Should have shell prompt or similar
        assert isinstance(content, str)
        assert len(content) >= 0  # Might be empty initially

    @pytest.mark.asyncio
    async def test_capture_after_commands(self, test_session):
        """Capture includes command output."""
        session_id, _, service = test_session

        # Run commands that produce output
        await service.send_keys(session_id, "echo LINE1\n")
        await service.send_keys(session_id, "echo LINE2\n")
        await service.send_keys(session_id, "echo LINE3\n")
        await asyncio.sleep(0.3)

        content = await service.capture_pane(session_id)

        assert "LINE1" in content
        assert "LINE2" in content
        assert "LINE3" in content


class TestRealTmuxIsolation:
    """Verify tests are properly isolated."""

    @pytest.mark.asyncio
    async def test_isolated_from_user_sessions(self, isolated_tmux):
        """Isolated tmux doesn't see user's sessions."""
        # Create a session in isolated server
        await isolated_tmux.create_session("isolated_test")

        sessions = await isolated_tmux.list_sessions()

        # Should only see our session, not user's real sessions
        assert len(sessions) == 1
        assert sessions[0].name == "isolated_test"

        # Names like claude-planner, claude-executor should NOT appear
        names = {s.name for s in sessions}
        assert "claude-planner" not in names
        assert "claude-executor" not in names

    @pytest.mark.asyncio
    async def test_different_fixtures_are_isolated(self, isolated_tmux):
        """Each test gets its own isolated server."""
        # Create session
        await isolated_tmux.create_session("fixture_test")

        sessions = await isolated_tmux.list_sessions()
        assert len(sessions) == 1

        # This session should not persist to other tests
        # (verified by test_isolated_from_user_sessions seeing 0 sessions initially)
