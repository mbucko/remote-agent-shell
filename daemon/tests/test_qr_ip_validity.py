"""Tests to ensure QR code contains valid, routable IP addresses.

These tests catch bugs where the QR code contains non-routable addresses
like 0.0.0.0 or 127.0.0.1 that phones cannot connect to.
"""

import ipaddress
import pytest
import aiohttp

from ras.server import UnifiedServer
from ras.device_store import JsonDeviceStore
from ras.ip_provider import (
    IpProvider,
    LocalNetworkIpProvider,
    StaticIpProvider,
    IpDiscoveryError,
)


class MockIpProvider:
    """Mock IP provider for testing."""

    def __init__(self, ip: str):
        self._ip = ip

    async def get_ip(self) -> str:
        return self._ip


class FailingIpProvider:
    """IP provider that always fails."""

    async def get_ip(self) -> str:
        raise IpDiscoveryError("Simulated failure")


class TestQrCodeIpValidity:
    """Verify QR code contains routable IP addresses."""

    @pytest.fixture
    async def server_with_real_ip(self, tmp_path):
        """Create server with real IP detection."""
        devices_file = tmp_path / "devices.json"
        store = JsonDeviceStore(devices_file)
        await store.load()

        ip_provider = LocalNetworkIpProvider()
        server = UnifiedServer(device_store=store, ip_provider=ip_provider)
        runner = await server.start("127.0.0.1", 0)
        yield server
        await runner.cleanup()

    @pytest.fixture
    async def server_with_mock_ip(self, tmp_path):
        """Create server with mock IP provider."""
        devices_file = tmp_path / "devices.json"
        store = JsonDeviceStore(devices_file)
        await store.load()

        ip_provider = MockIpProvider("192.168.1.100")
        server = UnifiedServer(device_store=store, ip_provider=ip_provider)
        runner = await server.start("127.0.0.1", 0)
        yield server
        await runner.cleanup()

    @pytest.mark.asyncio
    async def test_qr_code_ip_is_not_bind_address(self, server_with_real_ip):
        """QR code must not contain 0.0.0.0 (bind address)."""
        port = server_with_real_ip.get_port()

        async with aiohttp.ClientSession() as client:
            async with client.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                assert resp.status == 200
                data = await resp.json()

        ip = data["qr_data"]["ip"]
        assert ip != "0.0.0.0", (
            "QR code contains bind address 0.0.0.0 which phones cannot connect to. "
            "IP provider must return a routable IP."
        )

    @pytest.mark.asyncio
    async def test_qr_code_ip_is_valid_ipv4(self, server_with_real_ip):
        """QR code must contain a valid IPv4 address."""
        port = server_with_real_ip.get_port()

        async with aiohttp.ClientSession() as client:
            async with client.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                data = await resp.json()

        ip = data["qr_data"]["ip"]

        # Should parse as valid IP
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            pytest.fail(f"QR code IP '{ip}' is not a valid IP address")

        # Should not be unspecified (0.0.0.0)
        assert not addr.is_unspecified, f"QR code IP '{ip}' is unspecified address"

    @pytest.mark.asyncio
    async def test_qr_code_port_is_valid(self, server_with_mock_ip):
        """QR code must contain a valid port number."""
        port = server_with_mock_ip.get_port()

        async with aiohttp.ClientSession() as client:
            async with client.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                data = await resp.json()

        qr_port = data["qr_data"]["port"]

        assert isinstance(qr_port, int), "Port must be an integer"
        assert 1 <= qr_port <= 65535, f"Port {qr_port} is out of valid range"
        assert qr_port != 0, "Port 0 is not a valid connectable port"

    @pytest.mark.asyncio
    async def test_qr_code_uses_injected_ip(self, server_with_mock_ip):
        """QR code should use IP from injected provider."""
        port = server_with_mock_ip.get_port()

        async with aiohttp.ClientSession() as client:
            async with client.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                data = await resp.json()

        ip = data["qr_data"]["ip"]
        assert ip == "192.168.1.100", "QR should use IP from provider"


class TestIpProviderFailure:
    """Test behavior when IP provider fails."""

    @pytest.fixture
    async def server_with_failing_ip(self, tmp_path):
        """Create server with failing IP provider."""
        devices_file = tmp_path / "devices.json"
        store = JsonDeviceStore(devices_file)
        await store.load()

        ip_provider = FailingIpProvider()
        server = UnifiedServer(device_store=store, ip_provider=ip_provider)
        runner = await server.start("127.0.0.1", 0)
        yield server
        await runner.cleanup()

    @pytest.mark.asyncio
    async def test_pairing_fails_gracefully_when_ip_unavailable(
        self, server_with_failing_ip
    ):
        """Pairing should fail with clear error when IP can't be determined."""
        port = server_with_failing_ip.get_port()

        async with aiohttp.ClientSession() as client:
            async with client.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                assert resp.status == 500
                data = await resp.json()
                assert "error" in data
                assert "Cannot determine IP" in data["error"]


class TestLocalNetworkIpProvider:
    """Test the LocalNetworkIpProvider."""

    @pytest.mark.asyncio
    async def test_returns_valid_ip(self):
        """Provider should return a valid IPv4 address."""
        provider = LocalNetworkIpProvider()
        ip = await provider.get_ip()

        # Should parse as valid IP
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            pytest.fail(f"Provider returned invalid IP: '{ip}'")

        # Should be IPv4
        assert addr.version == 4, f"Expected IPv4, got {addr}"

    @pytest.mark.asyncio
    async def test_returns_non_unspecified_ip(self):
        """Provider should not return 0.0.0.0."""
        provider = LocalNetworkIpProvider()
        ip = await provider.get_ip()
        addr = ipaddress.ip_address(ip)
        assert not addr.is_unspecified, "Provider returned 0.0.0.0"

    @pytest.mark.asyncio
    async def test_is_deterministic(self):
        """Provider should return consistent results."""
        provider = LocalNetworkIpProvider()
        ip1 = await provider.get_ip()
        ip2 = await provider.get_ip()
        assert ip1 == ip2, "Provider returned different results"


class TestStaticIpProvider:
    """Test the StaticIpProvider."""

    @pytest.mark.asyncio
    async def test_returns_configured_ip(self):
        """Provider should return the configured IP."""
        provider = StaticIpProvider("10.0.0.1")
        ip = await provider.get_ip()
        assert ip == "10.0.0.1"

    def test_rejects_unroutable_ip(self):
        """Provider should reject 0.0.0.0."""
        with pytest.raises(ValueError, match="not routable"):
            StaticIpProvider("0.0.0.0")

    def test_rejects_broadcast_ip(self):
        """Provider should reject broadcast address."""
        with pytest.raises(ValueError, match="not routable"):
            StaticIpProvider("255.255.255.255")

    def test_rejects_invalid_format(self):
        """Provider should reject invalid IP format."""
        with pytest.raises(ValueError, match="Invalid IP"):
            StaticIpProvider("not-an-ip")
