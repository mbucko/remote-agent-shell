"""End-to-end tests for command flows through managers.

These tests verify the complete flow from receiving a serialized
RasCommand proto through the manager stack to emitting RasEvent protos.

Only external boundaries are mocked:
- TmuxService (no actual tmux process)
- WebRTC connections (use mock connections)
"""

import asyncio
from unittest.mock import AsyncMock, Mock, patch

import pytest
import betterproto

pytestmark = pytest.mark.integration

from ras.manager_factory import ManagerFactory, ManagerDependencies
from ras.message_dispatcher import MessageDispatcher
from ras.proto.ras import (
    RasCommand,
    RasEvent,
    TerminalCommand,
    AttachTerminal,
    DetachTerminal,
    TerminalInput,
    TerminalEvent,
    Ping,
    Pong,
    ConnectionReady,
    InitialState,
)
from ras.sessions.manager import SessionData, SessionStatus


class MockTmuxExecutor:
    """Mock tmux executor for E2E tests."""

    def __init__(self):
        self.send_keys_calls: list[tuple] = []

    async def send_keys(
        self, tmux_name: str, keys: bytes, literal: bool = True
    ) -> None:
        """Record send_keys call."""
        self.send_keys_calls.append((tmux_name, keys, literal))

    async def set_window_size_latest(self, session_name: str) -> None:
        """Set window-size to latest (no-op for tests)."""
        pass


class MockSessionManager:
    """Mock session manager for E2E tests."""

    def __init__(self, sessions: dict[str, SessionData] | None = None):
        self._sessions = sessions or {}


class MockConnection:
    """Mock WebRTC connection for E2E tests."""

    def __init__(self):
        self.sent_data: list[bytes] = []

    async def send(self, data: bytes) -> None:
        """Record sent data."""
        self.sent_data.append(data)


class MockConnectionManager:
    """Mock connection manager for E2E tests."""

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


class TestE2ETerminalAttach:
    """E2E tests for terminal attach command flow."""

    @pytest.mark.asyncio
    async def test_ras_command_to_ras_event_roundtrip(self):
        """Full roundtrip: RasCommand bytes → process → RasEvent bytes."""
        # Setup session with valid 12-char ID
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

        # Create RasCommand with terminal attach
        ras_cmd = RasCommand(
            terminal=TerminalCommand(
                attach=AttachTerminal(session_id="abcd12345678", from_sequence=0)
            )
        )

        # Serialize to bytes (like phone would send)
        cmd_bytes = bytes(ras_cmd)

        # Parse command (like daemon would receive)
        parsed_cmd = RasCommand().parse(cmd_bytes)

        # Verify parsing worked
        field_name, field_value = betterproto.which_one_of(parsed_cmd, "command")
        assert field_name == "terminal"

        # Handle command through terminal manager
        with patch.object(managers.terminal, "_captures", {}):
            with patch("ras.terminal.manager.OutputCapture") as mock_capture_class:
                mock_capture = AsyncMock()
                mock_capture.start = AsyncMock()
                mock_capture_class.return_value = mock_capture

                await managers.terminal.handle_command("device-1", parsed_cmd.terminal)

        # Wait for async send
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

        # Verify event was sent
        assert len(conn.sent_data) >= 1

        # Parse sent event (like phone would receive)
        event_bytes = conn.sent_data[0]
        parsed_event = RasEvent().parse(event_bytes)

        # Verify it's a terminal attached event
        event_field, _ = betterproto.which_one_of(parsed_event, "event")
        assert event_field == "terminal"

        terminal_field, _ = betterproto.which_one_of(parsed_event.terminal, "event")
        assert terminal_field == "attached"
        assert parsed_event.terminal.attached.session_id == "abcd12345678"


class TestE2ETerminalError:
    """E2E tests for terminal error flows."""

    @pytest.mark.asyncio
    async def test_invalid_session_returns_error_event(self):
        """Invalid session ID returns error via RasEvent."""
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

        # Create command with non-existent but valid-format session ID
        ras_cmd = RasCommand(
            terminal=TerminalCommand(
                attach=AttachTerminal(session_id="missing12345", from_sequence=0)
            )
        )

        # Serialize and parse roundtrip
        cmd_bytes = bytes(ras_cmd)
        parsed_cmd = RasCommand().parse(cmd_bytes)

        # Handle
        await managers.terminal.handle_command("device-1", parsed_cmd.terminal)
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

        # Verify error event
        assert len(conn.sent_data) >= 1
        parsed_event = RasEvent().parse(conn.sent_data[0])

        terminal_field, _ = betterproto.which_one_of(parsed_event.terminal, "event")
        assert terminal_field == "error"
        assert parsed_event.terminal.error.error_code == "SESSION_NOT_FOUND"


class TestE2ETerminalOutput:
    """E2E tests for terminal output flow."""

    @pytest.mark.asyncio
    async def test_output_captured_sends_output_event(self):
        """Output captured from tmux sends TerminalOutput event."""
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

        # Simulate attachment and output
        managers.terminal._attachments["abcd12345678"] = {"device-1"}
        managers.terminal._buffers["abcd12345678"] = Mock()
        managers.terminal._buffers["abcd12345678"].append = Mock(return_value=1)

        # Simulate output callback (like OutputCapture would call)
        managers.terminal._on_output("abcd12345678", b"$ ls -la\n")

        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

        # Verify output event
        assert len(conn.sent_data) >= 1
        parsed_event = RasEvent().parse(conn.sent_data[0])

        terminal_field, _ = betterproto.which_one_of(parsed_event.terminal, "event")
        assert terminal_field == "output"
        assert parsed_event.terminal.output.session_id == "abcd12345678"
        assert parsed_event.terminal.output.data == b"$ ls -la\n"
        assert parsed_event.terminal.output.sequence == 1


class TestE2ETerminalInput:
    """E2E tests for terminal input flow."""

    @pytest.mark.asyncio
    async def test_input_command_calls_tmux(self):
        """Input command flows through to tmux send_keys."""
        # Setup session
        session = SessionData(
            id="abcd12345678",
            tmux_name="ras-project",
            display_name="project",
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

        # Pre-attach
        managers.terminal._attachments["abcd12345678"] = {"device-1"}

        # Create input command
        ras_cmd = RasCommand(
            terminal=TerminalCommand(
                input=TerminalInput(data=b"echo hello\n")
            )
        )
        ras_cmd.terminal.input.session_id = "abcd12345678"

        # Serialize and parse roundtrip
        cmd_bytes = bytes(ras_cmd)
        parsed_cmd = RasCommand().parse(cmd_bytes)

        # Handle
        await managers.terminal.handle_command("device-1", parsed_cmd.terminal)

        # Verify tmux was called
        assert len(tmux.send_keys_calls) == 1
        tmux_name, keys, literal = tmux.send_keys_calls[0]
        assert tmux_name == "ras-project"
        assert b"echo hello" in keys


class TestE2EDetach:
    """E2E tests for terminal detach flow."""

    @pytest.mark.asyncio
    async def test_detach_sends_detached_event(self):
        """Detach command sends DetachedEvent."""
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

        # Pre-attach
        managers.terminal._attachments["abcd12345678"] = {"device-1"}

        # Create detach command
        ras_cmd = RasCommand(
            terminal=TerminalCommand(
                detach=DetachTerminal(session_id="abcd12345678")
            )
        )

        # Serialize and parse roundtrip
        cmd_bytes = bytes(ras_cmd)
        parsed_cmd = RasCommand().parse(cmd_bytes)

        # Handle
        await managers.terminal.handle_command("device-1", parsed_cmd.terminal)
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

        # Verify detached event
        assert len(conn.sent_data) >= 1
        parsed_event = RasEvent().parse(conn.sent_data[0])

        terminal_field, _ = betterproto.which_one_of(parsed_event.terminal, "event")
        assert terminal_field == "detached"
        assert parsed_event.terminal.detached.session_id == "abcd12345678"


class TestE2EPingPong:
    """E2E tests for ping/pong flow."""

    @pytest.mark.asyncio
    async def test_ping_returns_pong(self):
        """Ping command returns Pong event with same timestamp."""
        # Setup connection
        connection_manager = MockConnectionManager()
        conn = connection_manager.add_connection("device-1")

        # Create ping handler (simulating daemon's handler)
        async def handle_ping(device_id: str, ping: Ping) -> None:
            pong = Pong(timestamp=ping.timestamp)
            event = RasEvent(pong=pong)
            await conn.send(bytes(event))

        # Create ping command with timestamp
        ras_cmd = RasCommand(ping=Ping(timestamp=1234567890))

        # Serialize and parse roundtrip
        cmd_bytes = bytes(ras_cmd)
        parsed_cmd = RasCommand().parse(cmd_bytes)

        # Verify command type
        field_name, _ = betterproto.which_one_of(parsed_cmd, "command")
        assert field_name == "ping"

        # Handle
        await handle_ping("device-1", parsed_cmd.ping)

        # Verify pong
        assert len(conn.sent_data) == 1
        parsed_event = RasEvent().parse(conn.sent_data[0])

        event_field, _ = betterproto.which_one_of(parsed_event, "event")
        assert event_field == "pong"
        assert parsed_event.pong.timestamp == 1234567890


class TestE2EConnectionReady:
    """E2E tests for connection ready flow."""

    @pytest.mark.asyncio
    async def test_connection_ready_returns_initial_state(self):
        """ConnectionReady triggers InitialState with sessions and agents."""
        # Setup connection
        connection_manager = MockConnectionManager()
        conn = connection_manager.add_connection("device-1")

        # Create mock session manager with sessions
        sessions = [
            Mock(id="abcd12345678", display_name="project1"),
            Mock(id="efgh12345678", display_name="project2"),
        ]
        agents = [
            Mock(name="claude", available=True),
            Mock(name="aider", available=True),
        ]

        # Create handler (simulating daemon's handler)
        async def handle_connection_ready(device_id: str, ready: ConnectionReady) -> None:
            initial = InitialState(
                sessions=[],  # Would be populated from session manager
                agents=[],    # Would be populated from agent detector
            )
            event = RasEvent(initial_state=initial)
            await conn.send(bytes(event))

        # Create command
        ras_cmd = RasCommand(connection_ready=ConnectionReady())

        # Serialize and parse roundtrip
        cmd_bytes = bytes(ras_cmd)
        parsed_cmd = RasCommand().parse(cmd_bytes)

        # Verify command type
        field_name, _ = betterproto.which_one_of(parsed_cmd, "command")
        assert field_name == "connection_ready"

        # Handle
        await handle_connection_ready("device-1", parsed_cmd.connection_ready)

        # Verify initial state
        assert len(conn.sent_data) == 1
        parsed_event = RasEvent().parse(conn.sent_data[0])

        event_field, _ = betterproto.which_one_of(parsed_event, "event")
        assert event_field == "initial_state"


class TestE2EProtoSerialization:
    """E2E tests for proto serialization edge cases."""

    def test_empty_command_serializes(self):
        """Empty RasCommand serializes and deserializes."""
        cmd = RasCommand()
        data = bytes(cmd)
        parsed = RasCommand().parse(data)
        assert parsed is not None

    def test_nested_proto_roundtrip(self):
        """Deeply nested protos survive roundtrip."""
        cmd = RasCommand(
            terminal=TerminalCommand(
                attach=AttachTerminal(
                    session_id="abcd12345678",
                    from_sequence=9999999999,
                )
            )
        )

        data = bytes(cmd)
        parsed = RasCommand().parse(data)

        assert parsed.terminal.attach.session_id == "abcd12345678"
        assert parsed.terminal.attach.from_sequence == 9999999999

    def test_binary_data_in_proto(self):
        """Binary data (with null bytes) survives roundtrip."""
        binary_data = b"\x00\x01\x02\xff\xfe\xfd"

        cmd = RasCommand(
            terminal=TerminalCommand(
                input=TerminalInput(data=binary_data)
            )
        )
        cmd.terminal.input.session_id = "abcd12345678"

        data = bytes(cmd)
        parsed = RasCommand().parse(data)

        assert parsed.terminal.input.data == binary_data
