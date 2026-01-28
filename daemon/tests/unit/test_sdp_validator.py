"""Unit tests for SDP validator."""

import pytest

from ras.sdp_validator import (
    SdpValidationResult,
    extract_candidates,
    require_candidates,
    validate_sdp,
)


# Note: SDP lines must start at column 0, no leading whitespace
VALID_SDP_WITH_CANDIDATES = (
    "v=0\n"
    "o=- 123456 2 IN IP4 127.0.0.1\n"
    "s=-\n"
    "t=0 0\n"
    "a=group:BUNDLE 0\n"
    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\n"
    "c=IN IP4 0.0.0.0\n"
    "a=candidate:1 1 udp 2130706431 192.168.1.100 50000 typ host\n"
    "a=candidate:2 1 udp 1694498815 203.0.113.50 50000 typ srflx raddr 192.168.1.100 rport 50000\n"
    "a=end-of-candidates\n"
)

SDP_WITHOUT_CANDIDATES = (
    "v=0\n"
    "o=- 123456 2 IN IP4 127.0.0.1\n"
    "s=-\n"
    "t=0 0\n"
    "a=group:BUNDLE 0\n"
    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\n"
    "c=IN IP4 0.0.0.0\n"
)

SDP_WITH_RELAY = (
    "v=0\n"
    "o=- 123456 2 IN IP4 127.0.0.1\n"
    "s=-\n"
    "t=0 0\n"
    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\n"
    "a=candidate:1 1 udp 2130706431 192.168.1.100 50000 typ host\n"
    "a=candidate:2 1 udp 16777215 10.0.0.1 50000 typ relay raddr 192.168.1.100 rport 50000\n"
)


class TestValidateSdp:
    """Tests for validate_sdp function."""

    def test_valid_sdp_with_candidates(self):
        """Valid SDP with candidates passes validation."""
        result = validate_sdp(VALID_SDP_WITH_CANDIDATES, "Test SDP")

        assert result.is_valid
        assert result.candidate_count == 2
        assert result.has_host
        assert result.has_srflx
        assert not result.has_relay
        assert len(result.errors) == 0

    def test_sdp_without_candidates_fails(self):
        """SDP without candidates fails validation."""
        result = validate_sdp(SDP_WITHOUT_CANDIDATES, "Test SDP")

        assert not result.is_valid
        assert result.candidate_count == 0
        assert not result.has_host
        assert not result.has_srflx
        assert not result.has_relay
        assert len(result.errors) == 1
        assert "no ICE candidates" in result.errors[0]

    def test_sdp_with_relay_candidate(self):
        """SDP with relay candidate is detected."""
        result = validate_sdp(SDP_WITH_RELAY, "Test SDP")

        assert result.is_valid
        assert result.candidate_count == 2
        assert result.has_host
        assert result.has_relay

    def test_empty_sdp(self):
        """Empty SDP fails validation."""
        result = validate_sdp("", "Empty SDP")

        assert not result.is_valid
        assert result.candidate_count == 0


class TestRequireCandidates:
    """Tests for require_candidates function."""

    def test_valid_sdp_passes(self):
        """Valid SDP does not raise."""
        # Should not raise
        require_candidates(VALID_SDP_WITH_CANDIDATES, "Test SDP")

    def test_invalid_sdp_raises(self):
        """Invalid SDP raises ValueError."""
        with pytest.raises(ValueError) as exc_info:
            require_candidates(SDP_WITHOUT_CANDIDATES, "Test SDP")

        assert "no ICE candidates" in str(exc_info.value)
        assert "ICE gathering didn't complete" in str(exc_info.value)


class TestExtractCandidates:
    """Tests for extract_candidates function."""

    def test_extracts_all_candidates(self):
        """Extracts all candidate lines."""
        candidates = extract_candidates(VALID_SDP_WITH_CANDIDATES)

        assert len(candidates) == 2
        assert "host" in candidates[0]
        assert "srflx" in candidates[1]

    def test_empty_for_no_candidates(self):
        """Returns empty list when no candidates."""
        candidates = extract_candidates(SDP_WITHOUT_CANDIDATES)

        assert candidates == []
