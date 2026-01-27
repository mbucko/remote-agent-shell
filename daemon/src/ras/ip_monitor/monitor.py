"""IP change monitoring via STUN queries."""

import asyncio
import logging
from typing import Awaitable, Callable, Optional, Protocol

logger = logging.getLogger(__name__)


class StunClient(Protocol):
    """Protocol for STUN client dependency."""

    async def get_public_ip(self) -> tuple[str, int]:
        """Returns (ip, port) tuple."""
        ...


class IpMonitor:
    """Monitors public IP changes via periodic STUN queries.

    Uses dependency injection for the STUN client to allow testing
    and different STUN implementations.
    """

    def __init__(
        self,
        stun_client: StunClient,
        check_interval: float = 30.0,
        on_ip_change: Optional[Callable[[str, int], Awaitable[None]]] = None,
    ):
        """Initialize IP monitor.

        Args:
            stun_client: STUN client for getting public IP.
            check_interval: Seconds between IP checks.
            on_ip_change: Async callback when IP changes (receives new ip, port).
        """
        self._stun = stun_client
        self._interval = check_interval
        self._on_ip_change = on_ip_change
        self._current_ip: Optional[str] = None
        self._current_port: Optional[int] = None
        self._running = False
        self._task: Optional[asyncio.Task] = None

    @property
    def current_ip(self) -> Optional[str]:
        """Current public IP address."""
        return self._current_ip

    @property
    def current_port(self) -> Optional[int]:
        """Current public port."""
        return self._current_port

    @property
    def is_running(self) -> bool:
        """Whether monitoring is active."""
        return self._running

    async def start(self) -> None:
        """Start monitoring IP changes.

        Gets initial IP and starts periodic checking.
        """
        if self._running:
            return

        self._running = True

        # Get initial IP
        self._current_ip, self._current_port = await self._stun.get_public_ip()
        logger.info("Initial IP acquired")
        logger.debug(f"Initial IP: {self._current_ip}:{self._current_port}")

        self._task = asyncio.create_task(self._monitor_loop())

    async def stop(self) -> None:
        """Stop monitoring."""
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        logger.info("IP monitor stopped")

    async def check_now(self) -> bool:
        """Perform an immediate IP check.

        Returns:
            True if IP changed, False otherwise.
        """
        try:
            new_ip, new_port = await self._stun.get_public_ip()
            return await self._handle_ip_result(new_ip, new_port)
        except Exception as e:
            logger.warning(f"STUN query failed: {e}")
            return False

    async def _monitor_loop(self) -> None:
        """Periodically check for IP changes."""
        while self._running:
            await asyncio.sleep(self._interval)
            if not self._running:
                break
            try:
                new_ip, new_port = await self._stun.get_public_ip()
                await self._handle_ip_result(new_ip, new_port)
            except Exception as e:
                logger.warning(f"STUN query failed: {e}")

    async def _handle_ip_result(self, new_ip: str, new_port: int) -> bool:
        """Handle STUN query result.

        Args:
            new_ip: New IP address.
            new_port: New port.

        Returns:
            True if IP changed, False otherwise.
        """
        if new_ip != self._current_ip or new_port != self._current_port:
            logger.info("IP changed, notifying")
            logger.debug(
                f"IP changed: {self._current_ip}:{self._current_port} "
                f"-> {new_ip}:{new_port}"
            )
            self._current_ip = new_ip
            self._current_port = new_port

            if self._on_ip_change:
                try:
                    await self._on_ip_change(new_ip, new_port)
                except Exception as e:
                    logger.error(f"IP change callback failed: {e}")

            return True
        return False

    def set_callback(
        self, callback: Optional[Callable[[str, int], Awaitable[None]]]
    ) -> None:
        """Set or update the IP change callback.

        Args:
            callback: New callback or None to remove.
        """
        self._on_ip_change = callback
