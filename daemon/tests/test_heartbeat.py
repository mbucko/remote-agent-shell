"""Tests for HeartbeatManager - connection keepalive."""

import asyncio
import time
from unittest.mock import AsyncMock, Mock

import pytest

from ras.heartbeat import HeartbeatConfig, HeartbeatManager, ConnectionHealth
from ras.proto.ras import Heartbeat, RasEvent


class TestHeartbeatConfig:
    """Tests for HeartbeatConfig."""

    def test_default_values(self):
        """Default config should have reasonable values."""
        config = HeartbeatConfig()
        assert config.send_interval == 15.0
        assert config.receive_timeout == 60.0

    def test_custom_values(self):
        """Config should accept custom values."""
        config = HeartbeatConfig(send_interval=10.0, receive_timeout=30.0)
        assert config.send_interval == 10.0
        assert config.receive_timeout == 30.0

    def test_warns_on_long_interval(self, caplog):
        """Should warn if interval >= SCTP timeout (~30s)."""
        HeartbeatConfig(send_interval=30.0)
        assert "send_interval" in caplog.text
        assert "SCTP timeout" in caplog.text


class TestConnectionHealth:
    """Tests for ConnectionHealth dataclass."""

    def test_is_healthy_when_recent_activity(self):
        """Connection is healthy if activity is recent."""
        health = ConnectionHealth(device_id="test", last_activity=time.time())
        assert health.is_healthy

    def test_not_healthy_when_old_activity(self):
        """Connection is unhealthy if activity is old."""
        health = ConnectionHealth(
            device_id="test",
            last_activity=time.time() - 120.0  # 2 minutes ago
        )
        assert not health.is_healthy

    def test_seconds_since_activity(self):
        """Should correctly calculate seconds since activity."""
        health = ConnectionHealth(
            device_id="test",
            last_activity=time.time() - 5.0
        )
        assert 4.9 < health.seconds_since_activity < 5.1


class TestHeartbeatManager:
    """Tests for HeartbeatManager."""

    @pytest.fixture
    def config(self):
        """Standard test config with fast intervals for testing."""
        return HeartbeatConfig(send_interval=0.05, receive_timeout=0.5)

    @pytest.fixture
    def mock_send(self):
        """Mock send callback."""
        return AsyncMock()

    @pytest.fixture
    def manager(self, config, mock_send):
        """HeartbeatManager instance for testing."""
        return HeartbeatManager(config, mock_send)

    def test_on_connection_added(self, manager):
        """Adding connection should start tracking."""
        manager.on_connection_added("device1")
        health = manager.get_health("device1")
        assert health is not None
        assert health.device_id == "device1"
        assert health.is_healthy

    def test_on_connection_removed(self, manager):
        """Removing connection should stop tracking."""
        manager.on_connection_added("device1")
        manager.on_connection_removed("device1")
        assert manager.get_health("device1") is None

    def test_on_heartbeat_received(self, manager):
        """Receiving heartbeat should update health metrics."""
        manager.on_connection_added("device1")
        heartbeat = Heartbeat(timestamp=int(time.time() * 1000), sequence=1)

        manager.on_heartbeat_received("device1", heartbeat)

        health = manager.get_health("device1")
        assert health.heartbeats_received == 1
        assert health.last_heartbeat_received > 0

    def test_on_heartbeat_received_unknown_device(self, manager):
        """Receiving heartbeat for unknown device should be ignored."""
        heartbeat = Heartbeat(timestamp=1000, sequence=1)
        # Should not raise
        manager.on_heartbeat_received("unknown", heartbeat)

    def test_on_activity(self, manager):
        """Activity should update last_activity time."""
        manager.on_connection_added("device1")
        health = manager.get_health("device1")
        old_activity = health.last_activity

        # Small delay to ensure time difference
        time.sleep(0.01)
        manager.on_activity("device1")

        assert health.last_activity > old_activity

    def test_on_activity_unknown_device(self, manager):
        """Activity for unknown device should be ignored."""
        # Should not raise
        manager.on_activity("unknown")

    def test_get_stale_connections(self, manager):
        """Should detect connections that exceeded timeout."""
        manager.on_connection_added("fresh")
        manager.on_connection_added("stale")

        # Make one connection stale
        health = manager.get_health("stale")
        health.last_activity = time.time() - 10.0  # Older than 5s timeout

        stale = manager.get_stale_connections()
        assert "stale" in stale
        assert "fresh" not in stale

    @pytest.mark.asyncio
    async def test_start_stop(self, manager):
        """Manager should start and stop cleanly."""
        await manager.start()
        assert manager._running
        assert manager._task is not None

        await manager.stop()
        assert not manager._running
        assert manager._task is None

    @pytest.mark.asyncio
    async def test_start_is_idempotent(self, manager):
        """Starting twice should be safe."""
        await manager.start()
        task1 = manager._task

        await manager.start()
        assert manager._task is task1  # Same task

        await manager.stop()

    @pytest.mark.asyncio
    async def test_send_immediate(self, manager, mock_send):
        """send_immediate should send heartbeat right away."""
        manager.on_connection_added("device1")

        await manager.send_immediate("device1")

        mock_send.assert_called_once()
        device_id, data = mock_send.call_args[0]
        assert device_id == "device1"

        # Parse the message
        event = RasEvent().parse(data)
        assert event.heartbeat is not None
        assert event.heartbeat.sequence > 0

    @pytest.mark.asyncio
    async def test_send_immediate_unknown_device(self, manager, mock_send):
        """send_immediate for unknown device should be no-op."""
        await manager.send_immediate("unknown")
        mock_send.assert_not_called()

    @pytest.mark.asyncio
    async def test_heartbeat_loop_sends_to_connections(self, manager, mock_send):
        """Heartbeat loop should send to all tracked connections."""
        manager.on_connection_added("device1")
        manager.on_connection_added("device2")

        # Directly call the send method instead of running the loop with sleep
        await manager._send_heartbeats()

        # Both devices should have received heartbeats
        assert mock_send.call_count == 2
        device_ids = [call[0][0] for call in mock_send.call_args_list]
        assert "device1" in device_ids
        assert "device2" in device_ids

    @pytest.mark.asyncio
    async def test_heartbeat_loop_skips_stale_connections(self, manager, mock_send):
        """Heartbeat loop should skip stale connections."""
        manager.on_connection_added("fresh")
        manager.on_connection_added("stale")

        # Make one connection stale (older than receive_timeout)
        health = manager.get_health("stale")
        health.last_activity = time.time() - 1.0  # Older than 0.5s timeout

        # Directly call the send method instead of running the loop with sleep
        await manager._send_heartbeats()

        # Only fresh device should have received heartbeat
        device_ids = [call[0][0] for call in mock_send.call_args_list]
        assert "fresh" in device_ids
        assert "stale" not in device_ids

    @pytest.mark.asyncio
    async def test_heartbeat_loop_continues_on_send_failure(self, manager):
        """Heartbeat loop should continue if send fails for one device."""
        send_results = []

        async def failing_send(device_id, data):
            send_results.append(device_id)
            if device_id == "failing":
                raise Exception("Send failed")

        manager._send = failing_send
        manager.on_connection_added("failing")
        manager.on_connection_added("working")

        # Directly call the send method instead of running the loop with sleep
        await manager._send_heartbeats()

        # Both should have been attempted
        assert "failing" in send_results
        assert "working" in send_results

    @pytest.mark.asyncio
    async def test_config_property(self, manager, config):
        """Manager should expose config via property."""
        assert manager.config is config
        assert manager.config.send_interval == config.send_interval
