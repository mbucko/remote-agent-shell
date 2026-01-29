"""Terminal input handling."""

import asyncio
import logging
import time
from collections import defaultdict
from typing import Protocol

import betterproto

from ras.proto.ras import KeyType, SpecialKey, TerminalInput
from ras.terminal.keys import get_key_sequence
from ras.terminal.validation import (
    RATE_LIMIT_PER_SECOND,
    validate_input_data,
    validate_session_id,
)

logger = logging.getLogger(__name__)


class TmuxExecutor(Protocol):
    """Protocol for tmux command execution."""

    async def send_keys(
        self, tmux_name: str, keys: bytes, literal: bool = True
    ) -> None:
        """Send keys to a tmux session.

        Args:
            tmux_name: The tmux session name.
            keys: The keys to send as bytes.
            literal: If True, send keys literally without interpretation.
        """
        ...

    async def resize_session(
        self, session_id: str, rows: int, cols: int
    ) -> None:
        """Resize a tmux session window.

        Args:
            session_id: The tmux session name.
            rows: Number of rows.
            cols: Number of columns.
        """
        ...


class InputHandler:
    """Handles terminal input from phone.

    Processes raw data input, special keys, and resize events.
    Includes rate limiting to prevent abuse.
    """

    def __init__(self, tmux_executor: TmuxExecutor):
        """Initialize the input handler.

        Args:
            tmux_executor: The tmux executor for sending keys.
        """
        self._tmux = tmux_executor
        self._rate_limits: dict[str, list[float]] = defaultdict(list)

    async def handle_input(
        self,
        session_id: str,
        tmux_name: str,
        input_msg: TerminalInput,
    ) -> str | None:
        """Handle terminal input.

        Args:
            session_id: The session ID.
            tmux_name: The tmux session name.
            input_msg: The terminal input message.

        Returns:
            Error code if failed, None on success.
        """
        # Validate session ID
        err = validate_session_id(session_id)
        if err:
            return err.code

        # Check rate limit
        if self._is_rate_limited(session_id):
            return "RATE_LIMITED"

        # Process input type using betterproto's which_one_of
        field_name, _ = betterproto.which_one_of(input_msg, "input")

        if field_name == "data":
            return await self._handle_data(tmux_name, input_msg.data)
        elif field_name == "special":
            return await self._handle_special(tmux_name, input_msg.special)
        elif field_name == "resize":
            return await self._handle_resize(tmux_name, input_msg.resize)

        return None

    async def _handle_data(self, tmux_name: str, data: bytes) -> str | None:
        """Handle raw data input.

        Args:
            tmux_name: The tmux session name.
            data: The raw data to send.

        Returns:
            Error code if failed, None on success.
        """
        err = validate_input_data(data)
        if err:
            return err.code

        try:
            # Use literal mode (-l) to prevent shell expansion
            await self._tmux.send_keys(tmux_name, data, literal=True)
            return None
        except Exception as e:
            logger.error(f"Failed to send keys: {e}")
            return "PIPE_ERROR"

    async def _handle_special(
        self, tmux_name: str, special: SpecialKey
    ) -> str | None:
        """Handle special key input.

        Args:
            tmux_name: The tmux session name.
            special: The special key message.

        Returns:
            Error code if failed, None on success.
        """
        seq = get_key_sequence(special.key, special.modifiers)
        if not seq:
            logger.warning(f"Unknown key type: {special.key}")
            return None  # Ignore unknown keys

        try:
            # Special keys are escape sequences, send literally
            await self._tmux.send_keys(tmux_name, seq, literal=True)
            return None
        except Exception as e:
            logger.error(f"Failed to send special key: {e}")
            return "PIPE_ERROR"

    async def _handle_resize(
        self, tmux_name: str, resize: "TerminalResize"
    ) -> str | None:
        """Handle terminal resize request.

        Args:
            tmux_name: The tmux session name.
            resize: The resize message with cols and rows.

        Returns:
            Error code if failed, None on success.
        """
        from ras.proto.ras import TerminalResize

        cols = resize.cols
        rows = resize.rows

        # Validate dimensions
        if cols < 10 or cols > 500 or rows < 5 or rows > 200:
            logger.warning(f"Invalid resize dimensions: {cols}x{rows}")
            return None  # Ignore invalid dimensions

        try:
            await self._tmux.resize_session(tmux_name, rows, cols)
            logger.info(f"Resized {tmux_name} to {cols}x{rows}")
            return None
        except Exception as e:
            logger.error(f"Failed to resize session: {e}")
            return "PIPE_ERROR"

    def _is_rate_limited(self, session_id: str) -> bool:
        """Check if session is rate limited.

        Args:
            session_id: The session ID to check.

        Returns:
            True if rate limited, False otherwise.
        """
        now = time.monotonic()
        window = self._rate_limits[session_id]

        # Remove timestamps outside 1-second window
        window[:] = [t for t in window if now - t < 1.0]

        if len(window) >= RATE_LIMIT_PER_SECOND:
            return True

        window.append(now)
        return False

    def reset_rate_limit(self, session_id: str) -> None:
        """Reset rate limit for a session.

        Args:
            session_id: The session ID to reset.
        """
        self._rate_limits.pop(session_id, None)
