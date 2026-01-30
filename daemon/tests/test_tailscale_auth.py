"""Tests for daemon Tailscale authentication handler.

These tests prevent regression of Bug 2: Auth Protocol Bug
where the phone only sent auth_key, but daemon needed device_id
to look up the correct device.
"""

import asyncio
import os
import struct
from unittest.mock import AsyncMock, Mock, patch

import pytest

from ras.crypto import derive_key
from ras.device_store import JsonDeviceStore, PairedDevice


class MockTailscaleTransport:
    """Mock TailscaleTransport for testing auth handler."""

    def __init__(self):
        self.receive_data: bytes = b""
        self.sent_data: bytes = b""
        self.closed = False
        self.remote_address = ("100.64.0.2", 12345)

    async def receive(self, timeout: float = 10.0) -> bytes:
        """Return pre-configured receive data."""
        if not self.receive_data:
            await asyncio.sleep(timeout)
            raise TimeoutError("No data")
        return self.receive_data

    async def send(self, data: bytes) -> None:
        """Record sent data."""
        self.sent_data = data

    def close(self) -> None:
        """Mark as closed."""
        self.closed = True


def create_auth_message(device_id: str, auth_key: bytes) -> bytes:
    """Create an auth message in the expected format."""
    device_id_bytes = device_id.encode('utf-8')
    return (
        struct.pack(">I", len(device_id_bytes)) +
        device_id_bytes +
        auth_key
    )


def create_test_device(device_id: str = "test-device-123") -> PairedDevice:
    """Create a test paired device."""
    from datetime import datetime, timezone
    return PairedDevice(
        device_id=device_id,
        name="Test Phone",
        master_secret=os.urandom(32),
        paired_at=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    )


class MockDeviceStore:
    """Mock device store for testing."""

    def __init__(self):
        self.devices: dict[str, PairedDevice] = {}

    def add(self, device: PairedDevice) -> None:
        """Add a device."""
        self.devices[device.device_id] = device

    def get(self, device_id: str) -> PairedDevice | None:
        """Get a device by ID."""
        return self.devices.get(device_id)

    async def save(self) -> None:
        """Mock save."""
        pass


class TestSuccessfulAuth:
    """Tests for successful authentication."""

    @pytest.mark.asyncio
    async def test_valid_device_id_and_auth_key_returns_success(self):
        """Bug 2 regression: valid device_id + auth should succeed."""
        # Import here to allow patching
        from ras.daemon import Daemon

        # Create device
        device = create_test_device("test-device-123")
        auth_key = derive_key(device.master_secret, "auth")

        # Create mock store
        mock_store = MockDeviceStore()
        mock_store.add(device)

        # Create auth message
        auth_message = create_auth_message(device.device_id, auth_key)

        # Create mock transport
        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        # Create daemon with mock store
        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()
        daemon._connection_manager.add_connection = AsyncMock()

        # Mock on_new_connection to avoid full connection setup
        daemon.on_new_connection = AsyncMock()

        # Run auth handler
        await daemon._on_tailscale_connection(mock_transport)

        # Verify success response
        assert mock_transport.sent_data == b'\x01'

    @pytest.mark.asyncio
    async def test_auth_works_with_minimum_device_id_length(self):
        """Auth should work with minimum device_id length (1 byte)."""
        from ras.daemon import Daemon

        device = create_test_device("x")  # 1-byte device_id
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

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x01'

    @pytest.mark.asyncio
    async def test_auth_works_with_long_device_id(self):
        """Auth should work with longer device_id (up to 100 bytes)."""
        from ras.daemon import Daemon

        device = create_test_device("a" * 100)  # 100-byte device_id
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

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x01'


class TestAuthFailure:
    """Tests for authentication failures."""

    @pytest.mark.asyncio
    async def test_unknown_device_id_returns_failure(self):
        """Bug 2 regression: unknown device_id should fail."""
        from ras.daemon import Daemon

        # Create auth message for unknown device
        device_id = "unknown-device"
        fake_auth = os.urandom(32)
        auth_message = create_auth_message(device_id, fake_auth)

        mock_store = MockDeviceStore()
        # Don't add any device - it's unknown

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'  # Failure

    @pytest.mark.asyncio
    async def test_invalid_auth_key_returns_failure(self):
        """Bug 2 regression: invalid auth_key should fail."""
        from ras.daemon import Daemon

        device = create_test_device("test-device-123")
        wrong_auth_key = os.urandom(32)  # Wrong key

        mock_store = MockDeviceStore()
        mock_store.add(device)

        auth_message = create_auth_message(device.device_id, wrong_auth_key)

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'  # Failure

    @pytest.mark.asyncio
    async def test_message_too_short_returns_failure(self):
        """Malformed message (too short) should return failure."""
        from ras.daemon import Daemon

        # Message with only 30 bytes (need at least 37: 4 + 1 + 32)
        auth_message = b"x" * 30

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'  # Failure

    @pytest.mark.asyncio
    async def test_device_id_length_zero_returns_failure(self):
        """device_id length of 0 should return failure."""
        from ras.daemon import Daemon

        # Length prefix of 0
        auth_message = struct.pack(">I", 0) + os.urandom(32)

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'  # Failure

    @pytest.mark.asyncio
    async def test_device_id_length_over_100_returns_failure(self):
        """device_id length > 100 should return failure."""
        from ras.daemon import Daemon

        # Length prefix of 101
        auth_message = struct.pack(">I", 101) + b"x" * 101 + os.urandom(32)

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'  # Failure

    @pytest.mark.asyncio
    async def test_truncated_auth_key_returns_failure(self):
        """Incomplete auth_key (truncated message) should return failure."""
        from ras.daemon import Daemon

        device_id = "test-device"
        device_id_bytes = device_id.encode('utf-8')
        # Only 16 bytes of auth (need 32)
        auth_message = struct.pack(">I", len(device_id_bytes)) + device_id_bytes + os.urandom(16)

        mock_store = MockDeviceStore()

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'  # Failure


class TestTimeoutAndErrorHandling:
    """Tests for timeout and error handling."""

    @pytest.mark.asyncio
    async def test_auth_timeout_handles_gracefully(self):
        """Auth timeout (no data received) should handle gracefully."""
        from ras.daemon import Daemon

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = b""  # No data - will timeout

        mock_store = MockDeviceStore()

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = mock_store
        daemon._connection_manager = Mock()

        # Should not raise exception
        await daemon._on_tailscale_connection(mock_transport)

        # No response sent on timeout (connection just closes)
        # The key is no exception is raised

    @pytest.mark.asyncio
    async def test_no_store_configured_returns_failure(self):
        """No store configured should return failure."""
        from ras.daemon import Daemon

        device = create_test_device("test-device-123")
        auth_key = derive_key(device.master_secret, "auth")
        auth_message = create_auth_message(device.device_id, auth_key)

        mock_transport = MockTailscaleTransport()
        mock_transport.receive_data = auth_message

        daemon = Daemon.__new__(Daemon)
        daemon._device_store = None  # No store configured
        daemon._connection_manager = Mock()

        await daemon._on_tailscale_connection(mock_transport)

        assert mock_transport.sent_data == b'\x00'  # Failure


class TestAuthMessageFormat:
    """Tests for auth message format validation."""

    def test_auth_message_format_correct(self):
        """Verify auth message format: [4-byte len][device_id][32-byte auth]."""
        device_id = "test-device-123"
        auth_key = os.urandom(32)

        message = create_auth_message(device_id, auth_key)

        # Parse and verify
        device_id_len = struct.unpack(">I", message[:4])[0]
        assert device_id_len == len(device_id)

        parsed_device_id = message[4:4 + device_id_len].decode('utf-8')
        assert parsed_device_id == device_id

        parsed_auth_key = message[4 + device_id_len:4 + device_id_len + 32]
        assert parsed_auth_key == auth_key

    def test_device_id_utf8_encoded(self):
        """Device ID should be UTF-8 encoded."""
        device_id = "test-device-\u4e2d\u6587"  # Contains Chinese characters
        auth_key = os.urandom(32)

        message = create_auth_message(device_id, auth_key)

        device_id_len = struct.unpack(">I", message[:4])[0]
        parsed_device_id = message[4:4 + device_id_len].decode('utf-8')

        assert parsed_device_id == device_id

    def test_length_prefix_big_endian(self):
        """Length prefix should be big-endian."""
        device_id = "test"  # 4 bytes
        auth_key = os.urandom(32)

        message = create_auth_message(device_id, auth_key)

        # Big-endian: 0x00000004
        assert message[:4] == b'\x00\x00\x00\x04'
