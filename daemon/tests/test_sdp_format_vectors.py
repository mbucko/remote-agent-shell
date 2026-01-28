"""Tests for SDP format using cross-platform test vectors.

These tests ensure SDP format compatibility between:
- Python daemon (aiortc)
- Android app (google-webrtc)

SDP must always be raw format, never JSON-wrapped.
"""

import json
from pathlib import Path

import pytest


@pytest.fixture
def sdp_vectors():
    """Load SDP format test vectors."""
    # Path: daemon/tests/test_sdp_format_vectors.py -> ../.. -> daemon -> .. -> test-vectors
    vectors_path = Path(__file__).parent.parent.parent / "test-vectors" / "sdp_format.json"
    with open(vectors_path) as f:
        return json.load(f)


class TestSdpFormatValidation:
    """Tests for SDP format validation."""

    def test_valid_sdp_starts_with_version(self, sdp_vectors):
        """All valid SDP vectors start with 'v='."""
        for vector in sdp_vectors["valid_vectors"]:
            sdp = vector["sdp"]
            assert sdp.startswith("v="), f"Vector {vector['id']} should start with 'v='"

    def test_valid_sdp_has_required_lines(self, sdp_vectors):
        """All valid SDP vectors contain required lines."""
        required_prefixes = ["v=", "o=", "s=", "m="]

        for vector in sdp_vectors["valid_vectors"]:
            sdp = vector["sdp"]
            lines = sdp.split("\r\n")

            for prefix in required_prefixes:
                has_line = any(line.startswith(prefix) for line in lines)
                assert has_line, f"Vector {vector['id']} missing '{prefix}' line"

    def test_valid_sdp_uses_crlf(self, sdp_vectors):
        """Valid SDP uses CRLF line endings."""
        for vector in sdp_vectors["valid_vectors"]:
            sdp = vector["sdp"]
            # Should have CRLF but not bare LF
            assert "\r\n" in sdp, f"Vector {vector['id']} missing CRLF"

    def test_invalid_json_wrapped_rejected(self, sdp_vectors):
        """JSON-wrapped SDP is considered invalid."""
        for vector in sdp_vectors["invalid_vectors"]:
            if vector["id"] == "json_wrapped":
                sdp = vector["sdp"]
                # Should start with '{' not 'v='
                assert sdp.startswith("{"), "JSON-wrapped SDP should start with '{'"
                assert not sdp.startswith("v="), "JSON-wrapped SDP should not start with 'v='"

    def test_invalid_sdp_fails_basic_validation(self, sdp_vectors):
        """Invalid SDP vectors fail basic validation."""
        for vector in sdp_vectors["invalid_vectors"]:
            sdp = vector["sdp"]
            # Our validation: must start with "v="
            is_valid = sdp and sdp.startswith("v=")
            assert not is_valid, f"Vector {vector['id']} should be invalid but passed validation"


class TestSdpRoundtrip:
    """Tests for SDP roundtrip through the system."""

    def test_minimal_sdp_accepted_by_peer_connection(self, sdp_vectors):
        """Minimal valid SDP can be parsed by PeerConnection."""
        from unittest.mock import AsyncMock, Mock
        from ras.peer import PeerConnection

        # Get minimal SDP vector
        minimal_vector = next(
            v for v in sdp_vectors["valid_vectors"]
            if v["id"] == "minimal_sdp"
        )
        sdp = minimal_vector["sdp"]

        # Create mock RTCPeerConnection
        mock_pc = AsyncMock()
        mock_pc.connectionState = "new"
        mock_pc.iceGatheringState = "complete"

        mock_answer = Mock()
        mock_answer.sdp = "v=0\r\no=- 99999 1 IN IP4 127.0.0.1\r\ns=-\r\n"
        mock_answer.type = "answer"

        mock_pc.setRemoteDescription = AsyncMock()
        mock_pc.createAnswer = AsyncMock(return_value=mock_answer)
        mock_pc.setLocalDescription = AsyncMock()
        mock_pc.localDescription = mock_answer
        mock_pc.createDataChannel = Mock(return_value=Mock())

        def mock_on(event):
            return lambda fn: fn

        mock_pc.on = mock_on

        # Test that accept_offer works with raw SDP
        peer = PeerConnection(pc_factory=lambda cfg: mock_pc)

        # accept_offer should not raise for valid SDP
        import asyncio
        asyncio.run(peer.accept_offer(sdp))

        # Verify setRemoteDescription was called with raw SDP
        mock_pc.setRemoteDescription.assert_called_once()
        call_args = mock_pc.setRemoteDescription.call_args
        desc = call_args[0][0]
        assert desc.type == "offer"
        assert desc.sdp == sdp


class TestSdpNotJsonWrapped:
    """Tests to verify SDP is never JSON-wrapped."""

    def test_create_offer_returns_raw_sdp(self):
        """PeerConnection.create_offer returns raw SDP, not JSON."""
        from unittest.mock import AsyncMock, Mock
        from ras.peer import PeerConnection

        mock_pc = AsyncMock()
        mock_pc.connectionState = "new"
        mock_pc.iceGatheringState = "complete"

        mock_offer = Mock()
        mock_offer.sdp = "v=0\r\no=- 12345 1 IN IP4 127.0.0.1\r\ns=-\r\nm=application 9\r\n"
        mock_offer.type = "offer"

        mock_pc.createOffer = AsyncMock(return_value=mock_offer)
        mock_pc.setLocalDescription = AsyncMock()
        mock_pc.localDescription = mock_offer
        mock_pc.createDataChannel = Mock(return_value=Mock())

        def mock_on(event):
            return lambda fn: fn

        mock_pc.on = mock_on

        peer = PeerConnection(pc_factory=lambda cfg: mock_pc)

        import asyncio
        offer = asyncio.run(peer.create_offer())

        # Should be raw SDP
        assert offer.startswith("v="), f"Offer should start with 'v=', got: {offer[:50]}"
        assert not offer.startswith("{"), "Offer should not be JSON-wrapped"

    def test_accept_offer_returns_raw_sdp(self):
        """PeerConnection.accept_offer returns raw SDP, not JSON."""
        from unittest.mock import AsyncMock, Mock
        from ras.peer import PeerConnection

        mock_pc = AsyncMock()
        mock_pc.connectionState = "new"
        mock_pc.iceGatheringState = "complete"

        mock_answer = Mock()
        mock_answer.sdp = "v=0\r\no=- 99999 1 IN IP4 127.0.0.1\r\ns=-\r\nm=application 9\r\n"
        mock_answer.type = "answer"

        mock_pc.setRemoteDescription = AsyncMock()
        mock_pc.createAnswer = AsyncMock(return_value=mock_answer)
        mock_pc.setLocalDescription = AsyncMock()
        mock_pc.localDescription = mock_answer
        mock_pc.createDataChannel = Mock(return_value=Mock())

        def mock_on(event):
            return lambda fn: fn

        mock_pc.on = mock_on

        peer = PeerConnection(pc_factory=lambda cfg: mock_pc)

        import asyncio
        # Pass raw SDP offer
        raw_offer = "v=0\r\no=- 12345 1 IN IP4 127.0.0.1\r\ns=-\r\nm=application 9\r\n"
        answer = asyncio.run(peer.accept_offer(raw_offer))

        # Should be raw SDP
        assert answer.startswith("v="), f"Answer should start with 'v=', got: {answer[:50]}"
        assert not answer.startswith("{"), "Answer should not be JSON-wrapped"
