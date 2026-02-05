"""E2E tests for LAN Direct WebSocket auth flow.

These tests simulate the Android client flow end-to-end:
1. Start UnifiedServer with a paired device
2. Connect via WebSocket (like Android does)
3. Build and send LanDirectAuthRequest (same way Android does)
4. Receive LanDirectAuthResponse
5. Verify the full handshake succeeds/fails as expected
"""

import asyncio
import time
from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import WSMsgType
from aiohttp.client_exceptions import WSServerHandshakeError
from aiohttp.test_utils import AioHTTPTestCase

from ras.connection_manager import ConnectionManager
from ras.crypto import compute_signaling_hmac, derive_key
from ras.proto.ras import LanDirectAuthRequest, LanDirectAuthResponse
from ras.server import RateLimiter, UnifiedServer


class MockDeviceStore:
    """Mock device store for E2E testing."""

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
    async def get_ip(self) -> str:
        return "127.0.0.1"


# =========================================================================
# Helper: Build auth request like Android does
# =========================================================================

def build_auth_request_like_android(
    master_secret: bytes,
    device_id: str,
    timestamp: int | None = None,
) -> bytes:
    """Build a LanDirectAuthRequest the same way Android's LanDirectTransport does.

    This replicates the Android client flow:
    1. auth_key is already derived from master_secret (passed as authKey param)
    2. Compute signaling HMAC with device_id, timestamp, empty body
    3. Build protobuf and serialize
    """
    auth_key = derive_key(master_secret, "auth")
    if timestamp is None:
        timestamp = int(time.time())
    signature = compute_signaling_hmac(auth_key, device_id, timestamp, b"")

    request = LanDirectAuthRequest(
        device_id=device_id,
        timestamp=timestamp,
        signature=signature.hex(),
    )
    return bytes(request)


# =========================================================================
# E2E Tests: Full WebSocket auth flow
# =========================================================================

class TestLanDirectE2E(AioHTTPTestCase):
    """E2E test for LAN Direct WebSocket auth flow."""

    DEVICE_ID = "e2e-test-device-001"
    DEVICE_NAME = "E2E Test Phone"
    MASTER_SECRET = b"e2e-test-master-secret-32bytes!!"

    async def get_application(self):
        """Set up test application with UnifiedServer."""
        self.device_store = MockDeviceStore()
        self.ip_provider = MockIpProvider()
        self.on_device_connected = AsyncMock()

        self.ras_server = UnifiedServer(
            device_store=self.device_store,
            ip_provider=self.ip_provider,
            on_device_connected=self.on_device_connected,
        )
        return self.ras_server.app

    def _add_test_device(self):
        """Add the standard test device to the store."""
        self.device_store.add_device(
            self.DEVICE_ID, self.DEVICE_NAME, self.MASTER_SECRET
        )

    # =====================================================================
    # Success case
    # =====================================================================

    async def test_full_auth_flow_success(self):
        """Full E2E: derive key, build auth request, send via WS, get authenticated."""
        self._add_test_device()

        auth_bytes = build_auth_request_like_android(
            self.MASTER_SECRET, self.DEVICE_ID
        )

        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            # Send auth request (like Android does)
            await ws.send_bytes(auth_bytes)

            # Should receive auth response
            msg = await ws.receive()
            assert msg.type == WSMsgType.BINARY

            response = LanDirectAuthResponse().parse(msg.data)
            assert response.status == "authenticated"

            await ws.close()

        # Verify on_device_connected was called
        assert self.on_device_connected.called
        call_args = self.on_device_connected.call_args
        assert call_args[0][0] == self.DEVICE_ID  # device_id
        assert call_args[0][1] == self.DEVICE_NAME  # device_name

    # =====================================================================
    # Failure: using master_secret instead of derived key (the exact bug)
    # =====================================================================

    async def test_master_secret_instead_of_derived_key_fails(self):
        """Using master_secret directly (the double-derivation bug) must fail."""
        self._add_test_device()

        # Build auth request using master_secret directly (NOT derived key)
        # This is the exact bug: Android was passing master_secret to computeSignalingHmac
        timestamp = int(time.time())
        wrong_signature = compute_signaling_hmac(
            self.MASTER_SECRET,  # Bug: should be derive_key(MASTER_SECRET, "auth")
            self.DEVICE_ID,
            timestamp,
            b"",
        )

        request = LanDirectAuthRequest(
            device_id=self.DEVICE_ID,
            timestamp=timestamp,
            signature=wrong_signature.hex(),
        )

        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            await ws.send_bytes(bytes(request))

            # Should get closed with 4001
            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4001

        # on_device_connected should NOT have been called
        assert not self.on_device_connected.called

    # =====================================================================
    # Failure: expired timestamp
    # =====================================================================

    async def test_expired_timestamp_fails(self):
        """Auth request with timestamp >30s in the past must fail."""
        self._add_test_device()

        auth_key = derive_key(self.MASTER_SECRET, "auth")
        # 60 seconds ago - outside 30s tolerance
        timestamp = int(time.time()) - 60
        signature = compute_signaling_hmac(auth_key, self.DEVICE_ID, timestamp, b"")

        request = LanDirectAuthRequest(
            device_id=self.DEVICE_ID,
            timestamp=timestamp,
            signature=signature.hex(),
        )

        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            await ws.send_bytes(bytes(request))

            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4001

        assert not self.on_device_connected.called

    # =====================================================================
    # Failure: wrong device_id in auth request
    # =====================================================================

    async def test_wrong_device_id_fails(self):
        """Auth request with mismatched device_id must fail."""
        self._add_test_device()

        auth_key = derive_key(self.MASTER_SECRET, "auth")
        timestamp = int(time.time())
        wrong_device_id = "wrong-device-id"
        signature = compute_signaling_hmac(auth_key, wrong_device_id, timestamp, b"")

        request = LanDirectAuthRequest(
            device_id=wrong_device_id,
            timestamp=timestamp,
            signature=signature.hex(),
        )

        # Connect to the real device URL but send auth for wrong device
        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            await ws.send_bytes(bytes(request))

            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4001

        assert not self.on_device_connected.called

    # =====================================================================
    # Failure: unknown device
    # =====================================================================

    async def test_unknown_device_returns_404(self):
        """WebSocket to unknown device_id should return 404."""
        with pytest.raises(WSServerHandshakeError) as exc_info:
            async with self.client.ws_connect("/ws/nonexistent-device"):
                pass
        assert exc_info.value.status == 404

    # =====================================================================
    # Failure: invalid signature hex
    # =====================================================================

    async def test_invalid_signature_hex_fails(self):
        """Auth request with non-hex signature string must fail."""
        self._add_test_device()

        request = LanDirectAuthRequest(
            device_id=self.DEVICE_ID,
            timestamp=int(time.time()),
            signature="not-valid-hex-string!",
        )

        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            await ws.send_bytes(bytes(request))

            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4001

        assert not self.on_device_connected.called

    # =====================================================================
    # Failure: garbage data (not valid protobuf)
    # =====================================================================

    async def test_garbage_data_fails(self):
        """Sending non-protobuf data must fail."""
        self._add_test_device()

        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            await ws.send_bytes(b"this is not protobuf")

            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4001

        assert not self.on_device_connected.called

    # =====================================================================
    # Post-auth: message exchange through the peer
    # =====================================================================

    async def test_post_auth_message_exchange(self):
        """After auth, messages can be exchanged through the peer."""
        self._add_test_device()

        captured = {}
        received_messages = []

        async def on_connected(device_id, name, peer, auth_key):
            captured["peer"] = peer
            peer.on_message(lambda data: received_messages.append(data))

        self.on_device_connected.side_effect = on_connected

        auth_bytes = build_auth_request_like_android(
            self.MASTER_SECRET, self.DEVICE_ID
        )

        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            # Auth
            await ws.send_bytes(auth_bytes)
            msg = await ws.receive()
            assert msg.type == WSMsgType.BINARY
            assert LanDirectAuthResponse().parse(msg.data).status == "authenticated"

            # Client -> Server
            await ws.send_bytes(b"hello from client")
            await asyncio.sleep(0.05)
            assert received_messages == [b"hello from client"]

            # Server -> Client
            peer = captured["peer"]
            await peer.send(b"hello from server")
            msg = await ws.receive()
            assert msg.type == WSMsgType.BINARY
            assert msg.data == b"hello from server"

            await ws.close()

    # =====================================================================
    # Post-auth: encrypted message round-trip through ConnectionManager
    # =====================================================================

    async def test_encrypted_message_round_trip(self):
        """Messages through ConnectionManager are encrypted/decrypted."""
        self._add_test_device()

        class XorCodec:
            """Simple XOR codec for testing."""
            KEY = 0x42

            def encode(self, data: bytes) -> bytes:
                return bytes(b ^ self.KEY for b in data)

            def decode(self, data: bytes) -> bytes:
                return bytes(b ^ self.KEY for b in data)

        connection_manager = ConnectionManager()
        decrypted_messages = []

        async def on_connected(device_id, name, peer, auth_key):
            await connection_manager.add_connection(
                device_id, peer, XorCodec(),
                on_message=lambda data: decrypted_messages.append(data),
            )

        self.on_device_connected.side_effect = on_connected

        auth_bytes = build_auth_request_like_android(
            self.MASTER_SECRET, self.DEVICE_ID
        )

        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            # Auth
            await ws.send_bytes(auth_bytes)
            msg = await ws.receive()
            assert LanDirectAuthResponse().parse(msg.data).status == "authenticated"

            # Client sends encrypted message
            plaintext = b"secret message"
            encrypted = bytes(b ^ 0x42 for b in plaintext)
            await ws.send_bytes(encrypted)
            await asyncio.sleep(0.05)

            # ConnectionManager should have decrypted it
            assert decrypted_messages == [plaintext]

            # Server sends through ConnectionManager (auto-encrypts)
            conn = connection_manager.get_connection(self.DEVICE_ID)
            await conn.send(b"reply")
            msg = await ws.receive()
            assert msg.type == WSMsgType.BINARY
            expected_encrypted = bytes(b ^ 0x42 for b in b"reply")
            assert msg.data == expected_encrypted

            await ws.close()

        await connection_manager.close_all()

    # =====================================================================
    # Rate limiting: WebSocket connections by device
    # =====================================================================

    async def test_websocket_rate_limit_by_device(self):
        """WebSocket should be rate limited by device ID."""
        self._add_test_device()

        # Pre-fill the device limiter to exhaust it
        self.ras_server._device_limiter = RateLimiter(max_requests=2, window_seconds=60)
        self.ras_server._device_limiter.is_allowed(self.DEVICE_ID)
        self.ras_server._device_limiter.is_allowed(self.DEVICE_ID)

        # Next WS connection should be rate limited
        with pytest.raises(WSServerHandshakeError) as exc_info:
            async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}"):
                pass
        assert exc_info.value.status == 429

    # =====================================================================
    # Auth timeout: server closes with 4002
    # =====================================================================

    async def test_auth_timeout_closes_with_4002(self):
        """Server should close with 4002 if client doesn't send auth."""
        self._add_test_device()

        # Set very short timeout for testing
        self.ras_server.WS_AUTH_TIMEOUT = 0.1

        async with self.client.ws_connect(f"/ws/{self.DEVICE_ID}") as ws:
            # Don't send auth - just wait for timeout
            msg = await ws.receive()
            assert msg.type == WSMsgType.CLOSE
            assert ws.close_code == 4002
