"""Registry for managing attached tmux sessions."""

from typing import Any, Optional


class SessionRegistry:
    """Thread-safe registry for attached tmux sessions.

    Stores active AttachedSession objects and provides access by session_id.
    This is a simple state container - business logic belongs elsewhere.
    """

    def __init__(self):
        """Initialize empty registry."""
        self._sessions: dict[str, Any] = {}

    def add(self, session: Any) -> None:
        """Add or replace a session in the registry.

        Args:
            session: AttachedSession to add. Must have session_id attribute.
        """
        self._sessions[session.session_id] = session

    def get(self, session_id: str) -> Optional[Any]:
        """Get session by ID.

        Args:
            session_id: Session ID (e.g., "$0").

        Returns:
            AttachedSession if found, None otherwise.
        """
        return self._sessions.get(session_id)

    def remove(self, session_id: str) -> Optional[Any]:
        """Remove and return session by ID.

        Args:
            session_id: Session ID (e.g., "$0").

        Returns:
            Removed AttachedSession if found, None otherwise.
        """
        return self._sessions.pop(session_id, None)

    def list_all(self) -> list[Any]:
        """Get list of all sessions.

        Returns:
            List of all AttachedSession objects.
        """
        return list(self._sessions.values())

    def __len__(self) -> int:
        """Return number of sessions in registry."""
        return len(self._sessions)

    def __contains__(self, session_id: str) -> bool:
        """Check if session exists in registry."""
        return session_id in self._sessions
