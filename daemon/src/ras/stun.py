"""STUN client for discovering public IP address."""

import asyncio
import logging
from typing import Callable

from aiortc import RTCIceGatherer, RTCIceServer

logger = logging.getLogger(__name__)


class StunError(Exception):
    """STUN operation failed."""

    pass


class StunClient:
    """Client for discovering public IP via STUN protocol."""

    DEFAULT_SERVERS = [
        "stun:stun.l.google.com:19302",
        "stun:stun.cloudflare.com:3478",
    ]
    DEFAULT_TIMEOUT = 10.0

    def __init__(
        self,
        servers: list[str] | None = None,
        timeout: float = DEFAULT_TIMEOUT,
        gatherer_factory: Callable | None = None,
    ):
        """Initialize STUN client.

        Args:
            servers: List of STUN server URLs.
            timeout: Timeout in seconds for STUN operations.
            gatherer_factory: Factory function to create ICE gatherer (for testing).
        """
        self.servers = servers or self.DEFAULT_SERVERS
        self.timeout = timeout
        self._gatherer_factory = gatherer_factory or self._default_gatherer_factory

    def _default_gatherer_factory(self, ice_servers: list[RTCIceServer]) -> RTCIceGatherer:
        """Create default ICE gatherer."""
        return RTCIceGatherer(iceServers=ice_servers)

    async def get_public_ip(self) -> tuple[str, int]:
        """Query STUN server to discover public IP and port.

        Prefers IPv4 over IPv6 for better compatibility and display.

        Returns:
            Tuple of (public_ip, public_port).

        Raises:
            StunError: If STUN operation fails.
        """
        try:
            async with asyncio.timeout(self.timeout):
                ice_servers = [RTCIceServer(urls=self.servers)]
                gatherer = self._gatherer_factory(ice_servers)

                await gatherer.gather()

                # Collect all srflx candidates
                srflx_candidates = [
                    c for c in gatherer.getLocalCandidates()
                    if c.type == "srflx"
                ]

                if not srflx_candidates:
                    raise StunError("No server reflexive candidate found")

                # Prefer IPv4 over IPv6 (IPv4 doesn't contain colons)
                ipv4_candidates = [c for c in srflx_candidates if ":" not in c.ip]
                candidate = ipv4_candidates[0] if ipv4_candidates else srflx_candidates[0]

                logger.info(f"Discovered public IP via STUN: {candidate.ip}")
                logger.debug(f"Public IP: {candidate.ip}:{candidate.port}")
                return (candidate.ip, candidate.port)

        except asyncio.TimeoutError:
            raise StunError(f"STUN timeout after {self.timeout}s")
        except StunError:
            raise
        except Exception as e:
            raise StunError(f"STUN failed: {e}")
