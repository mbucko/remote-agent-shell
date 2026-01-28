"""Notification dispatcher with deduplication and cooldown."""

import logging
import time
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Optional, Protocol

from ras.proto.ras import (
    TerminalEvent,
    TerminalNotification,
    NotificationType as ProtoNotificationType,
)

from .types import MatchResult, NotificationConfig, NotificationType

logger = logging.getLogger(__name__)


@dataclass
class CooldownState:
    """Tracks notification cooldown for a session."""

    last_notification: float = 0.0
    last_pattern: str = ""
    last_type: str = ""


class WebRTCBroadcaster(Protocol):
    """Protocol for broadcasting messages via WebRTC."""

    async def broadcast(self, message: bytes) -> None:
        """Broadcast message to all connected peers."""
        ...


class NotificationDispatcher:
    """Dispatches notifications with deduplication and cooldown.

    Features:
    - Per-session cooldown tracking
    - Deduplication of same pattern within cooldown period
    - Different patterns/types still trigger within cooldown
    - Broadcast to all connected WebRTC peers

    Usage:
        dispatcher = NotificationDispatcher(broadcaster, config)

        # When pattern match found:
        sent = await dispatcher.dispatch(session_id, session_name, match)
    """

    def __init__(
        self,
        broadcast_fn: Callable[[bytes], Awaitable[None]],
        config: Optional[NotificationConfig] = None,
    ):
        """Initialize NotificationDispatcher.

        Args:
            broadcast_fn: Async function to broadcast bytes to all peers.
            config: Notification configuration (uses defaults if None).
        """
        self._broadcast = broadcast_fn
        self._config = config or NotificationConfig.default()
        self._cooldowns: dict[str, CooldownState] = {}

        logger.debug(
            "NotificationDispatcher initialized: cooldown=%.1fs",
            self._config.cooldown_seconds,
        )

    async def dispatch(
        self,
        session_id: str,
        session_name: str,
        match: MatchResult,
    ) -> bool:
        """Dispatch notification if not duplicate/in cooldown.

        Args:
            session_id: Session that triggered the notification.
            session_name: Display name for the session.
            match: The pattern match result.

        Returns:
            True if notification was sent, False if suppressed.
        """
        now = time.time()

        # Get or create cooldown state for this session
        if session_id not in self._cooldowns:
            self._cooldowns[session_id] = CooldownState()

        state = self._cooldowns[session_id]

        # Check cooldown
        time_since_last = now - state.last_notification
        if time_since_last < self._config.cooldown_seconds:
            # Within cooldown - suppress if same type
            # (This prevents multiple patterns matching same text from spamming)
            if state.last_type == match.type.value:
                logger.debug(
                    "Notification suppressed (same type within cooldown): "
                    "session=%s, type=%s, pattern=%s",
                    session_id,
                    match.type.value,
                    match.pattern[:30],
                )
                return False

        # Create and send notification
        notification = self._create_notification(session_id, session_name, match)
        try:
            await self._broadcast(bytes(notification))
            logger.info(
                "Notification sent: session=%s, type=%s, snippet=%s",
                session_id,
                match.type.value,
                match.snippet[:40],
            )
        except Exception as e:
            logger.error("Failed to broadcast notification: %s", e)
            return False

        # Update cooldown state
        state.last_notification = now
        state.last_pattern = match.pattern
        state.last_type = match.type.value

        return True

    def _create_notification(
        self,
        session_id: str,
        session_name: str,
        match: MatchResult,
    ) -> TerminalEvent:
        """Create notification protobuf message.

        Args:
            session_id: Session ID.
            session_name: Display name for the session.
            match: Match result.

        Returns:
            TerminalEvent with notification payload.
        """
        # Map internal type to proto type
        type_map = {
            NotificationType.APPROVAL: ProtoNotificationType.APPROVAL_NEEDED,
            NotificationType.COMPLETION: ProtoNotificationType.TASK_COMPLETED,
            NotificationType.ERROR: ProtoNotificationType.ERROR_DETECTED,
        }

        # Create title based on type
        title_map = {
            NotificationType.APPROVAL: f"{session_name}: Approval needed",
            NotificationType.COMPLETION: f"{session_name}: Task completed",
            NotificationType.ERROR: f"{session_name}: Error detected",
        }

        return TerminalEvent(
            notification=TerminalNotification(
                session_id=session_id,
                type=type_map[match.type],
                title=title_map[match.type],
                body=match.snippet,
                snippet=match.snippet,
                timestamp=int(time.time() * 1000),
            )
        )

    def clear_session(self, session_id: str) -> None:
        """Clear cooldown state for a session.

        Call this when a session is killed.

        Args:
            session_id: Session to clear.
        """
        if session_id in self._cooldowns:
            del self._cooldowns[session_id]
            logger.debug("Cleared cooldown state for session: %s", session_id)

    def get_cooldown_remaining(self, session_id: str) -> float:
        """Get remaining cooldown time for a session.

        Args:
            session_id: Session to check.

        Returns:
            Seconds remaining in cooldown, or 0 if not in cooldown.
        """
        state = self._cooldowns.get(session_id)
        if not state:
            return 0.0

        elapsed = time.time() - state.last_notification
        remaining = self._config.cooldown_seconds - elapsed

        return max(0.0, remaining)

    @property
    def session_count(self) -> int:
        """Number of sessions with cooldown state."""
        return len(self._cooldowns)
