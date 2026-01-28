"""End-to-end tests for daemon orchestration.

Tests the full message flow from connection through to response,
with mocked external dependencies (WebRTC, tmux).
"""

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.config import Config, DaemonConfig
from ras.connection_manager import Connection
from ras.daemon import Daemon
from ras.device_store import PairedDevice
from ras.message_dispatcher import MessageDispatcher
from ras.proto.ras import (
    InitialState,
    ListSessionsCommand,
    Ping,
    Pong,
    RasCommand,
    RasEvent,
    Session,
    SessionCommand,
    SessionEvent,
    SessionListEvent,
    SessionStatus,
)


class MockPeer:
    """Mock peer connection for E2E testing."""

    def __init__(self):
        self._message_handler = None
        self._close_handler = None
        self._closed = False
        self.sent_data = []

    def on_message(self, handler):
        self._message_handler = handler

    def on_close(self, handler):
        self._close_handler = handler

    async def send(self, data: bytes):
        if self._closed:
            raise Exception("Connection closed")
        self.sent_data.append(data)

    async def close(self):
        self._closed = True
        if self._close_handler:
            self._close_handler()

    def receive(self, data: bytes):
        """Simulate receiving data."""
        if self._message_handler:
            self._message_handler(data)


class MockCodec:
    """Mock codec that passes through data."""

    def encode(self, data: bytes) -> bytes:
        return data

    def decode(self, data: bytes) -> bytes:
        return data


@pytest.fixture
def config(tmp_path: Path) -> Config:
    """Create test config."""
    config = Config()
    config.daemon = DaemonConfig(
        devices_file=str(tmp_path / "devices.json"),
        sessions_file=str(tmp_path / "sessions.json"),
        send_timeout=5.0,
        handler_timeout=5.0,
        keepalive_interval=60.0,  # Long interval for tests
    )
    config.port = 0
    return config


# ====================
# Connection Lifecycle Tests (E2E-CL)
# ====================


class TestConnectionLifecycle:
    """E2E tests for connection lifecycle."""

    @pytest.mark.asyncio
    async def test_e2e_cl01_new_device_connection(self, config):
        """E2E-CL01: New device connects and receives initial state."""
        session_manager = AsyncMock()
        session_manager.list_sessions = AsyncMock(
            return_value=SessionEvent(list=SessionListEvent(sessions=[]))
        )
        session_manager.get_agents = AsyncMock(
            return_value=MagicMock(agents=MagicMock(agents=[]))
        )

        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()

        # Add device via pairing (normal flow)
        await daemon._device_store.add_device(
            device_id="new_device_123",
            device_name="Test Phone",
            master_secret=b"\x00" * 32,
        )

        peer = MockPeer()
        codec = MockCodec()

        # Simulate device connecting (already paired)
        await daemon.on_new_connection(
            device_id="new_device_123",
            device_name="Test Phone",
            peer=peer,
            codec=codec,
        )

        # Should have sent initial state
        assert len(peer.sent_data) == 1
        event = RasEvent().parse(peer.sent_data[0])
        assert event.initial_state is not None

        # Device should be stored
        assert daemon._device_store.is_paired("new_device_123")

    @pytest.mark.asyncio
    async def test_e2e_cl02_returning_device_connection(self, config):
        """E2E-CL02: Previously paired device reconnects."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        # Add existing device
        device = PairedDevice(
            device_id="returning_device",
            name="Old Phone",
            master_secret=b"\x00" * 32,
            paired_at="2024-01-01T00:00:00Z",
        )
        await daemon._device_store.add(device)

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="returning_device",
            device_name="Old Phone",
            peer=peer,
            codec=codec,
        )

        # Should update last_seen
        updated = daemon._device_store.get("returning_device")
        assert updated.last_seen is not None

    @pytest.mark.asyncio
    async def test_e2e_cl03_replace_existing_connection(self, config):
        """E2E-CL03: Same device reconnects, old connection closed."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        peer1 = MockPeer()
        peer2 = MockPeer()
        codec = MockCodec()

        # First connection
        await daemon.on_new_connection(
            device_id="device1",
            device_name="Phone",
            peer=peer1,
            codec=codec,
        )

        # Second connection from same device
        await daemon.on_new_connection(
            device_id="device1",
            device_name="Phone",
            peer=peer2,
            codec=codec,
        )

        # Allow async tasks to run
        await asyncio.sleep(0.01)

        # Old connection should be closed
        assert peer1._closed is True

        # New connection should be active
        conn = daemon._connection_manager.get_connection("device1")
        assert conn.peer is peer2

    @pytest.mark.asyncio
    async def test_e2e_cl04_connection_disconnect_cleanup(self, config):
        """E2E-CL04: Connection closes, cleanup performed."""
        terminal_manager = AsyncMock()
        daemon = Daemon(config=config, terminal_manager=terminal_manager)
        await daemon._initialize_stores()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="device1",
            device_name="Phone",
            peer=peer,
            codec=codec,
        )

        # Simulate disconnect
        await peer.close()
        await asyncio.sleep(0.01)

        # Terminal manager should have been notified
        terminal_manager.on_connection_closed.assert_called_with("device1")


# ====================
# Message Flow Tests (E2E-MF)
# ====================


class TestMessageFlow:
    """E2E tests for message flow."""

    @pytest.mark.asyncio
    async def test_e2e_mf01_session_list_request_response(self, config):
        """E2E-MF01: Phone requests session list, receives response."""
        session_manager = AsyncMock()
        session_manager.handle_command = AsyncMock(
            return_value=SessionEvent(
                list=SessionListEvent(
                    sessions=[
                        Session(
                            id="sess1",
                            tmux_name="ras-claude-test",
                            display_name="test",
                            directory="/tmp",
                            agent="claude",
                            status=SessionStatus.ACTIVE,
                        )
                    ]
                )
            )
        )
        session_manager.list_sessions = AsyncMock(
            return_value=SessionEvent(list=SessionListEvent(sessions=[]))
        )
        session_manager.get_agents = AsyncMock(
            return_value=MagicMock(agents=MagicMock(agents=[]))
        )

        daemon = Daemon(config=config, session_manager=session_manager)
        daemon._register_handlers()
        await daemon._initialize_stores()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="device1",
            device_name="Phone",
            peer=peer,
            codec=codec,
        )

        # Clear initial state message
        peer.sent_data.clear()

        # Send session list request
        cmd = RasCommand(session=SessionCommand(list=ListSessionsCommand()))
        peer.receive(bytes(cmd))

        # Allow async handler to run
        await asyncio.sleep(0.1)

        # Should have received session list broadcast
        assert len(peer.sent_data) >= 1

    @pytest.mark.asyncio
    async def test_e2e_mf02_ping_pong(self, config):
        """E2E-MF02: Phone sends ping, receives pong."""
        daemon = Daemon(config=config)
        daemon._register_handlers()
        await daemon._initialize_stores()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="device1",
            device_name="Phone",
            peer=peer,
            codec=codec,
        )

        # Clear initial state message
        peer.sent_data.clear()

        # Send ping
        cmd = RasCommand(ping=Ping(timestamp=1234567890))
        peer.receive(bytes(cmd))

        # Allow async handler to run
        await asyncio.sleep(0.1)

        # Should have received pong
        assert len(peer.sent_data) == 1
        event = RasEvent().parse(peer.sent_data[0])
        assert event.pong is not None
        assert event.pong.timestamp == 1234567890

    @pytest.mark.asyncio
    async def test_e2e_mf03_unknown_message_type(self, config):
        """E2E-MF03: Unknown message type is logged but doesn't crash."""
        daemon = Daemon(config=config)
        daemon._register_handlers()
        await daemon._initialize_stores()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="device1",
            device_name="Phone",
            peer=peer,
            codec=codec,
        )

        # Send malformed data
        peer.receive(b"not a valid protobuf")

        # Allow async handler to run
        await asyncio.sleep(0.1)

        # Daemon should still be functional
        conn = daemon._connection_manager.get_connection("device1")
        assert conn is not None


# ====================
# Broadcast Tests (E2E-BC)
# ====================


class TestBroadcast:
    """E2E tests for event broadcasting."""

    @pytest.mark.asyncio
    async def test_e2e_bc01_session_event_broadcast_to_all(self, config):
        """E2E-BC01: Session event broadcast to all connected phones."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        peers = [MockPeer() for _ in range(3)]
        codec = MockCodec()

        for i, peer in enumerate(peers):
            await daemon.on_new_connection(
                device_id=f"device{i}",
                device_name=f"Phone {i}",
                peer=peer,
                codec=codec,
            )
            peer.sent_data.clear()

        # Broadcast session event
        event = SessionEvent(list=SessionListEvent(sessions=[]))
        await daemon._broadcast_session_event(event)

        # All phones should receive it
        for peer in peers:
            assert len(peer.sent_data) == 1

    @pytest.mark.asyncio
    async def test_e2e_bc02_broadcast_partial_failure(self, config):
        """E2E-BC02: One failing connection doesn't affect others."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        peer1 = MockPeer()
        peer2 = MockPeer()
        peer3 = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection("device1", "Phone 1", peer1, codec)
        await daemon.on_new_connection("device2", "Phone 2", peer2, codec)
        await daemon.on_new_connection("device3", "Phone 3", peer3, codec)

        peer1.sent_data.clear()
        peer3.sent_data.clear()

        # Close peer2 after connection (simulating connection loss)
        peer2._closed = True

        # Broadcast
        event = SessionEvent(list=SessionListEvent(sessions=[]))
        await daemon._broadcast_session_event(event)

        # Working phones should receive it
        assert len(peer1.sent_data) == 1
        assert len(peer3.sent_data) == 1


# ====================
# Shutdown Tests (E2E-SD)
# ====================


class TestShutdown:
    """E2E tests for daemon shutdown."""

    @pytest.mark.asyncio
    async def test_e2e_sd01_graceful_shutdown_closes_connections(self, config):
        """E2E-SD01: Shutdown closes all connections."""
        terminal_manager = AsyncMock()
        daemon = Daemon(config=config, terminal_manager=terminal_manager)
        await daemon._initialize_stores()

        peers = [MockPeer() for _ in range(3)]
        codec = MockCodec()

        for i, peer in enumerate(peers):
            await daemon.on_new_connection(
                device_id=f"device{i}",
                device_name=f"Phone {i}",
                peer=peer,
                codec=codec,
            )

        # Shutdown
        await daemon._shutdown()

        # All connections should be closed
        for peer in peers:
            assert peer._closed is True

    @pytest.mark.asyncio
    async def test_e2e_sd02_shutdown_cleans_up_managers(self, config):
        """E2E-SD02: Shutdown cleans up all managers."""
        terminal_manager = AsyncMock()
        daemon = Daemon(config=config, terminal_manager=terminal_manager)
        await daemon._initialize_stores()

        await daemon._shutdown()

        terminal_manager.shutdown.assert_called_once()


# ====================
# Concurrency Tests (E2E-CC)
# ====================


class TestConcurrency:
    """E2E tests for concurrent operations."""

    @pytest.mark.asyncio
    async def test_e2e_cc01_concurrent_connections(self, config):
        """E2E-CC01: Multiple simultaneous connections."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        codec = MockCodec()

        async def connect(i):
            peer = MockPeer()
            await daemon.on_new_connection(
                device_id=f"device{i}",
                device_name=f"Phone {i}",
                peer=peer,
                codec=codec,
            )
            return peer

        # Connect 10 devices concurrently
        peers = await asyncio.gather(*[connect(i) for i in range(10)])

        assert len(daemon._connection_manager) == 10

    @pytest.mark.asyncio
    async def test_e2e_cc02_concurrent_messages(self, config):
        """E2E-CC02: Multiple messages processed concurrently."""
        call_count = 0

        async def slow_handler(device_id, message):
            nonlocal call_count
            await asyncio.sleep(0.01)
            call_count += 1

        daemon = Daemon(config=config)
        daemon._dispatcher.register("ping", slow_handler)
        await daemon._initialize_stores()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection("device1", "Phone", peer, codec)

        # Send multiple pings rapidly
        for _ in range(5):
            cmd = RasCommand(ping=Ping(timestamp=1234))
            peer.receive(bytes(cmd))

        # Wait for all handlers
        await asyncio.sleep(0.2)

        assert call_count == 5


# ====================
# Error Handling Tests (E2E-ER)
# ====================


class TestErrorHandling:
    """E2E tests for error handling."""

    @pytest.mark.asyncio
    async def test_e2e_er01_handler_exception_isolated(self, config):
        """E2E-ER01: Handler exception doesn't crash daemon."""
        session_manager = AsyncMock()
        session_manager.handle_command = AsyncMock(
            side_effect=Exception("Handler error")
        )

        daemon = Daemon(config=config, session_manager=session_manager)
        daemon._register_handlers()
        await daemon._initialize_stores()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection("device1", "Phone", peer, codec)

        # Send session command that will fail
        cmd = RasCommand(session=SessionCommand(list=ListSessionsCommand()))
        peer.receive(bytes(cmd))

        await asyncio.sleep(0.1)

        # Daemon should still be functional
        conn = daemon._connection_manager.get_connection("device1")
        assert conn is not None

    @pytest.mark.asyncio
    async def test_e2e_er02_malformed_message(self, config):
        """E2E-ER02: Malformed message is handled gracefully."""
        daemon = Daemon(config=config)
        daemon._register_handlers()
        await daemon._initialize_stores()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection("device1", "Phone", peer, codec)

        # Send garbage
        peer.receive(b"\x00\x01\x02\x03")

        await asyncio.sleep(0.1)

        # Connection should still be active
        conn = daemon._connection_manager.get_connection("device1")
        assert conn is not None
