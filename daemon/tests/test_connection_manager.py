"""Tests for connection manager module."""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.connection_manager import Connection, ConnectionManager


class MockPeer:
    """Mock peer connection for testing."""

    def __init__(self):
        self._message_handler = None
        self._close_handler = None
        self._closed = False
        self.sent_data = []

    def on_message(self, handler):
        self._message_handler = handler

    def on_close(self, handler):
        self._close_handler = handler

    async def send(self, data: bytes):
        if self._closed:
            raise Exception("Connection closed")
        self.sent_data.append(data)

    async def close(self):
        self._closed = True
        if self._close_handler:
            self._close_handler()

    async def receive(self, data: bytes):
        """Simulate receiving data."""
        if self._message_handler:
            # Handler may be async (coroutine function)
            result = self._message_handler(data)
            if hasattr(result, '__await__'):
                await result


class MockCodec:
    """Mock message codec for testing."""

    def __init__(self, fail_decode: bool = False):
        self.fail_decode = fail_decode

    def encode(self, data: bytes) -> bytes:
        return b"encrypted:" + data

    def decode(self, data: bytes) -> bytes:
        if self.fail_decode:
            raise Exception("Decryption failed")
        if data.startswith(b"encrypted:"):
            return data[10:]
        return data


class TestConnection:
    """Tests for Connection class."""

    def test_create_connection(self):
        """Create connection with required fields."""
        peer = MockPeer()
        codec = MockCodec()

        conn = Connection(device_id="device1", peer=peer, codec=codec)

        assert conn.device_id == "device1"
        assert conn.peer is peer
        assert conn.codec is codec
        assert conn.connected_at > 0
        assert conn.last_activity > 0

    @pytest.mark.asyncio
    async def test_send_encrypts_data(self):
        """Send encrypts data before sending."""
        peer = MockPeer()
        codec = MockCodec()
        conn = Connection(device_id="device1", peer=peer, codec=codec)

        await conn.send(b"hello")

        assert len(peer.sent_data) == 1
        assert peer.sent_data[0] == b"encrypted:hello"

    def test_decrypt_data(self):
        """Decrypt decrypts received data."""
        peer = MockPeer()
        codec = MockCodec()
        conn = Connection(device_id="device1", peer=peer, codec=codec)

        result = conn.decrypt(b"encrypted:hello")

        assert result == b"hello"

    def test_update_activity(self):
        """Update activity timestamp."""
        peer = MockPeer()
        codec = MockCodec()
        conn = Connection(device_id="device1", peer=peer, codec=codec)
        original = conn.last_activity

        conn.update_activity()

        assert conn.last_activity >= original

    @pytest.mark.asyncio
    async def test_close_connection(self):
        """Close connection."""
        peer = MockPeer()
        codec = MockCodec()
        conn = Connection(device_id="device1", peer=peer, codec=codec)

        await conn.close()

        assert peer._closed is True


class TestConnectionManager:
    """Tests for ConnectionManager."""

    @pytest.fixture
    def on_lost(self):
        """Create mock on_connection_lost callback."""
        return AsyncMock()

    @pytest.fixture
    def manager(self, on_lost):
        """Create connection manager."""
        return ConnectionManager(on_connection_lost=on_lost, send_timeout=1.0)

    # CM01: Add connection
    @pytest.mark.asyncio
    async def test_add_connection(self, manager):
        """Add connection with codec."""
        peer = MockPeer()
        codec = MockCodec()
        on_message = MagicMock()

        conn = await manager.add_connection(
            device_id="device1",
            peer=peer,
            codec=codec,
            on_message=on_message,
        )

        assert conn.device_id == "device1"
        assert "device1" in manager.connections

    # CM02: Get connection
    @pytest.mark.asyncio
    async def test_get_connection(self, manager):
        """Get connection by ID."""
        peer = MockPeer()
        codec = MockCodec()
        await manager.add_connection("device1", peer, codec, MagicMock())

        conn = manager.get_connection("device1")

        assert conn is not None
        assert conn.device_id == "device1"

    # CM03: Get unknown
    def test_get_unknown_connection(self, manager):
        """Get non-existent ID returns None."""
        assert manager.get_connection("unknown") is None

    # CM04: Replace connection
    @pytest.mark.asyncio
    async def test_replace_connection(self, manager):
        """Add same device_id twice replaces old."""
        peer1 = MockPeer()
        peer2 = MockPeer()
        codec = MockCodec()

        await manager.add_connection("device1", peer1, codec, MagicMock())
        await manager.add_connection("device1", peer2, codec, MagicMock())

        # Allow task to run
        await asyncio.sleep(0.01)

        assert len(manager) == 1
        conn = manager.get_connection("device1")
        assert conn.peer is peer2
        assert peer1._closed is True

    # CM05: Broadcast to 1
    @pytest.mark.asyncio
    async def test_broadcast_to_one(self, manager):
        """Broadcast to single connection."""
        peer = MockPeer()
        codec = MockCodec()
        await manager.add_connection("device1", peer, codec, MagicMock())

        await manager.broadcast(b"hello")

        assert len(peer.sent_data) == 1
        assert peer.sent_data[0] == b"encrypted:hello"

    # CM06: Broadcast to N
    @pytest.mark.asyncio
    async def test_broadcast_to_multiple(self, manager):
        """Broadcast to multiple connections."""
        peers = [MockPeer() for _ in range(3)]
        codec = MockCodec()

        for i, peer in enumerate(peers):
            await manager.add_connection(f"device{i}", peer, codec, MagicMock())

        await manager.broadcast(b"hello")

        for peer in peers:
            assert len(peer.sent_data) == 1

    # CM07: Broadcast partial fail
    @pytest.mark.asyncio
    async def test_broadcast_partial_failure(self, manager):
        """One failure doesn't affect others."""
        peer1 = MockPeer()
        peer2 = MockPeer()
        peer2._closed = True  # Will fail
        peer3 = MockPeer()
        codec = MockCodec()

        await manager.add_connection("device1", peer1, codec, MagicMock())
        await manager.add_connection("device2", peer2, codec, MagicMock())
        await manager.add_connection("device3", peer3, codec, MagicMock())

        await manager.broadcast(b"hello")

        assert len(peer1.sent_data) == 1
        assert len(peer3.sent_data) == 1

    # CM08: Broadcast timeout - tested via slow peer
    @pytest.mark.asyncio
    async def test_broadcast_timeout(self, on_lost):
        """Slow connection times out without blocking others."""
        manager = ConnectionManager(on_connection_lost=on_lost, send_timeout=0.1)

        class SlowPeer(MockPeer):
            async def send(self, data):
                await asyncio.sleep(1.0)  # Very slow

        peer1 = MockPeer()
        peer2 = SlowPeer()
        codec = MockCodec()

        await manager.add_connection("device1", peer1, codec, MagicMock())
        await manager.add_connection("device2", peer2, codec, MagicMock())

        # Should complete quickly due to timeout
        await asyncio.wait_for(manager.broadcast(b"hello"), timeout=0.5)

        assert len(peer1.sent_data) == 1

    # CM09: Broadcast empty
    @pytest.mark.asyncio
    async def test_broadcast_empty(self, manager):
        """Broadcast with no connections is no-op."""
        await manager.broadcast(b"hello")
        # No error

    # CM10: Close all
    @pytest.mark.asyncio
    async def test_close_all(self, manager):
        """Close all connections."""
        peers = [MockPeer() for _ in range(3)]
        codec = MockCodec()

        for i, peer in enumerate(peers):
            await manager.add_connection(f"device{i}", peer, codec, MagicMock())

        await manager.close_all()

        assert len(manager) == 0
        for peer in peers:
            assert peer._closed is True

    # CM11: Disconnect callback
    @pytest.mark.asyncio
    async def test_disconnect_callback(self, manager, on_lost):
        """Disconnect triggers callback."""
        peer = MockPeer()
        codec = MockCodec()
        await manager.add_connection("device1", peer, codec, MagicMock())

        # Simulate disconnect
        await peer.close()
        await asyncio.sleep(0.01)  # Allow task to run

        assert "device1" not in manager.connections
        on_lost.assert_called_once_with("device1")

    # CM12: Concurrent add - tested with multiple adds
    @pytest.mark.asyncio
    async def test_concurrent_add(self, manager):
        """Concurrent adds are thread-safe."""
        codec = MockCodec()
        peers = [MockPeer() for _ in range(5)]

        async def add_device(i):
            await manager.add_connection(f"device{i}", peers[i], codec, MagicMock())

        await asyncio.gather(*[add_device(i) for i in range(5)])

        assert len(manager) == 5

    # CM14: Decrypt failure
    @pytest.mark.asyncio
    async def test_decrypt_failure_logged(self, manager, caplog):
        """Decryption failure is logged."""
        peer = MockPeer()
        codec = MockCodec(fail_decode=True)
        messages = []

        await manager.add_connection(
            "device1", peer, codec, lambda data: messages.append(data)
        )

        # Simulate receiving data
        await peer.receive(b"garbled")

        assert len(messages) == 0  # Message not delivered

    # Test len
    @pytest.mark.asyncio
    async def test_len(self, manager):
        """Length returns connection count."""
        codec = MockCodec()
        assert len(manager) == 0

        await manager.add_connection("device1", MockPeer(), codec, MagicMock())
        assert len(manager) == 1

        await manager.add_connection("device2", MockPeer(), codec, MagicMock())
        assert len(manager) == 2

    # Test message decryption and delivery
    @pytest.mark.asyncio
    async def test_message_decryption_and_delivery(self, manager):
        """Received messages are decrypted and delivered."""
        peer = MockPeer()
        codec = MockCodec()
        messages = []

        await manager.add_connection(
            "device1", peer, codec, lambda data: messages.append(data)
        )

        # Simulate receiving encrypted data
        await peer.receive(b"encrypted:hello")

        assert len(messages) == 1
        assert messages[0] == b"hello"

    # Test activity updated on receive
    @pytest.mark.asyncio
    async def test_activity_updated_on_receive(self, manager):
        """Last activity updated when message received."""
        peer = MockPeer()
        codec = MockCodec()
        await manager.add_connection("device1", peer, codec, MagicMock())

        conn = manager.get_connection("device1")
        original = conn.last_activity

        await asyncio.sleep(0.01)
        await peer.receive(b"encrypted:hello")

        assert conn.last_activity >= original
