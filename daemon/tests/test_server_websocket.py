"""Tests for LAN Direct WebSocket endpoint in UnifiedServer."""

import asyncio
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from aiohttp import WSMsgType
from aiohttp.client_exceptions import WSServerHandshakeError
from aiohttp.test_utils import AioHTTPTestCase

from ras.crypto import compute_signaling_hmac, derive_key
from ras.proto.ras import LanDirectAuthRequest, LanDirectAuthResponse
from ras.server import UnifiedServer


class MockDeviceStore:
    """Mock device store for testing."""

    def __init__(self):
        self.devices = {}

    def get(self, device_id: str):
        return self.devices.get(device_id)

    def add_device(self, device_id: str, name: str, master_secret: bytes):
        mock_device = MagicMock()
        mock_device.device_id = device_id
        mock_device.name = name
        mock_device.master_secret = master_secret
        self.devices[device_id] = mock_device


class MockIpProvider:
    """Mock IP provider for testing."""

    async def get_ip(self) -> str:
        return "127.0.0.1"


class TestWebSocketEndpoint(AioHTTPTestCase):
    """Test the /ws/{device_id} WebSocket endpoint."""

    async def get_application(self):
        """Set up test application with UnifiedServer."""
        self.device_store = MockDeviceStore()
        self.on_device_connected = AsyncMock()

        self.server = UnifiedServer(
            device_store=self.device_store,
            on_device_connected=self.on_device_connected,
        )
        return self.server.app

    async def test_websocket_unknown_device_returns_404(self):
        """Should return 404 for unknown device."""
        with pytest.raises(WSServerHandshakeError) as exc_info:
            async with self.client.ws_connect("/ws/unknown-device-id"):
                pass
        assert exc_info.value.status == 404

    async def test_websocket_auth_success(self):
        """Should authenticate successfully with valid credentials."""
        # Setup device
        device_id = "test-device-123"
        master_secret = b"test-master-secret-exactly-32-!!"
        self.device_store.add_device(device_id, "Test Device", master_secret)

        auth_key = derive_key(master_secret, "auth")
        timestamp = int(time.time())
        signature = compute_signaling_hmac(auth_key, device_id, timestamp, b"")

        auth_request = LanDirectAuthRequest(
            device_id=device_id,
            timestamp=timestamp,
            signature=signature.hex(),
        )

        async with self.client.ws_connect(f"/ws/{device_id}") as ws:
            # Send auth
            await ws.send_bytes(bytes(auth_request))

            # Should receive auth response
            msg = await ws.receive()
            assert msg.type == WSMsgType.BINARY

            response = LanDirectAuthResponse().parse(msg.data)
            assert response.status == "authenticated"

            # Close cleanly
            await ws.close()

        # Should have called on_device_connected
        assert self.on_device_connected.called

    async def test_websocket_auth_invalid_hmac(self):
        """Should reject connection with invalid HMAC."""
        device_id = "test-device-123"
        master_secret = b"test-master-secret-exactly-32-!!"
        self.device_store.add_device(device_id, "Test Device", master_secret)

        timestamp = int(time.time())
        # Invalid signature
        invalid_signature = "0" * 64

        auth_request = LanDirectAuthRequest(
            device_id=device_id,
            timestamp=timestamp,
            signature=invalid_signature,
        )

        async with self.client.ws_connect(f"/ws/{device_id}") as ws:
            await ws.send_bytes(bytes(auth_request))

            # Should receive close with 4001
            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4001

    async def test_websocket_auth_expired_timestamp(self):
        """Should reject connection with expired timestamp."""
        device_id = "test-device-123"
        master_secret = b"test-master-secret-exactly-32-!!"
        self.device_store.add_device(device_id, "Test Device", master_secret)

        auth_key = derive_key(master_secret, "auth")
        # Timestamp from 1 minute ago (outside 30s tolerance)
        timestamp = int(time.time()) - 60
        signature = compute_signaling_hmac(auth_key, device_id, timestamp, b"")

        auth_request = LanDirectAuthRequest(
            device_id=device_id,
            timestamp=timestamp,
            signature=signature.hex(),
        )

        async with self.client.ws_connect(f"/ws/{device_id}") as ws:
            await ws.send_bytes(bytes(auth_request))

            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4001

    async def test_websocket_auth_wrong_device_id(self):
        """Should reject connection when auth device_id doesn't match URL."""
        device_id = "test-device-123"
        master_secret = b"test-master-secret-exactly-32-!!"
        self.device_store.add_device(device_id, "Test Device", master_secret)

        auth_key = derive_key(master_secret, "auth")
        timestamp = int(time.time())
        # Auth for different device
        wrong_device_id = "wrong-device-id"
        signature = compute_signaling_hmac(auth_key, wrong_device_id, timestamp, b"")

        auth_request = LanDirectAuthRequest(
            device_id=wrong_device_id,
            timestamp=timestamp,
            signature=signature.hex(),
        )

        async with self.client.ws_connect(f"/ws/{device_id}") as ws:
            await ws.send_bytes(bytes(auth_request))

            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4001


class TestValidateWsAuth:
    """Test the _validate_ws_auth helper method."""

    def setup_method(self):
        """Set up test fixtures."""
        self.device_store = MockDeviceStore()
        self.server = UnifiedServer(
            device_store=self.device_store,
        )

    def test_validate_valid_auth(self):
        """Should return True for valid auth."""
        device_id = "test-device"
        master_secret = b"test-master-secret-exactly-32-!!"
        auth_key = derive_key(master_secret, "auth")
        timestamp = int(time.time())
        signature = compute_signaling_hmac(auth_key, device_id, timestamp, b"")

        auth_request = LanDirectAuthRequest(
            device_id=device_id,
            timestamp=timestamp,
            signature=signature.hex(),
        )

        result = self.server._validate_ws_auth(
            bytes(auth_request),
            device_id,
            auth_key,
        )

        assert result is True

    def test_validate_invalid_protobuf(self):
        """Should return False for invalid protobuf."""
        result = self.server._validate_ws_auth(
            b"not valid protobuf",
            "device-id",
            b"auth-key",
        )

        assert result is False

    def test_validate_device_id_mismatch(self):
        """Should return False when device_id doesn't match."""
        device_id = "correct-device"
        wrong_device_id = "wrong-device"
        auth_key = b"x" * 32
        timestamp = int(time.time())
        signature = compute_signaling_hmac(auth_key, wrong_device_id, timestamp, b"")

        auth_request = LanDirectAuthRequest(
            device_id=wrong_device_id,
            timestamp=timestamp,
            signature=signature.hex(),
        )

        result = self.server._validate_ws_auth(
            bytes(auth_request),
            device_id,  # Expected device_id is different
            auth_key,
        )

        assert result is False

    def test_validate_expired_timestamp(self):
        """Should return False for expired timestamp."""
        device_id = "test-device"
        auth_key = b"x" * 32
        timestamp = int(time.time()) - 60  # 1 minute ago
        signature = compute_signaling_hmac(auth_key, device_id, timestamp, b"")

        auth_request = LanDirectAuthRequest(
            device_id=device_id,
            timestamp=timestamp,
            signature=signature.hex(),
        )

        result = self.server._validate_ws_auth(
            bytes(auth_request),
            device_id,
            auth_key,
        )

        assert result is False

    def test_validate_invalid_signature_hex(self):
        """Should return False for invalid signature hex."""
        device_id = "test-device"
        auth_key = b"x" * 32
        timestamp = int(time.time())

        auth_request = LanDirectAuthRequest(
            device_id=device_id,
            timestamp=timestamp,
            signature="not-hex!",
        )

        result = self.server._validate_ws_auth(
            bytes(auth_request),
            device_id,
            auth_key,
        )

        assert result is False

    def test_validate_invalid_hmac(self):
        """Should return False for invalid HMAC."""
        device_id = "test-device"
        auth_key = b"x" * 32
        timestamp = int(time.time())

        auth_request = LanDirectAuthRequest(
            device_id=device_id,
            timestamp=timestamp,
            signature="0" * 64,  # Wrong signature
        )

        result = self.server._validate_ws_auth(
            bytes(auth_request),
            device_id,
            auth_key,
        )

        assert result is False
