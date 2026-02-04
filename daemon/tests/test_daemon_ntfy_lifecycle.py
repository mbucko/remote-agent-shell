"""Tests for daemon ntfy reconnection subscriber lifecycle.

Verifies that the daemon correctly wires the ntfy reconnection manager
to pairing and device removal events:
- After pairing completes, a new ntfy subscriber is created
- After device removal, the ntfy subscriber is removed
- Re-pairing creates a subscriber on the new topic
"""

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

from ras.config import Config, DaemonConfig
from ras.crypto import derive_ntfy_topic
from ras.daemon import Daemon
from ras.device_store import PairedDevice


@pytest.fixture
def config(tmp_path: Path) -> Config:
    """Create test config."""
    config = Config()
    config.daemon = DaemonConfig(
        devices_file=str(tmp_path / "devices.json"),
        sessions_file=str(tmp_path / "sessions.json"),
        send_timeout=5.0,
        handler_timeout=5.0,
        keepalive_interval=60.0,
    )
    config.port = 0
    return config


class TestDaemonNtfySubscriberLifecycle:
    """Tests that daemon keeps ntfy reconnection subscribers in sync with paired devices."""

    @pytest.mark.asyncio
    async def test_pairing_complete_adds_ntfy_subscriber(self, config):
        """After pairing completes, reconnection manager should have a subscriber for the device."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        # Create a mock reconnection manager to track add_device calls
        mock_reconnection_mgr = MagicMock()
        mock_reconnection_mgr.add_device = AsyncMock()
        mock_reconnection_mgr.remove_device = AsyncMock()
        daemon._ntfy_reconnection_manager = mock_reconnection_mgr

        # Store a device (simulating what server.py does during pairing)
        master_secret = b"\xaa" * 32
        await daemon._device_store.add_device(
            device_id="phone-001",
            device_name="Test Phone",
            master_secret=master_secret,
        )

        # Simulate pairing complete callback (what server.py calls after auth succeeds)
        await daemon._on_pairing_complete("phone-001", "Test Phone")

        # Reconnection manager should have been told about the new device
        mock_reconnection_mgr.add_device.assert_called_once()
        added_device = mock_reconnection_mgr.add_device.call_args[0][0]
        assert added_device.device_id == "phone-001"
        assert added_device.master_secret == master_secret

    @pytest.mark.asyncio
    async def test_device_removal_removes_ntfy_subscriber(self, config):
        """After device removal, reconnection manager should remove the subscriber."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        # Create a mock reconnection manager
        mock_reconnection_mgr = MagicMock()
        mock_reconnection_mgr.add_device = AsyncMock()
        mock_reconnection_mgr.remove_device = AsyncMock()
        daemon._ntfy_reconnection_manager = mock_reconnection_mgr

        # Store a device first
        await daemon._device_store.add_device(
            device_id="phone-001",
            device_name="Test Phone",
            master_secret=b"\xaa" * 32,
        )

        # Simulate device removal callback
        await daemon._on_device_removed("phone-001", "Removed via CLI")

        # Reconnection manager should have been told to remove the device
        mock_reconnection_mgr.remove_device.assert_called_once_with("phone-001")

    @pytest.mark.asyncio
    async def test_repair_updates_ntfy_subscriber_topic(self, config):
        """Re-pairing a device should remove old subscriber and add new one with correct topic."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        mock_reconnection_mgr = MagicMock()
        mock_reconnection_mgr.add_device = AsyncMock()
        mock_reconnection_mgr.remove_device = AsyncMock()
        daemon._ntfy_reconnection_manager = mock_reconnection_mgr

        old_secret = b"\xaa" * 32
        new_secret = b"\xbb" * 32

        # First pairing
        await daemon._device_store.add_device(
            device_id="phone-001",
            device_name="Test Phone",
            master_secret=old_secret,
        )
        await daemon._on_pairing_complete("phone-001", "Test Phone")

        first_device = mock_reconnection_mgr.add_device.call_args[0][0]
        old_topic = derive_ntfy_topic(first_device.master_secret)

        # Remove device
        await daemon._on_device_removed("phone-001", "Removed via CLI")

        # Re-pair with new secret
        await daemon._device_store.add_device(
            device_id="phone-001",
            device_name="Test Phone",
            master_secret=new_secret,
        )
        await daemon._on_pairing_complete("phone-001", "Test Phone")

        # Should have been called twice (once per pairing)
        assert mock_reconnection_mgr.add_device.call_count == 2
        # Should have been removed once
        mock_reconnection_mgr.remove_device.assert_called_once_with("phone-001")

        # Second add should use the new secret
        second_device = mock_reconnection_mgr.add_device.call_args_list[1][0][0]
        new_topic = derive_ntfy_topic(second_device.master_secret)

        assert old_topic != new_topic, "Re-pairing should produce a different ntfy topic"
        assert second_device.master_secret == new_secret

    @pytest.mark.asyncio
    async def test_pairing_complete_without_reconnection_manager_doesnt_crash(self, config):
        """If reconnection manager isn't started yet, pairing complete shouldn't crash."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        # Don't set up reconnection manager (it's None)
        assert daemon._ntfy_reconnection_manager is None

        await daemon._device_store.add_device(
            device_id="phone-001",
            device_name="Test Phone",
            master_secret=b"\xaa" * 32,
        )

        # Should not raise
        await daemon._on_pairing_complete("phone-001", "Test Phone")

    @pytest.mark.asyncio
    async def test_device_removal_without_reconnection_manager_doesnt_crash(self, config):
        """If reconnection manager isn't started yet, device removal shouldn't crash."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        # Don't set up reconnection manager (it's None)
        assert daemon._ntfy_reconnection_manager is None

        # Should not raise
        await daemon._on_device_removed("phone-001", "Removed via CLI")


class TestUnifiedServerPairingCallback:
    """Tests that UnifiedServer is wired with on_pairing_complete callback."""

    @pytest.mark.asyncio
    async def test_server_created_with_pairing_callback(self, config):
        """UnifiedServer should be created with on_pairing_complete callback."""
        daemon = Daemon(config=config)
        await daemon._initialize_stores()

        # Start signaling server (creates UnifiedServer)
        await daemon._start_signaling_server()

        # Verify the server has a pairing complete callback
        assert daemon._unified_server._on_pairing_complete is not None
