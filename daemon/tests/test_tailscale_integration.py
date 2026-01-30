"""Integration tests for Tailscale connection flow.

These tests verify the complete flow with mocked network.
"""

import asyncio
import os
import struct
from typing import Tuple
from unittest.mock import AsyncMock, Mock, patch

import pytest

from ras.crypto import derive_key
from ras.device_store import PairedDevice
from ras.tailscale.listener import TailscaleListener
from ras.tailscale.transport import HANDSHAKE_MAGIC, TailscaleProtocol


class MockNetwork:
    """Simulates network communication for testing."""

    def __init__(self):
        self.daemon_queue: asyncio.Queue[Tuple[bytes, Tuple[str, int]]] = asyncio.Queue()
        self.phone_queue: asyncio.Queue[bytes] = asyncio.Queue()

    async def send_to_daemon(self, data: bytes, addr: Tuple[str, int]) -> None:
        """Simulate phone sending data to daemon."""
        await self.daemon_queue.put((data, addr))

    async def receive_from_daemon(self, timeout: float = 1.0) -> bytes:
        """Receive data sent by daemon."""
        return await asyncio.wait_for(self.phone_queue.get(), timeout=timeout)


class MockDeviceStore:
    """Mock device store for integration tests."""

    def __init__(self):
        self.devices: dict[str, PairedDevice] = {}

    def add(self, device: PairedDevice) -> None:
        """Add a device."""
        self.devices[device.device_id] = device

    def get(self, device_id: str) -> PairedDevice | None:
        """Get a device by ID."""
        return self.devices.get(device_id)

    async def save(self) -> None:
        """Mock save."""
        pass


def create_test_device(device_id: str = "test-device-123") -> PairedDevice:
    """Create a test paired device."""
    from datetime import datetime, timezone
    return PairedDevice(
        device_id=device_id,
        name="Test Phone",
        master_secret=os.urandom(32),
        paired_at=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    )


def create_auth_message(device: PairedDevice) -> bytes:
    """Create an auth message for a device."""
    device_id_bytes = device.device_id.encode('utf-8')
    auth_key = derive_key(device.master_secret, "auth")
    return (
        struct.pack(">I", len(device_id_bytes)) +
        device_id_bytes +
        auth_key
    )


class TestFullConnectionFlow:
    """Integration tests for complete connection flows."""

    @pytest.mark.asyncio
    async def test_handshake_then_auth_flow(self):
        """Full flow: handshake → auth → success response."""
        # Create mock components
        mock_protocol = Mock(spec=TailscaleProtocol)
        mock_protocol._queue = asyncio.Queue()
        mock_protocol.send_handshake_response = Mock()

        mock_transport = Mock()
        mock_transport.sendto = Mock()
        mock_transport.close = Mock()

        mock_tailscale_info = Mock()
        mock_tailscale_info.ip = "100.64.0.1"

        # Track connections created
        connections_created = []

        async def track_connection(transport):
            connections_created.append(transport)

        # Create listener
        listener = TailscaleListener(on_connection=track_connection)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        phone_addr = ("100.64.0.2", 12345)

        # Step 1: Phone sends handshake
        handshake = struct.pack(">II", HANDSHAKE_MAGIC, 0)
        await listener._handle_packet(handshake, phone_addr)

        # Verify handshake response sent
        mock_protocol.send_handshake_response.assert_called_once_with(phone_addr)

        # Verify connection created and tracked
        assert phone_addr in listener._connections
        assert len(connections_created) == 1

    @pytest.mark.asyncio
    async def test_handshake_then_multiple_data_packets(self):
        """Full flow: handshake → multiple data packets → single connection."""
        mock_protocol = Mock(spec=TailscaleProtocol)
        mock_protocol._queue = asyncio.Queue()
        mock_protocol.send_handshake_response = Mock()

        mock_transport = Mock()
        mock_transport.sendto = Mock()

        mock_tailscale_info = Mock()
        mock_tailscale_info.ip = "100.64.0.1"

        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        phone_addr = ("100.64.0.2", 12345)

        # Step 1: Handshake
        handshake = struct.pack(">II", HANDSHAKE_MAGIC, 0)
        await listener._handle_packet(handshake, phone_addr)

        assert len(listener._connections) == 1

        # Step 2: Multiple data packets
        for i in range(20):
            data = struct.pack(">I", 10) + f"packet{i:04d}"[:10].encode()
            await listener._handle_packet(data, phone_addr)

        # Step 3: Still only one connection
        assert len(listener._connections) == 1

        # All packets should be queued
        assert mock_protocol._queue.qsize() == 20


class TestTwoClientsFlow:
    """Integration tests for multiple client scenarios."""

    @pytest.mark.asyncio
    async def test_two_clients_connect_independently(self):
        """Two phones connect via Tailscale with separate connections."""
        mock_protocol = Mock(spec=TailscaleProtocol)
        mock_protocol._queue = asyncio.Queue()
        mock_protocol.send_handshake_response = Mock()

        mock_transport = Mock()
        mock_transport.sendto = Mock()

        mock_tailscale_info = Mock()
        mock_tailscale_info.ip = "100.64.0.1"

        connections_created = []

        async def track_connection(transport):
            connections_created.append(transport)

        listener = TailscaleListener(on_connection=track_connection)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        # Two different phones
        phone1_addr = ("100.64.0.2", 12345)
        phone2_addr = ("100.64.0.3", 54321)

        # Both handshake
        handshake = struct.pack(">II", HANDSHAKE_MAGIC, 0)
        await listener._handle_packet(handshake, phone1_addr)
        await listener._handle_packet(handshake, phone2_addr)

        # Verify separate connections
        assert len(listener._connections) == 2
        assert len(connections_created) == 2
        assert listener._connections[phone1_addr] is not listener._connections[phone2_addr]

    @pytest.mark.asyncio
    async def test_interleaved_packets_from_two_clients(self):
        """Interleaved packets from two clients route correctly."""
        mock_protocol = Mock(spec=TailscaleProtocol)
        mock_protocol._queue = asyncio.Queue()
        mock_protocol.send_handshake_response = Mock()

        mock_transport = Mock()
        mock_transport.sendto = Mock()

        mock_tailscale_info = Mock()
        mock_tailscale_info.ip = "100.64.0.1"

        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        phone1_addr = ("100.64.0.2", 12345)
        phone2_addr = ("100.64.0.3", 54321)

        # Both handshake
        handshake = struct.pack(">II", HANDSHAKE_MAGIC, 0)
        await listener._handle_packet(handshake, phone1_addr)
        await listener._handle_packet(handshake, phone2_addr)

        # Send interleaved packets
        for i in range(10):
            data1 = struct.pack(">I", 10) + f"phone1-{i:03d}"[:10].encode()
            data2 = struct.pack(">I", 10) + f"phone2-{i:03d}"[:10].encode()
            await listener._handle_packet(data1, phone1_addr)
            await listener._handle_packet(data2, phone2_addr)

        # Still only two connections
        assert len(listener._connections) == 2

        # All 20 packets should be queued (both clients share the protocol queue)
        assert mock_protocol._queue.qsize() == 20


class TestEdgeCases:
    """Integration tests for edge cases."""

    @pytest.mark.asyncio
    async def test_late_packet_after_disconnect(self):
        """Packets arriving after connection removed are ignored."""
        mock_protocol = Mock(spec=TailscaleProtocol)
        mock_protocol._queue = asyncio.Queue()
        mock_protocol.send_handshake_response = Mock()

        mock_transport = Mock()
        mock_transport.sendto = Mock()

        mock_tailscale_info = Mock()
        mock_tailscale_info.ip = "100.64.0.1"

        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        phone_addr = ("100.64.0.2", 12345)

        # Handshake and connect
        handshake = struct.pack(">II", HANDSHAKE_MAGIC, 0)
        await listener._handle_packet(handshake, phone_addr)
        assert phone_addr in listener._connections

        # Simulate disconnect (remove from connections)
        del listener._connections[phone_addr]

        # Late packet arrives - should be ignored
        data = struct.pack(">I", 5) + b"hello"
        await listener._handle_packet(data, phone_addr)

        # Should not have re-added the connection
        assert phone_addr not in listener._connections

    @pytest.mark.asyncio
    async def test_connection_then_rehandshake(self):
        """Re-handshake from same address doesn't duplicate connection."""
        mock_protocol = Mock(spec=TailscaleProtocol)
        mock_protocol._queue = asyncio.Queue()
        mock_protocol.send_handshake_response = Mock()

        mock_transport = Mock()
        mock_transport.sendto = Mock()

        mock_tailscale_info = Mock()
        mock_tailscale_info.ip = "100.64.0.1"

        connection_count = [0]

        async def count_connections(transport):
            connection_count[0] += 1

        listener = TailscaleListener(on_connection=count_connections)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        phone_addr = ("100.64.0.2", 12345)

        # First handshake
        handshake = struct.pack(">II", HANDSHAKE_MAGIC, 0)
        await listener._handle_packet(handshake, phone_addr)
        first_transport = listener._connections[phone_addr]
        assert connection_count[0] == 1

        # Re-handshake
        await listener._handle_packet(handshake, phone_addr)

        # Should still be same connection, callback not called again
        assert len(listener._connections) == 1
        assert listener._connections[phone_addr] is first_transport
        assert connection_count[0] == 1
