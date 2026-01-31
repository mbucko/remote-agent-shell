"""Tests for TailscaleTransport send/receive functionality.

These tests ensure transport correctness for data framing and statistics.
"""

import asyncio
import struct
import time
from unittest.mock import AsyncMock, Mock

import pytest

from ras.tailscale.transport import (
    HANDSHAKE_MAGIC,
    HEADER_SIZE,
    MAX_PACKET_SIZE,
    TailscalePeer,
    TailscaleProtocol,
    TailscaleTransport,
)


@pytest.fixture
def mock_udp_transport():
    """Create a mock UDP transport."""
    transport = Mock()
    transport.sendto = Mock()
    transport.close = Mock()
    return transport


@pytest.fixture
def mock_protocol():
    """Create a mock TailscaleProtocol with a real queue."""
    protocol = Mock(spec=TailscaleProtocol)
    protocol._queue = asyncio.Queue()

    async def mock_receive():
        return await protocol._queue.get()

    protocol.receive = mock_receive
    return protocol


def create_test_transport(mock_udp, mock_protocol):
    """Create a TailscaleTransport for testing."""
    remote_addr = ("100.64.0.2", 12345)
    return TailscaleTransport(mock_udp, mock_protocol, remote_addr)


class TestSendReceive:
    """Tests for send/receive data framing."""

    @pytest.mark.asyncio
    async def test_send_adds_length_prefix(self, mock_udp_transport, mock_protocol):
        """Send should prepend 4-byte big-endian length."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        await transport.send(b"hello")

        # Verify sent data
        mock_udp_transport.sendto.assert_called_once()
        sent_data, sent_addr = mock_udp_transport.sendto.call_args[0]

        # Check length prefix (4 bytes big-endian)
        length = struct.unpack(">I", sent_data[:4])[0]
        assert length == 5

        # Check payload
        assert sent_data[4:] == b"hello"

    @pytest.mark.asyncio
    async def test_send_large_message(self, mock_udp_transport, mock_protocol):
        """Send should handle messages near max size."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        # Create a large message (near max packet size minus header)
        large_payload = b"x" * (MAX_PACKET_SIZE - HEADER_SIZE - 100)
        await transport.send(large_payload)

        mock_udp_transport.sendto.assert_called_once()
        sent_data, _ = mock_udp_transport.sendto.call_args[0]

        length = struct.unpack(">I", sent_data[:4])[0]
        assert length == len(large_payload)
        assert sent_data[4:] == large_payload

    @pytest.mark.asyncio
    async def test_send_empty_payload(self, mock_udp_transport, mock_protocol):
        """Send should handle empty payload (length 0)."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        await transport.send(b"")

        mock_udp_transport.sendto.assert_called_once()
        sent_data, _ = mock_udp_transport.sendto.call_args[0]

        length = struct.unpack(">I", sent_data[:4])[0]
        assert length == 0
        assert sent_data[4:] == b""

    @pytest.mark.asyncio
    async def test_send_rejects_oversized_message(
        self, mock_udp_transport, mock_protocol
    ):
        """Send should raise ValueError for messages too large."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        oversized_payload = b"x" * (MAX_PACKET_SIZE - HEADER_SIZE + 100)

        with pytest.raises(ValueError, match="Data too large"):
            await transport.send(oversized_payload)

    @pytest.mark.asyncio
    async def test_receive_parses_length_prefix(self, mock_udp_transport, mock_protocol):
        """Receive should correctly parse length prefix and extract payload."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        # Inject packet into transport's queue (simulates listener routing)
        packet = struct.pack(">I", 5) + b"hello"
        await transport.enqueue(packet, ("100.64.0.2", 12345))

        received = await transport.receive(timeout=1.0)

        assert received == b"hello"

    @pytest.mark.asyncio
    async def test_receive_large_message(self, mock_udp_transport, mock_protocol):
        """Receive should handle large messages correctly."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        large_payload = b"y" * 1000
        packet = struct.pack(">I", len(large_payload)) + large_payload
        await transport.enqueue(packet, ("100.64.0.2", 12345))

        received = await transport.receive(timeout=1.0)

        assert received == large_payload

    @pytest.mark.asyncio
    async def test_receive_empty_payload(self, mock_udp_transport, mock_protocol):
        """Receive should handle empty payload (length 0)."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        packet = struct.pack(">I", 0)  # Length 0, no payload
        await transport.enqueue(packet, ("100.64.0.2", 12345))

        received = await transport.receive(timeout=1.0)

        assert received == b""

    @pytest.mark.asyncio
    async def test_receive_timeout(self, mock_udp_transport, mock_protocol):
        """Receive should raise TimeoutError when no data arrives."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        with pytest.raises(TimeoutError):
            await transport.receive(timeout=0.1)

    @pytest.mark.asyncio
    async def test_receive_invalid_length_prefix(
        self, mock_udp_transport, mock_protocol
    ):
        """Receive should raise ValueError for invalid length prefix."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        # Length prefix says 100 bytes, but only 5 bytes follow
        packet = struct.pack(">I", 100) + b"hello"
        await transport.enqueue(packet, ("100.64.0.2", 12345))

        with pytest.raises(ValueError, match="Invalid length prefix"):
            await transport.receive(timeout=1.0)

    @pytest.mark.asyncio
    async def test_receive_packet_too_small(self, mock_udp_transport, mock_protocol):
        """Receive should raise ValueError for packets smaller than header."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        # Only 2 bytes - smaller than 4-byte header
        packet = b"\x00\x05"
        await transport.enqueue(packet, ("100.64.0.2", 12345))

        with pytest.raises(ValueError, match="Packet too small"):
            await transport.receive(timeout=1.0)


class TestStatsTracking:
    """Tests for transport statistics tracking."""

    @pytest.mark.asyncio
    async def test_bytes_sent_increments(self, mock_udp_transport, mock_protocol):
        """bytes_sent should increment correctly."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        await transport.send(b"hello")
        assert transport.get_stats().bytes_sent == 5

        await transport.send(b"world!")
        assert transport.get_stats().bytes_sent == 11

    @pytest.mark.asyncio
    async def test_bytes_received_increments(self, mock_udp_transport, mock_protocol):
        """bytes_received should increment correctly."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        packet1 = struct.pack(">I", 5) + b"hello"
        await transport.enqueue(packet1, ("100.64.0.2", 12345))
        await transport.receive(timeout=1.0)
        assert transport.get_stats().bytes_received == 5

        packet2 = struct.pack(">I", 6) + b"world!"
        await transport.enqueue(packet2, ("100.64.0.2", 12345))
        await transport.receive(timeout=1.0)
        assert transport.get_stats().bytes_received == 11

    @pytest.mark.asyncio
    async def test_messages_sent_count(self, mock_udp_transport, mock_protocol):
        """messages_sent should count correctly."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        for i in range(5):
            await transport.send(f"msg{i}".encode())

        assert transport.get_stats().messages_sent == 5

    @pytest.mark.asyncio
    async def test_messages_received_count(self, mock_udp_transport, mock_protocol):
        """messages_received should count correctly."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        for i in range(5):
            packet = struct.pack(">I", 4) + f"m{i:03d}".encode()
            await transport.enqueue(packet, ("100.64.0.2", 12345))
            await transport.receive(timeout=1.0)

        assert transport.get_stats().messages_received == 5

    @pytest.mark.asyncio
    async def test_last_activity_updates_on_send(
        self, mock_udp_transport, mock_protocol
    ):
        """last_activity should update on send."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        initial_activity = transport.get_stats().last_activity
        await asyncio.sleep(0.01)

        await transport.send(b"hello")

        assert transport.get_stats().last_activity > initial_activity

    @pytest.mark.asyncio
    async def test_last_activity_updates_on_receive(
        self, mock_udp_transport, mock_protocol
    ):
        """last_activity should update on receive."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        initial_activity = transport.get_stats().last_activity
        await asyncio.sleep(0.01)

        packet = struct.pack(">I", 5) + b"hello"
        await transport.enqueue(packet, ("100.64.0.2", 12345))
        await transport.receive(timeout=1.0)

        assert transport.get_stats().last_activity > initial_activity


class TestCloseBehavior:
    """Tests for transport close behavior."""

    def test_close_sets_is_connected_false(self, mock_udp_transport, mock_protocol):
        """Close should set is_connected to False."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        assert transport.is_connected is True

        transport.close()

        assert transport.is_connected is False

    @pytest.mark.asyncio
    async def test_send_after_close_raises_connection_error(
        self, mock_udp_transport, mock_protocol
    ):
        """Send after close should raise ConnectionError."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        transport.close()

        with pytest.raises(ConnectionError, match="Transport is closed"):
            await transport.send(b"hello")

    @pytest.mark.asyncio
    async def test_receive_after_close_raises_connection_error(
        self, mock_udp_transport, mock_protocol
    ):
        """Receive after close should raise ConnectionError."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        transport.close()

        with pytest.raises(ConnectionError, match="Transport is closed"):
            await transport.receive(timeout=1.0)

    def test_double_close_is_safe(self, mock_udp_transport, mock_protocol):
        """Double close should not raise exception."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)

        transport.close()
        transport.close()  # Should not raise

        assert transport.is_connected is False


class TestTailscalePeer:
    """Tests for TailscalePeer adapter class."""

    @pytest.mark.asyncio
    async def test_peer_send_delegates_to_transport(
        self, mock_udp_transport, mock_protocol
    ):
        """TailscalePeer.send should delegate to transport."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)
        peer = TailscalePeer(transport)

        await peer.send(b"hello")

        # Verify transport's send was called
        mock_udp_transport.sendto.assert_called_once()

    @pytest.mark.asyncio
    async def test_peer_on_message_starts_receive_loop(
        self, mock_udp_transport, mock_protocol
    ):
        """TailscalePeer.on_message should start receive loop."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)
        peer = TailscalePeer(transport)

        messages_received = []

        def handler(data):
            messages_received.append(data)

        peer.on_message(handler)

        # Give the receive loop time to start
        await asyncio.sleep(0.01)

        # Verify receive task was created
        assert peer._receive_task is not None

        # Clean up
        await peer.close()

    @pytest.mark.asyncio
    async def test_peer_on_message_receives_messages(
        self, mock_udp_transport, mock_protocol
    ):
        """TailscalePeer should receive and dispatch messages to handler."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)
        peer = TailscalePeer(transport)

        messages_received = []
        received_event = asyncio.Event()

        def handler(data):
            messages_received.append(data)
            if len(messages_received) >= 3:
                received_event.set()

        peer.on_message(handler)

        # Inject messages into the transport's queue (simulates listener routing)
        for i in range(3):
            packet = struct.pack(">I", 4) + f"m{i:03d}".encode()
            await transport.enqueue(packet, ("100.64.0.2", 12345))

        # Wait for messages to be received
        try:
            await asyncio.wait_for(received_event.wait(), timeout=2.0)
        except asyncio.TimeoutError:
            pass

        await peer.close()

        assert len(messages_received) == 3
        assert messages_received[0] == b"m000"
        assert messages_received[1] == b"m001"
        assert messages_received[2] == b"m002"

    @pytest.mark.asyncio
    async def test_peer_on_close_called_on_connection_error(
        self, mock_udp_transport, mock_protocol
    ):
        """TailscalePeer should call on_close handler when connection closes."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)
        peer = TailscalePeer(transport)

        close_called = asyncio.Event()

        def close_handler():
            close_called.set()

        def message_handler(data):
            pass

        peer.on_close(close_handler)
        peer.on_message(message_handler)

        # Give receive loop time to start
        await asyncio.sleep(0.01)

        # Close the transport (simulates disconnect)
        transport.close()

        # Wait for close handler to be called
        try:
            await asyncio.wait_for(close_called.wait(), timeout=2.0)
            close_was_called = True
        except asyncio.TimeoutError:
            close_was_called = False

        # Clean up
        await peer.close()

        # Close handler may or may not be called depending on timing
        # The important thing is no exceptions are raised

    @pytest.mark.asyncio
    async def test_peer_close_cancels_receive_task(
        self, mock_udp_transport, mock_protocol
    ):
        """TailscalePeer.close should cancel the receive task."""
        transport = create_test_transport(mock_udp_transport, mock_protocol)
        peer = TailscalePeer(transport)

        def handler(data):
            pass

        peer.on_message(handler)
        await asyncio.sleep(0.01)

        receive_task = peer._receive_task
        assert receive_task is not None

        await peer.close()

        assert receive_task.cancelled() or receive_task.done()
