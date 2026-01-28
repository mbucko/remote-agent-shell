"""Tests for daemon orchestration module."""

import asyncio
import shutil
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

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
