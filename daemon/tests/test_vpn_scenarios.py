"""Tests for VPN/NAT scenario detection.

These tests verify:
- Detection of same public IP (hairpin NAT)
- Detection of symmetric NAT scenarios
- Proper handling when both devices are on VPN
- ICE candidate analysis utilities

Background:
- Symmetric NAT: Port mappings change per destination, breaks P2P
- Hairpin NAT: Both devices behind same NAT, requires NAT to route internal traffic
- VPN scenarios: Often create symmetric NAT conditions
"""

import pytest

from ras.sdp_validator import validate_sdp, extract_candidates


class TestSamePublicIpDetection:
    """Tests for detecting when both peers have the same public IP (hairpin NAT)."""

    def test_extract_srflx_ip_from_candidate(self):
        """Verify extraction of public IP from srflx candidate.

        ICE candidate format:
        a=candidate:<foundation> <component> <protocol> <priority> <ip> <port> typ <type> [extensions]

        For srflx: the <ip> is the public IP discovered via STUN
        """
        candidate = "a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345"

        ip = extract_srflx_ip(candidate)

        assert ip == "203.0.113.50"

    def test_extract_srflx_ip_returns_none_for_host(self):
        """Verify non-srflx candidates return None."""
        host_candidate = "a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host"

        ip = extract_srflx_ip(host_candidate)

        assert ip is None

    def test_detect_same_public_ip_when_both_behind_same_nat(self):
        """Verify detection when offer and answer have same srflx IP.

        This is the hairpin NAT scenario - both devices behind same NAT/VPN.
        """
        offer_sdp = """v=0
o=- 123 123 IN IP4 127.0.0.1
s=-
a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345"""

        answer_sdp = """v=0
o=- 456 456 IN IP4 127.0.0.1
s=-
a=candidate:0 1 UDP 2122252543 192.168.1.200 23456 typ host
a=candidate:1 1 UDP 1694498815 203.0.113.50 65432 typ srflx raddr 192.168.1.200 rport 23456"""

        result = detect_same_public_ip(offer_sdp, answer_sdp)

        assert result.same_public_ip is True
        assert result.offer_public_ip == "203.0.113.50"
        assert result.answer_public_ip == "203.0.113.50"

    def test_detect_different_public_ips(self):
        """Verify detection when devices have different public IPs (normal case)."""
        offer_sdp = """v=0
a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345"""

        answer_sdp = """v=0
a=candidate:1 1 UDP 1694498815 198.51.100.75 65432 typ srflx raddr 10.0.0.50 rport 23456"""

        result = detect_same_public_ip(offer_sdp, answer_sdp)

        assert result.same_public_ip is False
        assert result.offer_public_ip == "203.0.113.50"
        assert result.answer_public_ip == "198.51.100.75"

    def test_handles_missing_srflx_candidates(self):
        """Verify graceful handling when STUN fails (no srflx candidates)."""
        offer_sdp = """v=0
a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host"""

        answer_sdp = """v=0
a=candidate:0 1 UDP 2122252543 192.168.1.200 23456 typ host"""

        result = detect_same_public_ip(offer_sdp, answer_sdp)

        assert result.same_public_ip is False
        assert result.offer_public_ip is None
        assert result.answer_public_ip is None


class TestSdpValidatorCandidateTypes:
    """Tests for SDP validator candidate type detection."""

    def test_detects_host_candidates(self):
        """Verify detection of host (local network) candidates."""
        sdp = """v=0
a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host"""

        result = validate_sdp(sdp)

        assert result.has_host is True
        assert result.has_srflx is False
        assert result.has_relay is False

    def test_detects_srflx_candidates(self):
        """Verify detection of server-reflexive (STUN) candidates."""
        sdp = """v=0
a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345"""

        result = validate_sdp(sdp)

        assert result.has_host is False
        assert result.has_srflx is True
        assert result.has_relay is False

    def test_detects_relay_candidates(self):
        """Verify detection of relay (TURN) candidates."""
        sdp = """v=0
a=candidate:2 1 UDP 33562623 198.51.100.1 59000 typ relay raddr 203.0.113.50 rport 54321"""

        result = validate_sdp(sdp)

        assert result.has_host is False
        assert result.has_srflx is False
        assert result.has_relay is True

    def test_counts_all_candidate_types(self):
        """Verify counting of multiple candidate types."""
        sdp = """v=0
a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345
a=candidate:2 1 UDP 33562623 198.51.100.1 59000 typ relay raddr 203.0.113.50 rport 54321"""

        result = validate_sdp(sdp)

        assert result.candidate_count == 3
        assert result.has_host is True
        assert result.has_srflx is True
        assert result.has_relay is True


class TestVpnScenarios:
    """Tests for specific VPN scenarios."""

    def test_both_devices_on_same_vpn_server(self):
        """Scenario: Phone and laptop both connected to same VPN server.

        Both will have same public IP (VPN exit node).
        Direct P2P likely won't work (hairpin NAT not supported by most VPNs).

        Expected behavior:
        - Detect same public IP
        - Try host candidates first (might be on same LAN)
        - If host fails, fall back to TURN relay (when implemented)
        """
        phone_sdp = """v=0
a=candidate:0 1 UDP 2122252543 10.8.0.5 12345 typ host
a=candidate:1 1 UDP 1694498815 185.199.110.100 54321 typ srflx raddr 10.8.0.5 rport 12345"""

        laptop_sdp = """v=0
a=candidate:0 1 UDP 2122252543 10.8.0.10 23456 typ host
a=candidate:1 1 UDP 1694498815 185.199.110.100 65432 typ srflx raddr 10.8.0.10 rport 23456"""

        result = detect_same_public_ip(phone_sdp, laptop_sdp)

        assert result.same_public_ip is True
        assert result.offer_public_ip == "185.199.110.100"

        # Both have host candidates on VPN subnet - might be able to connect directly
        phone_result = validate_sdp(phone_sdp)
        laptop_result = validate_sdp(laptop_sdp)
        assert phone_result.has_host is True
        assert laptop_result.has_host is True

    def test_devices_on_different_vpn_servers(self):
        """Scenario: Both devices on VPN but different servers/locations.

        Different public IPs, but symmetric NAT may still block P2P.

        Expected behavior:
        - Different public IPs
        - ICE may still fail due to symmetric NAT
        - Need TURN relay as fallback
        """
        phone_sdp = """v=0
a=candidate:0 1 UDP 2122252543 10.8.0.5 12345 typ host
a=candidate:1 1 UDP 1694498815 185.199.110.100 54321 typ srflx raddr 10.8.0.5 rport 12345"""

        laptop_sdp = """v=0
a=candidate:0 1 UDP 2122252543 10.9.0.10 23456 typ host
a=candidate:1 1 UDP 1694498815 151.101.1.100 65432 typ srflx raddr 10.9.0.10 rport 23456"""

        result = detect_same_public_ip(phone_sdp, laptop_sdp)

        assert result.same_public_ip is False
        assert result.offer_public_ip == "185.199.110.100"
        assert result.answer_public_ip == "151.101.1.100"

    def test_no_relay_candidates_means_turn_not_available(self):
        """When TURN server is not configured, relay candidates won't be present.

        This limits connectivity options in restrictive NAT scenarios.
        """
        sdp = """v=0
a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345"""

        result = validate_sdp(sdp)

        assert result.has_relay is False
        assert result.candidate_count == 2


class TestSymmetricNatDetection:
    """Tests for symmetric NAT detection.

    Symmetric NAT creates different port mappings for each destination,
    making STUN-based hole punching unreliable.
    """

    def test_document_symmetric_nat_detection_strategy(self):
        """Document how symmetric NAT could be detected.

        Strategy:
        1. Both peers send ICE candidates
        2. ICE connectivity checks happen
        3. If srflx candidates fail but host candidates succeed -> might be symmetric NAT
        4. If all candidates fail and both have srflx -> likely symmetric NAT

        Note: Actual detection requires runtime analysis of ICE connection attempts.
        These tests document the expected SDP patterns.
        """
        # Symmetric NAT typically shows valid srflx candidates
        # but connectivity checks fail because port mappings don't match
        sdp_with_srflx = """v=0
a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345"""

        result = validate_sdp(sdp_with_srflx)

        # Has srflx candidate (STUN worked)
        assert result.has_srflx is True
        # But P2P might still fail due to symmetric NAT
        # This can only be detected at runtime by monitoring ICE state changes

    def test_ice_candidate_extraction(self):
        """Verify candidate extraction for analysis."""
        sdp = """v=0
o=- 123 123 IN IP4 127.0.0.1
s=-
a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345
a=candidate:2 1 UDP 33562623 198.51.100.1 59000 typ relay raddr 203.0.113.50 rport 54321"""

        candidates = extract_candidates(sdp)

        assert len(candidates) == 3
        assert any("host" in c for c in candidates)
        assert any("srflx" in c for c in candidates)
        assert any("relay" in c for c in candidates)


# ==================== Helper Functions ====================


def extract_srflx_ip(candidate: str) -> str | None:
    """Extract the public IP from an srflx (server reflexive) candidate.

    ICE candidate format:
    a=candidate:<foundation> <component> <protocol> <priority> <ip> <port> typ <type> [extensions]

    For srflx: the <ip> is the public IP discovered via STUN
    """
    if " srflx" not in candidate:
        return None

    # Split by spaces and find the IP (it's the 5th field after a=candidate:)
    # a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx ...
    parts = candidate.replace("a=candidate:", "").split()
    return parts[4] if len(parts) >= 5 else None


class SamePublicIpResult:
    """Result of same public IP detection."""

    def __init__(
        self,
        same_public_ip: bool,
        offer_public_ip: str | None,
        answer_public_ip: str | None,
    ):
        self.same_public_ip = same_public_ip
        self.offer_public_ip = offer_public_ip
        self.answer_public_ip = answer_public_ip


def detect_same_public_ip(offer_sdp: str, answer_sdp: str) -> SamePublicIpResult:
    """Detect if offer and answer have the same public IP (hairpin NAT scenario)."""
    offer_candidates = extract_candidates(offer_sdp)
    answer_candidates = extract_candidates(answer_sdp)

    offer_srflx_ip = None
    for c in offer_candidates:
        ip = extract_srflx_ip(c)
        if ip:
            offer_srflx_ip = ip
            break

    answer_srflx_ip = None
    for c in answer_candidates:
        ip = extract_srflx_ip(c)
        if ip:
            answer_srflx_ip = ip
            break

    same_ip = (
        offer_srflx_ip is not None
        and answer_srflx_ip is not None
        and offer_srflx_ip == answer_srflx_ip
    )

    return SamePublicIpResult(
        same_public_ip=same_ip,
        offer_public_ip=offer_srflx_ip,
        answer_public_ip=answer_srflx_ip,
    )
