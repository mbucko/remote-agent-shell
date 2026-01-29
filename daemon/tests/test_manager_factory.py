"""Tests for ManagerFactory.

These tests verify that ManagerFactory correctly creates and wires
managers with their dependencies.
"""

from unittest.mock import AsyncMock, Mock

import pytest

from ras.manager_factory import (
    ManagerFactory,
    ManagerDependencies,
    Managers,
)
from ras.adapters import SessionProviderAdapter, TerminalEventSender
from ras.terminal.manager import TerminalManager


class TestManagerFactory:
    """Tests for ManagerFactory."""

    def test_creates_terminal_manager(self):
        """Factory creates a TerminalManager instance."""
        # Setup dependencies
        mock_config = Mock()
        mock_connection_manager = Mock()
        mock_session_manager = Mock()
        mock_session_manager._sessions = {}

        mock_tmux = Mock()

        deps = ManagerDependencies(
            config=mock_config,
            connection_manager=mock_connection_manager,
            session_manager=mock_session_manager,
            tmux_service=mock_tmux,
        )

        # Create managers
        factory = ManagerFactory()
        managers = factory.create(deps)

        # Verify TerminalManager was created
        assert managers.terminal is not None
        assert isinstance(managers.terminal, TerminalManager)

    def test_terminal_manager_has_session_provider(self):
        """TerminalManager is wired with SessionProviderAdapter."""
        mock_config = Mock()
        mock_connection_manager = Mock()
        mock_session_manager = Mock()
        mock_session_manager._sessions = {"sess-1": Mock(tmux_name="ras-test", status="ACTIVE")}

        mock_tmux = Mock()

        deps = ManagerDependencies(
            config=mock_config,
            connection_manager=mock_connection_manager,
            session_manager=mock_session_manager,
            tmux_service=mock_tmux,
        )

        factory = ManagerFactory()
        managers = factory.create(deps)

        # The terminal manager should be able to look up sessions
        # via its session provider (which wraps session_manager)
        session_info = managers.terminal._sessions.get_session("sess-1")
        assert session_info is not None
        assert session_info["tmux_name"] == "ras-test"

    def test_terminal_manager_has_event_sender(self):
        """TerminalManager is wired with TerminalEventSender."""
        mock_config = Mock()
        mock_connection_manager = Mock()
        mock_connection_manager.get_connection = Mock(return_value=None)
        mock_session_manager = Mock()
        mock_session_manager._sessions = {}

        mock_tmux = Mock()

        deps = ManagerDependencies(
            config=mock_config,
            connection_manager=mock_connection_manager,
            session_manager=mock_session_manager,
            tmux_service=mock_tmux,
        )

        factory = ManagerFactory()
        managers = factory.create(deps)

        # The terminal manager should have send_event callback
        # that uses the connection manager
        assert managers.terminal._send_event is not None

    def test_handles_no_session_manager(self):
        """Factory returns None for terminal if no session manager provided."""
        mock_config = Mock()
        mock_connection_manager = Mock()
        mock_tmux = Mock()

        deps = ManagerDependencies(
            config=mock_config,
            connection_manager=mock_connection_manager,
            session_manager=None,
            tmux_service=mock_tmux,
        )

        factory = ManagerFactory()
        managers = factory.create(deps)

        # Terminal manager requires session manager
        assert managers.terminal is None

    def test_handles_no_tmux_service(self):
        """Factory returns None for terminal if no tmux service provided."""
        mock_config = Mock()
        mock_connection_manager = Mock()
        mock_session_manager = Mock()
        mock_session_manager._sessions = {}

        deps = ManagerDependencies(
            config=mock_config,
            connection_manager=mock_connection_manager,
            session_manager=mock_session_manager,
            tmux_service=None,
        )

        factory = ManagerFactory()
        managers = factory.create(deps)

        # Terminal manager requires tmux service
        assert managers.terminal is None

    def test_clipboard_manager_is_none_by_default(self):
        """Clipboard manager is None by default (not yet implemented)."""
        mock_config = Mock()
        mock_connection_manager = Mock()
        mock_session_manager = Mock()
        mock_session_manager._sessions = {}
        mock_tmux = Mock()

        deps = ManagerDependencies(
            config=mock_config,
            connection_manager=mock_connection_manager,
            session_manager=mock_session_manager,
            tmux_service=mock_tmux,
        )

        factory = ManagerFactory()
        managers = factory.create(deps)

        assert managers.clipboard is None

    def test_manager_dependencies_dataclass(self):
        """ManagerDependencies holds all required fields."""
        mock_config = Mock()
        mock_connection_manager = Mock()
        mock_session_manager = Mock()
        mock_tmux = Mock()

        deps = ManagerDependencies(
            config=mock_config,
            connection_manager=mock_connection_manager,
            session_manager=mock_session_manager,
            tmux_service=mock_tmux,
        )

        assert deps.config is mock_config
        assert deps.connection_manager is mock_connection_manager
        assert deps.session_manager is mock_session_manager
        assert deps.tmux_service is mock_tmux

    def test_managers_dataclass(self):
        """Managers dataclass holds created managers."""
        mock_terminal = Mock()

        managers = Managers(terminal=mock_terminal, clipboard=None)

        assert managers.terminal is mock_terminal
        assert managers.clipboard is None
