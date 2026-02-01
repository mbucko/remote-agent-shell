"""Factory for creating and wiring managers.

This module provides a centralized factory for creating all manager
instances with properly wired dependencies. This enables:
- Testability: Dependencies can be easily mocked
- Visibility: All wiring happens in one place
- Correctness: Ensures managers are properly initialized
"""

import asyncio
import logging
from dataclasses import dataclass
from typing import Any, Optional

from ras.adapters import SessionProviderAdapter, TerminalEventSender
from ras.clipboard_manager import ClipboardManager
from ras.clipboard_types import ClipboardConfig, ClipboardMessage
from ras.proto.ras import RasEvent
from ras.terminal.manager import TerminalManager

logger = logging.getLogger(__name__)


@dataclass
class ManagerDependencies:
    """Dependencies required to create managers.

    All managers are created using these shared dependencies.
    """

    config: Any
    connection_manager: Any
    session_manager: Optional[Any] = None
    tmux_service: Optional[Any] = None


@dataclass
class Managers:
    """Container for all created manager instances."""

    terminal: Optional[TerminalManager] = None
    clipboard: Optional[Any] = None  # ClipboardManager when implemented


class ManagerFactory:
    """Factory for creating properly wired manager instances.

    Usage:
        deps = ManagerDependencies(
            config=config,
            connection_manager=connection_manager,
            session_manager=session_manager,
            tmux_service=tmux_service,
        )
        factory = ManagerFactory()
        managers = factory.create(deps)

        # Use managers.terminal, managers.clipboard, etc.
    """

    def create(self, deps: ManagerDependencies) -> Managers:
        """Create all managers with properly wired dependencies.

        Args:
            deps: The dependencies to wire into managers.

        Returns:
            Managers container with all created instances.
        """
        terminal = self._create_terminal_manager(deps)
        clipboard = self._create_clipboard_manager(deps, terminal)

        return Managers(terminal=terminal, clipboard=clipboard)

    def _create_terminal_manager(
        self, deps: ManagerDependencies
    ) -> Optional[TerminalManager]:
        """Create TerminalManager with wired adapters.

        Args:
            deps: The dependencies to use.

        Returns:
            TerminalManager instance, or None if required deps are missing.
        """
        # Require session manager and tmux service
        if deps.session_manager is None:
            logger.warning("Cannot create TerminalManager: no session manager")
            return None

        if deps.tmux_service is None:
            logger.warning("Cannot create TerminalManager: no tmux service")
            return None

        # Create adapters
        session_provider = SessionProviderAdapter(deps.session_manager)
        event_sender = TerminalEventSender(deps.connection_manager)

        # Create send_event callback that uses the adapter
        def send_event(conn_id: str, event) -> None:
            event_sender.send(conn_id, event)

        # Create TerminalManager
        terminal_manager = TerminalManager(
            session_provider=session_provider,
            tmux_executor=deps.tmux_service,
            send_event=send_event,
        )

        logger.info("TerminalManager created with wired adapters")
        return terminal_manager

    def _create_clipboard_manager(
        self,
        deps: ManagerDependencies,
        terminal_manager: Optional[TerminalManager],
    ) -> Optional[ClipboardManager]:
        """Create ClipboardManager with wired callbacks.

        Args:
            deps: The dependencies to use.
            terminal_manager: TerminalManager for session lookup.

        Returns:
            ClipboardManager instance, or None if required deps are missing.
        """
        if deps.tmux_service is None:
            logger.warning("Cannot create ClipboardManager: no tmux service")
            return None

        if terminal_manager is None:
            logger.warning("Cannot create ClipboardManager: no terminal manager")
            return None

        connection_manager = deps.connection_manager
        tmux_service = deps.tmux_service

        async def send_message(device_id: str, message: ClipboardMessage) -> None:
            """Send clipboard message to a specific device."""
            conn = connection_manager.get_connection(device_id)
            if conn is None:
                logger.warning(f"Cannot send clipboard message: device {device_id} not connected")
                return

            ras_event = RasEvent(clipboard=message)
            try:
                await conn.send(bytes(ras_event))
            except Exception as e:
                logger.warning(f"Failed to send clipboard message to {device_id}: {e}")

        def _get_tmux_session_name(device_id: str) -> str:
            """Get tmux session name for a device.

            Args:
                device_id: The device ID to look up.

            Returns:
                The tmux session name.

            Raises:
                RuntimeError: If device is not attached or session not found.
            """
            session_id = terminal_manager.get_attached_session(device_id)
            if session_id is None:
                raise RuntimeError(f"Device {device_id} is not attached to any session")

            session_data = deps.session_manager.get_session(session_id)
            if session_data is None:
                raise RuntimeError(f"Session {session_id} not found")

            return session_data.tmux_name

        async def send_keys(device_id: str, keystroke: str) -> None:
            """Send keystroke to the session the device is attached to.

            Uses tmux send-keys to send keystrokes to the session.
            """
            tmux_name = _get_tmux_session_name(device_id)
            await tmux_service.send_keys(tmux_name, keystroke, literal=False)

        async def send_image_path(device_id: str, image_path: str) -> None:
            """Type image file path into the terminal for Claude Code.

            Claude Code accepts image file paths directly. We use tmux send-keys
            to type the path into the terminal. This avoids the need for
            Accessibility permissions on macOS.

            The path is typed without @ prefix since Claude Code will prompt
            the user to confirm the image attachment.
            """
            tmux_name = _get_tmux_session_name(device_id)
            # Add a space after so user can continue typing
            await tmux_service.send_keys(tmux_name, f"{image_path} ", literal=True)

        clipboard_manager = ClipboardManager(
            config=ClipboardConfig(),
            send_message=send_message,
            send_keys=send_keys,
            send_image_path=send_image_path,
        )

        logger.info("ClipboardManager created with wired callbacks")
        return clipboard_manager
