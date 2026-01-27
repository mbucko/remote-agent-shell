"""Tests for CLI module."""

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
        result = runner.invoke(main, ["daemon", "start"])

        assert result.exit_code == 0
        assert "Starting daemon" in result.output

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
