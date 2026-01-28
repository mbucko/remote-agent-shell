"""WebRTC integration tests for SDP format verification.

These tests use real aiortc WebRTC to verify:
- SDP offers/answers use raw format (start with "v=0")
- No JSON wrapping in SDP strings
- Full roundtrip works with raw SDP
"""

import asyncio

import pytest

from ras.peer import PeerConnection
from ras.protocols import PeerState


@pytest.mark.integration
class TestWebRtcSdpFormat:
    """Integration tests for WebRTC SDP format."""

    async def test_create_offer_returns_raw_sdp(self):
        """Real WebRTC offer is raw SDP starting with 'v=0'."""
        async with PeerConnection(stun_servers=[]) as peer:
            offer = await peer.create_offer()

            # Must be raw SDP
            assert offer.startswith("v=0"), f"Offer should start with 'v=0', got: {offer[:50]}"
            assert not offer.startswith("{"), "Offer should NOT be JSON-wrapped"

            # Must have required SDP fields
            assert "o=" in offer, "Offer should have origin line"
            assert "s=" in offer, "Offer should have session name line"
            assert "m=" in offer, "Offer should have media line"

            # Must use CRLF
            assert "\r\n" in offer, "Offer should use CRLF line endings"

    async def test_accept_offer_returns_raw_sdp(self):
        """Real WebRTC answer is raw SDP starting with 'v=0'."""
        async with PeerConnection(stun_servers=[]) as peer1, PeerConnection(stun_servers=[]) as peer2:
            offer = await peer1.create_offer()
            answer = await peer2.accept_offer(offer)

            # Must be raw SDP
            assert answer.startswith("v=0"), f"Answer should start with 'v=0', got: {answer[:50]}"
            assert not answer.startswith("{"), "Answer should NOT be JSON-wrapped"

            # Must have required SDP fields
            assert "o=" in answer, "Answer should have origin line"
            assert "s=" in answer, "Answer should have session name line"
            assert "m=" in answer, "Answer should have media line"

    async def test_sdp_roundtrip_with_real_webrtc(self):
        """Full SDP roundtrip works with raw format."""
        async with PeerConnection(stun_servers=[]) as peer1, PeerConnection(stun_servers=[]) as peer2:
            # Create offer (raw SDP)
            offer = await peer1.create_offer()
            assert offer.startswith("v=0")

            # Accept offer, get answer (raw SDP)
            answer = await peer2.accept_offer(offer)
            assert answer.startswith("v=0")

            # Set answer on offerer (raw SDP)
            await peer1.set_remote_description(answer)

            # Both should be connecting
            assert peer1.state == PeerState.CONNECTING
            assert peer2.state == PeerState.CONNECTING

    async def test_sdp_contains_ice_candidates(self):
        """Real SDP contains ICE candidates after gathering."""
        async with PeerConnection(stun_servers=[]) as peer:
            offer = await peer.create_offer()

            # Should have ICE credentials
            assert "ice-ufrag:" in offer.lower() or "a=ice-ufrag" in offer, \
                "Offer should have ICE ufrag"
            assert "ice-pwd:" in offer.lower() or "a=ice-pwd" in offer, \
                "Offer should have ICE password"

    async def test_full_connection_with_raw_sdp(self):
        """Two peers can connect using raw SDP format."""
        received = {"peer1": [], "peer2": []}

        async with PeerConnection(stun_servers=[]) as peer1, PeerConnection(stun_servers=[]) as peer2:
            peer1.on_message(lambda m: received["peer1"].append(m))
            peer2.on_message(lambda m: received["peer2"].append(m))

            # Exchange raw SDP
            offer = await peer1.create_offer()
            assert offer.startswith("v=0"), "Offer must be raw SDP"

            answer = await peer2.accept_offer(offer)
            assert answer.startswith("v=0"), "Answer must be raw SDP"

            await peer1.set_remote_description(answer)

            # Wait for connection
            await asyncio.gather(
                peer1.wait_connected(timeout=30),
                peer2.wait_connected(timeout=30),
            )

            assert peer1.state == PeerState.CONNECTED
            assert peer2.state == PeerState.CONNECTED

            # Verify messages work
            await peer1.send(b"test")
            await asyncio.sleep(0.5)
            assert b"test" in received["peer2"]
