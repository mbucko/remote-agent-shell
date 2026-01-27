"""Handler for tmux protocol messages."""

import logging
from typing import Any, Optional

from .session_registry import SessionRegistry
from .tmux import TmuxService

logger = logging.getLogger(__name__)


class TmuxHandler:
    """Handles tmux-related protocol messages.

    Uses TmuxService for tmux operations and SessionRegistry for
    attached session state.
    """

    def __init__(
        self,
        tmux_service: TmuxService,
        session_registry: SessionRegistry,
    ):
        """Initialize TmuxHandler.

        Args:
            tmux_service: Service for tmux operations.
            session_registry: Registry for attached sessions.
        """
        self._tmux_service = tmux_service
        self._session_registry = session_registry

    async def list_sessions(self) -> list[dict[str, Any]]:
        """List all tmux sessions.

        Returns:
            List of session info dicts with keys: id, name, windows, attached.
        """
        sessions = await self._tmux_service.list_sessions()
        return [
            {
                "id": s.id,
                "name": s.name,
                "windows": s.windows,
                "attached": s.attached,
            }
            for s in sessions
        ]

    async def list_windows(self, session_id: str) -> list[dict[str, Any]]:
        """List windows in a session.

        Args:
            session_id: Session ID (e.g., "$0").

        Returns:
            List of window info dicts with keys: id, name, active.
        """
        windows = await self._tmux_service.list_windows(session_id)
        return [
            {
                "id": w.id,
                "name": w.name,
                "active": w.active,
            }
            for w in windows
        ]

    async def send_keys(
        self, session_id: str, keys: str, literal: bool = True
    ) -> None:
        """Send keys to a session.

        Args:
            session_id: Session ID (e.g., "$0").
            keys: Keys to send.
            literal: If True, send as literal text.
        """
        await self._tmux_service.send_keys(session_id, keys, literal=literal)

    async def resize_session(
        self, session_id: str, rows: int, cols: int
    ) -> None:
        """Resize session terminal.

        Args:
            session_id: Session ID (e.g., "$0").
            rows: Number of rows.
            cols: Number of columns.
        """
        await self._tmux_service.resize_session(session_id, rows=rows, cols=cols)

    async def switch_window(self, session_id: str, window_id: str) -> None:
        """Switch to a window in a session.

        Args:
            session_id: Session ID (e.g., "$0").
            window_id: Window ID (e.g., "@1").
        """
        await self._tmux_service.switch_window(session_id, window_id)

    async def capture(self, session_id: str, lines: int = 100) -> str:
        """Capture pane content.

        Args:
            session_id: Session ID (e.g., "$0").
            lines: Number of lines to capture.

        Returns:
            Captured content as string.
        """
        return await self._tmux_service.capture_pane(session_id, lines=lines)

    async def get_session_size(self, session_id: str) -> dict[str, int]:
        """Get terminal size of a session.

        Args:
            session_id: Session ID (e.g., "$0").

        Returns:
            Dict with keys: rows, cols.
        """
        rows, cols = await self._tmux_service.get_session_size(session_id)
        return {"rows": rows, "cols": cols}
