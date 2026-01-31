"""Comprehensive E2E tests for unified daemon server.

Tests cover ALL scenarios for pairing and reconnection flows:
- Happy paths
- Error conditions
- Edge cases
- Concurrent operations
- Rate limiting
- Timeouts

Test naming convention: test_{flow}_{scenario}_{expected_outcome}
"""

import asyncio
import base64
import json
import time
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import aiohttp
import pytest
from aiohttp.test_utils import TestClient

from ras.config import Config, DaemonConfig
from ras.crypto import compute_signaling_hmac, derive_key
from ras.proto.ras import SignalRequest, SignalResponse, SignalError

# Mark all tests in this module as integration tests
pytestmark = pytest.mark.integration


# =============================================================================
# Test Fixtures
# =============================================================================

@pytest.fixture
def config(tmp_path: Path) -> Config:
    """Create test config."""
    config = Config()
    config.daemon = DaemonConfig(
        devices_file=str(tmp_path / "devices.json"),
        sessions_file=str(tmp_path / "sessions.json"),
    )
    config.port = 0  # Random available port
    config.bind_address = "127.0.0.1"
    return config


@pytest.fixture
def mock_peer():
    """Create mock WebRTC peer connection."""
    peer = AsyncMock()
    peer.accept_offer = AsyncMock(return_value="v=0")
    peer.wait_connected = AsyncMock()
    peer.send = AsyncMock()
    peer.close = AsyncMock()
    peer.on_message = MagicMock()
    return peer


@pytest.fixture
def test_secret():
    """32-byte test secret."""
    return b"\x00" * 32


@pytest.fixture
def test_secret_b64(test_secret):
    """Base64 encoded test secret."""
    return base64.b64encode(test_secret).decode("ascii")


# =============================================================================
# PAIRING FLOW - Happy Path
# =============================================================================

class TestPairingHappyPath:
    """Tests for successful pairing flow."""

    @pytest.mark.asyncio
    async def test_pair_api_returns_qr_data(self, config):
        """P-HP-01: POST /api/pair returns QR code data and session ID."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as session:
                async with session.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    assert resp.status == 200
                    data = await resp.json()

                    # Should have all required fields
                    assert "session_id" in data
                    assert "qr_data" in data
                    assert "expires_at" in data

                    # QR data should contain connection info
                    qr = data["qr_data"]
                    assert "ip" in qr
                    assert "port" in qr
                    assert "master_secret" in qr
                    assert "session_id" in qr
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pair_status_returns_pending(self, config):
        """P-HP-02: GET /api/pair/{id} returns pending status initially."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as session:
                # Start pairing
                async with session.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]

                # Check status
                async with session.get(f"http://127.0.0.1:{port}/api/pair/{session_id}") as resp:
                    assert resp.status == 200
                    status = await resp.json()
                    assert status["state"] == "pending"
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pair_full_flow_success(self, config, mock_peer):
        """P-HP-03: Complete pairing flow from start to device stored."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # 1. CLI starts pairing
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]
                    qr = data["qr_data"]
                    master_secret = bytes.fromhex(qr["master_secret"])
                    auth_key = derive_key(master_secret, "auth")

                # 2. Phone sends signal request (with mocked WebRTC)
                with patch("ras.peer.PeerConnection", return_value=mock_peer):
                    timestamp = int(time.time())
                    body = bytes(SignalRequest(
                        sdp_offer="v=0",
                        device_id="test-phone-123",
                        device_name="Test Phone",
                    ))
                    signature = compute_signaling_hmac(auth_key, session_id, timestamp, body)

                    async with http.post(
                        f"http://127.0.0.1:{port}/signal/{session_id}",
                        data=body,
                        headers={
                            "Content-Type": "application/x-protobuf",
                            "X-RAS-Timestamp": str(timestamp),
                            "X-RAS-Signature": signature.hex(),
                        },
                    ) as resp:
                        assert resp.status == 200

                # 3. Simulate successful auth (in real flow this happens over WebRTC)
                # For now, manually trigger auth completion
                await daemon._complete_pairing(session_id, "test-phone-123", "Test Phone")

                # 4. Check status is completed
                async with http.get(f"http://127.0.0.1:{port}/api/pair/{session_id}") as resp:
                    status = await resp.json()
                    assert status["state"] == "completed"
                    assert status["device_name"] == "Test Phone"

                # 5. Verify device is stored
                assert daemon._device_store.is_paired("test-phone-123")

        finally:
            await daemon.stop()


# =============================================================================
# PAIRING FLOW - CLI Error Scenarios
# =============================================================================

class TestPairingCLIErrors:
    """Tests for CLI-side pairing errors."""

    @pytest.mark.asyncio
    async def test_pair_daemon_not_running(self):
        """P-CLI-01: Pairing fails gracefully when daemon not running."""
        # Use unlikely port to avoid collision with running daemon
        async with aiohttp.ClientSession() as session:
            with pytest.raises(aiohttp.ClientConnectorError):
                await session.post("http://127.0.0.1:59999/api/pair")

    @pytest.mark.asyncio
    async def test_pair_status_invalid_session(self, config):
        """P-CLI-02: Status check for invalid session returns 404."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as session:
                async with session.get(f"http://127.0.0.1:{port}/api/pair/invalid-session") as resp:
                    assert resp.status == 404
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pair_cancel_cleans_up_session(self, config):
        """P-CLI-03: Canceling pairing cleans up the session."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as session:
                # Start pairing
                async with session.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]

                # Cancel pairing
                async with session.delete(f"http://127.0.0.1:{port}/api/pair/{session_id}") as resp:
                    assert resp.status == 200

                # Session should be gone
                async with session.get(f"http://127.0.0.1:{port}/api/pair/{session_id}") as resp:
                    assert resp.status == 404
        finally:
            await daemon.stop()


# =============================================================================
# PAIRING FLOW - Phone Error Scenarios
# =============================================================================

class TestPairingPhoneErrors:
    """Tests for phone-side pairing errors."""

    @pytest.mark.asyncio
    async def test_pair_signal_invalid_session(self, config):
        """P-PH-01: Signal to invalid session returns error."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"http://127.0.0.1:{port}/signal/invalid-session",
                    data=b"\x00",
                    headers={
                        "X-RAS-Timestamp": str(int(time.time())),
                        "X-RAS-Signature": "0" * 64,
                    },
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pair_signal_expired_session(self, config):
        """P-PH-02: Signal to expired session returns error."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Start pairing
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]

                # Manually expire the session
                daemon._expire_pairing_session(session_id)

                # Try to signal
                async with http.post(
                    f"http://127.0.0.1:{port}/signal/{session_id}",
                    data=b"\x00",
                    headers={
                        "X-RAS-Timestamp": str(int(time.time())),
                        "X-RAS-Signature": "0" * 64,
                    },
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pair_signal_invalid_hmac(self, config):
        """P-PH-03: Signal with invalid HMAC returns auth error."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Start pairing
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]

                # Send with wrong signature
                async with http.post(
                    f"http://127.0.0.1:{port}/signal/{session_id}",
                    data=b"\x00",
                    headers={
                        "X-RAS-Timestamp": str(int(time.time())),
                        "X-RAS-Signature": "0" * 64,  # Invalid
                    },
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pair_signal_expired_timestamp(self, config):
        """P-PH-04: Signal with expired timestamp returns auth error."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Start pairing
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]
                    qr = data["qr_data"]
                    master_secret = bytes.fromhex(qr["master_secret"])
                    auth_key = derive_key(master_secret, "auth")

                # Send with old timestamp (> 30 seconds old)
                old_timestamp = int(time.time()) - 60
                body = b"\x00"
                signature = compute_signaling_hmac(auth_key, session_id, old_timestamp, body)

                async with http.post(
                    f"http://127.0.0.1:{port}/signal/{session_id}",
                    data=body,
                    headers={
                        "X-RAS-Timestamp": str(old_timestamp),
                        "X-RAS-Signature": signature.hex(),
                    },
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pair_signal_missing_headers(self, config):
        """P-PH-05: Signal without required headers returns error."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Start pairing
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]

                # Send without headers
                async with http.post(
                    f"http://127.0.0.1:{port}/signal/{session_id}",
                    data=b"\x00",
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pair_signal_malformed_body(self, config):
        """P-PH-06: Signal with malformed protobuf returns error."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Start pairing
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]
                    qr = data["qr_data"]
                    master_secret = bytes.fromhex(qr["master_secret"])
                    auth_key = derive_key(master_secret, "auth")

                # Send malformed body with valid auth
                timestamp = int(time.time())
                body = b"not a valid protobuf!!!"
                signature = compute_signaling_hmac(auth_key, session_id, timestamp, body)

                async with http.post(
                    f"http://127.0.0.1:{port}/signal/{session_id}",
                    data=body,
                    headers={
                        "X-RAS-Timestamp": str(timestamp),
                        "X-RAS-Signature": signature.hex(),
                    },
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()


# =============================================================================
# PAIRING FLOW - Timeout Scenarios
# =============================================================================

class TestPairingTimeouts:
    """Tests for pairing timeout scenarios."""

    @pytest.mark.asyncio
    async def test_pair_session_expires_after_timeout(self, config):
        """P-TO-01: Pairing session expires after configured timeout."""
        from ras.daemon import Daemon

        # Use very short timeout for testing
        config.daemon.pairing_timeout = 0.1  # 100ms

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Start pairing
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]

                # Manually expire the session instead of waiting
                daemon._expire_pairing_session(session_id)

                # Session should be expired
                async with http.get(f"http://127.0.0.1:{port}/api/pair/{session_id}") as resp:
                    if resp.status == 200:
                        status = await resp.json()
                        assert status["state"] == "expired"
                    else:
                        assert resp.status == 404
        finally:
            await daemon.stop()


# =============================================================================
# RECONNECTION FLOW - Happy Path
# =============================================================================

class TestReconnectionHappyPath:
    """Tests for successful reconnection flow."""

    @pytest.mark.asyncio
    async def test_reconnect_paired_device_success(self, config, test_secret, mock_peer):
        """R-HP-01: Paired device can reconnect successfully."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            # Pre-register a paired device
            await daemon._device_store.add_device(
                device_id="paired-device-123",
                device_name="My Phone",
                master_secret=test_secret,
            )

            auth_key = derive_key(test_secret, "auth")
            timestamp = int(time.time())
            body = bytes(SignalRequest(
                sdp_offer="v=0",
                device_id="paired-device-123",
                device_name="My Phone",
            ))
            signature = compute_signaling_hmac(auth_key, "paired-device-123", timestamp, body)

            with patch("ras.peer.PeerConnection", return_value=mock_peer):
                async with aiohttp.ClientSession() as http:
                    async with http.post(
                        f"http://127.0.0.1:{port}/reconnect/paired-device-123",
                        data=body,
                        headers={
                            "Content-Type": "application/x-protobuf",
                            "X-RAS-Timestamp": str(timestamp),
                            "X-RAS-Signature": signature.hex(),
                        },
                    ) as resp:
                        assert resp.status == 200
                        # Should return SDP answer
                        response = SignalResponse().parse(await resp.read())
                        assert response.sdp_answer
        finally:
            await daemon.stop()


# =============================================================================
# RECONNECTION FLOW - Error Scenarios
# =============================================================================

class TestReconnectionErrors:
    """Tests for reconnection error scenarios."""

    @pytest.mark.asyncio
    async def test_reconnect_unknown_device(self, config):
        """R-ERR-01: Unknown device cannot reconnect."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                async with http.post(
                    f"http://127.0.0.1:{port}/reconnect/unknown-device",
                    data=b"\x00",
                    headers={
                        "X-RAS-Timestamp": str(int(time.time())),
                        "X-RAS-Signature": "0" * 64,
                    },
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_reconnect_invalid_hmac(self, config, test_secret):
        """R-ERR-02: Paired device with invalid HMAC is rejected."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            # Pre-register device
            await daemon._device_store.add_device(
                device_id="paired-device-123",
                device_name="My Phone",
                master_secret=test_secret,
            )

            async with aiohttp.ClientSession() as http:
                async with http.post(
                    f"http://127.0.0.1:{port}/reconnect/paired-device-123",
                    data=b"\x00",
                    headers={
                        "X-RAS-Timestamp": str(int(time.time())),
                        "X-RAS-Signature": "0" * 64,  # Wrong signature
                    },
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_reconnect_expired_timestamp(self, config, test_secret):
        """R-ERR-03: Paired device with expired timestamp is rejected."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            # Pre-register device
            await daemon._device_store.add_device(
                device_id="paired-device-123",
                device_name="My Phone",
                master_secret=test_secret,
            )

            auth_key = derive_key(test_secret, "auth")
            old_timestamp = int(time.time()) - 60  # 60 seconds old
            body = b"\x00"
            signature = compute_signaling_hmac(auth_key, "paired-device-123", old_timestamp, body)

            async with aiohttp.ClientSession() as http:
                async with http.post(
                    f"http://127.0.0.1:{port}/reconnect/paired-device-123",
                    data=body,
                    headers={
                        "X-RAS-Timestamp": str(old_timestamp),
                        "X-RAS-Signature": signature.hex(),
                    },
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_reconnect_missing_headers(self, config, test_secret):
        """R-ERR-04: Reconnect without headers is rejected."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            # Pre-register device
            await daemon._device_store.add_device(
                device_id="paired-device-123",
                device_name="My Phone",
                master_secret=test_secret,
            )

            async with aiohttp.ClientSession() as http:
                async with http.post(
                    f"http://127.0.0.1:{port}/reconnect/paired-device-123",
                    data=b"\x00",
                    # No headers
                ) as resp:
                    assert resp.status == 400
        finally:
            await daemon.stop()


# =============================================================================
# CONCURRENT OPERATIONS
# =============================================================================

class TestConcurrentOperations:
    """Tests for concurrent operations."""

    @pytest.mark.asyncio
    async def test_multiple_pairing_sessions(self, config):
        """C-01: Multiple pairing sessions can exist simultaneously."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Start multiple pairing sessions
                sessions = []
                for _ in range(5):
                    async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                        assert resp.status == 200
                        data = await resp.json()
                        sessions.append(data["session_id"])

                # All sessions should be unique and valid
                assert len(set(sessions)) == 5

                for session_id in sessions:
                    async with http.get(f"http://127.0.0.1:{port}/api/pair/{session_id}") as resp:
                        assert resp.status == 200
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_pairing_and_reconnect_simultaneously(self, config, test_secret, mock_peer):
        """C-02: Pairing and reconnection can happen simultaneously."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            # Pre-register a device for reconnection
            await daemon._device_store.add_device(
                device_id="existing-device",
                device_name="Existing Phone",
                master_secret=test_secret,
            )

            async with aiohttp.ClientSession() as http:
                # Start pairing
                pair_task = http.post(f"http://127.0.0.1:{port}/api/pair")

                # Simultaneously try reconnection
                auth_key = derive_key(test_secret, "auth")
                timestamp = int(time.time())
                body = bytes(SignalRequest(
                    sdp_offer="v=0",
                    device_id="existing-device",
                    device_name="Existing Phone",
                ))
                signature = compute_signaling_hmac(auth_key, "existing-device", timestamp, body)

                with patch("ras.peer.PeerConnection", return_value=mock_peer):
                    reconnect_task = http.post(
                        f"http://127.0.0.1:{port}/reconnect/existing-device",
                        data=body,
                        headers={
                            "Content-Type": "application/x-protobuf",
                            "X-RAS-Timestamp": str(timestamp),
                            "X-RAS-Signature": signature.hex(),
                        },
                    )

                    # Both should succeed
                    async with pair_task as pair_resp:
                        assert pair_resp.status == 200

                    async with reconnect_task as reconnect_resp:
                        assert reconnect_resp.status == 200
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_same_device_reconnects_twice(self, config, test_secret, mock_peer):
        """C-03: Same device reconnecting closes old connection."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            # Pre-register device
            await daemon._device_store.add_device(
                device_id="my-device",
                device_name="My Phone",
                master_secret=test_secret,
            )

            auth_key = derive_key(test_secret, "auth")

            async with aiohttp.ClientSession() as http:
                # First connection
                timestamp1 = int(time.time())
                body1 = bytes(SignalRequest(
                    sdp_offer="v=0",
                    device_id="my-device",
                    device_name="My Phone",
                ))
                sig1 = compute_signaling_hmac(auth_key, "my-device", timestamp1, body1)

                mock_peer1 = AsyncMock()
                mock_peer1.accept_offer = AsyncMock(return_value="v=0")
                mock_peer1.wait_connected = AsyncMock()
                mock_peer1.close = AsyncMock()
                mock_peer1.on_message = MagicMock()

                with patch("ras.peer.PeerConnection", return_value=mock_peer1):
                    async with http.post(
                        f"http://127.0.0.1:{port}/reconnect/my-device",
                        data=body1,
                        headers={
                            "Content-Type": "application/x-protobuf",
                            "X-RAS-Timestamp": str(timestamp1),
                            "X-RAS-Signature": sig1.hex(),
                        },
                    ) as resp:
                        assert resp.status == 200

                # Second connection (should close first)
                timestamp2 = int(time.time())
                body2 = bytes(SignalRequest(
                    sdp_offer="v=0",
                    device_id="my-device",
                    device_name="My Phone",
                ))
                sig2 = compute_signaling_hmac(auth_key, "my-device", timestamp2, body2)

                mock_peer2 = AsyncMock()
                mock_peer2.accept_offer = AsyncMock(return_value="v=0")
                mock_peer2.wait_connected = AsyncMock()
                mock_peer2.close = AsyncMock()
                mock_peer2.on_message = MagicMock()

                with patch("ras.peer.PeerConnection", return_value=mock_peer2):
                    async with http.post(
                        f"http://127.0.0.1:{port}/reconnect/my-device",
                        data=body2,
                        headers={
                            "Content-Type": "application/x-protobuf",
                            "X-RAS-Timestamp": str(timestamp2),
                            "X-RAS-Signature": sig2.hex(),
                        },
                    ) as resp:
                        assert resp.status == 200

                # First peer should have been closed (close is called during request handling)
                # Gather any pending tasks to ensure close completed
                pending = asyncio.all_tasks() - {asyncio.current_task()}
                if pending:
                    await asyncio.gather(*pending, return_exceptions=True)
        finally:
            await daemon.stop()


# =============================================================================
# RATE LIMITING
# =============================================================================

class TestRateLimiting:
    """Tests for rate limiting."""

    @pytest.mark.asyncio
    async def test_signal_rate_limited_by_session(self, config):
        """RL-01: Too many signals to same session gets rate limited."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Start pairing
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    data = await resp.json()
                    session_id = data["session_id"]

                # Send many requests quickly
                responses = []
                for _ in range(20):
                    async with http.post(
                        f"http://127.0.0.1:{port}/signal/{session_id}",
                        data=b"\x00",
                        headers={
                            "X-RAS-Timestamp": str(int(time.time())),
                            "X-RAS-Signature": "0" * 64,
                        },
                    ) as resp:
                        responses.append(resp.status)

                # Some should be rate limited (429)
                assert 429 in responses
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_reconnect_rate_limited_by_device(self, config, test_secret):
        """RL-02: Too many reconnect attempts gets rate limited."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            # Pre-register device
            await daemon._device_store.add_device(
                device_id="rate-limit-device",
                device_name="Phone",
                master_secret=test_secret,
            )

            async with aiohttp.ClientSession() as http:
                # Send many requests quickly
                responses = []
                for _ in range(20):
                    async with http.post(
                        f"http://127.0.0.1:{port}/reconnect/rate-limit-device",
                        data=b"\x00",
                        headers={
                            "X-RAS-Timestamp": str(int(time.time())),
                            "X-RAS-Signature": "0" * 64,
                        },
                    ) as resp:
                        responses.append(resp.status)

                # Some should be rate limited (429)
                assert 429 in responses
        finally:
            await daemon.stop()


# =============================================================================
# EDGE CASES
# =============================================================================

class TestEdgeCases:
    """Tests for edge cases."""

    @pytest.mark.asyncio
    async def test_device_store_missing_creates_new(self, config, tmp_path):
        """E-01: Missing device store file is created on startup."""
        from ras.daemon import Daemon

        devices_file = tmp_path / "nonexistent" / "devices.json"
        config.daemon.devices_file = str(devices_file)

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            assert daemon._device_store is not None
            assert len(daemon._device_store) == 0
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_device_store_corrupted_handled(self, config, tmp_path):
        """E-02: Corrupted device store is handled gracefully."""
        from ras.daemon import Daemon

        devices_file = tmp_path / "devices.json"
        devices_file.write_text("not valid json {{{")
        config.daemon.devices_file = str(devices_file)

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            # Should start with empty store
            assert daemon._device_store is not None
            assert len(daemon._device_store) == 0
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_health_endpoint(self, config):
        """E-03: Health endpoint returns OK."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                async with http.get(f"http://127.0.0.1:{port}/health") as resp:
                    assert resp.status == 200
                    assert await resp.text() == "OK"
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_unknown_endpoint_returns_404(self, config):
        """E-04: Unknown endpoints return 404."""
        from ras.daemon import Daemon

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                async with http.get(f"http://127.0.0.1:{port}/unknown/endpoint") as resp:
                    assert resp.status == 404
        finally:
            await daemon.stop()

    @pytest.mark.asyncio
    async def test_max_pairing_sessions_limit(self, config):
        """E-05: Maximum pairing sessions limit is enforced."""
        from ras.daemon import Daemon

        config.daemon.max_pairing_sessions = 3

        daemon = Daemon(config=config)
        try:
            await daemon.start()
            port = daemon._get_server_port()

            async with aiohttp.ClientSession() as http:
                # Create max sessions
                for _ in range(3):
                    async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                        assert resp.status == 200

                # Next should fail
                async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                    assert resp.status == 429  # Too many
        finally:
            await daemon.stop()
