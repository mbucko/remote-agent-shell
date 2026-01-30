"""Tailscale detection and utilities.

Detects if Tailscale is running and provides connection information.
"""

import logging
import re
import socket
from dataclasses import dataclass
from typing import Optional

import netifaces

logger = logging.getLogger(__name__)

# VPN interface patterns (Tailscale uses utun on macOS, tun on Linux)
VPN_INTERFACE_PATTERNS = [
    r"^tun\d*$",
    r"^tap\d*$",
    r"^utun\d*$",
    r"^wg\d*$",
    r"^tailscale\d*$",
]


@dataclass
class TailscaleInfo:
    """Information about detected Tailscale connection."""

    ip: str
    interface_name: str


def is_tailscale_ip(ip: str) -> bool:
    """Check if IP is in Tailscale range (100.64.0.0/10)."""
    try:
        parts = ip.split(".")
        if len(parts) != 4:
            return False
        first = int(parts[0])
        second = int(parts[1])
        # 100.64.0.0/10 = 100.64.0.0 - 100.127.255.255
        return first == 100 and 64 <= second <= 127
    except (ValueError, IndexError):
        return False


def detect_tailscale() -> Optional[TailscaleInfo]:
    """Detect if Tailscale is running and get connection info.

    Returns:
        TailscaleInfo if detected, None otherwise.
    """
    try:
        interfaces = netifaces.interfaces()

        for iface in interfaces:
            # Check if interface matches VPN pattern
            is_vpn_iface = any(
                re.match(pattern, iface)
                for pattern in VPN_INTERFACE_PATTERNS
            )

            addrs = netifaces.ifaddresses(iface)

            # Get IPv4 addresses
            if netifaces.AF_INET in addrs:
                for addr_info in addrs[netifaces.AF_INET]:
                    ip = addr_info.get("addr")
                    if ip and is_tailscale_ip(ip):
                        logger.info(f"Detected Tailscale: {ip} on interface {iface}")
                        return TailscaleInfo(ip=ip, interface_name=iface)

            # Also check by interface name for edge cases
            if is_vpn_iface and netifaces.AF_INET in addrs:
                for addr_info in addrs[netifaces.AF_INET]:
                    ip = addr_info.get("addr")
                    if ip and ip.startswith("100."):
                        logger.info(f"Detected VPN interface {iface} with IP {ip}")
                        return TailscaleInfo(ip=ip, interface_name=iface)

        logger.debug("Tailscale not detected")
        return None

    except Exception as e:
        logger.warning(f"Error detecting Tailscale: {e}")
        return None


def get_all_tailscale_ips() -> list[str]:
    """Get all Tailscale IPs (for logging/debugging)."""
    ips = []

    try:
        interfaces = netifaces.interfaces()

        for iface in interfaces:
            addrs = netifaces.ifaddresses(iface)

            if netifaces.AF_INET in addrs:
                for addr_info in addrs[netifaces.AF_INET]:
                    ip = addr_info.get("addr")
                    if ip and is_tailscale_ip(ip):
                        ips.append(ip)

    except Exception as e:
        logger.warning(f"Error getting Tailscale IPs: {e}")

    return ips
