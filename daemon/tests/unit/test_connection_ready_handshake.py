"""Tests for ConnectionReady handshake protocol.

The ConnectionReady handshake solves a race condition where:
1. Phone connects and daemon immediately sends InitialState
2. Phone's event listener isn't set up yet when InitialState arrives
3. InitialState is lost, phone thinks connection failed

Solution:
1. Daemon waits after connection is established
2. Phone sends ConnectionReady once its event listener is ready
3. Daemon receives ConnectionReady, then sends InitialState
"""

import betterproto
import pytest
from unittest.mock import AsyncMock, MagicMock

from ras.daemon import Daemon
from ras.config import Config, DaemonConfig
from ras.proto.ras import (
    ConnectionReady,
    RasCommand,
    RasEvent,
    SessionEvent,
    SessionListEvent,
)


class MockPeer:
    """Mock peer connection for testing."""

    def __init__(self):
        self._message_handler = None
        self._close_handler = None
        self.sent_data = []

    def on_message(self, handler):
        self._message_handler = handler

    def on_close(self, handler):
        self._close_handler = handler

    async def send(self, data: bytes):
        self.sent_data.append(data)

    async def close(self):
        if self._close_handler:
            self._close_handler()


class MockCodec:
    """Mock codec that passes through data."""

    def encode(self, data: bytes) -> bytes:
        return data

    def decode(self, data: bytes) -> bytes:
        return data


@pytest.fixture
def config(tmp_path):
    """Create test config."""
    config = Config()
    config.daemon = DaemonConfig(
        devices_file=str(tmp_path / "devices.json"),
        sessions_file=str(tmp_path / "sessions.json"),
    )
    config.port = 0
    return config


@pytest.fixture
def session_manager():
    """Create mock session manager."""
    manager = AsyncMock()
    manager.list_sessions = AsyncMock(
        return_value=SessionEvent(list=SessionListEvent(sessions=[]))
    )
    manager.get_agents = AsyncMock(
        return_value=MagicMock(agents=MagicMock(agents=[]))
    )
    return manager


class TestConnectionReadyHandshake:
    """Tests for the ConnectionReady handshake protocol."""

    @pytest.mark.asyncio
    async def test_initial_state_not_sent_immediately(self, config, session_manager):
        """InitialState should not be sent immediately on connection (only heartbeat)."""
        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()
        daemon._register_handlers()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="test_device",
            device_name="Test Phone",
            peer=peer,
            codec=codec,
        )

        # Only heartbeat sent, not InitialState
        assert len(peer.sent_data) == 1
        event = RasEvent().parse(peer.sent_data[0])
        assert betterproto.which_one_of(event, "event")[0] == "heartbeat"

    @pytest.mark.asyncio
    async def test_device_added_to_pending_ready(self, config, session_manager):
        """Device should be added to pending_ready set on connection."""
        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()
        daemon._register_handlers()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="test_device",
            device_name="Test Phone",
            peer=peer,
            codec=codec,
        )

        assert "test_device" in daemon._pending_ready

    @pytest.mark.asyncio
    async def test_initial_state_sent_after_connection_ready(self, config, session_manager):
        """InitialState should be sent after ConnectionReady received."""
        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()
        daemon._register_handlers()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="test_device",
            device_name="Test Phone",
            peer=peer,
            codec=codec,
        )

        # Send ConnectionReady
        cmd = RasCommand(connection_ready=ConnectionReady())
        await daemon._on_message("test_device", bytes(cmd))

        # InitialState should be sent (heartbeat + initial state = 2 messages)
        assert len(peer.sent_data) == 2
        event = RasEvent().parse(peer.sent_data[1])
        assert event.initial_state is not None

    @pytest.mark.asyncio
    async def test_device_removed_from_pending_after_ready(self, config, session_manager):
        """Device should be removed from pending_ready after ConnectionReady."""
        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()
        daemon._register_handlers()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="test_device",
            device_name="Test Phone",
            peer=peer,
            codec=codec,
        )

        assert "test_device" in daemon._pending_ready

        # Send ConnectionReady
        cmd = RasCommand(connection_ready=ConnectionReady())
        await daemon._on_message("test_device", bytes(cmd))

        assert "test_device" not in daemon._pending_ready

    @pytest.mark.asyncio
    async def test_unexpected_connection_ready_ignored(self, config, session_manager):
        """ConnectionReady from unknown device should be ignored."""
        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()
        daemon._register_handlers()

        # Send ConnectionReady without connecting first
        cmd = RasCommand(connection_ready=ConnectionReady())
        # Should not raise
        await daemon._on_message("unknown_device", bytes(cmd))

        # pending_ready should not contain the device
        assert "unknown_device" not in daemon._pending_ready

    @pytest.mark.asyncio
    async def test_duplicate_connection_ready_ignored(self, config, session_manager):
        """Duplicate ConnectionReady should be ignored."""
        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()
        daemon._register_handlers()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="test_device",
            device_name="Test Phone",
            peer=peer,
            codec=codec,
        )

        # Send ConnectionReady twice
        cmd = RasCommand(connection_ready=ConnectionReady())
        await daemon._on_message("test_device", bytes(cmd))
        await daemon._on_message("test_device", bytes(cmd))

        # InitialState should only be sent once (heartbeat + initial state = 2 messages)
        assert len(peer.sent_data) == 2

    @pytest.mark.asyncio
    async def test_pending_cleared_on_disconnect(self, config, session_manager):
        """Pending state should be cleared when connection is lost."""
        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()
        daemon._register_handlers()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="test_device",
            device_name="Test Phone",
            peer=peer,
            codec=codec,
        )

        assert "test_device" in daemon._pending_ready

        # Simulate disconnect
        await daemon._on_connection_lost("test_device")

        assert "test_device" not in daemon._pending_ready


class TestConnectionReadyTiming:
    """Tests for timing-related aspects of the handshake."""

    @pytest.mark.asyncio
    async def test_regular_commands_work_after_ready(self, config, session_manager):
        """Regular commands should work normally after ConnectionReady."""
        daemon = Daemon(config=config, session_manager=session_manager)
        await daemon._initialize_stores()
        daemon._register_handlers()

        peer = MockPeer()
        codec = MockCodec()

        await daemon.on_new_connection(
            device_id="test_device",
            device_name="Test Phone",
            peer=peer,
            codec=codec,
        )

        # Send ConnectionReady
        ready_cmd = RasCommand(connection_ready=ConnectionReady())
        await daemon._on_message("test_device", bytes(ready_cmd))

        # Clear sent data
        peer.sent_data.clear()

        # Send a ping
        from ras.proto.ras import Ping
        ping_cmd = RasCommand(ping=Ping(timestamp=12345))
        await daemon._on_message("test_device", bytes(ping_cmd))

        # Should receive pong
        assert len(peer.sent_data) == 1
        event = RasEvent().parse(peer.sent_data[0])
        assert event.pong is not None
        assert event.pong.timestamp == 12345
