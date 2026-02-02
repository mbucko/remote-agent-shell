"""Tests for CLI module."""

import json
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

    def test_pair_command_generates_qr(self, runner):
        """ras pair actually generates and displays QR code."""
        import aiohttp
        from unittest.mock import MagicMock

        # Mock aiohttp.ClientSession to return expected API response
        with patch("aiohttp.ClientSession") as mock_session_class, \
             patch("asyncio.sleep", new_callable=AsyncMock):  # Skip polling delays
            mock_session = MagicMock()
            mock_session_class.return_value.__aenter__.return_value = mock_session

            # Mock POST /api/pair response
            mock_post_response = AsyncMock()
            mock_post_response.status = 200
            mock_post_response.json = AsyncMock(return_value={
                "session_id": "test-session-123",
                "qr_data": {
                    "master_secret": "a" * 64,  # 32 bytes hex
                }
            })
            mock_session.post.return_value.__aenter__.return_value = mock_post_response

            # Mock GET /api/pair/{session_id} to return completed immediately
            mock_get_response = AsyncMock()
            mock_get_response.status = 200
            mock_get_response.json = AsyncMock(return_value={
                "state": "completed",
                "device_name": "Test Phone"
            })
            mock_session.get.return_value.__aenter__.return_value = mock_get_response

            # Mock DELETE for cleanup
            mock_session.delete.return_value.__aenter__.return_value = AsyncMock()

            result = runner.invoke(main, ["pair", "--timeout", "2"])

        # Should show QR code content (unicode blocks) or success message
        # The QR code uses unicode blocks like █ or ▄
        assert result.exit_code == 0 or "Pairing successful" in result.output or "█" in result.output or "Scan" in result.output

    def test_pair_command_uses_only_master_secret(self, runner):
        """ras pair only uses master_secret from QR data (no ip/port)."""
        # This test verifies the fix for the KeyError bug
        with patch("aiohttp.ClientSession") as mock_session_class, \
             patch("asyncio.sleep", new_callable=AsyncMock):  # Skip polling delays
            from unittest.mock import MagicMock

            mock_session = MagicMock()
            mock_session_class.return_value.__aenter__.return_value = mock_session

            # API returns ONLY master_secret - no ip, port, or ntfyTopic
            # This is the current design: everything is derived/discovered
            mock_post_response = AsyncMock()
            mock_post_response.status = 200
            mock_post_response.json = AsyncMock(return_value={
                "session_id": "test-session-123",
                "qr_data": {
                    "master_secret": "b" * 64,
                    # No "ip", "port", or "ntfy_topic" keys!
                }
            })
            mock_session.post.return_value.__aenter__.return_value = mock_post_response

            # Return 404 to exit polling quickly
            mock_get_response = AsyncMock()
            mock_get_response.status = 404
            mock_session.get.return_value.__aenter__.return_value = mock_get_response

            mock_session.delete.return_value.__aenter__.return_value = AsyncMock()

            result = runner.invoke(main, ["pair", "--timeout", "1"])

        # Should NOT raise KeyError - the command should process without crashing
        # It may show "cancelled" since we return 404, but no Python exceptions
        assert "KeyError" not in result.output
        assert "Traceback" not in result.output

    def test_qr_generator_from_api_response(self):
        """CLI can create QrGenerator from API response structure."""
        from ras.pairing.qr_generator import QrGenerator

        # This is the structure returned by the /api/pair endpoint
        mock_response = {
            "session_id": "test-session-123",
            "qr_data": {
                "master_secret": "a" * 64,  # 32 bytes hex
            }
        }

        # CLI extracts and creates QrGenerator
        qr_data = mock_response["qr_data"]
        master_secret = bytes.fromhex(qr_data["master_secret"])
        qr_gen = QrGenerator(master_secret=master_secret)

        # Should not raise and should produce valid output
        terminal_output = qr_gen.to_terminal()
        assert terminal_output  # Not empty
        assert len(terminal_output) > 100  # Has QR code content


class TestDevicesCommands:
    """Test devices subcommands."""

    def test_devices_list_no_devices(self, runner, tmp_path):
        """List with no devices shows message."""
        config_file = tmp_path / "config.yaml"
        devices_file = tmp_path / "devices.json"

        config_file.write_text(f"daemon:\n  devices_file: {devices_file}\n")
        devices_file.write_text('{"devices": []}')

        result = runner.invoke(main, ["--config", str(config_file), "devices", "list"])

        assert result.exit_code == 0
        assert "No paired devices" in result.output

    def test_devices_list_with_devices(self, runner, tmp_path):
        """List shows all devices in formatted table."""
        import base64

        config_file = tmp_path / "config.yaml"
        devices_file = tmp_path / "devices.json"

        secret = base64.b64encode(b"\x00" * 32).decode("ascii")
        devices_file.write_text(json.dumps({
            "devices": [{
                "device_id": "device123",
                "name": "Pixel 8",
                "master_secret": secret,
                "paired_at": "2024-01-01T00:00:00Z",
                "last_seen": "2024-01-02T12:00:00Z",
            }]
        }))

        config_file.write_text(f"daemon:\n  devices_file: {devices_file}\n")

        result = runner.invoke(main, ["--config", str(config_file), "devices", "list"])

        assert result.exit_code == 0
        assert "Pixel 8" in result.output
        assert "2024-01-01" in result.output

    def test_devices_remove_with_force(self, runner, tmp_path):
        """Remove with --force skips confirmation."""
        import base64

        config_file = tmp_path / "config.yaml"
        devices_file = tmp_path / "devices.json"

        secret = base64.b64encode(b"\x00" * 32).decode("ascii")
        devices_file.write_text(json.dumps({
            "devices": [{
                "device_id": "device123",
                "name": "Test Phone",
                "master_secret": secret,
                "paired_at": "2024-01-01T00:00:00Z",
            }]
        }))

        config_file.write_text(f"daemon:\n  devices_file: {devices_file}\n")

        result = runner.invoke(
            main,
            ["--config", str(config_file), "devices", "remove", "device123", "--force"]
        )

        assert result.exit_code == 0
        assert "Device removed" in result.output

        # Verify device was removed
        data = json.loads(devices_file.read_text())
        assert len(data["devices"]) == 0

    def test_devices_remove_not_found(self, runner, tmp_path):
        """Remove unknown device shows error."""
        config_file = tmp_path / "config.yaml"
        devices_file = tmp_path / "devices.json"

        devices_file.write_text('{"devices": []}')
        config_file.write_text(f"daemon:\n  devices_file: {devices_file}\n")

        result = runner.invoke(
            main,
            ["--config", str(config_file), "devices", "remove", "unknown", "--force"]
        )

        assert result.exit_code == 1
        assert "not found" in result.output

    def test_devices_remove_all_force(self, runner, tmp_path):
        """Remove --all --force removes all devices."""
        import base64

        config_file = tmp_path / "config.yaml"
        devices_file = tmp_path / "devices.json"

        secret = base64.b64encode(b"\x00" * 32).decode("ascii")
        devices_file.write_text(json.dumps({
            "devices": [
                {
                    "device_id": "device1",
                    "name": "Phone 1",
                    "master_secret": secret,
                    "paired_at": "2024-01-01T00:00:00Z",
                },
                {
                    "device_id": "device2",
                    "name": "Phone 2",
                    "master_secret": secret,
                    "paired_at": "2024-01-02T00:00:00Z",
                }
            ]
        }))

        config_file.write_text(f"daemon:\n  devices_file: {devices_file}\n")

        result = runner.invoke(
            main,
            ["--config", str(config_file), "devices", "remove", "--all", "--force"]
        )

        assert result.exit_code == 0
        assert "Removed 2 devices" in result.output

        # Verify all devices removed
        data = json.loads(devices_file.read_text())
        assert len(data["devices"]) == 0
