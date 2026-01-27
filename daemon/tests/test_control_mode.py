"""Tests for ControlModeClient."""

import asyncio
import pytest
from unittest.mock import AsyncMock, MagicMock

from ras.control_mode import ControlModeClient
from ras.protocols import (
    OutputEvent,
    WindowAddEvent,
    WindowCloseEvent,
    WindowRenamedEvent,
    SessionChangedEvent,
    ExitEvent,
)


class MockControlModeProcess:
    """Mock control mode subprocess for testing."""

    def __init__(self):
        self.lines: list[bytes] = []
        self._index = 0
        self._running = False
        self._start_called = False
        self._stop_called = False

    def add_line(self, line: str):
        """Add a line to be returned by readline."""
        self.lines.append(line.encode() + b"\n")

    async def start(self) -> None:
        """Start the mock process."""
        self._start_called = True
        self._running = True

    async def stop(self) -> None:
        """Stop the mock process."""
        self._stop_called = True
        self._running = False

    async def readline(self) -> bytes:
        """Read next line."""
        if self._index >= len(self.lines):
            # Simulate end of stream
            self._running = False
            return b""
        line = self.lines[self._index]
        self._index += 1
        return line

    def is_running(self) -> bool:
        """Check if process is running."""
        return self._running


class TestControlModeClientCreation:
    """Test ControlModeClient creation."""

    def test_create_with_process(self):
        """Can create ControlModeClient with injected process."""
        process = MockControlModeProcess()
        client = ControlModeClient(session_id="$0", process=process)
        assert client.session_id == "$0"

    def test_create_with_tmux_path(self):
        """Can specify custom tmux path."""
        client = ControlModeClient(session_id="$0", tmux_path="/custom/tmux")
        assert client._tmux_path == "/custom/tmux"


class TestControlModeClientLifecycle:
    """Test ControlModeClient start/stop lifecycle."""

    @pytest.mark.asyncio
    async def test_start_begins_processing(self):
        """start() begins reading from process."""
        process = MockControlModeProcess()
        process.add_line("%begin 123")
        process.add_line("%end 123")
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        # Give the reader task time to start
        await asyncio.sleep(0.01)

        assert process._start_called

        await client.stop()

    @pytest.mark.asyncio
    async def test_stop_terminates_processing(self):
        """stop() terminates the reader task."""
        process = MockControlModeProcess()
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        await client.stop()

        assert process._stop_called


class TestControlModeEventParsing:
    """Test parsing of tmux control mode notifications."""

    @pytest.mark.asyncio
    async def test_parse_output_event(self):
        """Parses %output notifications into OutputEvent."""
        process = MockControlModeProcess()
        # Format: %output %<pane_id> <base64_data>
        # "hello" in base64 is "aGVsbG8="
        process.add_line("%output %0 aGVsbG8=")
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        event = await asyncio.wait_for(client.get_event(), timeout=1.0)
        await client.stop()

        assert isinstance(event, OutputEvent)
        assert event.session_id == "$0"
        assert event.pane_id == "%0"
        assert event.data == b"hello"

    @pytest.mark.asyncio
    async def test_parse_window_add_event(self):
        """Parses %window-add notifications."""
        process = MockControlModeProcess()
        process.add_line("%window-add @1")
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        event = await asyncio.wait_for(client.get_event(), timeout=1.0)
        await client.stop()

        assert isinstance(event, WindowAddEvent)
        assert event.session_id == "$0"
        assert event.window_id == "@1"

    @pytest.mark.asyncio
    async def test_parse_window_close_event(self):
        """Parses %window-close notifications."""
        process = MockControlModeProcess()
        process.add_line("%window-close @1")
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        event = await asyncio.wait_for(client.get_event(), timeout=1.0)
        await client.stop()

        assert isinstance(event, WindowCloseEvent)
        assert event.session_id == "$0"
        assert event.window_id == "@1"

    @pytest.mark.asyncio
    async def test_parse_window_renamed_event(self):
        """Parses %window-renamed notifications."""
        process = MockControlModeProcess()
        process.add_line("%window-renamed @1 new-name")
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        event = await asyncio.wait_for(client.get_event(), timeout=1.0)
        await client.stop()

        assert isinstance(event, WindowRenamedEvent)
        assert event.session_id == "$0"
        assert event.window_id == "@1"
        assert event.name == "new-name"

    @pytest.mark.asyncio
    async def test_parse_session_changed_event(self):
        """Parses %session-changed notifications."""
        process = MockControlModeProcess()
        process.add_line("%session-changed $1 my-session")
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        event = await asyncio.wait_for(client.get_event(), timeout=1.0)
        await client.stop()

        assert isinstance(event, SessionChangedEvent)
        assert event.name == "my-session"

    @pytest.mark.asyncio
    async def test_parse_exit_event(self):
        """Parses %exit notifications."""
        process = MockControlModeProcess()
        process.add_line("%exit")
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        event = await asyncio.wait_for(client.get_event(), timeout=1.0)
        await client.stop()

        assert isinstance(event, ExitEvent)
        assert event.session_id == "$0"

    @pytest.mark.asyncio
    async def test_ignores_begin_end_blocks(self):
        """Ignores %begin/%end command response blocks."""
        process = MockControlModeProcess()
        process.add_line("%begin 123")
        process.add_line("some response data")
        process.add_line("%end 123")
        process.add_line("%window-add @1")  # Real event after
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        event = await asyncio.wait_for(client.get_event(), timeout=1.0)
        await client.stop()

        # Should get window-add, not the begin/end stuff
        assert isinstance(event, WindowAddEvent)

    @pytest.mark.asyncio
    async def test_handles_empty_output(self):
        """Handles empty output data gracefully."""
        process = MockControlModeProcess()
        process.add_line("%output %0 ")  # Empty base64
        process.add_line("%window-add @1")  # Follow-up event
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        # First event: output with empty data
        event1 = await asyncio.wait_for(client.get_event(), timeout=1.0)
        event2 = await asyncio.wait_for(client.get_event(), timeout=1.0)
        await client.stop()

        assert isinstance(event1, OutputEvent)
        assert event1.data == b""
        assert isinstance(event2, WindowAddEvent)


class TestControlModeEventQueue:
    """Test event queue behavior."""

    @pytest.mark.asyncio
    async def test_events_queued_in_order(self):
        """Events are queued in the order received."""
        process = MockControlModeProcess()
        process.add_line("%window-add @1")
        process.add_line("%window-add @2")
        process.add_line("%window-add @3")
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()
        events = []
        for _ in range(3):
            event = await asyncio.wait_for(client.get_event(), timeout=1.0)
            events.append(event)
        await client.stop()

        assert events[0].window_id == "@1"
        assert events[1].window_id == "@2"
        assert events[2].window_id == "@3"

    @pytest.mark.asyncio
    async def test_get_event_blocks_until_available(self):
        """get_event() blocks until an event is available."""
        process = MockControlModeProcess()
        client = ControlModeClient(session_id="$0", process=process)

        await client.start()

        # This should timeout since no events
        with pytest.raises(asyncio.TimeoutError):
            await asyncio.wait_for(client.get_event(), timeout=0.1)

        await client.stop()


class TestRealControlModeProcess:
    """Test the real ControlModeProcess implementation."""

    @pytest.mark.asyncio
    async def test_real_process_not_running_initially(self):
        """Real process is not running before start."""
        from ras.control_mode import ControlModeProcess

        process = ControlModeProcess(session_id="$0", tmux_path="tmux")
        assert not process.is_running()
