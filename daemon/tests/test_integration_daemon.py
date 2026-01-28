"""Integration tests for daemon startup.

These tests verify the daemon actually starts and listens on the configured port.
This is the type of test that would have caught the "signaling server not starting" bug.
"""

import asyncio
from pathlib import Path

import aiohttp
import pytest

from ras.config import Config, DaemonConfig
from ras.daemon import Daemon


@pytest.fixture
def config(tmp_path: Path) -> Config:
    """Create test config with a random available port."""
    config = Config()
    config.daemon = DaemonConfig(
        devices_file=str(tmp_path / "devices.json"),
        sessions_file=str(tmp_path / "sessions.json"),
    )
    config.port = 0  # Let OS choose available port
    config.bind_address = "127.0.0.1"
    return config


class TestDaemonStartup:
    """Integration tests that verify daemon starts properly."""

    @pytest.mark.asyncio
    async def test_daemon_starts_http_server(self, config):
        """INT-01: Daemon should actually listen on the configured port.

        This test would have caught the bug where _start_signaling_server()
        only started if a signaling server was injected.
        """
        daemon = Daemon(config=config)

        try:
            await daemon.start()

            # Get the actual port (since we used port=0)
            assert daemon._server_runner is not None
            site = daemon._server_runner._sites[0]
            actual_port = site._server.sockets[0].getsockname()[1]
            assert actual_port > 0

            # Verify we can actually connect and get a health response
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"http://127.0.0.1:{actual_port}/health"
                ) as resp:
                    assert resp.status == 200
                    text = await resp.text()
                    assert text == "OK"

        finally:
            await daemon.stop()
            await daemon._shutdown()

    @pytest.mark.asyncio
    async def test_daemon_reconnect_endpoint_exists(self, config):
        """INT-02: Daemon should have /reconnect endpoint for paired devices."""
        daemon = Daemon(config=config)

        try:
            await daemon.start()

            # Get actual port
            site = daemon._server_runner._sites[0]
            actual_port = site._server.sockets[0].getsockname()[1]

            # Try to hit reconnect endpoint (should fail auth, but endpoint should exist)
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"http://127.0.0.1:{actual_port}/reconnect/unknown-device",
                    data=b"test",
                ) as resp:
                    # Should get 400 (auth failure) not 404 (not found)
                    # 400 means the endpoint exists but auth failed
                    assert resp.status == 400

        finally:
            await daemon.stop()
            await daemon._shutdown()

    @pytest.mark.asyncio
    async def test_daemon_loads_paired_devices(self, config, tmp_path):
        """INT-03: Daemon loads previously paired devices on startup."""
        import base64
        import json

        # Create devices file with a paired device
        devices_file = tmp_path / "devices.json"
        test_secret = base64.b64encode(b"\x00" * 32).decode("ascii")
        devices_data = {
            "devices": [
                {
                    "device_id": "test-device-123",
                    "name": "Test Phone",
                    "master_secret": test_secret,
                    "paired_at": "2024-01-01T00:00:00Z",
                }
            ]
        }
        devices_file.write_text(json.dumps(devices_data))

        daemon = Daemon(config=config)

        try:
            await daemon.start()

            # Verify device was loaded
            assert daemon._device_store is not None
            assert len(daemon._device_store) == 1
            device = daemon._device_store.get("test-device-123")
            assert device is not None
            assert device.name == "Test Phone"

        finally:
            await daemon.stop()
            await daemon._shutdown()


class TestReconnectionFlow:
    """Integration tests for the reconnection flow."""

    @pytest.mark.asyncio
    async def test_unknown_device_rejected(self, config):
        """INT-04: Unknown device cannot reconnect."""
        daemon = Daemon(config=config)

        try:
            await daemon.start()

            site = daemon._server_runner._sites[0]
            actual_port = site._server.sockets[0].getsockname()[1]

            # Try to reconnect with unknown device
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"http://127.0.0.1:{actual_port}/reconnect/unknown-device",
                    headers={
                        "X-RAS-Timestamp": "1234567890",
                        "X-RAS-Signature": "0" * 64,  # Invalid signature
                    },
                    data=b"\x00",  # Some data
                ) as resp:
                    # Should be rejected (device not found)
                    assert resp.status == 400

        finally:
            await daemon.stop()
            await daemon._shutdown()

    @pytest.mark.asyncio
    async def test_invalid_hmac_rejected(self, config, tmp_path):
        """INT-05: Valid device with invalid HMAC is rejected."""
        import base64
        import json
        import time

        # Create devices file with a paired device
        devices_file = tmp_path / "devices.json"
        test_secret = base64.b64encode(b"\x00" * 32).decode("ascii")
        devices_data = {
            "devices": [
                {
                    "device_id": "test-device-123",
                    "name": "Test Phone",
                    "master_secret": test_secret,
                    "paired_at": "2024-01-01T00:00:00Z",
                }
            ]
        }
        devices_file.write_text(json.dumps(devices_data))

        daemon = Daemon(config=config)

        try:
            await daemon.start()

            site = daemon._server_runner._sites[0]
            actual_port = site._server.sockets[0].getsockname()[1]

            # Try to reconnect with valid device but wrong HMAC
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"http://127.0.0.1:{actual_port}/reconnect/test-device-123",
                    headers={
                        "X-RAS-Timestamp": str(int(time.time())),
                        "X-RAS-Signature": "0" * 64,  # Invalid signature
                    },
                    data=b"\x00",
                ) as resp:
                    # Should be rejected (invalid HMAC)
                    assert resp.status == 400

        finally:
            await daemon.stop()
            await daemon._shutdown()
