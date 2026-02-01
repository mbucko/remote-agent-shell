"""Tests for terminal manager."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.proto.ras import (
    AttachTerminal,
    DetachTerminal,
    TerminalCommand,
    TerminalInput,
)
from ras.terminal.manager import TerminalManager


class MockSessionProvider:
    """Mock session provider for testing."""

    def __init__(self, sessions: dict | None = None):
        self._sessions = sessions or {}

    def get_session(self, session_id: str) -> dict | None:
        return self._sessions.get(session_id)

    def add_session(self, session_id: str, tmux_name: str, status: str = "ACTIVE"):
        self._sessions[session_id] = {
            "tmux_name": tmux_name,
            "status": status,
        }


class MockTmuxExecutor:
    """Mock tmux executor for testing."""

    def __init__(self):
        self.send_keys = AsyncMock()


class TestTerminalManagerInit:
    """Test TerminalManager initialization."""

    def test_init_stores_parameters(self):
        """Init should store all parameters."""
        session_provider = MockSessionProvider()
        tmux_executor = MockTmuxExecutor()
        send_event = MagicMock()

        manager = TerminalManager(
            session_provider=session_provider,
            tmux_executor=tmux_executor,
            send_event=send_event,
            buffer_size_kb=50,
            chunk_interval_ms=100,
        )

        assert manager._sessions is session_provider
        assert manager._buffer_size == 50 * 1024
        assert manager._chunk_interval == 100

    def test_initial_state_empty(self):
        """Initial state should have no captures or attachments."""
        manager = TerminalManager(
            session_provider=MockSessionProvider(),
            tmux_executor=MockTmuxExecutor(),
            send_event=MagicMock(),
        )

        assert len(manager._captures) == 0
        assert len(manager._buffers) == 0
        assert len(manager._attachments) == 0


class TestTerminalManagerAttach:
    """Test attach functionality."""

    @pytest.fixture
    def setup(self):
        """Set up test fixtures."""
        session_provider = MockSessionProvider()
        session_provider.add_session("abc123def456", "ras-test-session")

        tmux_executor = MockTmuxExecutor()
        events = []

        def capture_event(conn_id: str, event):
            events.append((conn_id, event))

        manager = TerminalManager(
            session_provider=session_provider,
            tmux_executor=tmux_executor,
            send_event=capture_event,
        )

        return manager, session_provider, events

    @pytest.mark.asyncio
    async def test_attach_to_nonexistent_session_sends_error(self, setup):
        """Attaching to nonexistent session should send error."""
        manager, _, events = setup

        command = TerminalCommand(
            attach=AttachTerminal(session_id="nonexist0000", from_sequence=0)
        )

        await manager.handle_command("conn1", command)

        assert len(events) == 1
        conn_id, event = events[0]
        assert conn_id == "conn1"
        assert event.error.error_code == "SESSION_NOT_FOUND"

    @pytest.mark.asyncio
    async def test_attach_with_invalid_session_id_sends_error(self, setup):
        """Attaching with invalid session ID should send error."""
        manager, _, events = setup

        command = TerminalCommand(
            attach=AttachTerminal(session_id="invalid", from_sequence=0)
        )

        await manager.handle_command("conn1", command)

        assert len(events) == 1
        assert events[0][1].error.error_code == "INVALID_SESSION_ID"

    @pytest.mark.asyncio
    async def test_attach_to_killing_session_sends_error(self, setup):
        """Attaching to session being killed should send error."""
        manager, session_provider, events = setup
        session_provider.add_session("killing12345", "ras-killing", status="KILLING")

        command = TerminalCommand(
            attach=AttachTerminal(session_id="killing12345", from_sequence=0)
        )

        await manager.handle_command("conn1", command)

        assert len(events) == 1
        assert events[0][1].error.error_code == "SESSION_KILLING"

    @pytest.mark.asyncio
    async def test_attach_creates_buffer(self, setup):
        """Attaching should create buffer for session."""
        manager, _, events = setup

        command = TerminalCommand(
            attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
        )

        with patch.object(manager, "_captures", {}):
            with patch(
                "ras.terminal.manager.OutputCapture"
            ) as mock_capture_class:
                mock_capture = AsyncMock()
                mock_capture.start = AsyncMock()
                mock_capture_class.return_value = mock_capture

                await manager.handle_command("conn1", command)

        assert "abc123def456" in manager._buffers

    @pytest.mark.asyncio
    async def test_attach_tracks_connection(self, setup):
        """Attaching should track connection in attachments."""
        manager, _, events = setup

        command = TerminalCommand(
            attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
        )

        with patch(
            "ras.terminal.manager.OutputCapture"
        ) as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            await manager.handle_command("conn1", command)

        assert "conn1" in manager._attachments.get("abc123def456", set())

    @pytest.mark.asyncio
    async def test_attach_sends_attached_event(self, setup):
        """Attaching should send attached event."""
        manager, _, events = setup

        command = TerminalCommand(
            attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
        )

        with patch(
            "ras.terminal.manager.OutputCapture"
        ) as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            await manager.handle_command("conn1", command)

        # Should have received attached event
        attached_events = [e for _, e in events if e.attached.session_id]
        assert len(attached_events) == 1
        assert attached_events[0].attached.session_id == "abc123def456"
        assert attached_events[0].attached.cols == 80
        assert attached_events[0].attached.rows == 24

    @pytest.mark.asyncio
    async def test_attach_to_externally_killed_session_sends_session_gone(self, setup):
        """Attaching to session killed externally should send SESSION_GONE error."""
        manager, _, events = setup

        command = TerminalCommand(
            attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
        )

        with patch(
            "ras.terminal.manager.OutputCapture"
        ) as mock_capture_class:
            mock_capture = AsyncMock()
            # Simulate tmux error when session was killed externally
            mock_capture.start = AsyncMock(
                side_effect=RuntimeError("pipe-pane failed: can't find pane: %0")
            )
            mock_capture_class.return_value = mock_capture

            await manager.handle_command("conn1", command)

        # Should have received SESSION_GONE error
        assert len(events) == 1
        conn_id, event = events[0]
        assert conn_id == "conn1"
        assert event.error.error_code == "SESSION_GONE"
        assert "no longer exists" in event.error.message

    @pytest.mark.asyncio
    async def test_attach_pipe_setup_failure_sends_error(self, setup):
        """Other pipe setup failures should send PIPE_SETUP_FAILED error."""
        manager, _, events = setup

        command = TerminalCommand(
            attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
        )

        with patch(
            "ras.terminal.manager.OutputCapture"
        ) as mock_capture_class:
            mock_capture = AsyncMock()
            # Simulate other runtime error
            mock_capture.start = AsyncMock(
                side_effect=RuntimeError("pipe-pane failed: permission denied")
            )
            mock_capture_class.return_value = mock_capture

            await manager.handle_command("conn1", command)

        # Should have received PIPE_SETUP_FAILED error
        assert len(events) == 1
        conn_id, event = events[0]
        assert conn_id == "conn1"
        assert event.error.error_code == "PIPE_SETUP_FAILED"


class TestTerminalManagerDetach:
    """Test detach functionality."""

    @pytest.fixture
    def setup(self):
        """Set up test fixtures."""
        session_provider = MockSessionProvider()
        session_provider.add_session("abc123def456", "ras-test-session")

        tmux_executor = MockTmuxExecutor()
        events = []

        def capture_event(conn_id: str, event):
            events.append((conn_id, event))

        manager = TerminalManager(
            session_provider=session_provider,
            tmux_executor=tmux_executor,
            send_event=capture_event,
        )

        return manager, session_provider, events

    @pytest.mark.asyncio
    async def test_detach_removes_connection_from_attachments(self, setup):
        """Detaching should remove connection from attachments."""
        manager, _, events = setup

        # Set up attachment
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            detach=DetachTerminal(session_id="abc123def456")
        )

        await manager.handle_command("conn1", command)

        assert "conn1" not in manager._attachments.get("abc123def456", set())

    @pytest.mark.asyncio
    async def test_detach_sends_detached_event(self, setup):
        """Detaching should send detached event."""
        manager, _, events = setup

        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            detach=DetachTerminal(session_id="abc123def456")
        )

        await manager.handle_command("conn1", command)

        detached_events = [e for _, e in events if e.detached.session_id]
        assert len(detached_events) == 1
        assert detached_events[0].detached.session_id == "abc123def456"
        assert detached_events[0].detached.reason == "user_request"

    @pytest.mark.asyncio
    async def test_detach_stops_capture_when_no_attachments(self, setup):
        """Detaching last connection should stop capture."""
        manager, _, events = setup

        # Set up with single attachment
        manager._attachments["abc123def456"] = {"conn1"}
        mock_capture = AsyncMock()
        mock_capture.stop = AsyncMock()
        manager._captures["abc123def456"] = mock_capture

        command = TerminalCommand(
            detach=DetachTerminal(session_id="abc123def456")
        )

        await manager.handle_command("conn1", command)

        mock_capture.stop.assert_called_once()
        assert "abc123def456" not in manager._captures


class TestTerminalManagerInput:
    """Test input handling."""

    @pytest.fixture
    def setup(self):
        """Set up test fixtures."""
        session_provider = MockSessionProvider()
        session_provider.add_session("abc123def456", "ras-test-session")

        tmux_executor = MockTmuxExecutor()
        events = []

        def capture_event(conn_id: str, event):
            events.append((conn_id, event))

        manager = TerminalManager(
            session_provider=session_provider,
            tmux_executor=tmux_executor,
            send_event=capture_event,
        )

        return manager, tmux_executor, events

    @pytest.mark.asyncio
    async def test_input_when_not_attached_sends_error(self, setup):
        """Input when not attached should send error."""
        manager, _, events = setup

        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"test")
        )

        await manager.handle_command("conn1", command)

        assert len(events) == 1
        assert events[0][1].error.error_code == "NOT_ATTACHED"

    @pytest.mark.asyncio
    async def test_input_when_attached_sends_to_tmux(self, setup):
        """Input when attached should be sent to tmux."""
        manager, tmux_executor, events = setup

        # Set up attachment
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"hello")
        )

        await manager.handle_command("conn1", command)

        tmux_executor.send_keys.assert_called_once()

    @pytest.mark.asyncio
    async def test_input_to_nonexistent_session_sends_error(self, setup):
        """Input to nonexistent session should send error."""
        manager, _, events = setup

        # Attach to a session that will be removed
        manager._attachments["removed12345"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(session_id="removed12345", data=b"test")
        )

        await manager.handle_command("conn1", command)

        assert len(events) == 1
        assert events[0][1].error.error_code == "SESSION_NOT_FOUND"


class TestTerminalManagerSessionKilled:
    """Test on_session_killed handling."""

    @pytest.fixture
    def setup(self):
        """Set up test fixtures."""
        session_provider = MockSessionProvider()
        tmux_executor = MockTmuxExecutor()
        events = []

        def capture_event(conn_id: str, event):
            events.append((conn_id, event))

        manager = TerminalManager(
            session_provider=session_provider,
            tmux_executor=tmux_executor,
            send_event=capture_event,
        )

        return manager, events

    @pytest.mark.asyncio
    async def test_session_killed_notifies_attached_connections(self, setup):
        """Session killed should notify all attached connections."""
        manager, events = setup

        manager._attachments["abc123def456"] = {"conn1", "conn2"}

        await manager.on_session_killed("abc123def456")

        # Should have detached events for both connections
        assert len(events) == 2
        for _, event in events:
            assert event.detached.session_id == "abc123def456"
            assert event.detached.reason == "session_killed"

    @pytest.mark.asyncio
    async def test_session_killed_stops_capture(self, setup):
        """Session killed should stop capture."""
        manager, events = setup

        mock_capture = AsyncMock()
        mock_capture.stop = AsyncMock()
        manager._captures["abc123def456"] = mock_capture
        manager._attachments["abc123def456"] = set()

        await manager.on_session_killed("abc123def456")

        mock_capture.stop.assert_called_once()
        assert "abc123def456" not in manager._captures

    @pytest.mark.asyncio
    async def test_session_killed_clears_buffer(self, setup):
        """Session killed should clear buffer."""
        manager, events = setup

        from ras.terminal.buffer import CircularBuffer

        manager._buffers["abc123def456"] = CircularBuffer()
        manager._attachments["abc123def456"] = set()

        await manager.on_session_killed("abc123def456")

        assert "abc123def456" not in manager._buffers


class MockTmuxService:
    """Mock tmux service for testing."""

    def __init__(self):
        self.resize_window_to_largest = AsyncMock()


class TestTerminalManagerConnectionClosed:
    """Test on_connection_closed handling."""

    @pytest.fixture
    def setup(self):
        """Set up test fixtures."""
        manager = TerminalManager(
            session_provider=MockSessionProvider(),
            tmux_executor=MockTmuxExecutor(),
            send_event=MagicMock(),
        )
        return manager

    @pytest.fixture
    def setup_with_tmux_service(self):
        """Set up test fixtures with tmux service for resize tests."""
        session_provider = MockSessionProvider()
        session_provider.add_session("session1", "ras-test1")
        session_provider.add_session("session2", "ras-test2")
        tmux_service = MockTmuxService()
        manager = TerminalManager(
            session_provider=session_provider,
            tmux_executor=MockTmuxExecutor(),
            send_event=MagicMock(),
            tmux_service=tmux_service,
        )
        return manager, tmux_service, session_provider

    @pytest.mark.asyncio
    async def test_connection_closed_removes_from_all_sessions(self, setup):
        """Connection closed should remove from all session attachments."""
        manager = setup

        manager._attachments["session1"] = {"conn1", "conn2"}
        manager._attachments["session2"] = {"conn1"}
        manager._attachments["session3"] = {"conn2"}

        await manager.on_connection_closed("conn1")

        assert "conn1" not in manager._attachments["session1"]
        assert "conn1" not in manager._attachments.get("session2", set())
        assert "conn2" in manager._attachments["session3"]

    @pytest.mark.asyncio
    async def test_connection_closed_stops_capture_when_last(self, setup):
        """Connection closed should stop capture when last attachment."""
        manager = setup

        manager._attachments["session1"] = {"conn1"}
        mock_capture = AsyncMock()
        mock_capture.stop = AsyncMock()
        manager._captures["session1"] = mock_capture

        await manager.on_connection_closed("conn1")

        mock_capture.stop.assert_called_once()

    @pytest.mark.asyncio
    async def test_connection_closed_resizes_attached_sessions(self, setup_with_tmux_service):
        """Connection closed should resize windows for sessions the connection was attached to."""
        manager, tmux_service, _ = setup_with_tmux_service
        manager._attachments["session1"] = {"conn1", "conn2"}

        await manager.on_connection_closed("conn1")

        tmux_service.resize_window_to_largest.assert_called_once_with("ras-test1")

    @pytest.mark.asyncio
    async def test_connection_closed_resizes_multiple_sessions(self, setup_with_tmux_service):
        """Connection closed should resize all sessions the connection was attached to."""
        manager, tmux_service, _ = setup_with_tmux_service
        manager._attachments["session1"] = {"conn1"}
        manager._attachments["session2"] = {"conn1"}

        await manager.on_connection_closed("conn1")

        assert tmux_service.resize_window_to_largest.call_count == 2

    @pytest.mark.asyncio
    async def test_connection_closed_does_not_resize_unattached_sessions(self, setup_with_tmux_service):
        """Connection closed should not resize sessions the connection wasn't attached to."""
        manager, tmux_service, _ = setup_with_tmux_service
        manager._attachments["session1"] = {"conn2"}  # conn1 not attached

        await manager.on_connection_closed("conn1")

        tmux_service.resize_window_to_largest.assert_not_called()

    @pytest.mark.asyncio
    async def test_connection_closed_handles_missing_session_info(self, setup_with_tmux_service):
        """Connection closed should handle case where session info is not found."""
        manager, tmux_service, session_provider = setup_with_tmux_service
        # Create attachment for session that doesn't exist in provider
        manager._attachments["nonexistent"] = {"conn1"}

        # Should not raise
        await manager.on_connection_closed("conn1")

        tmux_service.resize_window_to_largest.assert_not_called()

    @pytest.mark.asyncio
    async def test_connection_closed_handles_resize_error_gracefully(self, setup_with_tmux_service):
        """Connection closed should continue if resize fails for one session."""
        manager, tmux_service, _ = setup_with_tmux_service
        manager._attachments["session1"] = {"conn1"}
        manager._attachments["session2"] = {"conn1"}
        tmux_service.resize_window_to_largest.side_effect = [Exception("fail"), None]

        # Should not raise, should try both sessions
        await manager.on_connection_closed("conn1")

        assert tmux_service.resize_window_to_largest.call_count == 2

    @pytest.mark.asyncio
    async def test_connection_closed_without_tmux_service(self, setup):
        """Connection closed should work without tmux service (no resize)."""
        manager = setup
        manager._attachments["session1"] = {"conn1"}

        # Should not raise even without tmux service
        await manager.on_connection_closed("conn1")

        assert "conn1" not in manager._attachments.get("session1", set())


class TestTerminalManagerShutdown:
    """Test shutdown functionality."""

    @pytest.mark.asyncio
    async def test_shutdown_stops_all_captures(self):
        """Shutdown should stop all captures."""
        manager = TerminalManager(
            session_provider=MockSessionProvider(),
            tmux_executor=MockTmuxExecutor(),
            send_event=MagicMock(),
        )

        mock_capture1 = AsyncMock()
        mock_capture1.stop = AsyncMock()
        mock_capture2 = AsyncMock()
        mock_capture2.stop = AsyncMock()

        manager._captures["session1"] = mock_capture1
        manager._captures["session2"] = mock_capture2

        await manager.shutdown()

        mock_capture1.stop.assert_called_once()
        mock_capture2.stop.assert_called_once()

    @pytest.mark.asyncio
    async def test_shutdown_clears_state(self):
        """Shutdown should clear all state."""
        manager = TerminalManager(
            session_provider=MockSessionProvider(),
            tmux_executor=MockTmuxExecutor(),
            send_event=MagicMock(),
        )

        from ras.terminal.buffer import CircularBuffer

        manager._buffers["session1"] = CircularBuffer()
        manager._attachments["session1"] = {"conn1"}

        await manager.shutdown()

        assert len(manager._buffers) == 0
        assert len(manager._attachments) == 0


class TestTerminalManagerHelpers:
    """Test helper methods."""

    def test_get_attachment_count(self):
        """get_attachment_count should return correct count."""
        manager = TerminalManager(
            session_provider=MockSessionProvider(),
            tmux_executor=MockTmuxExecutor(),
            send_event=MagicMock(),
        )

        manager._attachments["session1"] = {"conn1", "conn2", "conn3"}
        manager._attachments["session2"] = {"conn1"}

        assert manager.get_attachment_count("session1") == 3
        assert manager.get_attachment_count("session2") == 1
        assert manager.get_attachment_count("nonexistent") == 0

    def test_is_session_attached(self):
        """is_session_attached should return correct boolean."""
        manager = TerminalManager(
            session_provider=MockSessionProvider(),
            tmux_executor=MockTmuxExecutor(),
            send_event=MagicMock(),
        )

        manager._attachments["session1"] = {"conn1"}
        manager._attachments["session2"] = set()

        assert manager.is_session_attached("session1") is True
        assert manager.is_session_attached("session2") is False
        assert manager.is_session_attached("nonexistent") is False
