"""IP address discovery providers.

Provides different strategies for discovering the IP address to use
in QR codes for pairing. Follows dependency injection pattern.
"""

import socket
import netifaces
from typing import Protocol


class IpProvider(Protocol):
    """Protocol for IP address discovery.

    Implementations provide different strategies:
    - LocalNetworkIpProvider: Discovers LAN IP (192.168.x.x)
    - StunIpProvider: Discovers public IP via STUN
    - StaticIpProvider: Uses a configured static IP
    """

    async def get_ip(self) -> str:
        """Get the IP address to use for pairing.

        Returns:
            Routable IP address (not 0.0.0.0 or similar).

        Raises:
            IpDiscoveryError: If IP cannot be determined.
        """
        ...


class IpDiscoveryError(Exception):
    """Failed to discover IP address."""
    pass


class LocalNetworkIpProvider:
    """Discovers local LAN IP address.

    Prefers physical LAN IPs (192.168.x.x, etc.) over VPN tunnel IPs
    to ensure the daemon can receive connections on the advertised IP.

    Example:
        provider = LocalNetworkIpProvider()
        ip = await provider.get_ip()  # "192.168.1.100"
    """

    # Interface name prefixes that indicate physical network (not VPN/tunnel)
    PHYSICAL_PREFIXES = ("en", "eth", "wlan", "bridge")
    # VPN/tunnel interface prefixes to avoid
    VPN_PREFIXES = ("utun", "tun", "tap", "wg", "tailscale")

    async def get_ip(self) -> str:
        """Get local network IP address, preferring physical interfaces.

        Returns:
            Local IP address (e.g., '192.168.1.100').

        Raises:
            IpDiscoveryError: If local IP cannot be determined.
        """
        # First, try to find an IP on a physical interface
        physical_ip = self._get_physical_interface_ip()
        if physical_ip:
            return physical_ip

        # Fall back to socket trick (may return VPN IP)
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("8.8.8.8", 80))
                ip = s.getsockname()[0]

            if ip == "0.0.0.0":
                raise IpDiscoveryError("Could not determine local IP (got 0.0.0.0)")
            if ip.startswith("127."):
                raise IpDiscoveryError(f"Got loopback address {ip}, not LAN IP")

            return ip

        except OSError as e:
            raise IpDiscoveryError(f"Network error discovering local IP: {e}")

    def _get_physical_interface_ip(self) -> str | None:
        """Get IP from a physical network interface (not VPN/tunnel).

        Returns:
            IP address or None if no physical interface found.
        """
        try:
            for iface in netifaces.interfaces():
                # Skip loopback
                if iface == "lo" or iface.startswith("lo"):
                    continue

                # Skip VPN/tunnel interfaces
                if any(iface.startswith(prefix) for prefix in self.VPN_PREFIXES):
                    continue

                # Prefer physical interfaces
                if any(iface.startswith(prefix) for prefix in self.PHYSICAL_PREFIXES):
                    addrs = netifaces.ifaddresses(iface)
                    if netifaces.AF_INET in addrs:
                        for addr in addrs[netifaces.AF_INET]:
                            ip = addr.get("addr")
                            if ip and not ip.startswith("127."):
                                return ip
        except Exception:
            pass
        return None

    def get_all_ips(self) -> dict[str, str]:
        """Get all available IPs categorized by type.

        Returns:
            Dict with 'lan' and 'vpn' keys containing IPs if available.
        """
        result = {}

        # Get LAN IP from physical interface
        lan_ip = self._get_physical_interface_ip()
        if lan_ip:
            result["lan"] = lan_ip

        # Get VPN IP from socket trick (routes through default gateway)
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("8.8.8.8", 80))
                socket_ip = s.getsockname()[0]
                # If different from LAN IP, it's likely VPN
                if socket_ip and socket_ip != lan_ip and not socket_ip.startswith("127."):
                    result["vpn"] = socket_ip
        except OSError:
            pass

        return result


class StunIpProvider:
    """Discovers public IP via STUN protocol.

    Uses STUN servers to discover the public-facing IP address.
    Useful when the daemon is behind NAT and needs to advertise
    its public IP.

    Example:
        from ras.stun import StunClient
        stun = StunClient()
        provider = StunIpProvider(stun)
        ip = await provider.get_ip()  # "203.0.113.50"
    """

    def __init__(self, stun_client):
        """Initialize with STUN client.

        Args:
            stun_client: StunClient instance for STUN queries.
        """
        self._stun_client = stun_client

    async def get_ip(self) -> str:
        """Get public IP via STUN.

        Returns:
            Public IP address.

        Raises:
            IpDiscoveryError: If STUN query fails.
        """
        try:
            ip, _port = await self._stun_client.get_public_ip()
            return ip
        except Exception as e:
            raise IpDiscoveryError(f"STUN discovery failed: {e}")


class StaticIpProvider:
    """Uses a statically configured IP address.

    For cases where the IP is known and configured explicitly.
    Validates that the IP is routable at construction time.

    Example:
        provider = StaticIpProvider("192.168.1.100")
        ip = await provider.get_ip()  # "192.168.1.100"
    """

    # IPs that are never valid for pairing
    INVALID_IPS = {"0.0.0.0", "255.255.255.255"}

    def __init__(self, ip: str):
        """Initialize with static IP.

        Args:
            ip: The IP address to use.

        Raises:
            ValueError: If IP is not routable.
        """
        self._validate_ip(ip)
        self._ip = ip

    def _validate_ip(self, ip: str) -> None:
        """Validate IP is routable."""
        if ip in self.INVALID_IPS:
            raise ValueError(
                f"IP '{ip}' is not routable. "
                "Use LocalNetworkIpProvider for auto-detection."
            )

        # Basic format validation
        parts = ip.split(".")
        if len(parts) != 4:
            raise ValueError(f"Invalid IP format: {ip}")

        try:
            nums = [int(p) for p in parts]
            if not all(0 <= n <= 255 for n in nums):
                raise ValueError(f"Invalid IP octets: {ip}")
        except ValueError:
            raise ValueError(f"Invalid IP format: {ip}")

    async def get_ip(self) -> str:
        """Return the configured static IP.

        Returns:
            The configured IP address.
        """
        return self._ip
