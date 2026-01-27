"""Tests for HTTP signaling server module."""

import asyncio
from unittest.mock import AsyncMock, Mock

import pytest
from aiohttp.test_utils import TestClient

from ras.signaling import SignalingServer


@pytest.fixture
def mock_peer():
    """Create a mock PeerConnection."""
    mock = AsyncMock()
    mock.accept_offer = AsyncMock(return_value='{"type": "answer", "sdp": "v=0\\r\\n"}')
    mock.wait_connected = AsyncMock()
    mock.close = AsyncMock()
    return mock


@pytest.fixture
def peer_factory(mock_peer):
    """Factory that returns the mock peer."""
    return lambda: mock_peer


class TestSignalingServer:
    """Test SignalingServer class."""

    async def test_health_endpoint(self, aiohttp_client, peer_factory):
        """GET /health returns 200."""
        server = SignalingServer(peer_factory=peer_factory)
        client: TestClient = await aiohttp_client(server.app)

        resp = await client.get("/health")

        assert resp.status == 200
        data = await resp.json()
        assert data["status"] == "ok"

    async def test_signal_invalid_session_returns_404(self, aiohttp_client, peer_factory):
        """POST /signal/{invalid} returns 404."""
        server = SignalingServer(peer_factory=peer_factory)
        client: TestClient = await aiohttp_client(server.app)

        resp = await client.post("/signal/invalid-session", json={"offer": "test"})

        assert resp.status == 404
        data = await resp.json()
        assert "error" in data

    async def test_signal_missing_offer_returns_400(self, aiohttp_client, peer_factory):
        """POST /signal/{session} without offer returns 400."""
        server = SignalingServer(peer_factory=peer_factory)
        session_id = server.create_session()
        client: TestClient = await aiohttp_client(server.app)

        resp = await client.post(f"/signal/{session_id}", json={})

        assert resp.status == 400
        data = await resp.json()
        assert "error" in data

    async def test_signal_post_returns_answer(self, aiohttp_client, mock_peer, peer_factory):
        """POST /signal/{session} with offer returns answer."""
        server = SignalingServer(peer_factory=peer_factory)
        session_id = server.create_session()
        client: TestClient = await aiohttp_client(server.app)

        resp = await client.post(
            f"/signal/{session_id}", json={"offer": '{"type": "offer", "sdp": "v=0\\r\\n"}'}
        )

        assert resp.status == 200
        data = await resp.json()
        assert "answer" in data
        mock_peer.accept_offer.assert_called_once()

    async def test_create_session_returns_unique_ids(self, peer_factory):
        """create_session returns unique session IDs."""
        server = SignalingServer(peer_factory=peer_factory)

        id1 = server.create_session()
        id2 = server.create_session()

        assert id1 != id2
        assert len(id1) >= 8
        assert len(id2) >= 8

    async def test_session_valid_after_create(self, peer_factory):
        """Session is valid immediately after creation."""
        server = SignalingServer(peer_factory=peer_factory)

        session_id = server.create_session()

        assert server.is_session_valid(session_id)

    async def test_session_invalid_after_use(self, aiohttp_client, peer_factory):
        """Session is invalid after successful signaling."""
        server = SignalingServer(peer_factory=peer_factory)
        session_id = server.create_session()
        client: TestClient = await aiohttp_client(server.app)

        await client.post(
            f"/signal/{session_id}", json={"offer": '{"type": "offer", "sdp": "v=0\\r\\n"}'}
        )

        assert not server.is_session_valid(session_id)

    async def test_on_connected_callback(self, aiohttp_client, mock_peer, peer_factory):
        """Calls on_connected when peer connects."""
        server = SignalingServer(peer_factory=peer_factory)
        session_id = server.create_session()

        connected_sessions = []

        async def on_connected(sid, peer):
            connected_sessions.append(sid)

        server.on_connected(on_connected)

        # Make the mock report connected immediately
        mock_peer.wait_connected = AsyncMock()

        client: TestClient = await aiohttp_client(server.app)
        await client.post(
            f"/signal/{session_id}", json={"offer": '{"type": "offer", "sdp": "v=0\\r\\n"}'}
        )

        # Give time for the background task to run
        await asyncio.sleep(0.1)

        assert session_id in connected_sessions

    async def test_signal_timeout_returns_504(self, aiohttp_client, peer_factory):
        """POST /signal/{session} timeout returns 504."""

        async def slow_accept_offer(offer):
            await asyncio.sleep(10)
            return '{"type": "answer", "sdp": "v=0\\r\\n"}'

        slow_mock = AsyncMock()
        slow_mock.accept_offer = slow_accept_offer
        slow_mock.close = AsyncMock()

        slow_factory = lambda: slow_mock

        server = SignalingServer(peer_factory=slow_factory, signaling_timeout=0.1)
        session_id = server.create_session()
        client: TestClient = await aiohttp_client(server.app)

        resp = await client.post(
            f"/signal/{session_id}", json={"offer": '{"type": "offer", "sdp": "v=0\\r\\n"}'}
        )

        assert resp.status == 504
        data = await resp.json()
        assert "timeout" in data["error"].lower()

    async def test_close_cleans_up(self, peer_factory, mock_peer):
        """close() cleans up resources."""
        server = SignalingServer(peer_factory=peer_factory)
        server.create_session()

        await server.close()

        assert len(server._pending_sessions) == 0
