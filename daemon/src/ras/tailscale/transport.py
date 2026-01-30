"""Tailscale direct transport using UDP sockets."""

import asyncio
import logging
import struct
import time
from dataclasses import dataclass, field
from typing import Optional, Tuple

logger = logging.getLogger(__name__)

HANDSHAKE_MAGIC = 0x52415354  # "RAST" in hex
HEADER_SIZE = 4  # 4-byte length prefix
MAX_PACKET_SIZE = 65507  # Max UDP payload


@dataclass
class TransportStats:
    """Transport statistics for monitoring."""

    bytes_sent: int = 0
    bytes_received: int = 0
    messages_sent: int = 0
    messages_received: int = 0
    connected_at: float = field(default_factory=time.time)
    last_activity: float = field(default_factory=time.time)


class TailscaleTransport:
    """UDP transport over Tailscale for direct communication.

    Protocol:
    - Each message is prefixed with 4-byte length (big-endian)
    - Initial handshake verifies both sides are using same protocol
    """

    def __init__(
        self,
        transport: asyncio.DatagramTransport,
        protocol: "TailscaleProtocol",
        remote_addr: Tuple[str, int],
    ):
        """Initialize transport with existing connection.

        Args:
            transport: asyncio datagram transport
            protocol: asyncio datagram protocol
            remote_addr: Remote (ip, port) tuple
        """
        self._transport = transport
        self._protocol = protocol
        self._remote_addr = remote_addr
        self._closed = False
        self._stats = TransportStats()
        logger.info(f"TailscaleTransport created for {remote_addr}")

    @property
    def is_connected(self) -> bool:
        """Whether transport is connected."""
        return not self._closed and self._transport is not None

    @property
    def remote_address(self) -> Tuple[str, int]:
        """Remote address tuple (ip, port)."""
        return self._remote_addr

    async def send(self, data: bytes) -> None:
        """Send data to remote peer.

        Args:
            data: Data to send (will be length-prefixed)

        Raises:
            ConnectionError: If transport is closed
            ValueError: If data is too large
        """
        if self._closed:
            raise ConnectionError("Transport is closed")

        if len(data) > MAX_PACKET_SIZE - HEADER_SIZE:
            raise ValueError(f"Data too large: {len(data)} bytes")

        # Prepend length header
        packet = struct.pack(">I", len(data)) + data
        self._transport.sendto(packet, self._remote_addr)

        self._stats.bytes_sent += len(data)
        self._stats.messages_sent += 1
        self._stats.last_activity = time.time()

        logger.debug(f"Sent {len(data)} bytes to {self._remote_addr}")

    async def receive(self, timeout: float = 30.0) -> bytes:
        """Receive data from remote peer.

        Args:
            timeout: Timeout in seconds

        Returns:
            Received data (without length prefix)

        Raises:
            ConnectionError: If transport is closed
            TimeoutError: If timeout expires
        """
        if self._closed:
            raise ConnectionError("Transport is closed")

        try:
            data, addr = await asyncio.wait_for(
                self._protocol.receive(),
                timeout=timeout
            )
        except asyncio.TimeoutError:
            raise TimeoutError(f"Receive timeout after {timeout}s")

        if len(data) < HEADER_SIZE:
            raise ValueError(f"Packet too small: {len(data)} bytes")

        length = struct.unpack(">I", data[:HEADER_SIZE])[0]
        if length > len(data) - HEADER_SIZE:
            raise ValueError(f"Invalid length prefix: {length}")

        payload = data[HEADER_SIZE:HEADER_SIZE + length]

        self._stats.bytes_received += len(payload)
        self._stats.messages_received += 1
        self._stats.last_activity = time.time()

        logger.debug(f"Received {len(payload)} bytes from {addr}")
        return payload

    def close(self) -> None:
        """Close the transport."""
        if self._closed:
            return
        self._closed = True
        logger.info(f"Closing TailscaleTransport to {self._remote_addr}")
        try:
            self._transport.close()
        except Exception as e:
            logger.warning(f"Error closing transport: {e}")

    def get_stats(self) -> TransportStats:
        """Get transport statistics."""
        return self._stats


class TailscaleProtocol(asyncio.DatagramProtocol):
    """asyncio protocol for Tailscale UDP communication."""

    def __init__(self):
        self._queue: asyncio.Queue[Tuple[bytes, Tuple[str, int]]] = asyncio.Queue()
        self._transport: Optional[asyncio.DatagramTransport] = None
        self._closed = False

    def connection_made(self, transport: asyncio.DatagramTransport) -> None:
        """Called when connection is established."""
        self._transport = transport
        logger.debug("TailscaleProtocol connection made")

    def datagram_received(self, data: bytes, addr: Tuple[str, int]) -> None:
        """Called when a datagram is received."""
        if not self._closed:
            self._queue.put_nowait((data, addr))

    def error_received(self, exc: Exception) -> None:
        """Called when an error is received."""
        logger.warning(f"TailscaleProtocol error: {exc}")

    def connection_lost(self, exc: Optional[Exception]) -> None:
        """Called when connection is lost."""
        self._closed = True
        if exc:
            logger.warning(f"TailscaleProtocol connection lost: {exc}")
        else:
            logger.debug("TailscaleProtocol connection closed")

    async def receive(self) -> Tuple[bytes, Tuple[str, int]]:
        """Wait for and return the next datagram."""
        return await self._queue.get()

    def send_handshake_response(self, addr: Tuple[str, int]) -> None:
        """Send handshake response."""
        if self._transport:
            response = struct.pack(">II", HANDSHAKE_MAGIC, 0)
            self._transport.sendto(response, addr)
            logger.debug(f"Sent handshake response to {addr}")
