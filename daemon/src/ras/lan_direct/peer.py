"""LAN Direct peer using WebSocket transport."""

import asyncio
import logging
from typing import Any, Callable, Optional, Protocol

from ras.protocols import PeerOwnership

logger = logging.getLogger(__name__)


class WebSocketProtocol(Protocol):
    """Protocol for WebSocket objects (for type hints)."""

    closed: bool

    async def send_bytes(self, data: bytes) -> None:
        """Send binary data."""
        ...

    async def close(self, code: int = 1000, message: bytes = b"") -> None:
        """Close the WebSocket."""
        ...

    def __aiter__(self):
        """Async iteration over messages."""
        ...

    async def __anext__(self):
        """Get next message."""
        ...


class LanDirectPeer:
    """WebSocket-based peer for LAN direct connections.

    Implements PeerProtocol interface with ownership tracking pattern.
    Uses callback-based message handling (not blocking run loop).

    The ownership pattern prevents accidental connection closes during
    handoff from signaling handler to connection manager.
    """

    def __init__(
        self,
        ws: Any,  # WebSocketResponse or compatible
        owner: PeerOwnership = PeerOwnership.SignalingHandler,
    ):
        """Initialize peer with WebSocket.

        Args:
            ws: The WebSocket connection.
            owner: Initial owner of this peer.
        """
        self._ws = ws
        self._owner = owner
        self._message_handler: Optional[Callable[[bytes], Any]] = None
        self._close_handler: Optional[Callable[[], None]] = None
        self._receive_task: Optional[asyncio.Task] = None
        self._closed = False
        self._close_event = asyncio.Event()

    # =========================================================================
    # PeerProtocol interface
    # =========================================================================

    def on_message(self, handler: Callable[[bytes], Any]) -> None:
        """Set message handler and start receive loop.

        Args:
            handler: Callback for received messages (can be sync or async).
        """
        self._message_handler = handler
        # Start receive loop if not already running
        if self._receive_task is None and not self._closed:
            self._receive_task = asyncio.create_task(self._receive_loop())

    def on_close(self, handler: Callable[[], None]) -> None:
        """Set close handler.

        Args:
            handler: Callback when connection closes.
        """
        self._close_handler = handler

    async def send(self, data: bytes) -> None:
        """Send data to remote peer.

        Args:
            data: Binary data to send.

        Raises:
            ConnectionError: If peer is closed.
        """
        if self._closed:
            raise ConnectionError("Peer is closed")
        await self._ws.send_bytes(data)

    async def close(self) -> None:
        """Close the connection unconditionally.

        Note: Prefer close_by_owner() for ownership-aware closing.
        """
        await self._do_close()

    # =========================================================================
    # Ownership tracking (matches PeerConnection pattern)
    # =========================================================================

    @property
    def owner(self) -> PeerOwnership:
        """Current owner of this connection."""
        return self._owner

    def transfer_ownership(self, new_owner: PeerOwnership) -> bool:
        """Transfer ownership of this connection.

        Args:
            new_owner: The new owner.

        Returns:
            True if transfer succeeded, False if already disposed.
        """
        if self._owner == PeerOwnership.Disposed:
            logger.warning("transfer_ownership() called but peer is disposed")
            return False

        old_owner = self._owner
        self._owner = new_owner
        logger.debug(
            f"LanDirectPeer ownership transferred: {old_owner.value} -> {new_owner.value}"
        )
        return True

    async def close_by_owner(self, caller: PeerOwnership) -> bool:
        """Close the connection, but only if caller is the current owner.

        This prevents accidental closes after ownership has been transferred.

        Args:
            caller: The PeerOwnership of the caller.

        Returns:
            True if closed, False if caller was not the owner.
        """
        if self._owner != caller:
            logger.debug(
                f"close_by_owner() ignored: caller={caller.value}, owner={self._owner.value}"
            )
            return False
        self._owner = PeerOwnership.Disposed
        await self._do_close()
        return True

    # =========================================================================
    # Internal methods
    # =========================================================================

    async def wait_closed(self) -> None:
        """Wait for the peer to close.

        This is useful for keeping the WebSocket handler alive
        while the connection is active.
        """
        await self._close_event.wait()

    async def _do_close(self) -> None:
        """Internal close implementation."""
        if self._closed:
            return
        self._closed = True

        # Cancel receive task
        if self._receive_task:
            self._receive_task.cancel()
            try:
                await self._receive_task
            except asyncio.CancelledError:
                pass
            self._receive_task = None

        # Close WebSocket
        if not self._ws.closed:
            await self._ws.close()

        # Notify close handler
        if self._close_handler:
            try:
                self._close_handler()
            except Exception as e:
                logger.error(f"Error in close handler: {e}")

        # Signal wait_closed()
        self._close_event.set()
        logger.debug("LanDirectPeer closed")

    async def _receive_loop(self) -> None:
        """Background task to receive messages and dispatch to handler.

        This loop runs until the WebSocket closes or an error occurs.
        On exit, it closes the peer to ensure cleanup.
        """
        logger.debug("LanDirectPeer receive loop started")

        try:
            async for msg in self._ws:
                if self._closed:
                    break

                # Check message type - aiohttp uses WSMsgType enum
                # WSMsgType.BINARY = 2, WSMsgType.CLOSE = 8, WSMsgType.ERROR = 256
                msg_type = getattr(msg, "type", None)

                # Handle binary messages
                if msg_type == 2:  # WSMsgType.BINARY
                    if self._message_handler:
                        try:
                            result = self._message_handler(msg.data)
                            if asyncio.iscoroutine(result):
                                await result
                        except Exception as e:
                            logger.error(f"Error in message handler: {e}")

                # Handle close
                elif msg_type == 8:  # WSMsgType.CLOSE
                    logger.debug("WebSocket closed by remote")
                    break

                # Handle error
                elif msg_type == 256:  # WSMsgType.ERROR
                    logger.error(f"WebSocket error: {self._ws.exception()}")
                    break

        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.error(f"Receive loop error: {e}")
        finally:
            logger.debug("LanDirectPeer receive loop ended")
            await self._do_close()
