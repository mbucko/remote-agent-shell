"""Tests for WebRTC peer connection module."""

import asyncio
from unittest.mock import AsyncMock, Mock, MagicMock, patch

import pytest

from ras.peer import PeerConnection, PeerConnectionError
from ras.protocols import PeerState


class TestPeerConnection:
    """Test PeerConnection class."""

    def test_initial_state_is_new(self):
        """Peer starts in NEW state."""
        peer = PeerConnection()
        assert peer.state == PeerState.NEW

    def test_uses_configured_stun_servers(self):
        """Uses STUN servers from constructor."""
        servers = ["stun:custom.stun.server:3478"]
        peer = PeerConnection(stun_servers=servers)
        assert peer.stun_servers == servers

    def test_uses_default_stun_servers(self):
        """Uses default STUN servers when none provided."""
        peer = PeerConnection()
        assert len(peer.stun_servers) > 0

    async def test_create_offer_returns_sdp(self):
        """Peer can create SDP offer."""
        # Mock the RTCPeerConnection
        mock_pc = AsyncMock()
        mock_pc.connectionState = "new"
        mock_pc.iceGatheringState = "complete"

        mock_offer = Mock()
        mock_offer.sdp = "v=0\r\no=- 12345 1 IN IP4 127.0.0.1\r\n"
        mock_offer.type = "offer"

        mock_pc.createOffer = AsyncMock(return_value=mock_offer)
        mock_pc.setLocalDescription = AsyncMock()
        mock_pc.localDescription = mock_offer
        mock_pc.createDataChannel = Mock(return_value=Mock())

        # Capture the on decorator to simulate events
        handlers = {}

        def mock_on(event):
            def decorator(fn):
                handlers[event] = fn
                return fn

            return decorator

        mock_pc.on = mock_on

        peer = PeerConnection(pc_factory=lambda cfg: mock_pc)
        offer = await peer.create_offer()

        assert offer
        assert peer.state == PeerState.CONNECTING
        mock_pc.createOffer.assert_called_once()

    async def test_accept_offer_returns_answer(self):
        """Peer can accept offer and return answer."""
        mock_pc = AsyncMock()
        mock_pc.connectionState = "new"
        mock_pc.iceGatheringState = "complete"

        mock_answer = Mock()
        mock_answer.sdp = "v=0\r\no=- 12345 1 IN IP4 127.0.0.1\r\n"
        mock_answer.type = "answer"

        mock_pc.setRemoteDescription = AsyncMock()
        mock_pc.createAnswer = AsyncMock(return_value=mock_answer)
        mock_pc.setLocalDescription = AsyncMock()
        mock_pc.localDescription = mock_answer
        mock_pc.createDataChannel = Mock(return_value=Mock())

        handlers = {}

        def mock_on(event):
            def decorator(fn):
                handlers[event] = fn
                return fn

            return decorator

        mock_pc.on = mock_on

        peer = PeerConnection(pc_factory=lambda cfg: mock_pc)

        # Fake offer SDP (raw format)
        fake_offer = "v=0\r\no=- 12345 1 IN IP4 127.0.0.1\r\n"
        answer = await peer.accept_offer(fake_offer)

        assert answer
        assert peer.state == PeerState.CONNECTING

    async def test_send_raises_when_not_connected(self):
        """Send raises error when not connected."""
        peer = PeerConnection()

        with pytest.raises(PeerConnectionError, match="Cannot send in state"):
            await peer.send(b"test")

    async def test_close_changes_state(self):
        """Close sets state to CLOSED."""
        mock_pc = AsyncMock()
        mock_pc.close = AsyncMock()

        peer = PeerConnection(pc_factory=lambda cfg: mock_pc)
        peer._pc = mock_pc

        await peer.close()

        assert peer.state == PeerState.CLOSED
        mock_pc.close.assert_called_once()

    async def test_context_manager(self):
        """Works as async context manager."""
        mock_pc = AsyncMock()
        mock_pc.close = AsyncMock()

        peer = PeerConnection(pc_factory=lambda cfg: mock_pc)
        peer._pc = mock_pc

        async with peer as p:
            assert p is peer

        mock_pc.close.assert_called_once()

    async def test_wait_connected_timeout(self):
        """wait_connected raises on timeout."""
        peer = PeerConnection()

        # Mock wait_for to raise TimeoutError immediately
        with patch("ras.peer.asyncio.wait_for", side_effect=asyncio.TimeoutError()):
            with pytest.raises(PeerConnectionError, match="Connection timeout"):
                await peer.wait_connected(timeout=1.0)

    def test_on_message_registers_callback(self):
        """on_message registers callback."""
        peer = PeerConnection()
        callback = AsyncMock()

        peer.on_message(callback)

        assert peer._message_callback is callback


@pytest.mark.integration
class TestPeerConnectionIntegration:
    """Integration tests for peer connection (requires WebRTC support)."""

    async def test_two_peers_connect_locally(self):
        """Two peers can connect and exchange messages locally."""
        received_peer1 = []
        received_peer2 = []

        async def on_message_1(msg):
            received_peer1.append(msg)

        async def on_message_2(msg):
            received_peer2.append(msg)

        async with PeerConnection(stun_servers=[]) as peer1, PeerConnection(stun_servers=[]) as peer2:
            peer1.on_message(on_message_1)
            peer2.on_message(on_message_2)

            # Exchange SDP
            offer = await peer1.create_offer()
            answer = await peer2.accept_offer(offer)
            await peer1.set_remote_description(answer)

            # Wait for connection
            await asyncio.gather(
                peer1.wait_connected(timeout=30),
                peer2.wait_connected(timeout=30),
            )

            assert peer1.state == PeerState.CONNECTED
            assert peer2.state == PeerState.CONNECTED

            # Exchange messages
            await peer1.send(b"hello from peer1")
            await peer2.send(b"hello from peer2")

            # Wait for messages to arrive
            await asyncio.sleep(0.5)

            assert b"hello from peer2" in received_peer1
            assert b"hello from peer1" in received_peer2
