"""Tests for HTTP signaling server."""

import time
from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase, unittest_run_loop

from ras.pairing.signaling_server import SignalingServer, RateLimiter
from ras.pairing.session import PairingSession, PairingState
from ras.crypto import compute_signaling_hmac
from ras.proto.ras import SignalRequest, SignalResponse, SignalError, SignalErrorErrorCode


class TestRateLimiter:
    """Tests for rate limiter."""

    def test_allows_first_request(self):
        """First request is allowed."""
        limiter = RateLimiter(max_requests=10, window_seconds=60)
        assert limiter.is_allowed("key1") is True

    def test_allows_up_to_limit(self):
        """Allows requests up to limit."""
        limiter = RateLimiter(max_requests=3, window_seconds=60)
        assert limiter.is_allowed("key1") is True
        assert limiter.is_allowed("key1") is True
        assert limiter.is_allowed("key1") is True

    def test_blocks_over_limit(self):
        """Blocks requests over limit."""
        limiter = RateLimiter(max_requests=3, window_seconds=60)
        limiter.is_allowed("key1")
        limiter.is_allowed("key1")
        limiter.is_allowed("key1")
        assert limiter.is_allowed("key1") is False

    def test_different_keys_independent(self):
        """Different keys have independent limits."""
        limiter = RateLimiter(max_requests=2, window_seconds=60)
        limiter.is_allowed("key1")
        limiter.is_allowed("key1")
        assert limiter.is_allowed("key1") is False
        assert limiter.is_allowed("key2") is True

    def test_window_reset(self):
        """Requests reset after window."""
        limiter = RateLimiter(max_requests=2, window_seconds=1)
        limiter.is_allowed("key1")
        limiter.is_allowed("key1")
        assert limiter.is_allowed("key1") is False

        # Manually age the requests
        limiter.requests["key1"] = [time.time() - 2]
        assert limiter.is_allowed("key1") is True


class TestSignalingServerRoutes:
    """Tests for signaling server routes."""

    @pytest.fixture
    def mock_pairing_manager(self):
        """Create mock pairing manager."""
        manager = MagicMock()
        manager.get_session = MagicMock(return_value=None)
        manager.handle_signal = AsyncMock(return_value="sdp-answer")
        return manager

    @pytest.fixture
    def app(self, mock_pairing_manager):
        """Create test app."""
        server = SignalingServer(mock_pairing_manager)
        return server.app

    @pytest.mark.asyncio
    async def test_health_endpoint(self, aiohttp_client, app):
        """Health endpoint returns OK."""
        client = await aiohttp_client(app)
        resp = await client.get("/health")
        assert resp.status == 200
        text = await resp.text()
        assert text == "OK"

    @pytest.mark.asyncio
    async def test_signal_without_session(self, aiohttp_client, app, mock_pairing_manager):
        """Signal without valid session returns error."""
        mock_pairing_manager.get_session.return_value = None

        client = await aiohttp_client(app)
        resp = await client.post(
            "/signal/invalid-session",
            data=b"test",
            headers={
                "X-RAS-Timestamp": str(int(time.time())),
                "X-RAS-Signature": "0" * 64,
            },
        )
        assert resp.status == 400

    @pytest.mark.asyncio
    async def test_signal_without_timestamp(self, aiohttp_client, app):
        """Signal without timestamp returns error."""
        client = await aiohttp_client(app)
        resp = await client.post(
            "/signal/test-session",
            data=b"test",
            headers={
                "X-RAS-Signature": "0" * 64,
            },
        )
        assert resp.status == 400

    @pytest.mark.asyncio
    async def test_signal_without_signature(self, aiohttp_client, app):
        """Signal without signature returns error."""
        client = await aiohttp_client(app)
        resp = await client.post(
            "/signal/test-session",
            data=b"test",
            headers={
                "X-RAS-Timestamp": str(int(time.time())),
            },
        )
        assert resp.status == 400


class TestSignalingServerAuth:
    """Tests for signaling server authentication."""

    @pytest.fixture
    def session(self):
        """Create test session."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        return session

    @pytest.fixture
    def mock_pairing_manager(self, session):
        """Create mock pairing manager with session."""
        manager = MagicMock()
        manager.get_session = MagicMock(return_value=session)
        manager.handle_signal = AsyncMock(return_value="sdp-answer")
        return manager

    @pytest.fixture
    def app(self, mock_pairing_manager):
        """Create test app."""
        server = SignalingServer(mock_pairing_manager)
        return server.app

    @pytest.mark.asyncio
    async def test_valid_signature_succeeds(
        self, aiohttp_client, app, mock_pairing_manager, session
    ):
        """Valid signature allows request."""
        # Create valid request
        request = SignalRequest(
            sdp_offer="test-offer",
            device_name="Test Device",
            device_id="device-123",
        )
        body = bytes(request)

        timestamp = int(time.time())
        signature = compute_signaling_hmac(
            session.auth_key,
            session.session_id,
            timestamp,
            body,
        )

        client = await aiohttp_client(app)
        resp = await client.post(
            f"/signal/{session.session_id}",
            data=body,
            headers={
                "Content-Type": "application/x-protobuf",
                "X-RAS-Timestamp": str(timestamp),
                "X-RAS-Signature": signature.hex(),
            },
        )
        assert resp.status == 200

    @pytest.mark.asyncio
    async def test_invalid_signature_fails(
        self, aiohttp_client, app, session
    ):
        """Invalid signature rejects request."""
        request = SignalRequest(
            sdp_offer="test-offer",
            device_name="Test Device",
            device_id="device-123",
        )
        body = bytes(request)

        timestamp = int(time.time())
        wrong_signature = "0" * 64

        client = await aiohttp_client(app)
        resp = await client.post(
            f"/signal/{session.session_id}",
            data=body,
            headers={
                "Content-Type": "application/x-protobuf",
                "X-RAS-Timestamp": str(timestamp),
                "X-RAS-Signature": wrong_signature,
            },
        )
        assert resp.status == 400

    @pytest.mark.asyncio
    async def test_old_timestamp_fails(
        self, aiohttp_client, app, session
    ):
        """Old timestamp rejects request."""
        request = SignalRequest(
            sdp_offer="test-offer",
            device_name="Test Device",
            device_id="device-123",
        )
        body = bytes(request)

        # Timestamp 60 seconds ago (> 30 second tolerance)
        timestamp = int(time.time()) - 60
        signature = compute_signaling_hmac(
            session.auth_key,
            session.session_id,
            timestamp,
            body,
        )

        client = await aiohttp_client(app)
        resp = await client.post(
            f"/signal/{session.session_id}",
            data=body,
            headers={
                "Content-Type": "application/x-protobuf",
                "X-RAS-Timestamp": str(timestamp),
                "X-RAS-Signature": signature.hex(),
            },
        )
        assert resp.status == 400


class TestSignalingServerRateLimiting:
    """Tests for rate limiting."""

    @pytest.fixture
    def session(self):
        """Create test session."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        return session

    @pytest.fixture
    def mock_pairing_manager(self, session):
        """Create mock pairing manager."""
        manager = MagicMock()
        manager.get_session = MagicMock(return_value=session)
        manager.handle_signal = AsyncMock(return_value="sdp-answer")
        return manager

    @pytest.mark.asyncio
    async def test_rate_limit_by_session(self, aiohttp_client, mock_pairing_manager, session):
        """Rate limits by session ID."""
        server = SignalingServer(mock_pairing_manager)
        # Set low limit for testing
        server.session_limiter = RateLimiter(max_requests=2, window_seconds=60)
        app = server.app

        request = SignalRequest(
            sdp_offer="test-offer",
            device_name="Test Device",
            device_id="device-123",
        )
        body = bytes(request)

        client = await aiohttp_client(app)

        # First two requests should succeed
        for _ in range(2):
            timestamp = int(time.time())
            signature = compute_signaling_hmac(
                session.auth_key,
                session.session_id,
                timestamp,
                body,
            )
            resp = await client.post(
                f"/signal/{session.session_id}",
                data=body,
                headers={
                    "Content-Type": "application/x-protobuf",
                    "X-RAS-Timestamp": str(timestamp),
                    "X-RAS-Signature": signature.hex(),
                },
            )
            assert resp.status == 200

        # Third request should be rate limited
        timestamp = int(time.time())
        signature = compute_signaling_hmac(
            session.auth_key,
            session.session_id,
            timestamp,
            body,
        )
        resp = await client.post(
            f"/signal/{session.session_id}",
            data=body,
            headers={
                "Content-Type": "application/x-protobuf",
                "X-RAS-Timestamp": str(timestamp),
                "X-RAS-Signature": signature.hex(),
            },
        )
        assert resp.status == 429
