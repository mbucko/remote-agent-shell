"""Factory for creating and wiring managers.

This module provides a centralized factory for creating all manager
instances with properly wired dependencies. This enables:
- Testability: Dependencies can be easily mocked
- Visibility: All wiring happens in one place
- Correctness: Ensures managers are properly initialized
"""

import logging
from dataclasses import dataclass
from typing import Any, Optional

from ras.adapters import SessionProviderAdapter, TerminalEventSender
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
        clipboard = None  # Not yet implemented

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
