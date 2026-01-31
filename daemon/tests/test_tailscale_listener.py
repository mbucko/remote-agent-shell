"""Tests for Tailscale listener.

These tests verify:
- Listener binds to Tailscale IP (not 0.0.0.0)
- Handshake response is sent correctly
- Re-handshake from same address is handled
- Auth messages are routed to correct transport
"""

import asyncio
import struct
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.tailscale.transport import HANDSHAKE_MAGIC, TailscaleProtocol


class TestTailscaleListenerBinding:
    """Tests for Tailscale listener IP binding."""

    @pytest.mark.asyncio
    async def test_listener_binds_to_tailscale_ip(self):
        """Verify listener binds to specific Tailscale IP, not 0.0.0.0."""
        from ras.tailscale.listener import TailscaleListener

        listener = TailscaleListener(port=9876)

        # Track what address was passed to create_datagram_endpoint
        captured_local_addr = None

        # Mock detect_tailscale to return a specific IP
        with patch("ras.tailscale.listener.detect_tailscale") as mock_detect:
            mock_detect.return_value = MagicMock(ip="100.64.0.1", interface="utun0")

            # Mock the event loop's create_datagram_endpoint
            mock_transport = MagicMock()
            mock_transport.get_extra_info.return_value = MagicMock()
            mock_protocol = MagicMock()

            async def capture_endpoint(protocol_factory, local_addr=None, **kwargs):
                nonlocal captured_local_addr
                captured_local_addr = local_addr
                return mock_transport, protocol_factory()

            # Patch at module level where asyncio is used
            with patch("ras.tailscale.listener.asyncio.get_running_loop") as mock_loop:
                mock_loop_instance = MagicMock()
                mock_loop_instance.create_datagram_endpoint = capture_endpoint
                mock_loop.return_value = mock_loop_instance

                # Also prevent the receive loop from running
                with patch.object(listener, "_receive_loop", return_value=asyncio.sleep(0)):
                    await listener.start()

                    # Verify it bound to Tailscale IP, not 0.0.0.0
                    assert captured_local_addr is not None
                    assert captured_local_addr[0] == "100.64.0.1"
                    assert captured_local_addr[1] == 9876

    @pytest.mark.asyncio
    async def test_listener_not_started_without_tailscale(self):
        """Verify listener doesn't start when Tailscale not detected."""
        from ras.tailscale.listener import TailscaleListener

        listener = TailscaleListener(port=9876)

        with patch("ras.tailscale.listener.detect_tailscale") as mock_detect:
            mock_detect.return_value = None

            result = await listener.start()

            assert result is False
            assert listener.is_available is False


class TestHandshakeResponse:
    """Tests for handshake response handling."""

    def test_handshake_response_format(self):
        """Verify handshake response has correct format."""
        protocol = TailscaleProtocol()
        protocol._transport = MagicMock()

        # Call send_handshake_response
        addr = ("100.64.0.2", 12345)
        protocol.send_handshake_response(addr)

        # Verify sendto was called
        protocol._transport.sendto.assert_called_once()

        # Get the sent data
        call_args = protocol._transport.sendto.call_args
        sent_data = call_args[0][0]
        sent_addr = call_args[0][1]

        # Verify format: 8 bytes, starts with HANDSHAKE_MAGIC
        assert len(sent_data) == 8
        magic, reserved = struct.unpack(">II", sent_data)
        assert magic == HANDSHAKE_MAGIC
        assert reserved == 0
        assert sent_addr == addr

    def test_handshake_response_not_sent_without_transport(self):
        """Verify no error when transport is None."""
        protocol = TailscaleProtocol()
        protocol._transport = None

        # Should not raise
        protocol.send_handshake_response(("100.64.0.2", 12345))


class TestReHandshake:
    """Tests for re-handshake from same address."""

    @pytest.mark.asyncio
    async def test_re_handshake_sends_response(self):
        """Verify re-handshake from same address sends response but doesn't create new transport."""
        from ras.tailscale.listener import TailscaleListener

        on_connection_called = []

        async def on_connection(transport):
            on_connection_called.append(transport)

        listener = TailscaleListener(port=9876, on_connection=on_connection)

        # Set up mocks
        mock_transport = MagicMock()
        mock_protocol = MagicMock(spec=TailscaleProtocol)
        mock_protocol.send_handshake_response = MagicMock()

        listener._transport = mock_transport
        listener._protocol = mock_protocol
        listener._running = True
        listener._connections = {}

        addr = ("100.64.0.2", 12345)

        # First handshake - should create transport
        await listener._handle_handshake(addr)
        assert len(on_connection_called) == 1
        assert addr in listener._connections

        # Second handshake from same address - should send response but not create new transport
        mock_protocol.send_handshake_response.reset_mock()
        await listener._handle_handshake(addr)

        # Should have sent response
        mock_protocol.send_handshake_response.assert_called_with(addr)
        # Should still only have one connection
        assert len(on_connection_called) == 1


class TestAuthRouting:
    """Tests for routing auth messages to correct transport."""

    @pytest.mark.asyncio
    async def test_auth_message_routed_to_transport(self):
        """Verify non-handshake packets are routed to existing transport."""
        from ras.tailscale.listener import TailscaleListener

        listener = TailscaleListener(port=9876)
        listener._running = True

        # Create a mock transport with an enqueue method
        mock_ts_transport = MagicMock()
        mock_ts_transport.enqueue = AsyncMock()

        addr = ("100.64.0.2", 12345)
        listener._connections = {addr: mock_ts_transport}

        # Send a non-handshake packet (auth data)
        auth_data = b"\x00\x00\x00\x10test-device-id\x00" + b"x" * 32

        await listener._handle_packet(auth_data, addr)

        # Verify it was routed to the transport
        mock_ts_transport.enqueue.assert_called_once_with(auth_data, addr)

    @pytest.mark.asyncio
    async def test_unknown_address_logged(self):
        """Verify packets from unknown address are logged."""
        from ras.tailscale.listener import TailscaleListener

        listener = TailscaleListener(port=9876)
        listener._running = True
        listener._connections = {}

        addr = ("100.64.0.99", 99999)
        data = b"unknown data"

        # Should not raise, just log warning
        with patch("ras.tailscale.listener.logger") as mock_logger:
            await listener._handle_packet(data, addr)
            # Should log warning about unknown address
            mock_logger.warning.assert_called()


class TestHandshakeDetection:
    """Tests for detecting handshake packets."""

    @pytest.mark.asyncio
    async def test_handshake_packet_detected(self):
        """Verify handshake packets are correctly identified."""
        from ras.tailscale.listener import TailscaleListener

        listener = TailscaleListener(port=9876)
        listener._running = True
        listener._protocol = MagicMock()
        listener._protocol.send_handshake_response = MagicMock()
        listener._transport = MagicMock()
        listener._connections = {}

        async def mock_on_connection(transport):
            pass

        listener._on_connection = mock_on_connection

        addr = ("100.64.0.2", 12345)

        # Create a valid handshake packet
        handshake = struct.pack(">II", HANDSHAKE_MAGIC, 0)

        await listener._handle_packet(handshake, addr)

        # Should have called send_handshake_response
        listener._protocol.send_handshake_response.assert_called_with(addr)

    @pytest.mark.asyncio
    async def test_non_handshake_8_bytes_not_treated_as_handshake(self):
        """Verify 8-byte packets without magic are not treated as handshakes."""
        from ras.tailscale.listener import TailscaleListener

        listener = TailscaleListener(port=9876)
        listener._running = True
        listener._protocol = MagicMock()
        listener._connections = {}

        addr = ("100.64.0.2", 12345)

        # Create an 8-byte packet that's NOT a handshake
        not_handshake = struct.pack(">II", 0x12345678, 0)

        # Should not call send_handshake_response (no connection for this addr)
        await listener._handle_packet(not_handshake, addr)

        listener._protocol.send_handshake_response.assert_not_called()
