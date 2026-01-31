"""VPN candidate injection for ICE.

Detects VPN interfaces (Tailscale, WireGuard, etc.) and injects their IPs
as ICE candidates into SDP. This works around the issue where WebRTC
doesn't properly enumerate VPN/TUN interfaces.
"""

import logging
import re
import socket
from typing import List, Optional

import netifaces

logger = logging.getLogger(__name__)

# VPN interface name patterns
VPN_INTERFACE_PATTERNS = [
    r"^tun\d*$",      # Linux/macOS TUN
    r"^tap\d*$",      # TAP interfaces
    r"^utun\d*$",     # macOS utun (used by Tailscale)
    r"^wg\d*$",       # WireGuard
    r"^tailscale\d*$", # Tailscale (some platforms)
]


def get_vpn_addresses() -> List[str]:
    """Get all VPN interface IPv4 addresses.

    Returns:
        List of IPv4 addresses from VPN interfaces.
    """
    vpn_addresses = []

    try:
        interfaces = netifaces.interfaces()

        for iface in interfaces:
            # Check if interface name matches VPN pattern
            is_vpn = any(re.match(pattern, iface) for pattern in VPN_INTERFACE_PATTERNS)

            if is_vpn:
                addrs = netifaces.ifaddresses(iface)

                # Get IPv4 addresses (AF_INET = 2)
                if netifaces.AF_INET in addrs:
                    for addr_info in addrs[netifaces.AF_INET]:
                        ip = addr_info.get("addr")
                        if ip and not ip.startswith("127."):
                            logger.debug(f"Found VPN address: {ip} on interface {iface}")
                            vpn_addresses.append(ip)

    except Exception as e:
        logger.warning(f"Error enumerating network interfaces: {e}")

    return vpn_addresses


def inject_vpn_candidates(sdp: str, port: Optional[int] = None) -> str:
    """Inject VPN addresses as ICE candidates into SDP.

    Args:
        sdp: The original SDP string.
        port: Optional port to use (extracted from existing candidates if not provided).

    Returns:
        Modified SDP with VPN candidates added.
    """
    vpn_addresses = get_vpn_addresses()

    if not vpn_addresses:
        logger.debug("No VPN addresses found, returning original SDP")
        return sdp

    # Extract port from existing UDP candidate if not provided
    candidate_port = port or _extract_udp_port(sdp)

    if not candidate_port:
        logger.warning("No UDP port found in SDP, cannot inject VPN candidates")
        return sdp

    # Generate candidate lines for VPN addresses
    new_candidates = []
    for i, ip in enumerate(vpn_addresses):
        foundation = 900000000 + i
        priority = 2130706431  # Maximum host priority

        candidate = f"a=candidate:{foundation} 1 udp {priority} {ip} {candidate_port} typ host generation 0"
        new_candidates.append(candidate)

    if not new_candidates:
        return sdp

    logger.info(f"Injecting {len(new_candidates)} VPN candidates: {vpn_addresses}")

    # Insert new candidates after existing candidates
    lines = sdp.split("\r\n") if "\r\n" in sdp else sdp.split("\n")

    # Find last candidate line
    last_candidate_idx = -1
    for i, line in enumerate(lines):
        if line.startswith("a=candidate:"):
            last_candidate_idx = i

    if last_candidate_idx >= 0:
        # Insert after last candidate
        for j, candidate in enumerate(new_candidates):
            lines.insert(last_candidate_idx + 1 + j, candidate)
    else:
        # Find media line and insert after it
        for i, line in enumerate(lines):
            if line.startswith("m="):
                for j, candidate in enumerate(new_candidates):
                    lines.insert(i + 1 + j, candidate)
                break

    # Use CRLF for SDP
    return "\r\n".join(lines)


def _extract_udp_port(sdp: str) -> Optional[int]:
    """Extract UDP port from existing candidates."""
    pattern = r"a=candidate:\S+\s+\d+\s+udp\s+\d+\s+[\d.]+\s+(\d+)"
    match = re.search(pattern, sdp, re.IGNORECASE)

    if match:
        return int(match.group(1))

    return None
