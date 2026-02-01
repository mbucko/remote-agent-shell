"""Terminal I/O manager - main coordinator."""

import asyncio
import logging
from typing import Awaitable, Callable, Optional, Protocol

import betterproto

from ras.proto.ras import (
    OutputSkipped,
    TerminalAttached,
    TerminalCommand,
    TerminalDetached,
    TerminalError,
    TerminalEvent,
    TerminalOutput,
)
from ras.terminal.buffer import CircularBuffer
from ras.terminal.capture import OutputCapture
from ras.terminal.input import InputHandler, TmuxExecutor
from ras.terminal.validation import validate_session_id

# Optional notification support
try:
    from ras.notifications.matcher import PatternMatcher
    from ras.notifications.dispatcher import NotificationDispatcher
    from ras.notifications.types import NotificationConfig

    NOTIFICATIONS_AVAILABLE = True
except ImportError:
    NOTIFICATIONS_AVAILABLE = False
    PatternMatcher = None  # type: ignore
    NotificationDispatcher = None  # type: ignore
    NotificationConfig = None  # type: ignore

logger = logging.getLogger(__name__)


class SessionProvider(Protocol):
    """Protocol for getting session info."""

    def get_session(self, session_id: str) -> dict | None:
        """Get session info by ID.

        Args:
            session_id: The session ID to look up.

        Returns:
            Dict with 'tmux_name', 'status' fields, or None if not found.
        """
        ...


class TmuxServiceProtocol(Protocol):
    """Protocol for tmux service operations."""

    async def resize_window_to_largest(self, session_name: str) -> None:
        """Resize session window to fit the largest attached client."""
        ...


class TerminalManager:
    """Manages terminal I/O for all sessions.

    Coordinates output capture, input handling, and buffering for
    all attached terminal sessions.
    """

    def __init__(
        self,
        session_provider: SessionProvider,
        tmux_executor: TmuxExecutor,
        send_event: Callable[[str, TerminalEvent], None],
        buffer_size_kb: int = 100,
        chunk_interval_ms: int = 50,
        tmux_path: str = "tmux",
        socket_path: Optional[str] = None,
        broadcast_notification: Optional[Callable[[bytes], Awaitable[None]]] = None,
        notification_config: Optional["NotificationConfig"] = None,
        tmux_service: Optional[TmuxServiceProtocol] = None,
    ):
        """Initialize the terminal manager.

        Args:
            session_provider: Provider for session info lookup.
            tmux_executor: Executor for tmux commands.
            send_event: Callback to send events to connections.
            buffer_size_kb: Size of output buffer in KB (default 100).
            chunk_interval_ms: Interval for chunking output (default 50).
            tmux_path: Path to tmux binary.
            socket_path: Optional tmux socket path for isolated server.
            broadcast_notification: Optional async function to broadcast notifications.
            notification_config: Optional notification configuration.
            tmux_service: Optional tmux service for window operations (resize on disconnect).
        """
        self._sessions = session_provider
        self._send_event = send_event
        self._buffer_size = buffer_size_kb * 1024
        self._chunk_interval = chunk_interval_ms
        self._tmux_path = tmux_path
        self._socket_path = socket_path
        self._tmux_service = tmux_service

        self._input_handler = InputHandler(tmux_executor)

        # Per-session state
        self._captures: dict[str, OutputCapture] = {}
        self._buffers: dict[str, CircularBuffer] = {}
        # session_id -> set of connection_ids
        self._attachments: dict[str, set[str]] = {}

        # Notification support (optional)
        self._notification_matcher: Optional["PatternMatcher"] = None
        self._notification_dispatcher: Optional["NotificationDispatcher"] = None
        self._notification_matchers: dict[str, "PatternMatcher"] = {}  # Per-session

        if NOTIFICATIONS_AVAILABLE and broadcast_notification is not None:
            config = notification_config or NotificationConfig.default()
            self._notification_dispatcher = NotificationDispatcher(
                broadcast_notification, config
            )
            self._notification_config = config
            logger.info("Notification system enabled")
        else:
            self._notification_config = None
            if broadcast_notification is not None and not NOTIFICATIONS_AVAILABLE:
                logger.warning(
                    "Notification broadcast provided but notifications module not available"
                )

    def get_attached_session(self, connection_id: str) -> Optional[str]:
        """Get the session ID that a connection is attached to.

        Args:
            connection_id: The connection/device ID.

        Returns:
            Session ID if attached, None otherwise.
        """
        for session_id, connections in self._attachments.items():
            if connection_id in connections:
                return session_id
        return None

    async def handle_command(
        self,
        connection_id: str,
        command: TerminalCommand,
    ) -> None:
        """Handle a terminal command from a connection.

        Args:
            connection_id: The connection ID.
            command: The terminal command to handle.
        """
        field_name, _ = betterproto.which_one_of(command, "command")

        if field_name == "attach":
            await self._handle_attach(connection_id, command.attach)
        elif field_name == "detach":
            await self._handle_detach(connection_id, command.detach)
        elif field_name == "input":
            await self._handle_input(connection_id, command.input)

    async def _handle_attach(self, connection_id: str, attach) -> None:
        """Handle attach terminal request.

        Args:
            connection_id: The connection ID.
            attach: The attach request.
        """
        session_id = attach.session_id

        # Validate session ID
        err = validate_session_id(session_id)
        if err:
            self._send_error(connection_id, session_id, err.code, err.message)
            return

        # Get session info
        session = self._sessions.get_session(session_id)
        if not session:
            self._send_error(
                connection_id, session_id, "SESSION_NOT_FOUND", "Session not found"
            )
            return

        if session.get("status") == "KILLING":
            self._send_error(
                connection_id,
                session_id,
                "SESSION_KILLING",
                "Session is being killed",
            )
            return

        tmux_name = session["tmux_name"]

        # Initialize buffer if needed
        if session_id not in self._buffers:
            self._buffers[session_id] = CircularBuffer(self._buffer_size)

        buffer = self._buffers[session_id]

        # Start capture if not running
        if session_id not in self._captures:
            capture = OutputCapture(
                session_id=session_id,
                tmux_name=tmux_name,
                on_output=lambda data, sid=session_id: self._on_output(sid, data),
                chunk_interval_ms=self._chunk_interval,
                tmux_path=self._tmux_path,
                socket_path=self._socket_path,
            )
            try:
                await capture.start()
                self._captures[session_id] = capture
            except RuntimeError as e:
                error_msg = str(e)
                # Check if session was killed externally (tmux reports "can't find pane/session")
                if "can't find" in error_msg:
                    logger.info(
                        f"Session {session_id} no longer exists in tmux: {error_msg}"
                    )
                    self._send_error(
                        connection_id,
                        session_id,
                        "SESSION_GONE",
                        "Session no longer exists",
                    )
                else:
                    logger.error(f"Failed to start capture for {session_id}: {e}")
                    self._send_error(
                        connection_id, session_id, "PIPE_SETUP_FAILED", error_msg
                    )
                return
            except Exception as e:
                logger.error(f"Failed to start capture for {session_id}: {e}")
                self._send_error(
                    connection_id, session_id, "PIPE_SETUP_FAILED", str(e)
                )
                return

        # Track attachment
        if session_id not in self._attachments:
            self._attachments[session_id] = set()
        self._attachments[session_id].add(connection_id)

        # Send attached event
        event = TerminalEvent(
            attached=TerminalAttached(
                session_id=session_id,
                cols=80,
                rows=24,
                buffer_start_seq=buffer.start_sequence,
                current_seq=buffer.current_sequence,
            )
        )
        self._send_event(connection_id, event)

        # Send buffered output from requested sequence
        if attach.from_sequence >= 0:
            chunks, missing_from = buffer.get_from_sequence(attach.from_sequence)

            if missing_from is not None:
                # Some data was dropped
                skip_event = TerminalEvent(
                    skipped=OutputSkipped(
                        session_id=session_id,
                        from_sequence=missing_from,
                        to_sequence=buffer.start_sequence - 1,
                    )
                )
                self._send_event(connection_id, skip_event)

            # Send buffered chunks
            for chunk in chunks:
                out_event = TerminalEvent(
                    output=TerminalOutput(
                        session_id=session_id,
                        data=chunk.data,
                        sequence=chunk.sequence,
                    )
                )
                self._send_event(connection_id, out_event)

    async def _handle_detach(self, connection_id: str, detach) -> None:
        """Handle detach terminal request.

        Args:
            connection_id: The connection ID.
            detach: The detach request.
        """
        session_id = detach.session_id

        if session_id in self._attachments:
            self._attachments[session_id].discard(connection_id)

            # Stop capture and resize if no more attachments
            if not self._attachments[session_id]:
                await self._stop_capture(session_id)
                # Resize window back to local terminal size
                await self._resize_windows_to_local([session_id])

        # Send detached event
        event = TerminalEvent(
            detached=TerminalDetached(
                session_id=session_id,
                reason="user_request",
            )
        )
        self._send_event(connection_id, event)

    async def _handle_input(self, connection_id: str, input_msg) -> None:
        """Handle terminal input.

        Args:
            connection_id: The connection ID.
            input_msg: The input message.
        """
        session_id = input_msg.session_id

        # Check if attached
        if (
            session_id not in self._attachments
            or connection_id not in self._attachments[session_id]
        ):
            self._send_error(
                connection_id, session_id, "NOT_ATTACHED", "Not attached to session"
            )
            return

        # Get session info
        session = self._sessions.get_session(session_id)
        if not session:
            self._send_error(
                connection_id, session_id, "SESSION_NOT_FOUND", "Session not found"
            )
            return

        # Process input
        error_code = await self._input_handler.handle_input(
            session_id=session_id,
            tmux_name=session["tmux_name"],
            input_msg=input_msg,
        )

        if error_code:
            self._send_error(
                connection_id, session_id, error_code, f"Input failed: {error_code}"
            )

    def _on_output(self, session_id: str, data: bytes) -> None:
        """Called when output is captured from tmux.

        Args:
            session_id: The session ID.
            data: The captured output data.
        """
        buffer = self._buffers.get(session_id)
        if not buffer:
            return

        seq = buffer.append(data)

        # Broadcast to all attached connections
        connections = self._attachments.get(session_id, set())
        event = TerminalEvent(
            output=TerminalOutput(
                session_id=session_id,
                data=data,
                sequence=seq,
            )
        )

        for conn_id in connections:
            self._send_event(conn_id, event)

        # Process notifications if enabled
        if self._notification_dispatcher is not None:
            self._process_notifications(session_id, data)

    def _process_notifications(self, session_id: str, data: bytes) -> None:
        """Process terminal output for notification patterns.

        Args:
            session_id: The session ID.
            data: The captured output data.
        """
        # Get or create per-session matcher
        if session_id not in self._notification_matchers:
            self._notification_matchers[session_id] = PatternMatcher(
                self._notification_config
            )

        matcher = self._notification_matchers[session_id]
        matches = matcher.process_chunk(data)

        if not matches:
            return

        # Get session name for notification title
        session = self._sessions.get_session(session_id)
        session_name = session.get("display_name", session_id) if session else session_id

        # Dispatch notifications (async, fire-and-forget)
        for match in matches:
            asyncio.create_task(
                self._dispatch_notification(session_id, session_name, match)
            )

    async def _dispatch_notification(
        self, session_id: str, session_name: str, match
    ) -> None:
        """Dispatch a notification.

        Args:
            session_id: The session ID.
            session_name: The display name for the session.
            match: The pattern match result.
        """
        try:
            sent = await self._notification_dispatcher.dispatch(
                session_id, session_name, match
            )
            if sent:
                logger.debug(
                    "Notification sent: session=%s, type=%s",
                    session_id,
                    match.type.value,
                )
        except Exception as e:
            logger.error("Failed to dispatch notification: %s", e)

    async def _stop_capture(self, session_id: str) -> None:
        """Stop output capture for a session.

        Args:
            session_id: The session ID.
        """
        capture = self._captures.pop(session_id, None)
        if capture:
            await capture.stop()

    def _send_error(
        self, connection_id: str, session_id: str, code: str, message: str
    ) -> None:
        """Send error event to connection.

        Args:
            connection_id: The connection ID.
            session_id: The session ID.
            code: The error code.
            message: The error message.
        """
        event = TerminalEvent(
            error=TerminalError(
                session_id=session_id,
                error_code=code,
                message=message,
            )
        )
        self._send_event(connection_id, event)

    async def on_session_killed(self, session_id: str) -> None:
        """Called when a session is killed.

        Args:
            session_id: The session ID.
        """
        # Notify all attached connections
        connections = self._attachments.pop(session_id, set())
        for conn_id in connections:
            event = TerminalEvent(
                detached=TerminalDetached(
                    session_id=session_id,
                    reason="session_killed",
                )
            )
            self._send_event(conn_id, event)

        # Stop capture and clear buffer
        await self._stop_capture(session_id)
        self._buffers.pop(session_id, None)
        self._input_handler.reset_rate_limit(session_id)

        # Clear notification state for this session
        self._notification_matchers.pop(session_id, None)
        if self._notification_dispatcher is not None:
            self._notification_dispatcher.clear_session(session_id)

    async def on_connection_closed(self, connection_id: str) -> None:
        """Called when a connection closes.

        Args:
            connection_id: The connection ID.
        """
        sessions_to_resize: list[str] = []

        logger.debug(f"on_connection_closed: {connection_id}, attachments: {[(k, list(v)) for k, v in self._attachments.items()]}")

        # Remove from all attachments
        for session_id, connections in list(self._attachments.items()):
            if connection_id in connections:
                connections.discard(connection_id)
                # Track sessions this connection was attached to
                sessions_to_resize.append(session_id)
                logger.debug(f"Connection was attached to session {session_id}")
                if not connections:
                    await self._stop_capture(session_id)

        logger.debug(f"Sessions to resize: {sessions_to_resize}")

        # Resize windows back to local terminal size
        await self._resize_windows_to_local(sessions_to_resize)

    async def _resize_windows_to_local(self, session_ids: list[str]) -> None:
        """Resize terminal windows back to local client size.

        Called when a remote client disconnects to restore windows to
        the local terminal dimensions.

        Args:
            session_ids: List of session IDs to resize.
        """
        if not session_ids:
            logger.debug("No sessions to resize")
            return
        if not self._tmux_service:
            logger.warning("No tmux_service available for resize")
            return

        for session_id in session_ids:
            # Get tmux session name from session registry
            session_info = self._sessions.get_session(session_id)
            if session_info and session_info.get("tmux_name"):
                tmux_name = session_info["tmux_name"]
                logger.info(f"Resizing {tmux_name} to local size on disconnect")
                # TmuxService handles errors internally (non-fatal)
                await self._tmux_service.resize_window_to_largest(tmux_name)
            else:
                logger.warning(f"Session {session_id} not found or has no tmux_name")

    async def shutdown(self) -> None:
        """Shutdown all captures."""
        for session_id in list(self._captures.keys()):
            await self._stop_capture(session_id)
        self._buffers.clear()
        self._attachments.clear()
        self._notification_matchers.clear()

    def get_attachment_count(self, session_id: str) -> int:
        """Get number of connections attached to a session.

        Args:
            session_id: The session ID.

        Returns:
            Number of attached connections.
        """
        return len(self._attachments.get(session_id, set()))

    def is_session_attached(self, session_id: str) -> bool:
        """Check if any connection is attached to a session.

        Args:
            session_id: The session ID.

        Returns:
            True if at least one connection is attached.
        """
        return bool(self._attachments.get(session_id))
