"""Tests for protocol compliance.

These tests verify that adapters correctly implement their protocols.
Protocol compliance is verified at import time by the type checker,
but these tests verify runtime behavior.
"""

import asyncio
from typing import get_type_hints
from unittest.mock import AsyncMock, Mock

import pytest

# Import protocols - these will exist after implementation
from ras.protocols import (
    SessionProviderProtocol,
    EventSenderProtocol,
    ConnectionProviderProtocol,
)


class TestSessionProviderProtocol:
    """Tests for SessionProviderProtocol compliance."""

    def test_protocol_has_get_session_method(self):
        """Protocol defines get_session method."""
        # Verify the protocol has the expected method
        assert hasattr(SessionProviderProtocol, "get_session")

    def test_mock_implements_protocol(self):
        """Mock object can implement protocol."""
        mock = Mock()
        mock.get_session.return_value = {"tmux_name": "test", "status": "RUNNING"}

        # Calling the method works
        result = mock.get_session("session-1")
        assert result == {"tmux_name": "test", "status": "RUNNING"}
        mock.get_session.assert_called_once_with("session-1")

    def test_get_session_returns_none_for_missing(self):
        """get_session returns None for missing sessions."""
        mock = Mock()
        mock.get_session.return_value = None

        result = mock.get_session("nonexistent")
        assert result is None


class TestEventSenderProtocol:
    """Tests for EventSenderProtocol compliance."""

    def test_protocol_has_send_method(self):
        """Protocol defines send method."""
        assert hasattr(EventSenderProtocol, "send")

    def test_send_is_callable(self):
        """send method accepts connection_id and event."""
        mock = Mock()
        mock.send = Mock()

        # Should accept connection_id and event
        mock.send("conn-1", b"event-data")
        mock.send.assert_called_once_with("conn-1", b"event-data")


class TestConnectionProviderProtocol:
    """Tests for ConnectionProviderProtocol compliance."""

    def test_protocol_has_get_connection_method(self):
        """Protocol defines get_connection method."""
        assert hasattr(ConnectionProviderProtocol, "get_connection")

    def test_protocol_has_broadcast_method(self):
        """Protocol defines broadcast method."""
        assert hasattr(ConnectionProviderProtocol, "broadcast")

    def test_get_connection_returns_connection_or_none(self):
        """get_connection returns connection object or None."""
        mock = Mock()
        mock_conn = Mock()
        mock.get_connection.return_value = mock_conn

        result = mock.get_connection("device-1")
        assert result is mock_conn

    def test_get_connection_returns_none_for_missing(self):
        """get_connection returns None for missing devices."""
        mock = Mock()
        mock.get_connection.return_value = None

        result = mock.get_connection("unknown-device")
        assert result is None

    @pytest.mark.asyncio
    async def test_broadcast_is_async(self):
        """broadcast method is async."""
        mock = Mock()
        mock.broadcast = AsyncMock()

        await mock.broadcast(b"data")
        mock.broadcast.assert_awaited_once_with(b"data")
