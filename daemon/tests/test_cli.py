"""Tests for CLI module."""

from unittest.mock import AsyncMock, patch

import pytest
from click.testing import CliRunner

from ras.cli import main


@pytest.fixture
def runner():
    """Create a CLI test runner."""
    return CliRunner()


class TestCLIHelp:
    """Test CLI help output."""

    def test_cli_help(self, runner):
        """ras --help shows usage."""
        result = runner.invoke(main, ["--help"])

        assert result.exit_code == 0
        assert "RemoteAgentShell" in result.output
        assert "daemon" in result.output

    def test_daemon_help(self, runner):
        """ras daemon --help shows daemon commands."""
        result = runner.invoke(main, ["daemon", "--help"])

        assert result.exit_code == 0
        assert "start" in result.output
        assert "stop" in result.output
        assert "status" in result.output


class TestDaemonCommands:
    """Test daemon subcommands."""

    def test_daemon_start_command_exists(self, runner):
        """ras daemon start is a valid command."""
        # Mock the DaemonLock to avoid checking for existing daemon
        # and mock the Daemon to avoid actually starting a server
        with patch("ras.daemon_lock.DaemonLock") as mock_lock_class, \
             patch("ras.daemon.Daemon") as mock_daemon_class:
            mock_lock = mock_lock_class.return_value
            mock_lock.acquire = lambda: None
            mock_lock.release = lambda: None

            mock_daemon = AsyncMock()
            mock_daemon.start = AsyncMock()
            mock_daemon.run_forever = AsyncMock(side_effect=KeyboardInterrupt)
            mock_daemon.stop = AsyncMock()
            mock_daemon_class.return_value = mock_daemon

            result = runner.invoke(main, ["daemon", "start"])

        # Command should parse successfully (exit via KeyboardInterrupt simulation)
        assert "Daemon started" in result.output or "Shutting down" in result.output

    def test_daemon_stop_command_exists(self, runner):
        """ras daemon stop is a valid command."""
        result = runner.invoke(main, ["daemon", "stop"])

        assert result.exit_code == 0
        assert "Stopping daemon" in result.output

    def test_daemon_status_command_exists(self, runner):
        """ras daemon status is a valid command."""
        result = runner.invoke(main, ["daemon", "status"])

        assert result.exit_code == 0
        assert "status" in result.output.lower()


class TestVersionCommand:
    """Test version command."""

    def test_version_command(self, runner):
        """ras version shows version."""
        result = runner.invoke(main, ["version"])

        assert result.exit_code == 0
        assert "0.1.0" in result.output


class TestConfigOption:
    """Test config file option."""

    def test_config_option_accepted(self, runner, tmp_path):
        """ras --config accepts a path."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text("port: 9999\n")

        result = runner.invoke(main, ["--config", str(config_file), "version"])

        assert result.exit_code == 0


class TestPairCommand:
    """Test pair command."""

    def test_pair_command_exists(self, runner):
        """ras pair --help shows usage."""
        result = runner.invoke(main, ["pair", "--help"])

        assert result.exit_code == 0
        assert "QR code" in result.output
        assert "--timeout" in result.output
