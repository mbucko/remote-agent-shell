"""Integration tests for manager flows.

These tests verify the full flow through managers using:
- Real ManagerFactory
- Real adapters
- Real TerminalManager
- Mocked external boundaries (tmux CLI, connections)
"""

import asyncio
from unittest.mock import AsyncMock, Mock, MagicMock, patch

import pytest

from ras.manager_factory import ManagerFactory, ManagerDependencies, Managers
from ras.adapters import SessionProviderAdapter, TerminalEventSender
from ras.terminal.manager import TerminalManager
from ras.proto.ras import (
    TerminalCommand,
    AttachTerminal,
    DetachTerminal,
    TerminalInput,
    TerminalEvent,
    RasEvent,
)
from ras.sessions.manager import SessionData, SessionStatus


class MockTmuxExecutor:
    """Mock tmux executor for integration tests."""

    def __init__(self):
        self.send_keys_calls: list[tuple] = []
        self.send_keys_result: str | None = None

    async def send_keys(
        self, tmux_name: str, keys: bytes, literal: bool = True
    ) -> None:
        """Record send_keys call."""
        self.send_keys_calls.append((tmux_name, keys, literal))


class MockSessionManager:
    """Mock session manager for integration tests."""

    def __init__(self, sessions: dict[str, SessionData] | None = None):
        self._sessions = sessions or {}


class MockConnection:
    """Mock connection for integration tests."""

    def __init__(self):
        self.sent_data: list[bytes] = []
        self.send_async = AsyncMock(side_effect=self._record_send)

    async def _record_send(self, data: bytes) -> None:
        self.sent_data.append(data)

    async def send(self, data: bytes) -> None:
        await self.send_async(data)


class MockConnectionManager:
    """Mock connection manager for integration tests."""

    def __init__(self):
        self.connections: dict[str, MockConnection] = {}
        self.broadcast_data: list[bytes] = []

    def add_connection(self, device_id: str) -> MockConnection:
        """Add a mock connection."""
        conn = MockConnection()
        self.connections[device_id] = conn
        return conn

    def get_connection(self, device_id: str):
        """Get connection by ID."""
        return self.connections.get(device_id)

    async def broadcast(self, data: bytes) -> None:
        """Record broadcast data."""
        self.broadcast_data.append(data)


class TestAttachTerminalFlow:
    """Integration tests for terminal attach flow."""

    @pytest.mark.asyncio
    async def test_attach_sends_attached_event(self):
        """Full flow: Attach command → TerminalManager → Attached event sent."""
        # Setup session
        session = SessionData(
            id="abcd12345678",
            tmux_name="ras-claude-project",
            display_name="claude-project",
            directory="/home/user/project",
            agent="claude",
            created_at=1000,
            last_activity_at=1000,
            status=SessionStatus.ACTIVE,
        )
        session_manager = MockSessionManager({"abcd12345678": session})

        # Setup connection
        connection_manager = MockConnectionManager()
        conn = connection_manager.add_connection("device-1")

        # Setup tmux
        tmux = MockTmuxExecutor()

        # Create managers via factory
        deps = ManagerDependencies(
            config=Mock(),
            connection_manager=connection_manager,
            session_manager=session_manager,
            tmux_service=tmux,
        )
        factory = ManagerFactory()
        managers = factory.create(deps)

        # Create attach command
        command = TerminalCommand(
            attach=AttachTerminal(session_id="abcd12345678", from_sequence=0)
        )

        # Handle command (with mocked output capture)
        with patch.object(managers.terminal, "_captures", {}):
            with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
                mock_capture = AsyncMock()
                mock_capture.start = AsyncMock()
                mock_capture_class.return_value = mock_capture

                await managers.terminal.handle_command("device-1", command)

        # Wait for async send to complete
        await asyncio.sleep(0.02)

        # Verify attached event was sent
        assert len(conn.sent_data) >= 1

        # Parse the sent event
        sent_event = RasEvent().parse(conn.sent_data[0])
        assert sent_event.terminal is not None
        assert sent_event.terminal.attached is not None
        assert sent_event.terminal.attached.session_id == "abcd12345678"


class TestTerminalInputFlow:
    """Integration tests for terminal input flow."""

    @pytest.mark.asyncio
    async def test_input_calls_tmux_send_keys(self):
        """Full flow: Input command → TerminalManager → TmuxService.send_keys."""
        # Setup session
        session = SessionData(
            id="abcd12345678",
            tmux_name="ras-claude-project",
            display_name="claude-project",
            directory="/home/user/project",
            agent="claude",
            created_at=1000,
            last_activity_at=1000,
            status=SessionStatus.ACTIVE,
        )
        session_manager = MockSessionManager({"abcd12345678": session})

        # Setup connection
        connection_manager = MockConnectionManager()
        connection_manager.add_connection("device-1")

        # Setup tmux
        tmux = MockTmuxExecutor()

        # Create managers
        deps = ManagerDependencies(
            config=Mock(),
            connection_manager=connection_manager,
            session_manager=session_manager,
            tmux_service=tmux,
        )
        factory = ManagerFactory()
        managers = factory.create(deps)

        # Pre-attach the connection to the session
        managers.terminal._attachments["abcd12345678"] = {"device-1"}

        # Create input command
        command = TerminalCommand(
            input=TerminalInput(session_id="abcd12345678", data=b"ls -la\n")
        )

        # Handle command
        await managers.terminal.handle_command("device-1", command)

        # Verify send_keys was called with tmux_name (not session_id)
        assert len(tmux.send_keys_calls) == 1
        tmux_name, keys, literal = tmux.send_keys_calls[0]
        assert tmux_name == "ras-claude-project"
        assert b"ls -la" in keys


class TestDetachTerminalFlow:
    """Integration tests for terminal detach flow."""

    @pytest.mark.asyncio
    async def test_detach_sends_detached_event(self):
        """Full flow: Detach command → TerminalManager → Detached event sent."""
        # Setup session
        session = SessionData(
            id="abcd12345678",
            tmux_name="ras-claude-project",
            display_name="claude-project",
            directory="/home/user/project",
            agent="claude",
            created_at=1000,
            last_activity_at=1000,
            status=SessionStatus.ACTIVE,
        )
        session_manager = MockSessionManager({"abcd12345678": session})

        # Setup connection
        connection_manager = MockConnectionManager()
        conn = connection_manager.add_connection("device-1")

        # Setup tmux
        tmux = MockTmuxExecutor()

        # Create managers
        deps = ManagerDependencies(
            config=Mock(),
            connection_manager=connection_manager,
            session_manager=session_manager,
            tmux_service=tmux,
        )
        factory = ManagerFactory()
        managers = factory.create(deps)

        # Pre-attach the connection
        managers.terminal._attachments["abcd12345678"] = {"device-1"}

        # Create detach command
        command = TerminalCommand(
            detach=DetachTerminal(session_id="abcd12345678")
        )

        # Handle command
        await managers.terminal.handle_command("device-1", command)

        # Wait for async send
        await asyncio.sleep(0.02)

        # Verify detached event was sent
        assert len(conn.sent_data) >= 1

        sent_event = RasEvent().parse(conn.sent_data[0])
        assert sent_event.terminal is not None
        assert sent_event.terminal.detached is not None
        assert sent_event.terminal.detached.session_id == "abcd12345678"


class TestCrossManagerSessionLookup:
    """Integration tests for cross-manager session lookup."""

    @pytest.mark.asyncio
    async def test_terminal_manager_looks_up_session_via_adapter(self):
        """TerminalManager uses SessionProviderAdapter to look up session info."""
        # Setup session with specific tmux_name
        session = SessionData(
            id="xyz456789012",
            tmux_name="ras-aider-myapp",
            display_name="aider-myapp",
            directory="/home/user/myapp",
            agent="aider",
            created_at=2000,
            last_activity_at=2000,
            status=SessionStatus.ACTIVE,
        )
        session_manager = MockSessionManager({"xyz456789012": session})

        # Setup connection
        connection_manager = MockConnectionManager()
        connection_manager.add_connection("device-2")

        # Setup tmux
        tmux = MockTmuxExecutor()

        # Create managers
        deps = ManagerDependencies(
            config=Mock(),
            connection_manager=connection_manager,
            session_manager=session_manager,
            tmux_service=tmux,
        )
        factory = ManagerFactory()
        managers = factory.create(deps)

        # Verify the session provider adapter works
        # This is what TerminalManager uses internally
        session_info = managers.terminal._sessions.get_session("xyz456789012")
        assert session_info is not None
        assert session_info["tmux_name"] == "ras-aider-myapp"
        assert session_info["status"] == SessionStatus.ACTIVE


class TestErrorHandling:
    """Integration tests for error handling through the stack."""

    @pytest.mark.asyncio
    async def test_attach_to_missing_session_sends_error(self):
        """Attach to non-existent session sends error event."""
        # Empty session manager
        session_manager = MockSessionManager({})

        # Setup connection
        connection_manager = MockConnectionManager()
        conn = connection_manager.add_connection("device-1")

        # Setup tmux
        tmux = MockTmuxExecutor()

        # Create managers
        deps = ManagerDependencies(
            config=Mock(),
            connection_manager=connection_manager,
            session_manager=session_manager,
            tmux_service=tmux,
        )
        factory = ManagerFactory()
        managers = factory.create(deps)

        # Try to attach to non-existent session
        command = TerminalCommand(
            attach=AttachTerminal(session_id="missing12345", from_sequence=0)
        )

        await managers.terminal.handle_command("device-1", command)

        # Wait for async send
        await asyncio.sleep(0.02)

        # Verify error event was sent
        assert len(conn.sent_data) >= 1

        sent_event = RasEvent().parse(conn.sent_data[0])
        assert sent_event.terminal is not None
        assert sent_event.terminal.error is not None
        assert sent_event.terminal.error.session_id == "missing12345"
        assert sent_event.terminal.error.error_code == "SESSION_NOT_FOUND"

    @pytest.mark.asyncio
    async def test_input_without_attachment_sends_error(self):
        """Input without being attached sends error event."""
        # Setup session
        session = SessionData(
            id="abcd12345678",
            tmux_name="ras-test",
            display_name="test",
            directory="/tmp",
            agent="test",
            created_at=1000,
            last_activity_at=1000,
            status=SessionStatus.ACTIVE,
        )
        session_manager = MockSessionManager({"abcd12345678": session})

        # Setup connection
        connection_manager = MockConnectionManager()
        conn = connection_manager.add_connection("device-1")

        # Setup tmux
        tmux = MockTmuxExecutor()

        # Create managers
        deps = ManagerDependencies(
            config=Mock(),
            connection_manager=connection_manager,
            session_manager=session_manager,
            tmux_service=tmux,
        )
        factory = ManagerFactory()
        managers = factory.create(deps)

        # Try to send input without being attached
        command = TerminalCommand(
            input=TerminalInput(session_id="abcd12345678", data=b"hello")
        )

        await managers.terminal.handle_command("device-1", command)

        # Wait for async send
        await asyncio.sleep(0.02)

        # Verify error event was sent
        assert len(conn.sent_data) >= 1

        sent_event = RasEvent().parse(conn.sent_data[0])
        assert sent_event.terminal is not None
        assert sent_event.terminal.error is not None
        assert sent_event.terminal.error.error_code == "NOT_ATTACHED"
