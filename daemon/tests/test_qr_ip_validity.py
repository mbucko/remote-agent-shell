"""Tests to ensure QR code contains valid, routable IP addresses.

These tests catch bugs where the QR code contains non-routable addresses
like 0.0.0.0 or 127.0.0.1 that phones cannot connect to.
"""

import ipaddress
import pytest
import aiohttp

from ras.server import UnifiedServer, get_local_ip
from ras.device_store import JsonDeviceStore


class TestQrCodeIpValidity:
    """Verify QR code contains routable IP addresses."""

    @pytest.fixture
    async def server(self, tmp_path):
        """Create and start a test server."""
        devices_file = tmp_path / "devices.json"
        store = JsonDeviceStore(devices_file)
        await store.load()

        server = UnifiedServer(device_store=store)
        runner = await server.start("127.0.0.1", 0)
        yield server
        await runner.cleanup()

    @pytest.mark.asyncio
    async def test_qr_code_ip_is_not_bind_address(self, server):
        """QR code must not contain 0.0.0.0 (bind address)."""
        port = server.get_port()

        async with aiohttp.ClientSession() as client:
            async with client.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                assert resp.status == 200
                data = await resp.json()

        ip = data["qr_data"]["ip"]
        assert ip != "0.0.0.0", (
            "QR code contains bind address 0.0.0.0 which phones cannot connect to. "
            "Server must auto-detect or be configured with a routable IP."
        )

    @pytest.mark.asyncio
    async def test_qr_code_ip_is_not_localhost(self, server):
        """QR code should not contain localhost for LAN pairing."""
        port = server.get_port()

        async with aiohttp.ClientSession() as client:
            async with client.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                data = await resp.json()

        ip = data["qr_data"]["ip"]
        # Note: 127.0.0.1 might be valid for emulator testing,
        # but for real device pairing it won't work
        assert ip != "127.0.0.1" or ip == get_local_ip(), (
            "QR code contains localhost. For real device pairing, "
            "this should be the LAN IP."
        )

    @pytest.mark.asyncio
    async def test_qr_code_ip_is_valid_ipv4(self, server):
        """QR code must contain a valid IPv4 address."""
        port = server.get_port()

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
        assert not addr.is_unspecified, (
            f"QR code IP '{ip}' is unspecified address"
        )

    @pytest.mark.asyncio
    async def test_qr_code_port_is_valid(self, server):
        """QR code must contain a valid port number."""
        port = server.get_port()

        async with aiohttp.ClientSession() as client:
            async with client.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                data = await resp.json()

        qr_port = data["qr_data"]["port"]

        assert isinstance(qr_port, int), "Port must be an integer"
        assert 1 <= qr_port <= 65535, f"Port {qr_port} is out of valid range"
        assert qr_port != 0, "Port 0 is not a valid connectable port"


class TestLocalIpDetection:
    """Test the local IP detection utility."""

    def test_get_local_ip_returns_valid_ip(self):
        """get_local_ip() should return a valid IPv4 address."""
        ip = get_local_ip()

        # Should parse as valid IP
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            pytest.fail(f"get_local_ip() returned invalid IP: '{ip}'")

        # Should be IPv4
        assert addr.version == 4, f"Expected IPv4, got {addr}"

    def test_get_local_ip_is_not_unspecified(self):
        """get_local_ip() should not return 0.0.0.0."""
        ip = get_local_ip()
        addr = ipaddress.ip_address(ip)
        assert not addr.is_unspecified, "get_local_ip() returned 0.0.0.0"

    def test_get_local_ip_is_deterministic(self):
        """get_local_ip() should return consistent results."""
        ip1 = get_local_ip()
        ip2 = get_local_ip()
        assert ip1 == ip2, "get_local_ip() returned different results"
