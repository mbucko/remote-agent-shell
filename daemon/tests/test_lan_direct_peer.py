"""Tests for LanDirectPeer."""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.lan_direct.peer import LanDirectPeer
from ras.protocols import PeerOwnership


class MockWebSocket:
    """Mock WebSocket for testing."""

    def __init__(self):
        self.sent_messages: list[bytes] = []
        self.closed = False
        self.close_code = None
        self.close_message = None
        self._message_queue: asyncio.Queue = asyncio.Queue()

    async def send_bytes(self, data: bytes) -> None:
        if self.closed:
            raise ConnectionError("WebSocket is closed")
        self.sent_messages.append(data)

    async def close(self, code: int = 1000, message: bytes = b"") -> None:
        self.closed = True
        self.close_code = code
        self.close_message = message

    def add_message(self, data: bytes) -> None:
        """Add a message to be received."""
        self._message_queue.put_nowait(data)

    def add_close(self) -> None:
        """Signal close."""
        self._message_queue.put_nowait(None)

    def __aiter__(self):
        return self

    async def __anext__(self):
        msg = await self._message_queue.get()
        if msg is None:
            raise StopAsyncIteration
        # Return a mock message object
        mock_msg = MagicMock()
        mock_msg.type = 2  # WSMsgType.BINARY
        mock_msg.data = msg
        return mock_msg


class TestLanDirectPeerWaitConnected:
    """Test wait_connected functionality."""

    @pytest.mark.asyncio
    async def test_wait_connected_noop_when_open(self):
        """Should succeed immediately when peer is open."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        await peer.wait_connected()  # Should not raise

    @pytest.mark.asyncio
    async def test_wait_connected_raises_when_closed(self):
        """Should raise ConnectionError when peer is closed."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        await peer.close()

        with pytest.raises(ConnectionError, match="Peer is closed"):
            await peer.wait_connected()


class TestLanDirectPeerInit:
    """Test LanDirectPeer initialization."""

    def test_init_default_owner(self):
        """Should default to SignalingHandler ownership."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        assert peer.owner == PeerOwnership.SignalingHandler

    def test_init_custom_owner(self):
        """Should accept custom owner."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws, owner=PeerOwnership.ConnectionManager)
        assert peer.owner == PeerOwnership.ConnectionManager


class TestLanDirectPeerOwnership:
    """Test ownership tracking."""

    def test_transfer_ownership_success(self):
        """Should transfer ownership successfully."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws, owner=PeerOwnership.SignalingHandler)

        result = peer.transfer_ownership(PeerOwnership.ConnectionManager)

        assert result is True
        assert peer.owner == PeerOwnership.ConnectionManager

    def test_transfer_ownership_disposed_fails(self):
        """Should fail if already disposed."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws, owner=PeerOwnership.Disposed)

        result = peer.transfer_ownership(PeerOwnership.ConnectionManager)

        assert result is False
        assert peer.owner == PeerOwnership.Disposed

    @pytest.mark.asyncio
    async def test_close_by_owner_success(self):
        """Should close when caller is owner."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws, owner=PeerOwnership.SignalingHandler)

        result = await peer.close_by_owner(PeerOwnership.SignalingHandler)

        assert result is True
        assert peer.owner == PeerOwnership.Disposed
        assert ws.closed

    @pytest.mark.asyncio
    async def test_close_by_owner_wrong_owner(self):
        """Should not close when caller is not owner."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws, owner=PeerOwnership.ConnectionManager)

        result = await peer.close_by_owner(PeerOwnership.SignalingHandler)

        assert result is False
        assert peer.owner == PeerOwnership.ConnectionManager
        assert not ws.closed


class TestLanDirectPeerSend:
    """Test send functionality."""

    @pytest.mark.asyncio
    async def test_send_success(self):
        """Should send data through WebSocket."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)

        await peer.send(b"test data")

        assert ws.sent_messages == [b"test data"]

    @pytest.mark.asyncio
    async def test_send_when_closed_raises(self):
        """Should raise ConnectionError when closed."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        await peer.close()

        with pytest.raises(ConnectionError, match="Peer is closed"):
            await peer.send(b"test data")


class TestLanDirectPeerMessageHandling:
    """Test message handling."""

    @pytest.mark.asyncio
    async def test_on_message_starts_receive_loop(self):
        """Should start receive loop when handler is set."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        handler = MagicMock()

        peer.on_message(handler)

        # Give the loop time to start
        await asyncio.sleep(0.01)
        assert peer._receive_task is not None

        # Cleanup
        await peer.close()

    @pytest.mark.asyncio
    async def test_on_message_dispatches_messages(self):
        """Should dispatch received messages to handler."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        received = []

        def handler(data: bytes):
            received.append(data)

        peer.on_message(handler)

        # Add messages
        ws.add_message(b"message 1")
        ws.add_message(b"message 2")

        # Give time to process
        await asyncio.sleep(0.05)

        assert received == [b"message 1", b"message 2"]

        # Cleanup
        await peer.close()

    @pytest.mark.asyncio
    async def test_on_message_handles_async_handler(self):
        """Should handle async message handlers."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        received = []

        async def handler(data: bytes):
            await asyncio.sleep(0.001)
            received.append(data)

        peer.on_message(handler)

        ws.add_message(b"async message")
        await asyncio.sleep(0.05)

        assert received == [b"async message"]

        await peer.close()


class TestLanDirectPeerClose:
    """Test close functionality."""

    @pytest.mark.asyncio
    async def test_close_closes_websocket(self):
        """Should close the WebSocket."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)

        await peer.close()

        assert ws.closed

    @pytest.mark.asyncio
    async def test_close_cancels_receive_task(self):
        """Should cancel receive task on close."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        peer.on_message(lambda x: None)
        await asyncio.sleep(0.01)

        task = peer._receive_task
        assert task is not None

        await peer.close()

        assert task.cancelled() or task.done()

    @pytest.mark.asyncio
    async def test_close_calls_close_handler(self):
        """Should call close handler on close."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        handler_called = []

        peer.on_close(lambda: handler_called.append(True))
        await peer.close()

        assert handler_called == [True]

    @pytest.mark.asyncio
    async def test_close_idempotent(self):
        """Should be safe to call close multiple times."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)

        await peer.close()
        await peer.close()  # Should not raise

        assert ws.closed

    @pytest.mark.asyncio
    async def test_wait_closed_blocks_until_closed(self):
        """Should block until peer is closed."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)

        # Start waiting in background
        wait_task = asyncio.create_task(peer.wait_closed())

        # Should not be done yet
        await asyncio.sleep(0.01)
        assert not wait_task.done()

        # Close the peer
        await peer.close()

        # Now wait should complete
        await asyncio.wait_for(wait_task, timeout=1.0)
        assert wait_task.done()


class TestLanDirectPeerReceiveLoopClose:
    """Test receive loop closes peer on disconnect."""

    @pytest.mark.asyncio
    async def test_receive_loop_closes_on_websocket_close(self):
        """Should close peer when WebSocket closes."""
        ws = MockWebSocket()
        peer = LanDirectPeer(ws)
        close_called = []

        peer.on_close(lambda: close_called.append(True))
        peer.on_message(lambda x: None)

        # Signal close from WebSocket
        ws.add_close()

        # Wait for close to propagate
        await asyncio.wait_for(peer.wait_closed(), timeout=1.0)

        assert close_called == [True]
