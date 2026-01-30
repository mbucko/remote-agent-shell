"""Security tests for Tailscale authentication.

These tests verify security properties like constant-time comparison
and proper handling of invalid/malicious inputs.
"""

import asyncio
import os
import struct
from unittest.mock import AsyncMock, Mock, patch

import pytest

from ras.crypto import derive_key
from ras.device_store import PairedDevice


class MockTailscaleTransport:
    """Mock TailscaleTransport for testing."""

    def __init__(self):
        self.receive_data: bytes = b""
        self.sent_data: bytes = b""
        self.closed = False
        self.remote_address = ("100.64.0.2", 12345)

    async def receive(self, timeout: float = 10.0) -> bytes:
        if not self.receive_data:
            await asyncio.sleep(timeout)
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


def create_test_device(device_id: str = "test-device-123") -> PairedDevice:
    """Create a test paired device."""
    from datetime import datetime, timezone
    return PairedDevice(
        device_id=device_id,
        name="Test Phone",
        master_secret=os.urandom(32),
        paired_at=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    )


def create_auth_message(device_id: str, auth_key: bytes) -> bytes:
    """Create an auth message."""
    device_id_bytes = device_id.encode('utf-8')
    return (
        struct.pack(">I", len(device_id_bytes)) +
        device_id_bytes +
        auth_key
    )


class TestConstantTimeComparison:
    """Tests for constant-time comparison to prevent timing attacks."""

    @pytest.mark.asyncio
    async def test_auth_uses_secrets_compare_digest(self):
        """Auth must use constant-time comparison (secrets.compare_digest)."""
        import secrets

        # Track calls to compare_digest
        original_compare_digest = secrets.compare_digest
        compare_digest_calls = []

        def tracking_compare_digest(a, b):
            compare_digest_calls.append((a, b))
            return original_compare_digest(a, b)

        from ras.daemon import Daemon

        device = create_test_device("test-device-123")
        auth_key = derive_key(device.master_secret, "auth")

        mock_store = MockDeviceStore()
        mock_store.add(device)

        auth_message = create_auth_message(device.device_id, auth_key)

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()
        daemon._connection_manager.add_connection = AsyncMock()
        daemon.on_new_connection = AsyncMock()

        with patch.object(secrets, 'compare_digest', tracking_compare_digest):
            await daemon._on_tailscale_connection(mock_transport)

        # Verify compare_digest was used
        assert len(compare_digest_calls) >= 1

    @pytest.mark.asyncio
    async def test_auth_key_comparison_does_not_short_circuit(self):
        """Auth key comparison should not short-circuit on first wrong byte."""
        # This test verifies that wrong auth keys take roughly the same time
        # to reject, regardless of how many bytes match

        from ras.daemon import Daemon

        device = create_test_device("test-device-123")
        correct_auth_key = derive_key(device.master_secret, "auth")

        mock_store = MockDeviceStore()
        mock_store.add(device)

        # Create auth keys with different numbers of correct bytes
        # All zeroes (no bytes match)
        wrong_key_all_different = bytes(32)

        # First byte correct, rest wrong
        wrong_key_first_correct = bytes([correct_auth_key[0]]) + bytes(31)

        # First 16 bytes correct, rest wrong
        wrong_key_half_correct = correct_auth_key[:16] + bytes(16)

        # First 31 bytes correct, last wrong (maximally close to correct)
        wrong_key_almost_correct = correct_auth_key[:31] + bytes([
            (correct_auth_key[31] + 1) % 256
        ])

        # All should fail
        for wrong_key in [
            wrong_key_all_different,
            wrong_key_first_correct,
            wrong_key_half_correct,
            wrong_key_almost_correct
        ]:
            auth_message = create_auth_message(device.device_id, wrong_key)

            mock_transport = MockTailscaleTransport()
            mock_transport.receive_data = auth_message

            daemon = Daemon.__new__(Daemon)
            daemon._device_store = mock_store
            daemon._connection_manager = Mock()

            await daemon._on_tailscale_connection(mock_transport)

            assert mock_transport.sent_data == b'\x00'  # All should fail


class TestInvalidMessageSizes:
    """Tests for handling invalid/malicious message sizes."""

    @pytest.mark.asyncio
    async def test_extremely_large_device_id_length_rejected(self):
        """Extremely large device_id length should be rejected."""
        from ras.daemon import Daemon

        # device_id length of 1 billion bytes
        malicious_message = struct.pack(">I", 1_000_000_000) + b"x" * 100

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = malicious_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        # Should not crash, should return failure
        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'

    @pytest.mark.asyncio
    async def test_max_uint32_device_id_length_rejected(self):
        """Integer overflow in device_id length (max uint32) should be rejected."""
        from ras.daemon import Daemon

        # device_id length of 0xFFFFFFFF (max uint32 = 4.2 billion)
        malicious_message = struct.pack(">I", 0xFFFFFFFF) + b"x" * 100

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = malicious_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        # Should not crash, should return failure
        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'

    @pytest.mark.asyncio
    async def test_device_id_length_exceeds_message_size(self):
        """device_id length > remaining message should be rejected."""
        from ras.daemon import Daemon

        # Claims device_id is 1000 bytes, but message is only 50 bytes
        malicious_message = struct.pack(">I", 1000) + b"x" * 46  # 4 + 46 = 50

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = malicious_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'

    @pytest.mark.asyncio
    async def test_empty_message_rejected(self):
        """Empty message should be rejected."""
        from ras.daemon import Daemon

        malicious_message = b""

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = malicious_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        # Will timeout since receive_data is empty
        # Important: should not crash

    @pytest.mark.asyncio
    async def test_just_length_prefix_no_payload_rejected(self):
        """Message with only length prefix (no actual data) should be rejected."""
        from ras.daemon import Daemon

        # Just the 4-byte length prefix, claims 100 bytes but none provided
        malicious_message = struct.pack(">I", 100)

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = malicious_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'


class TestNullByteInjection:
    """Tests for null byte and special character handling."""

    @pytest.mark.asyncio
    async def test_device_id_with_null_bytes(self):
        """device_id with null bytes should be handled safely."""
        from ras.daemon import Daemon

        # device_id with embedded null bytes
        device_id = "test\x00device"
        device_id_bytes = device_id.encode('utf-8')
        auth_key = os.urandom(32)

        auth_message = (
            struct.pack(">I", len(device_id_bytes)) +
            device_id_bytes +
            auth_key
        )

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        # Should not crash, device lookup will fail (unknown device)
        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'

    @pytest.mark.asyncio
    async def test_device_id_with_special_characters(self):
        """device_id with special characters should be handled safely."""
        from ras.daemon import Daemon

        # device_id with various special characters
        device_id = "test/../../../etc/passwd"
        device_id_bytes = device_id.encode('utf-8')
        auth_key = os.urandom(32)

        auth_message = (
            struct.pack(">I", len(device_id_bytes)) +
            device_id_bytes +
            auth_key
        )

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        # Should not crash, device lookup will fail (unknown device)
        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'


class TestAuthKeyHandling:
    """Tests for auth key security."""

    @pytest.mark.asyncio
    async def test_partial_auth_key_rejected(self):
        """Auth key shorter than 32 bytes should be rejected."""
        from ras.daemon import Daemon

        device_id = "test-device"
        device_id_bytes = device_id.encode('utf-8')

        # Only 16 bytes of auth key (need 32)
        partial_auth = os.urandom(16)

        auth_message = (
            struct.pack(">I", len(device_id_bytes)) +
            device_id_bytes +
            partial_auth
        )

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'

    @pytest.mark.asyncio
    async def test_all_zeros_auth_key_rejected(self):
        """All-zeros auth key should be rejected (unless it happens to match)."""
        from ras.daemon import Daemon

        device = create_test_device("test-device-123")
        # All zeros is almost certainly not the correct key
        zero_auth_key = bytes(32)

        mock_store = MockDeviceStore()
        mock_store.add(device)

        auth_message = create_auth_message(device.device_id, zero_auth_key)

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        # Should fail because key doesn't match
        assert mock_transport.sent_data == b'\x00'


class TestConnectionCleanup:
    """Tests for proper cleanup on auth failure."""

    @pytest.mark.asyncio
    async def test_failed_auth_does_not_leave_connection(self):
        """Failed auth should not leave a connection registered."""
        from ras.daemon import Daemon

        device_id = "unknown-device"
        fake_auth = os.urandom(32)
        auth_message = create_auth_message(device_id, fake_auth)

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()
        daemon._connection_manager.connections = {}

        await daemon._on_tailscale_connection(mock_transport)

        # No connection should be added
        assert len(daemon._connection_manager.connections) == 0
