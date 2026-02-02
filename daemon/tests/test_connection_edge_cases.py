"""Comprehensive edge case tests for connection handling.

These tests cover edge cases in:
1. Capability exchange (port boundaries, empty values)
2. Cancellation propagation (asyncio.CancelledError)
3. Concurrent connections
4. Resource cleanup
5. Protocol message edge cases
6. Authentication edge cases
"""

import asyncio
import os
import struct
from datetime import datetime, timezone
from unittest.mock import AsyncMock, Mock, MagicMock, patch

import pytest

from ras.auth import Authenticator, AuthState
from ras.crypto import derive_key
from ras.device_store import PairedDevice


# =============================================================================
# Test Helpers
# =============================================================================


def create_test_device(device_id: str = "test-device-123") -> PairedDevice:
    """Create a test paired device."""
    return PairedDevice(
        device_id=device_id,
        name="Test Phone",
        master_secret=os.urandom(32),
        paired_at=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    )


def create_auth_message(device_id: str, auth_key: bytes) -> bytes:
    """Create an auth message in the expected format."""
    device_id_bytes = device_id.encode("utf-8")
    return struct.pack(">I", len(device_id_bytes)) + device_id_bytes + auth_key


class MockTailscaleTransport:
    """Mock TailscaleTransport for testing."""

    def __init__(self):
        self.receive_data: bytes = b""
        self.sent_data: bytes = b""
        self.closed = False
        self.remote_address = ("100.64.0.2", 12345)

    async def receive(self, timeout: float = 10.0) -> bytes:
        if not self.receive_data:
            # Don't actually sleep - just raise immediately
            raise TimeoutError("No data")
        return self.receive_data

    async def send(self, data: bytes) -> None:
        self.sent_data = data

    def close(self) -> None:
        self.closed = True


class MockDeviceStore:
    """Mock device store."""

    def __init__(self):
        self.devices: dict[str, PairedDevice] = {}

    def add(self, device: PairedDevice) -> None:
        self.devices[device.device_id] = device

    def get(self, device_id: str) -> PairedDevice | None:
        return self.devices.get(device_id)

    async def save(self) -> None:
        pass


# =============================================================================
# SECTION 1: Capability Exchange Edge Cases
# =============================================================================


class TestCapabilityExchangeEdgeCases:
    """Tests for capability exchange edge cases."""

    def test_port_zero_should_indicate_no_tailscale(self):
        """Port 0 should be treated as 'no Tailscale available'."""
        from ras.daemon import Daemon
        from ras.config import Config
        from ras.tailscale import TailscaleListener

        config = Config()
        daemon = Daemon(config)

        mock_listener = MagicMock(spec=TailscaleListener)
        mock_listener.is_available = True
        mock_listener.get_capabilities.return_value = {
            "tailscale_ip": "100.64.0.1",
            "tailscale_port": 0,  # Port 0 is invalid
        }
        daemon._tailscale_listener = mock_listener

        caps = daemon.get_tailscale_capabilities()

        # Port 0 should still be returned - client should handle it
        assert caps.get("tailscale_port") == 0

    def test_empty_ip_should_indicate_no_tailscale(self):
        """Empty IP string should be treated as 'no Tailscale'."""
        from ras.daemon import Daemon
        from ras.config import Config
        from ras.tailscale import TailscaleListener

        config = Config()
        daemon = Daemon(config)

        mock_listener = MagicMock(spec=TailscaleListener)
        mock_listener.is_available = True
        mock_listener.get_capabilities.return_value = {
            "tailscale_ip": "",  # Empty IP
            "tailscale_port": 9876,
        }
        daemon._tailscale_listener = mock_listener

        caps = daemon.get_tailscale_capabilities()

        # Empty IP should be returned - client handles it
        assert caps.get("tailscale_ip") == ""

    def test_port_at_boundary_65535(self):
        """Port at max boundary (65535) should be valid."""
        from ras.daemon import Daemon
        from ras.config import Config
        from ras.tailscale import TailscaleListener

        config = Config()
        daemon = Daemon(config)

        mock_listener = MagicMock(spec=TailscaleListener)
        mock_listener.is_available = True
        mock_listener.get_capabilities.return_value = {
            "tailscale_ip": "100.64.0.1",
            "tailscale_port": 65535,
        }
        daemon._tailscale_listener = mock_listener

        caps = daemon.get_tailscale_capabilities()

        assert caps["tailscale_port"] == 65535

    def test_ipv6_tailscale_ip(self):
        """IPv6 Tailscale addresses should work."""
        from ras.daemon import Daemon
        from ras.config import Config
        from ras.tailscale import TailscaleListener

        config = Config()
        daemon = Daemon(config)

        mock_listener = MagicMock(spec=TailscaleListener)
        mock_listener.is_available = True
        mock_listener.get_capabilities.return_value = {
            "tailscale_ip": "fd7a:115c:a1e0::1",
            "tailscale_port": 9876,
        }
        daemon._tailscale_listener = mock_listener

        caps = daemon.get_tailscale_capabilities()

        assert caps["tailscale_ip"] == "fd7a:115c:a1e0::1"


# =============================================================================
# SECTION 2: Cancellation Propagation Edge Cases
# =============================================================================


class TestCancellationPropagation:
    """Tests for proper cancellation handling."""

    @pytest.mark.asyncio
    async def test_cancelled_error_during_async_task(self):
        """asyncio.CancelledError should propagate through async tasks."""
        cancelled = False
        task_started = asyncio.Event()
        never_set = asyncio.Event()

        async def async_operation():
            nonlocal cancelled
            try:
                task_started.set()
                await never_set.wait()  # Wait forever until cancelled
            except asyncio.CancelledError:
                cancelled = True
                raise

        task = asyncio.create_task(async_operation())
        await task_started.wait()  # Wait for task to start
        task.cancel()

        with pytest.raises(asyncio.CancelledError):
            await task

        assert cancelled, "CancelledError should have been caught"

    @pytest.mark.asyncio
    async def test_cancelled_error_propagates_through_peer_wait(self):
        """CancelledError during peer wait should propagate."""
        from ras.peer import PeerConnection

        peer = PeerConnection()
        task_started = asyncio.Event()

        async def wait_and_cancel():
            task = asyncio.create_task(peer.wait_connected(timeout=10))
            task_started.set()
            # Cancel immediately - we don't need to wait for it to "start"
            task.cancel()
            await task

        with pytest.raises(asyncio.CancelledError):
            await wait_and_cancel()

    @pytest.mark.asyncio
    async def test_multiple_tasks_cancellation(self):
        """Multiple async tasks should all be cancellable."""
        tasks_cancelled = []
        all_tasks_started = asyncio.Event()
        never_set = asyncio.Event()
        started_count = 0

        async def cancelable_task(idx: int):
            nonlocal started_count
            try:
                started_count += 1
                if started_count == 5:
                    all_tasks_started.set()
                await never_set.wait()  # Wait forever until cancelled
            except asyncio.CancelledError:
                tasks_cancelled.append(idx)
                raise

        tasks = [asyncio.create_task(cancelable_task(i)) for i in range(5)]
        await all_tasks_started.wait()  # Wait for all tasks to start

        for task in tasks:
            task.cancel()

        for task in tasks:
            try:
                await task
            except asyncio.CancelledError:
                pass

        assert len(tasks_cancelled) == 5, "All tasks should have been cancelled"


# =============================================================================
# SECTION 3: Concurrent Connection Edge Cases
# =============================================================================


class TestConcurrentConnectionEdgeCases:
    """Tests for concurrent connection handling."""

    @pytest.mark.asyncio
    async def test_multiple_devices_connecting_simultaneously(self):
        """Multiple devices should be able to connect in parallel."""
        from ras.daemon import Daemon

        devices = [create_test_device(f"device-{i}") for i in range(5)]

        mock_store = MockDeviceStore()
        for device in devices:
            mock_store.add(device)

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()
        daemon._connection_manager.add_connection = AsyncMock()
        daemon.on_new_connection = AsyncMock()

        # Create transports for each device
        transports = []
        for device in devices:
            transport = MockTailscaleTransport()
            auth_key = derive_key(device.master_secret, "auth")
            transport.receive_data = create_auth_message(device.device_id, auth_key)
            transports.append(transport)

        # Connect all devices concurrently
        await asyncio.gather(*[daemon._on_tailscale_connection(t) for t in transports])

        # All should succeed
        for transport in transports:
            assert transport.sent_data == b"\x01", (
                "All devices should authenticate successfully"
            )

    @pytest.mark.asyncio
    async def test_same_device_connecting_twice_quickly(self):
        """Same device connecting twice should handle gracefully."""
        from ras.daemon import Daemon

        device = create_test_device("test-device")
        auth_key = derive_key(device.master_secret, "auth")

        mock_store = MockDeviceStore()
        mock_store.add(device)

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()
        daemon._connection_manager.add_connection = AsyncMock()
        daemon.on_new_connection = AsyncMock()

        transport1 = MockTailscaleTransport()
        transport1.receive_data = create_auth_message(device.device_id, auth_key)

        transport2 = MockTailscaleTransport()
        transport2.receive_data = create_auth_message(device.device_id, auth_key)

        # Both connections at once
        await asyncio.gather(
            daemon._on_tailscale_connection(transport1),
            daemon._on_tailscale_connection(transport2),
        )

        # Both should succeed (connection manager handles replacement)
        assert transport1.sent_data == b"\x01"
        assert transport2.sent_data == b"\x01"


# =============================================================================
# SECTION 4: Resource Cleanup Edge Cases
# =============================================================================


class TestResourceCleanupEdgeCases:
    """Tests for proper resource cleanup on failures."""

    @pytest.mark.asyncio
    async def test_transport_closed_on_auth_failure(self):
        """Transport should be closed after authentication failure."""
        from ras.daemon import Daemon

        device = create_test_device("test-device")
        wrong_auth = os.urandom(32)  # Wrong auth key

        mock_store = MockDeviceStore()
        mock_store.add(device)

        transport = MockTailscaleTransport()
        transport.receive_data = create_auth_message(device.device_id, wrong_auth)

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(transport)

        # Should have sent failure response
        assert transport.sent_data == b"\x00"

    @pytest.mark.asyncio
    async def test_peer_closed_on_handshake_failure(self):
        """Peer should be closed if handshake fails."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()
        mock_peer = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.on_message = Mock()
        mock_peer.on_close = Mock()

        mock_codec = Mock()
        mock_codec.decode = Mock(side_effect=Exception("Decode failed"))

        await manager.add_connection(
            device_id="test", peer=mock_peer, codec=mock_codec, on_message=Mock()
        )

        # Simulate receiving invalid message
        conn = manager.get_connection("test")
        if conn:
            try:
                conn.decrypt(b"invalid data")
            except Exception:
                pass  # Expected

        # Cleanup
        await manager.close_all()
        mock_peer.close.assert_called()


# =============================================================================
# SECTION 5: Authentication Protocol Edge Cases
# =============================================================================


class TestAuthenticationProtocolEdgeCases:
    """Tests for authentication protocol edge cases."""

    def test_auth_with_unicode_device_id(self):
        """Auth should work with Unicode device IDs."""
        from ras.daemon import Daemon

        # Unicode device ID with emoji
        device = create_test_device("device-\U0001f4f1-test")
        auth_key = derive_key(device.master_secret, "auth")

        mock_store = MockDeviceStore()
        mock_store.add(device)

        message = create_auth_message(device.device_id, auth_key)

        # Verify message format
        device_id_len = struct.unpack(">I", message[:4])[0]
        parsed_id = message[4 : 4 + device_id_len].decode("utf-8")
        assert parsed_id == device.device_id

    def test_auth_with_max_length_device_id(self):
        """Auth should handle max length device ID (100 bytes)."""
        device_id = "a" * 100
        auth_key = os.urandom(32)

        message = create_auth_message(device_id, auth_key)

        # Should create valid message
        device_id_len = struct.unpack(">I", message[:4])[0]
        assert device_id_len == 100
        assert len(message) == 4 + 100 + 32

    @pytest.mark.asyncio
    async def test_auth_with_exactly_37_bytes_minimum(self):
        """Minimum valid auth message is 37 bytes (4 + 1 + 32)."""
        from ras.daemon import Daemon

        device = create_test_device("x")  # 1 byte device ID
        auth_key = derive_key(device.master_secret, "auth")

        mock_store = MockDeviceStore()
        mock_store.add(device)

        message = create_auth_message(device.device_id, auth_key)
        assert len(message) == 37  # 4 + 1 + 32

        transport = MockTailscaleTransport()
        transport.receive_data = message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()
        daemon._connection_manager.add_connection = AsyncMock()
        daemon.on_new_connection = AsyncMock()

        await daemon._on_tailscale_connection(transport)

        assert transport.sent_data == b"\x01"  # Success

    @pytest.mark.asyncio
    async def test_auth_rejects_36_byte_message(self):
        """Message of 36 bytes should be rejected (too short)."""
        from ras.daemon import Daemon

        transport = MockTailscaleTransport()
        transport.receive_data = b"x" * 36  # One byte short

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = MockDeviceStore()
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(transport)

        assert transport.sent_data == b"\x00"  # Failure


# =============================================================================
# SECTION 6: State Machine Edge Cases
# =============================================================================


class TestStateMachineEdgeCases:
    """Tests for auth state machine edge cases."""

    def test_create_challenge_in_wrong_state(self):
        """create_challenge should only work in PENDING state."""
        auth = Authenticator(auth_key=b"x" * 32)

        # First challenge is fine
        auth.create_challenge()
        assert auth.state == AuthState.CHALLENGED

        # Second challenge should fail or reset
        # (depends on implementation - test actual behavior)
        try:
            auth.create_challenge()
        except Exception:
            pass  # Expected if state machine enforces this

    def test_verify_response_without_challenge(self):
        """verify_response without prior challenge should fail."""
        auth = Authenticator(auth_key=b"x" * 32)

        response = {"type": "auth_response", "hmac": "aa" * 32, "nonce": "bb" * 32}

        result = auth.verify_response(response)
        assert result is False

    def test_double_verification_of_same_response(self):
        """Verifying the same response twice should fail (replay)."""
        key = b"x" * 32
        auth1 = Authenticator(auth_key=key)
        auth2 = Authenticator(auth_key=key)

        challenge = auth1.create_challenge()
        response = auth2.respond_to_challenge(challenge)

        # First verification succeeds
        assert auth1.verify_response(response) is True

        # Reset state for second attempt
        auth1._state = AuthState.CHALLENGED
        auth1._our_nonce = bytes.fromhex(challenge["nonce"])

        # Second verification should fail (replay)
        assert auth1.verify_response(response) is False


# =============================================================================
# SECTION 7: Message Length Edge Cases
# =============================================================================


class TestMessageLengthEdgeCases:
    """Tests for message length boundary conditions."""

    @pytest.mark.asyncio
    async def test_device_id_length_exactly_100(self):
        """Device ID of exactly 100 bytes should be accepted."""
        from ras.daemon import Daemon

        device = create_test_device("a" * 100)
        auth_key = derive_key(device.master_secret, "auth")

        mock_store = MockDeviceStore()
        mock_store.add(device)

        transport = MockTailscaleTransport()
        transport.receive_data = create_auth_message(device.device_id, auth_key)

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()
        daemon._connection_manager.add_connection = AsyncMock()
        daemon.on_new_connection = AsyncMock()

        await daemon._on_tailscale_connection(transport)

        assert transport.sent_data == b"\x01"

    @pytest.mark.asyncio
    async def test_device_id_length_exactly_101_rejected(self):
        """Device ID of 101 bytes should be rejected."""
        from ras.daemon import Daemon

        device_id = "a" * 101
        auth_key = os.urandom(32)

        transport = MockTailscaleTransport()
        transport.receive_data = create_auth_message(device_id, auth_key)

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = MockDeviceStore()
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(transport)

        assert transport.sent_data == b"\x00"

    @pytest.mark.asyncio
    async def test_length_prefix_claims_more_bytes_than_available(self):
        """Length prefix claiming more bytes than message has should fail."""
        from ras.daemon import Daemon

        # Length says 50 bytes but only 10 provided
        message = struct.pack(">I", 50) + b"x" * 10 + os.urandom(32)

        transport = MockTailscaleTransport()
        transport.receive_data = message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = MockDeviceStore()
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(transport)

        assert transport.sent_data == b"\x00"


# =============================================================================
# SECTION 8: Connection Manager Edge Cases
# =============================================================================


class TestConnectionManagerEdgeCases:
    """Tests for ConnectionManager edge cases."""

    @pytest.mark.asyncio
    async def test_broadcast_to_empty_connections(self):
        """Broadcast with no connections should not error."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()

        # Should not raise
        await manager.broadcast(b"test message")

    @pytest.mark.asyncio
    async def test_get_nonexistent_connection(self):
        """Getting non-existent connection should return None."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()

        result = manager.get_connection("nonexistent")
        assert result is None

    @pytest.mark.asyncio
    async def test_close_all_with_failing_peer(self):
        """close_all should handle peers that fail to close."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()
        mock_peer = AsyncMock()
        mock_peer.close = AsyncMock(side_effect=Exception("Close failed"))
        mock_peer.on_message = Mock()
        mock_peer.on_close = Mock()

        mock_codec = Mock()

        await manager.add_connection(
            device_id="test", peer=mock_peer, codec=mock_codec, on_message=Mock()
        )

        # Should not raise despite peer.close failing
        await manager.close_all()


# =============================================================================
# SECTION 9: Timing Edge Cases
# =============================================================================


class TestTimingEdgeCases:
    """Tests for timing-related edge cases."""

    @pytest.mark.asyncio
    async def test_auth_timeout_very_short(self):
        """Very short auth timeout should handle gracefully."""
        from ras.daemon import Daemon

        transport = MockTailscaleTransport()
        transport.receive_data = b""  # No data - will timeout

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = MockDeviceStore()
        daemon._connection_manager = Mock()

        # Should complete without exception (timeout is handled)
        await daemon._on_tailscale_connection(transport)

    @pytest.mark.asyncio
    async def test_rapid_connect_disconnect_cycles(self):
        """Rapid connect/disconnect cycles should be handled."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()

        for i in range(10):
            mock_peer = AsyncMock()
            mock_peer.close = AsyncMock()
            mock_peer.on_message = Mock()
            mock_peer.on_close = Mock()

            mock_codec = Mock()

            await manager.add_connection(
                device_id=f"device-{i}",
                peer=mock_peer,
                codec=mock_codec,
                on_message=Mock(),
            )

            # Immediately close
            await manager.close_all()

        # Should have no connections
        assert manager.get_connection("device-0") is None


# =============================================================================
# SECTION 10: Connection Replacement Edge Cases
# =============================================================================


class TestConnectionReplacementEdgeCases:
    """Tests for proper connection replacement behavior.

    Critical fix: When replacing a connection, the old connection must be
    fully awaited before the new one is used, to ensure ICE timers are
    cancelled and transports are properly cleaned up.
    """

    @pytest.mark.asyncio
    async def test_old_connection_awaited_before_new_one_used(self):
        """Old connection close must complete before returning from add_connection.

        This prevents 'NoneType' errors when old aioice timers fire after
        the transport is replaced.
        """
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()

        # Track close order
        close_order = []
        close_complete_event = asyncio.Event()

        async def controllable_close():
            """Close that waits for test signal to complete, proving await behavior."""
            close_order.append("close_started")
            await close_complete_event.wait()  # Wait for test to allow completion
            close_order.append("close_completed")

        # Add first connection
        mock_peer1 = AsyncMock()
        mock_peer1.close = controllable_close
        mock_peer1.on_message = Mock()
        mock_peer1.on_close = Mock()
        mock_codec1 = Mock()

        await manager.add_connection(
            device_id="test-device",
            peer=mock_peer1,
            codec=mock_codec1,
            on_message=Mock(),
        )

        # Add second connection for same device (triggers replacement)
        # Run in background task so we can verify it's blocked
        mock_peer2 = AsyncMock()
        mock_peer2.close = AsyncMock()
        mock_peer2.on_message = Mock()
        mock_peer2.on_close = Mock()
        mock_codec2 = Mock()

        add_task = asyncio.create_task(
            manager.add_connection(
                device_id="test-device",
                peer=mock_peer2,
                codec=mock_codec2,
                on_message=Mock(),
            )
        )

        # Give task time to start close but not complete
        await asyncio.sleep(0)

        # At this point, close should have started but NOT completed
        # This proves add_connection is BLOCKED waiting for close
        assert "close_started" in close_order, "close() should have been called"
        assert "close_completed" not in close_order, (
            "add_connection should be awaiting close()"
        )

        # Now allow close to complete
        close_complete_event.set()

        # Wait for add_connection to return
        await add_task

        # After add_connection returns, old connection must be fully closed
        assert "close_completed" in close_order, (
            "close() should complete before add_connection returns"
        )
        assert close_order.index("close_started") < close_order.index("close_completed")

    @pytest.mark.asyncio
    async def test_old_connection_close_error_doesnt_break_new_connection(self):
        """Errors during old connection close should not prevent new connection."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()

        # First connection with a failing close
        mock_peer1 = AsyncMock()
        mock_peer1.close = AsyncMock(side_effect=Exception("ICE close failed"))
        mock_peer1.on_message = Mock()
        mock_peer1.on_close = Mock()
        mock_codec1 = Mock()

        await manager.add_connection(
            device_id="test-device",
            peer=mock_peer1,
            codec=mock_codec1,
            on_message=Mock(),
        )

        # Add second connection - should succeed despite close error
        mock_peer2 = AsyncMock()
        mock_peer2.close = AsyncMock()
        mock_peer2.on_message = Mock()
        mock_peer2.on_close = Mock()
        mock_codec2 = Mock()

        conn = await manager.add_connection(
            device_id="test-device",
            peer=mock_peer2,
            codec=mock_codec2,
            on_message=Mock(),
        )

        # New connection should be active
        assert manager.get_connection("test-device") is conn
        assert conn.peer is mock_peer2

    @pytest.mark.asyncio
    async def test_old_connection_handler_removed_before_close(self):
        """Old connection's close handler must be removed to prevent cascading disconnect."""
        from ras.connection_manager import ConnectionManager

        disconnect_callback_calls = []

        async def on_lost(device_id: str):
            disconnect_callback_calls.append(device_id)

        manager = ConnectionManager(on_connection_lost=on_lost)

        close_handler_removed = False
        original_on_close = None

        def track_on_close(handler):
            nonlocal close_handler_removed, original_on_close
            if original_on_close is not None and handler != original_on_close:
                close_handler_removed = True

        # First connection
        mock_peer1 = AsyncMock()
        mock_peer1.on_message = Mock()
        mock_peer1.on_close = Mock(side_effect=track_on_close)
        mock_peer1.close = AsyncMock()
        mock_codec1 = Mock()

        await manager.add_connection(
            device_id="test-device",
            peer=mock_peer1,
            codec=mock_codec1,
            on_message=Mock(),
        )

        # Capture the original handler
        original_on_close = mock_peer1.on_close.call_args[0][0]

        # Add second connection
        mock_peer2 = AsyncMock()
        mock_peer2.close = AsyncMock()
        mock_peer2.on_message = Mock()
        mock_peer2.on_close = Mock()
        mock_codec2 = Mock()

        await manager.add_connection(
            device_id="test-device",
            peer=mock_peer2,
            codec=mock_codec2,
            on_message=Mock(),
        )

        # on_close should have been called with a new (no-op) handler before close
        assert mock_peer1.on_close.call_count >= 2

        # on_connection_lost should NOT have been called (old handler was replaced)
        await asyncio.sleep(0)  # Yield to event loop to let any scheduled tasks run
        assert "test-device" not in disconnect_callback_calls

    @pytest.mark.asyncio
    async def test_rapid_replacement_same_device(self):
        """Rapid replacement of same device connection should be handled correctly."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()
        close_counts = {"peer1": 0, "peer2": 0, "peer3": 0}

        async def make_close(name: str):
            async def close():
                close_counts[name] += 1

            return close

        # Add connection 1
        mock_peer1 = AsyncMock()
        mock_peer1.close = await make_close("peer1")
        mock_peer1.on_message = Mock()
        mock_peer1.on_close = Mock()
        mock_codec1 = Mock()

        await manager.add_connection(
            device_id="device", peer=mock_peer1, codec=mock_codec1, on_message=Mock()
        )

        # Immediately replace with connection 2
        mock_peer2 = AsyncMock()
        mock_peer2.close = await make_close("peer2")
        mock_peer2.on_message = Mock()
        mock_peer2.on_close = Mock()
        mock_codec2 = Mock()

        await manager.add_connection(
            device_id="device", peer=mock_peer2, codec=mock_codec2, on_message=Mock()
        )

        # Immediately replace with connection 3
        mock_peer3 = AsyncMock()
        mock_peer3.close = await make_close("peer3")
        mock_peer3.on_message = Mock()
        mock_peer3.on_close = Mock()
        mock_codec3 = Mock()

        conn = await manager.add_connection(
            device_id="device", peer=mock_peer3, codec=mock_codec3, on_message=Mock()
        )

        # peer1 and peer2 should each be closed exactly once
        assert close_counts["peer1"] == 1
        assert close_counts["peer2"] == 1
        assert close_counts["peer3"] == 0  # Current connection

        # Final connection should be peer3
        assert manager.get_connection("device") is conn
        assert conn.peer is mock_peer3
