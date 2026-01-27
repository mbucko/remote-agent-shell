"""Tests for AttachedSession."""

import asyncio
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from ras.attached_session import AttachedSession
from ras.protocols import OutputEvent, WindowAddEvent, ExitEvent


class MockControlModeClient:
    """Mock control mode client for testing."""

    def __init__(self, session_id: str):
        self.session_id = session_id
        self.events: list = []
        self._event_index = 0
        self._started = False
        self._stopped = False

    def add_event(self, event):
        """Add event to be returned by get_event."""
        self.events.append(event)

    async def start(self):
        """Start the mock client."""
        self._started = True

    async def stop(self):
        """Stop the mock client."""
        self._stopped = True

    async def get_event(self):
        """Get next event."""
        while self._event_index >= len(self.events):
            await asyncio.sleep(0.01)  # Wait for more events
        event = self.events[self._event_index]
        self._event_index += 1
        return event


class MockOutputStreamer:
    """Mock output streamer for testing."""

    def __init__(self):
        self.writes: list[bytes] = []
        self._started = False
        self._stopped = False

    async def start(self):
        """Start the mock streamer."""
        self._started = True

    async def stop(self):
        """Stop the mock streamer."""
        self._stopped = True

    def write(self, data: bytes):
        """Write data."""
        self.writes.append(data)

    async def flush(self):
        """Flush (no-op for mock)."""
        pass


class TestAttachedSessionCreation:
    """Test AttachedSession creation."""

    def test_create_with_session_id(self):
        """Can create AttachedSession with session_id."""
        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
        )
        assert session.session_id == "$0"

    def test_create_with_injected_components(self):
        """Can inject control client and streamer for testing."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        assert session._control_client is client
        assert session._output_streamer is streamer


class TestAttachedSessionLifecycle:
    """Test AttachedSession lifecycle management."""

    @pytest.mark.asyncio
    async def test_context_manager_starts_components(self):
        """Context manager starts control client and streamer."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()
        client.add_event(ExitEvent(session_id="$0"))

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        async with session:
            assert client._started
            assert streamer._started

    @pytest.mark.asyncio
    async def test_context_manager_stops_components_on_exit(self):
        """Context manager stops components on exit."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()
        client.add_event(ExitEvent(session_id="$0"))

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        async with session:
            pass

        assert client._stopped
        assert streamer._stopped

    @pytest.mark.asyncio
    async def test_context_manager_stops_on_exception(self):
        """Context manager stops components even on exception."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        with pytest.raises(ValueError):
            async with session:
                raise ValueError("test error")

        assert client._stopped
        assert streamer._stopped


class TestAttachedSessionEventProcessing:
    """Test AttachedSession event processing."""

    @pytest.mark.asyncio
    async def test_output_events_routed_to_streamer(self):
        """OutputEvents are written to the output streamer."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()

        client.add_event(OutputEvent(session_id="$0", pane_id="%0", data=b"hello"))
        client.add_event(OutputEvent(session_id="$0", pane_id="%0", data=b"world"))
        client.add_event(ExitEvent(session_id="$0"))

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        async with session:
            await session.wait()

        assert b"hello" in streamer.writes
        assert b"world" in streamer.writes

    @pytest.mark.asyncio
    async def test_exit_event_terminates_session(self):
        """ExitEvent causes session to terminate cleanly."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()
        client.add_event(ExitEvent(session_id="$0"))

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        # Should complete without hanging
        async with asyncio.timeout(1.0):
            async with session:
                await session.wait()

    @pytest.mark.asyncio
    async def test_window_events_are_ignored(self):
        """Window events don't cause errors."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()

        client.add_event(WindowAddEvent(session_id="$0", window_id="@1"))
        client.add_event(ExitEvent(session_id="$0"))

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        # Should complete without errors
        async with session:
            await session.wait()


class TestAttachedSessionManualControl:
    """Test manual start/stop without context manager."""

    @pytest.mark.asyncio
    async def test_manual_start_stop(self):
        """Can manually start and stop session."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()
        client.add_event(ExitEvent(session_id="$0"))

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        await session.start()
        assert client._started
        assert streamer._started

        await session.stop()
        assert client._stopped
        assert streamer._stopped

    @pytest.mark.asyncio
    async def test_stop_is_idempotent(self):
        """Calling stop multiple times is safe."""
        client = MockControlModeClient("$0")
        streamer = MockOutputStreamer()
        client.add_event(ExitEvent(session_id="$0"))

        session = AttachedSession(
            session_id="$0",
            output_callback=AsyncMock(),
            control_client=client,
            output_streamer=streamer,
        )

        await session.start()
        await session.stop()
        await session.stop()  # Should not raise
