"""Attached session for managing tmux control mode connection."""

import asyncio
import logging
from types import TracebackType
from typing import Awaitable, Callable, Optional, Type

from .control_mode import ControlModeClient
from .output_streamer import OutputStreamer
from .protocols import OutputEvent, ExitEvent

logger = logging.getLogger(__name__)


class AttachedSession:
    """Manages an attached tmux session.

    Owns ControlModeClient and OutputStreamer, handling their lifecycle.
    Can be used as an async context manager for clean resource management.
    """

    def __init__(
        self,
        session_id: str,
        output_callback: Callable[[bytes], Awaitable[None]],
        control_client: Optional[ControlModeClient] = None,
        output_streamer: Optional[OutputStreamer] = None,
        tmux_path: str = "tmux",
        throttle_ms: int = 50,
    ):
        """Initialize attached session.

        Args:
            session_id: tmux session ID (e.g., "$0").
            output_callback: Async callback for sending output to client.
            control_client: Control mode client (for DI/testing).
            output_streamer: Output streamer (for DI/testing).
            tmux_path: Path to tmux binary.
            throttle_ms: Output throttle interval in milliseconds.
        """
        self.session_id = session_id
        self._output_callback = output_callback
        self._tmux_path = tmux_path

        # Create or use injected components
        self._control_client = control_client or ControlModeClient(
            session_id=session_id,
            tmux_path=tmux_path,
        )
        self._output_streamer = output_streamer or OutputStreamer(
            callback=output_callback,
            throttle_ms=throttle_ms,
        )

        self._event_task: Optional[asyncio.Task] = None
        self._running = False

    async def __aenter__(self) -> "AttachedSession":
        """Enter context manager - start session."""
        await self.start()
        return self

    async def __aexit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc_val: Optional[BaseException],
        exc_tb: Optional[TracebackType],
    ) -> None:
        """Exit context manager - stop session."""
        await self.stop()

    async def start(self) -> None:
        """Start the attached session."""
        self._running = True
        await self._control_client.start()
        await self._output_streamer.start()
        self._event_task = asyncio.create_task(self._event_loop())

    async def stop(self) -> None:
        """Stop the attached session."""
        if not self._running:
            return

        self._running = False

        if self._event_task:
            self._event_task.cancel()
            try:
                await self._event_task
            except asyncio.CancelledError:
                pass
            self._event_task = None

        await self._output_streamer.stop()
        await self._control_client.stop()

    async def wait(self) -> None:
        """Wait for session to complete (ExitEvent or error)."""
        if self._event_task:
            await self._event_task

    async def _event_loop(self) -> None:
        """Process events from control mode client."""
        try:
            while self._running:
                event = await self._control_client.get_event()

                if isinstance(event, OutputEvent):
                    self._output_streamer.write(event.data)
                elif isinstance(event, ExitEvent):
                    logger.info(f"Session {self.session_id} exited")
                    break
                # Other events (window add/close/renamed) are logged but not acted on
                else:
                    logger.debug(f"Session {self.session_id} event: {event}")

        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.error(f"Event loop error for session {self.session_id}: {e}")
