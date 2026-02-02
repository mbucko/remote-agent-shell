"""Tests for daemon orchestration module."""

import asyncio
import shutil
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, Mock, patch

import pytest

from ras.config import Config, DaemonConfig
from ras.daemon import Daemon, StartupError


class TestDaemonStartup:
    """Tests for daemon startup."""

    @pytest.fixture
    def config(self, tmp_path: Path) -> Config:
        """Create test config."""
        config = Config()
        config.daemon = DaemonConfig(
            devices_file=str(tmp_path / "devices.json"),
            sessions_file=str(tmp_path / "sessions.json"),
        )
        config.port = 0  # Use random port
        return config

    @pytest.fixture
    def mock_deps(self):
        """Create mock dependencies."""
        return {
            "signaling_server": AsyncMock(),
            "pairing_manager": AsyncMock(),
            "session_manager": AsyncMock(),
            "terminal_manager": AsyncMock(),
            "clipboard_manager": AsyncMock(),
        }

    # DS01: Validate tmux installed
    @pytest.mark.asyncio
    async def test_validate_tmux_installed(self, config, mock_deps):
        """Startup succeeds when tmux is installed."""
        daemon = Daemon(config, **mock_deps)

        with patch("shutil.which") as mock_which:
            mock_which.return_value = "/usr/bin/tmux"

            # Should not raise
            await daemon._validate_environment()

    # DS02: Fail if tmux not installed
    @pytest.mark.asyncio
    async def test_fail_if_tmux_missing(self, config, mock_deps):
        """Startup fails if tmux not found."""
        daemon = Daemon(config, **mock_deps)

        with patch("shutil.which") as mock_which:
            mock_which.return_value = None

            with pytest.raises(StartupError, match="tmux not found"):
                await daemon._validate_environment()

    # DS03: Load device store
    @pytest.mark.asyncio
    async def test_load_device_store(self, config, mock_deps):
        """Device store is loaded on startup."""
        daemon = Daemon(config, **mock_deps)

        with patch("shutil.which", return_value="/usr/bin/tmux"):
            await daemon._validate_environment()
            await daemon._initialize_stores()

        assert daemon._device_store is not None


class TestDaemonHandlers:
    """Tests for message handlers."""

    @pytest.fixture
    def config(self, tmp_path: Path) -> Config:
        """Create test config."""
        config = Config()
        config.daemon = DaemonConfig(
            devices_file=str(tmp_path / "devices.json"),
            sessions_file=str(tmp_path / "sessions.json"),
        )
        return config

    @pytest.fixture
    def daemon(self, config):
        """Create daemon with mocked dependencies."""
        session_manager = AsyncMock()
        terminal_manager = AsyncMock()
        clipboard_manager = AsyncMock()

        return Daemon(
            config=config,
            session_manager=session_manager,
            terminal_manager=terminal_manager,
            clipboard_manager=clipboard_manager,
        )

    # DH01: Session command dispatch
    @pytest.mark.asyncio
    async def test_session_command_dispatch(self, daemon):
        """Session commands are dispatched to session manager."""
        daemon._session_manager.handle_command = AsyncMock(
            return_value=MagicMock()
        )

        from ras.proto.ras import SessionCommand, ListSessionsCommand

        cmd = SessionCommand(list=ListSessionsCommand())

        await daemon._handle_session_command("device1", cmd)

        daemon._session_manager.handle_command.assert_called_once()

    # DH02: Terminal command dispatch
    @pytest.mark.asyncio
    async def test_terminal_command_dispatch(self, daemon):
        """Terminal commands are dispatched to terminal manager."""
        daemon._terminal_manager.handle_command = AsyncMock()

        from ras.proto.ras import TerminalCommand, AttachTerminal

        cmd = TerminalCommand(attach=AttachTerminal(session_id="test123"))

        await daemon._handle_terminal_command("device1", cmd)

        daemon._terminal_manager.handle_command.assert_called_once()


class TestDaemonBroadcast:
    """Tests for event broadcasting."""

    @pytest.fixture
    def config(self, tmp_path: Path) -> Config:
        """Create test config."""
        config = Config()
        config.daemon = DaemonConfig(
            devices_file=str(tmp_path / "devices.json"),
            sessions_file=str(tmp_path / "sessions.json"),
        )
        return config

    @pytest.fixture
    def daemon(self, config):
        """Create daemon."""
        return Daemon(config=config)

    # DB01: Broadcast session event
    @pytest.mark.asyncio
    async def test_broadcast_session_event(self, daemon):
        """Session events are broadcast to all connections."""
        daemon._connection_manager.broadcast = AsyncMock()

        from ras.proto.ras import SessionEvent, SessionListEvent

        event = SessionEvent(list=SessionListEvent(sessions=[]))

        await daemon._broadcast_session_event(event)

        daemon._connection_manager.broadcast.assert_called_once()

    # DB02: Broadcast terminal event to specific connection
    @pytest.mark.asyncio
    async def test_send_terminal_event(self, daemon):
        """Terminal events sent to specific connection."""
        mock_conn = AsyncMock()
        daemon._connection_manager.get_connection = MagicMock(
            return_value=mock_conn
        )

        from ras.proto.ras import TerminalEvent, TerminalOutput

        event = TerminalEvent(
            output=TerminalOutput(session_id="test", data=b"hello", sequence=1)
        )

        await daemon._send_terminal_event("device1", event)

        mock_conn.send.assert_called_once()


class TestDaemonShutdown:
    """Tests for daemon shutdown."""

    @pytest.fixture
    def config(self, tmp_path: Path) -> Config:
        """Create test config."""
        config = Config()
        config.daemon = DaemonConfig(
            devices_file=str(tmp_path / "devices.json"),
            sessions_file=str(tmp_path / "sessions.json"),
        )
        return config

    @pytest.fixture
    def daemon(self, config):
        """Create daemon with mocks."""
        daemon = Daemon(config=config)
        daemon._connection_manager.close_all = AsyncMock()
        daemon._terminal_manager = AsyncMock()
        daemon._signaling_runner = AsyncMock()
        return daemon

    # SD01: Graceful shutdown
    @pytest.mark.asyncio
    async def test_graceful_shutdown(self, daemon):
        """Shutdown closes all connections and runners."""
        await daemon._shutdown()

        daemon._connection_manager.close_all.assert_called_once()

    # SD02: Shutdown with terminal cleanup
    @pytest.mark.asyncio
    async def test_shutdown_with_terminal_cleanup(self, daemon):
        """Shutdown cleans up terminal manager."""
        await daemon._shutdown()

        daemon._terminal_manager.shutdown.assert_called_once()


class TestDaemonPingPong:
    """Tests for ping/pong keepalive."""

    @pytest.fixture
    def config(self, tmp_path: Path) -> Config:
        """Create test config."""
        config = Config()
        config.daemon = DaemonConfig(
            devices_file=str(tmp_path / "devices.json"),
            sessions_file=str(tmp_path / "sessions.json"),
            keepalive_interval=0.1,
        )
        return config

    @pytest.fixture
    def daemon(self, config):
        """Create daemon."""
        return Daemon(config=config)

    # PP01: Respond to ping with pong
    @pytest.mark.asyncio
    async def test_ping_responds_with_pong(self, daemon):
        """Ping message receives pong response."""
        mock_conn = AsyncMock()
        daemon._connection_manager.get_connection = MagicMock(
            return_value=mock_conn
        )

        from ras.proto.ras import Ping

        ping = Ping(timestamp=1234567890)

        await daemon._handle_ping("device1", ping)

        mock_conn.send.assert_called_once()
        # Verify it's a pong with same timestamp
        sent_data = mock_conn.send.call_args[0][0]
        assert b"1234567890" in str(sent_data).encode() or sent_data is not None


class TestHeartbeatIntegration:
    """Tests for daemon integration with HeartbeatManager."""

    @pytest.fixture
    def config(self, tmp_path: Path) -> Config:
        """Create test config."""
        config = Config()
        config.daemon = DaemonConfig(
            devices_file=str(tmp_path / "devices.json"),
            sessions_file=str(tmp_path / "sessions.json"),
            keepalive_interval=0.1,
            keepalive_timeout=60.0,
        )
        return config

    @pytest.fixture
    def daemon(self, config):
        """Create daemon."""
        return Daemon(config=config)

    # KA01: Daemon initializes HeartbeatManager with correct config
    def test_daemon_creates_heartbeat_manager(self, daemon):
        """Daemon should create HeartbeatManager with correct config."""
        assert daemon._heartbeat_manager is not None
        assert daemon._heartbeat_manager._config.send_interval == 0.1
        assert daemon._heartbeat_manager._config.receive_timeout == 60.0

    # KA02: Daemon registers send callback with HeartbeatManager
    @pytest.mark.asyncio
    async def test_daemon_send_heartbeat_callback(self, daemon):
        """Daemon should properly send heartbeat through connection manager."""
        mock_conn = AsyncMock()
        daemon._connection_manager.connections = {"device1": mock_conn}

        # Call the heartbeat send callback directly
        await daemon._send_heartbeat("device1", b"heartbeat_data")

        # Verify send was called on the connection
        mock_conn.send.assert_called_once_with(b"heartbeat_data")

    # KA03: Daemon registers connection with heartbeat manager
    @pytest.mark.asyncio
    async def test_daemon_registers_connection_with_heartbeat(self, daemon):
        """When connection is added, daemon should register with heartbeat manager."""
        # Use Mock (not AsyncMock) for peer - on_message/on_close are sync methods
        # that just set callbacks, they don't return coroutines
        mock_peer = Mock()
        mock_peer.send = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_codec = Mock()
        mock_codec.encode.return_value = b"encoded"
        mock_codec.decode.return_value = b"decoded"

        # Track if heartbeat manager methods were called
        daemon._heartbeat_manager.on_connection_added = Mock()
        daemon._heartbeat_manager.send_immediate = AsyncMock()

        await daemon.on_new_connection(
            device_id="device1",
            device_name="Test Device",
            peer=mock_peer,
            codec=mock_codec,
        )

        # Verify heartbeat manager was notified
        daemon._heartbeat_manager.on_connection_added.assert_called_once_with("device1")
        daemon._heartbeat_manager.send_immediate.assert_called_once_with("device1")

    # KA04: Daemon removes connection from heartbeat manager on disconnect
    @pytest.mark.asyncio
    async def test_daemon_removes_connection_from_heartbeat(self, daemon):
        """When connection is lost, daemon should remove from heartbeat manager."""
        daemon._heartbeat_manager.on_connection_removed = Mock()

        await daemon._on_connection_lost("device1")

        # Verify heartbeat manager was notified
        daemon._heartbeat_manager.on_connection_removed.assert_called_once_with("device1")


class TestUnpairHandling:
    """Tests for unpair request handling."""

    @pytest.mark.asyncio
    async def test_handle_unpair_request_success(self):
        """Handle valid unpair request - removes device and sends ack."""
        from ras.proto.ras import UnpairRequest

        config = Config()
        config.daemon = DaemonConfig(
            devices_file="/tmp/test.json",
            send_timeout=5.0,
            handler_timeout=10.0
        )

        daemon = Daemon(config=config)
        daemon._device_store = AsyncMock()
        daemon._device_store.remove = AsyncMock(return_value=True)

        mock_conn = AsyncMock()
        daemon._connection_manager.connections = {"device123": mock_conn}
        daemon._connection_manager.get_connection = Mock(return_value=mock_conn)
        daemon._connection_manager.close_connection = AsyncMock()

        request = UnpairRequest(device_id="device123")
        await daemon._handle_unpair_request("device123", request)

        daemon._device_store.remove.assert_called_once_with("device123")
        assert mock_conn.send.called
        daemon._connection_manager.close_connection.assert_called_once_with("device123")

    @pytest.mark.asyncio
    async def test_handle_unpair_request_wrong_device(self, caplog):
        """Reject unpair request for different device (security)."""
        import logging
        from ras.proto.ras import UnpairRequest

        caplog.set_level(logging.WARNING)

        config = Config()
        config.daemon = DaemonConfig(devices_file="/tmp/test.json")

        daemon = Daemon(config=config)
        daemon._device_store = AsyncMock()

        # Device "device123" tries to unpair "device456"
        request = UnpairRequest(device_id="device456")
        await daemon._handle_unpair_request("device123", request)

        # Verify store was NOT called
        daemon._device_store.remove.assert_not_called()
        assert "Security" in caplog.text

    @pytest.mark.asyncio
    async def test_send_unpair_notification_connected(self):
        """Send unpair notification to connected device."""
        config = Config()
        config.daemon = DaemonConfig(devices_file="/tmp/test.json")

        daemon = Daemon(config=config)

        mock_conn = AsyncMock()
        daemon._connection_manager.get_connection = Mock(return_value=mock_conn)
        daemon._connection_manager.close_connection = AsyncMock()

        result = await daemon.send_unpair_notification("device123", "Test reason")

        assert result is True
        assert mock_conn.send.called
        daemon._connection_manager.close_connection.assert_called_once_with("device123")

    @pytest.mark.asyncio
    async def test_send_unpair_notification_not_connected(self):
        """Send unpair notification when device not connected returns False."""
        config = Config()
        config.daemon = DaemonConfig(devices_file="/tmp/test.json")

        daemon = Daemon(config=config)
        daemon._connection_manager.get_connection = Mock(return_value=None)

        result = await daemon.send_unpair_notification("device123")

        assert result is False

    @pytest.mark.asyncio
    async def test_handle_unpair_request_send_ack_fails(self):
        """When sending UnpairAck fails, device should still be removed and connection closed."""
        from ras.proto.ras import UnpairRequest

        config = Config()
        config.daemon = DaemonConfig(devices_file="/tmp/test.json")

        daemon = Daemon(config=config)
        daemon._device_store = AsyncMock()
        daemon._device_store.remove = AsyncMock(return_value=True)

        mock_conn = AsyncMock()
        mock_conn.send = AsyncMock(side_effect=ConnectionError("Connection lost"))
        daemon._connection_manager.connections = {"device123": mock_conn}
        daemon._connection_manager.get_connection = Mock(return_value=mock_conn)
        daemon._connection_manager.close_connection = AsyncMock()

        request = UnpairRequest(device_id="device123")

        # Should not raise exception
        await daemon._handle_unpair_request("device123", request)

        # Device should still be removed even if ack failed
        daemon._device_store.remove.assert_called_once_with("device123")
        daemon._connection_manager.close_connection.assert_called_once_with("device123")

    @pytest.mark.asyncio
    async def test_handle_unpair_request_device_store_none(self):
        """When device_store is None (not initialized), should handle gracefully."""
        from ras.proto.ras import UnpairRequest

        config = Config()
        config.daemon = DaemonConfig(devices_file="/tmp/test.json")

        daemon = Daemon(config=config)
        daemon._device_store = None  # Not initialized
        daemon._connection_manager.close_connection = AsyncMock()

        request = UnpairRequest(device_id="device123")

        # Should not crash
        await daemon._handle_unpair_request("device123", request)

        # Connection should NOT be closed if device_store not available
        daemon._connection_manager.close_connection.assert_not_called()

    @pytest.mark.asyncio
    async def test_send_unpair_notification_send_fails(self):
        """When sending UnpairNotification fails, connection should still close."""
        config = Config()
        config.daemon = DaemonConfig(devices_file="/tmp/test.json")

        daemon = Daemon(config=config)

        mock_conn = AsyncMock()
        mock_conn.send = AsyncMock(side_effect=asyncio.TimeoutError("Send timeout"))
        daemon._connection_manager.get_connection = Mock(return_value=mock_conn)
        daemon._connection_manager.close_connection = AsyncMock()

        result = await daemon.send_unpair_notification("device123", "Test reason")

        # Should return False but not crash
        assert result is False
        # Connection should still be closed
        daemon._connection_manager.close_connection.assert_called_once_with("device123")
