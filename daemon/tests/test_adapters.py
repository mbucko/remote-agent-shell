"""Tests for manager adapters.

These tests verify that adapters correctly bridge between
managers and the protocols they implement.
"""

import asyncio
from unittest.mock import AsyncMock, Mock, MagicMock

import pytest

from ras.adapters import (
    SessionProviderAdapter,
    TerminalEventSender,
    NotificationBroadcaster,
)
from ras.proto.ras import RasEvent, TerminalEvent, TerminalOutput


class TestSessionProviderAdapter:
    """Tests for SessionProviderAdapter."""

    def test_returns_dict_for_existing_session(self):
        """get_session returns dict with tmux_name and status for existing session."""
        # Setup mock session manager with a session
        mock_session = Mock()
        mock_session.tmux_name = "ras-claude-project"
        mock_session.status = "ACTIVE"

        mock_session_manager = Mock()
        mock_session_manager._sessions = {"session-123": mock_session}

        # Create adapter
        adapter = SessionProviderAdapter(mock_session_manager)

        # Get session
        result = adapter.get_session("session-123")

        # Verify
        assert result is not None
        assert result["tmux_name"] == "ras-claude-project"
        assert result["status"] == "ACTIVE"

    def test_returns_none_for_missing_session(self):
        """get_session returns None for missing session."""
        mock_session_manager = Mock()
        mock_session_manager._sessions = {}

        adapter = SessionProviderAdapter(mock_session_manager)

        result = adapter.get_session("nonexistent")

        assert result is None

    def test_handles_killing_status(self):
        """get_session passes through KILLING status correctly."""
        mock_session = Mock()
        mock_session.tmux_name = "ras-test"
        mock_session.status = "KILLING"

        mock_session_manager = Mock()
        mock_session_manager._sessions = {"session-456": mock_session}

        adapter = SessionProviderAdapter(mock_session_manager)

        result = adapter.get_session("session-456")

        assert result is not None
        assert result["status"] == "KILLING"

    def test_returns_display_name_if_available(self):
        """get_session includes display_name if session has it."""
        mock_session = Mock()
        mock_session.tmux_name = "ras-claude-project"
        mock_session.status = "ACTIVE"
        mock_session.display_name = "My Project"

        mock_session_manager = Mock()
        mock_session_manager._sessions = {"session-789": mock_session}

        adapter = SessionProviderAdapter(mock_session_manager)

        result = adapter.get_session("session-789")

        assert result is not None
        assert result.get("display_name") == "My Project"


class TestTerminalEventSender:
    """Tests for TerminalEventSender."""

    @pytest.mark.asyncio
    async def test_sends_event_to_connection(self):
        """send wraps event in RasEvent and sends to connection."""
        # Setup mock connection with send method
        mock_connection = Mock()
        mock_connection.send = AsyncMock()

        mock_connection_manager = Mock()
        mock_connection_manager.get_connection = Mock(return_value=mock_connection)

        # Create sender
        sender = TerminalEventSender(mock_connection_manager)

        # Create a terminal event
        event = TerminalEvent(
            output=TerminalOutput(session_id="sess-1", data=b"hello", sequence=1)
        )

        # Send
        sender.send("device-123", event)

        # Verify get_connection was called
        mock_connection_manager.get_connection.assert_called_once_with("device-123")

        # Wait for background task to complete
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

        # Verify send was called
        mock_connection.send.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_sends_event_async(self):
        """send correctly sends to connection asynchronously."""
        # Setup mock connection
        mock_connection = Mock()
        mock_connection.send = AsyncMock()

        mock_connection_manager = Mock()
        mock_connection_manager.get_connection = Mock(return_value=mock_connection)

        sender = TerminalEventSender(mock_connection_manager)

        event = TerminalEvent(
            output=TerminalOutput(session_id="sess-1", data=b"hello", sequence=1)
        )

        # Send and wait for the async task
        sender.send("device-123", event)

        # Wait for background task to complete
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

        # Verify send was called with RasEvent-wrapped data
        assert mock_connection.send.await_count == 1
        call_args = mock_connection.send.call_args
        sent_data = call_args[0][0]

        # Verify it's a valid RasEvent with terminal field
        parsed = RasEvent().parse(sent_data)
        assert parsed.terminal is not None
        assert parsed.terminal.output.session_id == "sess-1"

    def test_handles_missing_connection_gracefully(self):
        """send does not raise when connection not found."""
        mock_connection_manager = Mock()
        mock_connection_manager.get_connection = Mock(return_value=None)

        sender = TerminalEventSender(mock_connection_manager)

        event = TerminalEvent(
            output=TerminalOutput(session_id="sess-1", data=b"hello", sequence=1)
        )

        # Should not raise
        sender.send("unknown-device", event)

        # get_connection was called
        mock_connection_manager.get_connection.assert_called_once_with("unknown-device")

    @pytest.mark.asyncio
    async def test_handles_send_error_gracefully(self):
        """send catches and logs errors from connection.send()."""
        mock_connection = Mock()
        mock_connection.send = AsyncMock(side_effect=Exception("Network error"))

        mock_connection_manager = Mock()
        mock_connection_manager.get_connection = Mock(return_value=mock_connection)

        sender = TerminalEventSender(mock_connection_manager)

        event = TerminalEvent(
            output=TerminalOutput(session_id="sess-1", data=b"hello", sequence=1)
        )

        # Should not raise
        sender.send("device-123", event)

        # Wait for background task to complete
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

        # Verify send was attempted
        assert mock_connection.send.await_count == 1


class TestNotificationBroadcaster:
    """Tests for NotificationBroadcaster."""

    @pytest.mark.asyncio
    async def test_broadcasts_to_all_connections(self):
        """broadcast sends data to all connections via connection manager."""
        mock_connection_manager = Mock()
        mock_connection_manager.broadcast = AsyncMock()

        broadcaster = NotificationBroadcaster(mock_connection_manager)

        await broadcaster.broadcast(b"notification-data")

        mock_connection_manager.broadcast.assert_awaited_once_with(b"notification-data")

    @pytest.mark.asyncio
    async def test_handles_broadcast_error_gracefully(self):
        """broadcast catches errors from connection manager."""
        mock_connection_manager = Mock()
        mock_connection_manager.broadcast = AsyncMock(
            side_effect=Exception("Broadcast failed")
        )

        broadcaster = NotificationBroadcaster(mock_connection_manager)

        # Should not raise
        await broadcaster.broadcast(b"notification-data")

        mock_connection_manager.broadcast.assert_awaited_once()
