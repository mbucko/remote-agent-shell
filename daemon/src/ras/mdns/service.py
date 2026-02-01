"""mDNS service registration for local network discovery.

Registers the daemon as a _ras._tcp service so phones on the same
LAN can discover it without needing ntfy or manual IP configuration.
"""

import asyncio
import logging
import socket
from typing import Callable

from zeroconf import IPVersion, ServiceInfo, Zeroconf
from zeroconf.asyncio import AsyncZeroconf

logger = logging.getLogger(__name__)

# Service type for RAS daemon discovery
SERVICE_TYPE = "_ras._tcp.local."
SERVICE_NAME = "daemon._ras._tcp.local."


class MdnsService:
    """mDNS service registration for local discovery.

    Registers the daemon on the local network so phones can discover
    it via mDNS/Bonjour without needing external services.

    Example:
        mdns = MdnsService(port=8765, device_id="abc123")
        await mdns.start()
        # ... daemon running ...
        await mdns.stop()
    """

    def __init__(
        self,
        port: int,
        device_id: str,
        ip_provider: Callable[[], dict[str, str]] | None = None,
    ):
        """Initialize mDNS service.

        Args:
            port: Port the daemon is listening on.
            device_id: Unique device identifier for this daemon.
            ip_provider: Optional callable that returns dict of IPs
                         {"lan": "192.168.1.38", "vpn": "10.12.12.3", ...}
        """
        self._port = port
        self._device_id = device_id
        self._ip_provider = ip_provider
        self._zeroconf: AsyncZeroconf | None = None
        self._service_info: ServiceInfo | None = None
        self._update_task: asyncio.Task | None = None

    async def start(self) -> None:
        """Start mDNS service registration."""
        if self._zeroconf is not None:
            return

        logger.info("Starting mDNS service registration")

        # Get current IPs
        addresses = self._get_addresses()
        if not addresses:
            logger.warning("No addresses found for mDNS registration")
            return

        # Create service info
        self._service_info = ServiceInfo(
            SERVICE_TYPE,
            SERVICE_NAME,
            addresses=addresses,
            port=self._port,
            properties={
                "device_id": self._device_id,
                "version": "1",
            },
        )

        # Register service
        self._zeroconf = AsyncZeroconf(ip_version=IPVersion.V4Only)
        await self._zeroconf.async_register_service(self._service_info)

        logger.info(f"mDNS service registered: {SERVICE_NAME} on port {self._port}")
        for addr in addresses:
            logger.info(f"  Address: {socket.inet_ntoa(addr)}")

        # Start background task to update IPs periodically
        self._update_task = asyncio.create_task(self._update_loop())

    async def stop(self) -> None:
        """Stop mDNS service registration."""
        if self._update_task:
            self._update_task.cancel()
            try:
                await self._update_task
            except asyncio.CancelledError:
                pass
            self._update_task = None

        if self._zeroconf and self._service_info:
            logger.info("Unregistering mDNS service")
            await self._zeroconf.async_unregister_service(self._service_info)
            await self._zeroconf.async_close()
            self._zeroconf = None
            self._service_info = None

    async def update_addresses(self) -> None:
        """Update registered addresses (call when IP changes)."""
        if not self._zeroconf or not self._service_info:
            return

        new_addresses = self._get_addresses()
        if new_addresses == self._service_info.addresses:
            return

        logger.info("Updating mDNS addresses")

        # Unregister old, register new
        await self._zeroconf.async_unregister_service(self._service_info)

        self._service_info = ServiceInfo(
            SERVICE_TYPE,
            SERVICE_NAME,
            addresses=new_addresses,
            port=self._port,
            properties={
                "device_id": self._device_id,
                "version": "1",
            },
        )

        await self._zeroconf.async_register_service(self._service_info)

        for addr in new_addresses:
            logger.info(f"  New address: {socket.inet_ntoa(addr)}")

    def _get_addresses(self) -> list[bytes]:
        """Get list of addresses to register."""
        addresses = []

        if self._ip_provider:
            ips = self._ip_provider()
            for ip in ips.values():
                if ip:
                    try:
                        addresses.append(socket.inet_aton(ip))
                    except OSError:
                        pass
        else:
            # Fallback: use local IP detection
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.connect(("8.8.8.8", 80))
                    local_ip = s.getsockname()[0]
                    addresses.append(socket.inet_aton(local_ip))
            except OSError:
                pass

        return addresses

    async def _update_loop(self) -> None:
        """Periodically check for IP changes and update registration."""
        while True:
            try:
                await asyncio.sleep(30)  # Check every 30 seconds
                await self.update_addresses()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.warning(f"Error updating mDNS addresses: {e}")
