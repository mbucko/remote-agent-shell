"""Tailscale listener for accepting direct connections from phones."""

import asyncio
import logging
import struct
from typing import Awaitable, Callable, Optional, Tuple, Union

from ras.tailscale.detector import detect_tailscale, TailscaleInfo
from ras.tailscale.transport import (
    HANDSHAKE_MAGIC,
    TailscaleProtocol,
    TailscaleTransport,
)

logger = logging.getLogger(__name__)

DEFAULT_PORT = 9876


class TailscaleListener:
    """Listens for direct Tailscale connections from phones.

    When a phone detects both sides are on Tailscale, it will attempt
    a direct UDP connection to this listener instead of using WebRTC.
    """

    def __init__(
        self,
        port: int = DEFAULT_PORT,
        on_connection: Optional[
            Callable[[TailscaleTransport], Awaitable[None]]
        ] = None,
    ):
        """Initialize Tailscale listener.

        Args:
            port: Port to listen on (default: 9876)
            on_connection: Callback when new connection is established
        """
        self._port = port
        self._on_connection = on_connection
        self._transport: Optional[asyncio.DatagramTransport] = None
        self._protocol: Optional[TailscaleProtocol] = None
        self._tailscale_info: Optional[TailscaleInfo] = None
        self._running = False
        self._pending_handshakes: dict[Tuple[str, int], float] = {}
        # Track established connections by address to route packets correctly
        self._connections: dict[Tuple[str, int], TailscaleTransport] = {}

    @property
    def tailscale_ip(self) -> Optional[str]:
        """Get local Tailscale IP, or None if not available."""
        return self._tailscale_info.ip if self._tailscale_info else None

    @property
    def port(self) -> int:
        """Get listening port."""
        return self._port

    @property
    def is_available(self) -> bool:
        """Whether Tailscale is available for direct connections."""
        return self._tailscale_info is not None

    async def start(self) -> bool:
        """Start listening for Tailscale connections.

        Returns:
            True if started successfully, False if Tailscale not available
        """
        # Detect Tailscale
        self._tailscale_info = detect_tailscale()

        if not self._tailscale_info:
            logger.info("Tailscale not detected, listener not started")
            return False

        try:
            loop = asyncio.get_running_loop()

            # Create UDP socket bound to Tailscale IP specifically
            # This ensures responses go out with correct source IP
            self._transport, self._protocol = await loop.create_datagram_endpoint(
                TailscaleProtocol,
                local_addr=(self._tailscale_info.ip, self._port),
            )

            # Verify actual bound address
            sock = self._transport.get_extra_info('socket')
            if sock:
                actual_addr = sock.getsockname()
                logger.info(f"Tailscale socket bound to {actual_addr}")

            self._running = True
            logger.info(
                f"Tailscale listener started on {self._tailscale_info.ip}:{self._port}"
            )

            # Start receiving loop
            asyncio.create_task(self._receive_loop())

            return True

        except Exception as e:
            logger.error(f"Failed to start Tailscale listener: {e}")
            return False

    async def stop(self) -> None:
        """Stop the listener."""
        self._running = False
        if self._transport:
            self._transport.close()
            self._transport = None
        logger.info("Tailscale listener stopped")

    async def _receive_loop(self) -> None:
        """Main receive loop for handling incoming packets."""
        logger.debug("Tailscale receive loop started")

        while self._running and self._protocol:
            try:
                data, addr = await asyncio.wait_for(
                    self._protocol.receive(),
                    timeout=1.0
                )
                await self._handle_packet(data, addr)

            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                break
            except Exception as e:
                if self._running:
                    logger.error(f"Error in receive loop: {e}")

        logger.debug("Tailscale receive loop ended")

    async def _handle_packet(
        self,
        data: bytes,
        addr: Tuple[str, int]
    ) -> Optional[asyncio.Task]:
        """Handle an incoming packet.

        Args:
            data: Packet data
            addr: Remote (ip, port) tuple

        Returns:
            Task running the on_connection callback if this was a new handshake,
            None otherwise. Callers can await this task if needed.
        """
        # Check for handshake
        if len(data) == 8:
            try:
                magic, _ = struct.unpack(">II", data)
                if magic == HANDSHAKE_MAGIC:
                    return await self._handle_handshake(addr)
            except struct.error:
                pass

        # Regular data packet - route to existing connection
        if addr in self._connections:
            # Route packet to the transport's own queue
            logger.debug(f"Routing {len(data)} bytes to existing connection from {addr}")
            transport = self._connections[addr]
            await transport.enqueue(data, addr)
        else:
            # No established connection for this address - might be a late handshake or error
            logger.warning(f"Received {len(data)} bytes from unknown address {addr}")
        return None

    async def _handle_handshake(self, addr: Tuple[str, int]) -> Optional[asyncio.Task]:
        """Handle handshake from phone.

        Args:
            addr: Remote (ip, port) tuple

        Returns:
            Task running the on_connection callback, or None if no callback
            or re-handshake from existing connection. Callers can await this
            task if they need to wait for the callback to complete.
        """
        # Check if we already have a connection from this address
        if addr in self._connections:
            logger.debug(f"Re-handshake from existing connection {addr}")
            # Send response but don't create new transport
            self._protocol.send_handshake_response(addr)
            return None

        logger.info(f"Received Tailscale handshake from {addr}")

        # Send handshake response
        self._protocol.send_handshake_response(addr)

        # Create transport, track it, and notify callback
        if self._on_connection and self._transport and self._protocol:
            transport = TailscaleTransport(
                self._transport,
                self._protocol,
                addr
            )
            self._connections[addr] = transport
            # Spawn callback as a task and return it.
            # The callback may wait for data (auth) from the transport,
            # which requires the receive loop to keep running to route packets.
            # Returning the task allows callers to await it if needed (e.g., tests).
            return asyncio.create_task(self._on_connection(transport))
        return None

    def get_capabilities(self) -> dict:
        """Get capabilities for signaling exchange.

        Returns:
            Dict with Tailscale capabilities
        """
        if self._tailscale_info:
            return {
                "tailscale_ip": self._tailscale_info.ip,
                "tailscale_port": self._port,
            }
        return {}
