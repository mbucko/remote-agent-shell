"""Manage phone connections and broadcast events."""

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional, Protocol

logger = logging.getLogger(__name__)


class PeerProtocol(Protocol):
    """Protocol for peer connections."""

    def on_message(self, handler: Callable[[bytes], None]) -> None:
        """Set message handler."""
        ...

    def on_close(self, handler: Callable[[], None]) -> None:
        """Set close handler."""
        ...

    async def send(self, data: bytes) -> None:
        """Send data."""
        ...

    async def close(self) -> None:
        """Close connection."""
        ...


class CodecProtocol(Protocol):
    """Protocol for message codec."""

    def encode(self, data: bytes) -> bytes:
        """Encrypt/encode data."""
        ...

    def decode(self, data: bytes) -> bytes:
        """Decrypt/decode data."""
        ...


@dataclass
class Connection:
    """Wrapper around a phone connection with encryption."""

    device_id: str
    peer: Any  # PeerProtocol
    codec: Any  # CodecProtocol
    connected_at: float = field(default_factory=time.time)
    last_activity: float = field(default_factory=time.time)

    async def send(self, data: bytes) -> None:
        """Encrypt and send data to this connection."""
        encrypted = self.codec.encode(data)
        await self.peer.send(encrypted)

    def decrypt(self, data: bytes) -> bytes:
        """Decrypt received data."""
        return self.codec.decode(data)

    def update_activity(self) -> None:
        """Update last activity timestamp."""
        self.last_activity = time.time()

    async def close(self) -> None:
        """Close this connection."""
        await self.peer.close()


class ConnectionManager:
    """Track and manage phone connections with thread-safety."""

    def __init__(
        self,
        on_connection_lost: Optional[Callable[[str], Awaitable[None]]] = None,
        send_timeout: float = 5.0,
    ):
        """Initialize connection manager.

        Args:
            on_connection_lost: Callback when a connection is lost.
            send_timeout: Timeout for send operations.
        """
        self.connections: dict[str, Connection] = {}
        self._on_connection_lost = on_connection_lost
        self._send_timeout = send_timeout
        self._lock = asyncio.Lock()  # Thread-safe mutations

    async def add_connection(
        self,
        device_id: str,
        peer: PeerProtocol,
        codec: CodecProtocol,
        on_message: Callable[[bytes], None],
    ) -> Connection:
        """Add a new connection (thread-safe).

        Args:
            device_id: Unique device identifier.
            peer: Peer connection object.
            codec: Message codec for encryption/decryption.
            on_message: Callback for received messages.

        Returns:
            The created Connection object.
        """
        old_conn = None
        conn = Connection(device_id=device_id, peer=peer, codec=codec)

        async with self._lock:
            # Get existing connection from same device
            if device_id in self.connections:
                logger.info(f"Replacing existing connection for {device_id}")
                old_conn = self.connections[device_id]

            self.connections[device_id] = conn

        # Close old connection outside lock (don't trigger our disconnect handler)
        if old_conn is not None:
            # Remove close handler before closing to avoid race
            old_conn.peer.on_close(lambda: None)
            asyncio.create_task(old_conn.close())

        # Set up message handler (wraps with decryption)
        # Must be async because peer.py awaits the callback
        async def handle_encrypted(data: bytes) -> None:
            try:
                decrypted = conn.decrypt(data)
                conn.update_activity()
                on_message(decrypted)
            except Exception as e:
                logger.warning(f"Failed to decrypt from {device_id}: {e}")

        peer.on_message(handle_encrypted)

        # Set up disconnect handler - only for the current connection
        def on_disconnect():
            # Only handle if this connection is still the active one
            if self.connections.get(device_id) is conn:
                asyncio.create_task(self._handle_disconnect(device_id))

        peer.on_close(on_disconnect)

        return conn

    def get_connection(self, device_id: str) -> Optional[Connection]:
        """Get connection by device ID."""
        return self.connections.get(device_id)

    async def _handle_disconnect(self, device_id: str) -> None:
        """Handle connection disconnect (thread-safe)."""
        async with self._lock:
            if device_id in self.connections:
                del self.connections[device_id]

        if self._on_connection_lost:
            await self._on_connection_lost(device_id)

    async def broadcast(self, data: bytes) -> None:
        """Broadcast data to all connections (concurrent, fault-tolerant).

        Args:
            data: Data to broadcast to all connections.
        """
        # Snapshot connections under lock
        async with self._lock:
            conns = list(self.connections.values())

        if not conns:
            return

        async def send_with_timeout(
            conn: Connection,
        ) -> tuple[str, Optional[Exception]]:
            try:
                await asyncio.wait_for(conn.send(data), timeout=self._send_timeout)
                return (conn.device_id, None)
            except asyncio.TimeoutError:
                return (
                    conn.device_id,
                    TimeoutError(f"Send timeout to {conn.device_id}"),
                )
            except Exception as e:
                return (conn.device_id, e)

        results = await asyncio.gather(
            *[send_with_timeout(conn) for conn in conns],
            return_exceptions=True,
        )

        # Log failures
        for result in results:
            if isinstance(result, tuple):
                device_id, error = result
                if error is not None:
                    logger.warning(f"Broadcast to {device_id} failed: {error}")

    async def close_all(self) -> None:
        """Close all connections gracefully."""
        async with self._lock:
            conns = list(self.connections.values())
            self.connections.clear()

        if conns:
            await asyncio.gather(
                *[conn.close() for conn in conns],
                return_exceptions=True,
            )

    def __len__(self) -> int:
        return len(self.connections)
