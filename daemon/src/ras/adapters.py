"""Adapters for bridging managers to protocols.

These adapters implement the protocols defined in protocols.py and
wrap the actual manager implementations to provide clean interfaces.
"""

import asyncio
import logging
from typing import Any

from ras.proto.ras import RasEvent, TerminalEvent
from ras.protocols import (
    SessionProviderProtocol,
    EventSenderProtocol,
    ConnectionProviderProtocol,
)

logger = logging.getLogger(__name__)


class SessionProviderAdapter:
    """Adapts SessionManager to SessionProviderProtocol.

    Provides session lookup capability for TerminalManager.
    """

    def __init__(self, session_manager: Any):
        """Initialize adapter.

        Args:
            session_manager: The SessionManager instance to wrap.
        """
        self._manager = session_manager

    def get_session(self, session_id: str) -> dict | None:
        """Get session info by ID.

        Args:
            session_id: The session ID to look up.

        Returns:
            Dict with 'tmux_name', 'status', optionally 'display_name',
            or None if not found.
        """
        session = self._manager._sessions.get(session_id)
        if session is None:
            return None

        result = {
            "tmux_name": session.tmux_name,
            "status": session.status,
        }

        # Include display_name if available
        if hasattr(session, "display_name"):
            result["display_name"] = session.display_name

        return result


class TerminalEventSender:
    """Sends terminal events to specific connections.

    Wraps TerminalEvent in RasEvent and sends to the target connection.
    """

    def __init__(self, connection_manager: Any):
        """Initialize sender.

        Args:
            connection_manager: The ConnectionManager instance to use.
        """
        self._connections = connection_manager

    def send(self, connection_id: str, event: TerminalEvent) -> None:
        """Send terminal event to a specific connection.

        Args:
            connection_id: The device/connection ID to send to.
            event: The TerminalEvent to send.
        """
        conn = self._connections.get_connection(connection_id)
        if conn is None:
            return

        # Wrap in RasEvent and serialize
        ras_event = RasEvent(terminal=event)
        data = bytes(ras_event)

        # Schedule async send
        asyncio.create_task(self._do_send(conn, data, connection_id))

    async def _do_send(self, conn: Any, data: bytes, connection_id: str) -> None:
        """Actually send data to connection.

        Args:
            conn: The connection to send to.
            data: The serialized data to send.
            connection_id: The connection ID (for logging).
        """
        try:
            await conn.send(data)
        except Exception as e:
            logger.warning(f"Failed to send to {connection_id}: {e}")


class NotificationBroadcaster:
    """Broadcasts notifications to all connections.

    Wraps ConnectionManager.broadcast() with error handling.
    """

    def __init__(self, connection_manager: Any):
        """Initialize broadcaster.

        Args:
            connection_manager: The ConnectionManager instance to use.
        """
        self._connections = connection_manager

    async def broadcast(self, data: bytes) -> None:
        """Broadcast data to all connected devices.

        Args:
            data: Serialized data to broadcast.
        """
        try:
            await self._connections.broadcast(data)
        except Exception as e:
            logger.warning(f"Broadcast failed: {e}")
