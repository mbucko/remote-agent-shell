"""Tests for STUN client module."""

import asyncio
from unittest.mock import AsyncMock, Mock, patch

import pytest

from ras.stun import StunClient, StunError


class TestStunClient:
    """Test STUN client."""

    async def test_uses_configured_servers(self):
        """Uses STUN servers from constructor."""
        servers = ["stun:custom.stun.server:3478"]
        client = StunClient(servers=servers)
        assert client.servers == servers

    async def test_uses_default_servers(self):
        """Uses default STUN servers when none provided."""
        client = StunClient()
        assert len(client.servers) > 0
        assert "stun.l.google.com" in client.servers[0]

    async def test_uses_configured_timeout(self):
        """Uses timeout from constructor."""
        client = StunClient(timeout=5.0)
        assert client.timeout == 5.0

    async def test_returns_ip_and_port_with_gatherer(self):
        """STUN client returns public IP and port using injected gatherer."""
        mock_candidate = Mock()
        mock_candidate.type = "srflx"
        mock_candidate.ip = "98.23.45.1"
        mock_candidate.port = 12345

        mock_gatherer = AsyncMock()
        mock_gatherer.gather = AsyncMock()
        mock_gatherer.getLocalCandidates = Mock(return_value=[mock_candidate])

        client = StunClient(gatherer_factory=lambda cfg: mock_gatherer)
        ip, port = await client.get_public_ip()

        assert ip == "98.23.45.1"
        assert port == 12345

    async def test_raises_when_no_srflx_candidate(self):
        """Raise StunError if no server reflexive candidate found."""
        mock_candidate = Mock()
        mock_candidate.type = "host"  # Not srflx

        mock_gatherer = AsyncMock()
        mock_gatherer.gather = AsyncMock()
        mock_gatherer.getLocalCandidates = Mock(return_value=[mock_candidate])

        client = StunClient(gatherer_factory=lambda cfg: mock_gatherer)

        with pytest.raises(StunError, match="No server reflexive candidate"):
            await client.get_public_ip()

    async def test_raises_when_no_candidates(self):
        """Raise StunError if no candidates at all."""
        mock_gatherer = AsyncMock()
        mock_gatherer.gather = AsyncMock()
        mock_gatherer.getLocalCandidates = Mock(return_value=[])

        client = StunClient(gatherer_factory=lambda cfg: mock_gatherer)

        with pytest.raises(StunError, match="No server reflexive candidate"):
            await client.get_public_ip()

    async def test_timeout_handling(self):
        """Raises StunError on timeout."""

        async def gather_that_times_out():
            raise asyncio.TimeoutError()

        mock_gatherer = AsyncMock()
        mock_gatherer.gather = gather_that_times_out

        client = StunClient(timeout=0.1, gatherer_factory=lambda cfg: mock_gatherer)

        with pytest.raises(StunError, match="timeout"):
            await client.get_public_ip()

    async def test_handles_gatherer_exception(self):
        """Raises StunError when gatherer fails."""
        mock_gatherer = AsyncMock()
        mock_gatherer.gather = AsyncMock(side_effect=Exception("Network error"))

        client = StunClient(gatherer_factory=lambda cfg: mock_gatherer)

        with pytest.raises(StunError, match="STUN failed"):
            await client.get_public_ip()


@pytest.mark.integration
class TestStunClientIntegration:
    """Integration tests for STUN client (requires network)."""

    async def test_real_stun_server(self):
        """Actually contact STUN server (needs network)."""
        client = StunClient(timeout=15.0)
        ip, port = await client.get_public_ip()

        assert ip
        assert "." in ip  # IPv4 format
        assert port > 0
