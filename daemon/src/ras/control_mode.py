"""Control mode client for tmux -CC integration."""

import asyncio
import base64
import logging
from typing import Optional

from .protocols import (
    ControlModeProcessProtocol,
    TmuxEvent,
    OutputEvent,
    WindowAddEvent,
    WindowCloseEvent,
    WindowRenamedEvent,
    SessionChangedEvent,
    ExitEvent,
)

logger = logging.getLogger(__name__)


class ControlModeProcess:
    """Real subprocess for tmux control mode.

    Manages the tmux -CC subprocess lifecycle.
    """

    def __init__(self, session_id: str, tmux_path: str = "tmux"):
        """Initialize control mode process.

        Args:
            session_id: tmux session ID to attach to.
            tmux_path: Path to tmux binary.
        """
        self._session_id = session_id
        self._tmux_path = tmux_path
        self._process: Optional[asyncio.subprocess.Process] = None

    async def start(self) -> None:
        """Start the control mode subprocess."""
        self._process = await asyncio.create_subprocess_exec(
            self._tmux_path,
            "-CC",
            "attach-session",
            "-t",
            self._session_id,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            stdin=asyncio.subprocess.PIPE,
        )

    async def stop(self) -> None:
        """Stop the control mode subprocess."""
        if self._process:
            self._process.terminate()
            try:
                await asyncio.wait_for(self._process.wait(), timeout=5.0)
            except asyncio.TimeoutError:
                self._process.kill()
            self._process = None

    async def readline(self) -> bytes:
        """Read a line from stdout."""
        if not self._process or not self._process.stdout:
            return b""
        return await self._process.stdout.readline()

    def is_running(self) -> bool:
        """Check if process is still running."""
        return self._process is not None and self._process.returncode is None


class ControlModeClient:
    """Client for tmux control mode (-CC).

    Parses tmux control mode notifications and produces typed events
    via an async queue.
    """

    def __init__(
        self,
        session_id: str,
        process: Optional[ControlModeProcessProtocol] = None,
        tmux_path: str = "tmux",
    ):
        """Initialize control mode client.

        Args:
            session_id: tmux session ID.
            process: Control mode process (for DI/testing).
            tmux_path: Path to tmux binary.
        """
        self.session_id = session_id
        self._tmux_path = tmux_path
        self._process = process or ControlModeProcess(session_id, tmux_path)
        self._event_queue: asyncio.Queue[TmuxEvent] = asyncio.Queue()
        self._reader_task: Optional[asyncio.Task] = None
        self._in_block = False  # Track %begin/%end blocks

    async def start(self) -> None:
        """Start control mode and begin processing events."""
        await self._process.start()
        self._reader_task = asyncio.create_task(self._read_loop())

    async def stop(self) -> None:
        """Stop control mode processing."""
        if self._reader_task:
            self._reader_task.cancel()
            try:
                await self._reader_task
            except asyncio.CancelledError:
                pass
            self._reader_task = None
        await self._process.stop()

    async def get_event(self) -> TmuxEvent:
        """Get next event from queue.

        Returns:
            Next TmuxEvent.

        Raises:
            asyncio.CancelledError: If cancelled while waiting.
        """
        return await self._event_queue.get()

    async def _read_loop(self) -> None:
        """Read and parse control mode output."""
        try:
            while self._process.is_running():
                line = await self._process.readline()
                if not line:
                    break

                line_str = line.decode().rstrip("\n")
                event = self._parse_line(line_str)
                if event:
                    await self._event_queue.put(event)

        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.error(f"Error in control mode read loop: {e}")

    def _parse_line(self, line: str) -> Optional[TmuxEvent]:
        """Parse a control mode line into an event.

        Args:
            line: Raw line from tmux control mode.

        Returns:
            TmuxEvent if line is a notification, None otherwise.
        """
        if not line.startswith("%"):
            # Not a notification, might be response data in a block
            return None

        parts = line.split(" ", 2)
        notification = parts[0]

        # Track %begin/%end blocks (command responses)
        if notification == "%begin":
            self._in_block = True
            return None
        if notification == "%end" or notification == "%error":
            self._in_block = False
            return None

        # Ignore lines inside blocks
        if self._in_block:
            return None

        # Parse notifications
        if notification == "%output":
            return self._parse_output(parts)
        elif notification == "%window-add":
            return self._parse_window_add(parts)
        elif notification == "%window-close":
            return self._parse_window_close(parts)
        elif notification == "%window-renamed":
            return self._parse_window_renamed(parts)
        elif notification == "%session-changed":
            return self._parse_session_changed(parts)
        elif notification == "%exit":
            return ExitEvent(session_id=self.session_id)

        return None

    def _parse_output(self, parts: list[str]) -> OutputEvent:
        """Parse %output notification.

        Format: %output %<pane_id> <base64_data>
        """
        pane_id = parts[1] if len(parts) > 1 else "%0"
        data_b64 = parts[2] if len(parts) > 2 else ""

        try:
            data = base64.b64decode(data_b64) if data_b64.strip() else b""
        except Exception:
            data = b""

        return OutputEvent(
            session_id=self.session_id,
            pane_id=pane_id,
            data=data,
        )

    def _parse_window_add(self, parts: list[str]) -> WindowAddEvent:
        """Parse %window-add notification.

        Format: %window-add @<window_id>
        """
        window_id = parts[1] if len(parts) > 1 else "@0"
        return WindowAddEvent(
            session_id=self.session_id,
            window_id=window_id,
        )

    def _parse_window_close(self, parts: list[str]) -> WindowCloseEvent:
        """Parse %window-close notification.

        Format: %window-close @<window_id>
        """
        window_id = parts[1] if len(parts) > 1 else "@0"
        return WindowCloseEvent(
            session_id=self.session_id,
            window_id=window_id,
        )

    def _parse_window_renamed(self, parts: list[str]) -> WindowRenamedEvent:
        """Parse %window-renamed notification.

        Format: %window-renamed @<window_id> <name>
        """
        window_id = parts[1] if len(parts) > 1 else "@0"
        name = parts[2] if len(parts) > 2 else ""
        return WindowRenamedEvent(
            session_id=self.session_id,
            window_id=window_id,
            name=name,
        )

    def _parse_session_changed(self, parts: list[str]) -> SessionChangedEvent:
        """Parse %session-changed notification.

        Format: %session-changed $<session_id> <name>
        """
        name = parts[2] if len(parts) > 2 else ""
        return SessionChangedEvent(
            session_id=self.session_id,
            name=name,
        )
