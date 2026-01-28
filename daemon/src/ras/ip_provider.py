"""IP address discovery providers.

Provides different strategies for discovering the IP address to use
in QR codes for pairing. Follows dependency injection pattern.
"""

import socket
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

    Uses UDP socket trick to find the local IP that would be used
    to reach external addresses. This is the IP other devices on
    the same network can use to connect.

    Example:
        provider = LocalNetworkIpProvider()
        ip = await provider.get_ip()  # "192.168.1.100"
    """

    async def get_ip(self) -> str:
        """Get local network IP address.

        Returns:
            Local IP address (e.g., '192.168.1.100').

        Raises:
            IpDiscoveryError: If local IP cannot be determined.
        """
        try:
            # Create a UDP socket and "connect" to an external address
            # This doesn't send any packets, but determines the local IP
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                # Use Google DNS as the target (doesn't actually connect)
                s.connect(("8.8.8.8", 80))
                ip = s.getsockname()[0]

            # Validate result
            if ip == "0.0.0.0":
                raise IpDiscoveryError("Could not determine local IP (got 0.0.0.0)")
            if ip.startswith("127."):
                raise IpDiscoveryError(f"Got loopback address {ip}, not LAN IP")

            return ip

        except OSError as e:
            raise IpDiscoveryError(f"Network error discovering local IP: {e}")


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
