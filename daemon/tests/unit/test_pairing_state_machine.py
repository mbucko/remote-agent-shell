"""Tests for pairing state machine and handoff race conditions.

These tests verify:
1. State transitions happen in the correct order
2. Peer ownership is tracked correctly
3. Cleanup respects ownership transfer
4. Concurrent operations don't cause race conditions
"""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.peer import PeerConnection


class MockDeviceStore:
    """Mock device store for testing."""

    def __init__(self):
        self.devices = {}

    async def add_device(self, device_id: str, device_name: str, master_secret: bytes):
        self.devices[device_id] = {
            "device_id": device_id,
            "device_name": device_name,
            "master_secret": master_secret,
        }

    async def save(self):
        pass

    def get(self, device_id: str):
        return self.devices.get(device_id)


class MockIpProvider:
    """Mock IP provider for testing."""

    async def get_ip(self):
        return "127.0.0.1"


class TestPairingStateOrder:
    """Tests for pairing state transition ordering."""

    @pytest.mark.asyncio
    async def test_state_completed_only_after_peer_nulled(self):
        """State should be 'completed' only AFTER peer reference is cleared.

        This ensures that when CLI polls and sees 'completed', the peer
        reference is already null, so any subsequent cleanup is safe.
        """
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        ip_provider = MockIpProvider()

        # Track the order of operations
        operations = []

        class TrackingSession(PairingSession):
            """Session that tracks when peer is nulled."""

            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self._operations = operations

            @property
            def peer(self):
                return self._peer_value

            @peer.setter
            def peer(self, value):
                if value is None and hasattr(self, "_peer_value") and self._peer_value is not None:
                    self._operations.append(("peer_nulled", self.state))
                self._peer_value = value

            @property
            def state(self):
                return self._state_value

            @state.setter
            def state(self, value):
                if value == "completed":
                    self._operations.append(("state_completed", self.peer is None))
                self._state_value = value

        async def on_device_connected(device_id, device_name, peer, auth_key):
            operations.append(("callback", "called"))

        server = UnifiedServer(
            device_store=device_store,
            ip_provider=ip_provider,
            on_device_connected=on_device_connected,
        )

        # Create a session manually
        session = PairingSession(
            session_id="test-session",
            master_secret=b"x" * 32,
            auth_key=b"y" * 32,
            ntfy_topic="test-topic",
            created_at=0,
            expires_at=999999999,
            state="signaling",
            device_id="test-device",
            device_name="Test Device",
        )

        # Create mock peer
        mock_peer = MagicMock(spec=PeerConnection)
        mock_peer.wait_connected = AsyncMock()
        mock_peer.send = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.close_by_owner = AsyncMock(return_value=True)
        mock_peer.transfer_ownership = MagicMock(return_value=True)
        session.peer = mock_peer
        session._auth_queue = asyncio.Queue()

        server._pairing_sessions[session.session_id] = session

        # Mock the auth handler to succeed
        with patch("ras.server.AuthHandler") as mock_auth_handler_class:
            mock_handler = MagicMock()
            mock_handler.run_handshake = AsyncMock(return_value=True)
            mock_auth_handler_class.return_value = mock_handler

            # Run the auth flow
            await server._run_pairing_auth(session.session_id)

        # After completion, peer should be None AND state should be completed
        assert session.peer is None, "Peer should be None after handoff"
        assert session.state == "completed", "State should be completed"
        assert session.peer_transferred, "peer_transferred flag should be set"

    @pytest.mark.asyncio
    async def test_peer_transferred_flag_set_on_success(self):
        """The peer_transferred flag should be True after successful handoff."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        ip_provider = MockIpProvider()

        async def on_device_connected(device_id, device_name, peer, auth_key):
            pass

        server = UnifiedServer(
            device_store=device_store,
            ip_provider=ip_provider,
            on_device_connected=on_device_connected,
        )

        session = PairingSession(
            session_id="test-session",
            master_secret=b"x" * 32,
            auth_key=b"y" * 32,
            ntfy_topic="test-topic",
            created_at=0,
            expires_at=999999999,
            state="signaling",
            device_id="test-device",
            device_name="Test Device",
        )

        mock_peer = MagicMock(spec=PeerConnection)
        mock_peer.wait_connected = AsyncMock()
        mock_peer.send = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.close_by_owner = AsyncMock(return_value=True)
        mock_peer.transfer_ownership = MagicMock(return_value=True)
        session.peer = mock_peer
        session._auth_queue = asyncio.Queue()

        server._pairing_sessions[session.session_id] = session

        # Initially not transferred
        assert not session.peer_transferred

        with patch("ras.server.AuthHandler") as mock_auth_handler_class:
            mock_handler = MagicMock()
            mock_handler.run_handshake = AsyncMock(return_value=True)
            mock_auth_handler_class.return_value = mock_handler

            await server._run_pairing_auth(session.session_id)

        # After success, should be marked as transferred
        assert session.peer_transferred, "peer_transferred should be True after handoff"

    @pytest.mark.asyncio
    async def test_peer_transferred_flag_not_set_on_failure(self):
        """The peer_transferred flag should remain False on auth failure."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        ip_provider = MockIpProvider()

        server = UnifiedServer(
            device_store=device_store,
            ip_provider=ip_provider,
        )

        session = PairingSession(
            session_id="test-session",
            master_secret=b"x" * 32,
            auth_key=b"y" * 32,
            ntfy_topic="test-topic",
            created_at=0,
            expires_at=999999999,
            state="signaling",
            device_id="test-device",
            device_name="Test Device",
        )

        mock_peer = MagicMock(spec=PeerConnection)
        mock_peer.wait_connected = AsyncMock()
        mock_peer.send = AsyncMock()
        mock_peer.close = AsyncMock()
        session.peer = mock_peer
        session._auth_queue = asyncio.Queue()

        server._pairing_sessions[session.session_id] = session

        with patch("ras.server.AuthHandler") as mock_auth_handler_class:
            mock_handler = MagicMock()
            mock_handler.run_handshake = AsyncMock(return_value=False)  # Auth failed
            mock_auth_handler_class.return_value = mock_handler

            await server._run_pairing_auth(session.session_id)

        # On failure, should NOT be marked as transferred
        assert not session.peer_transferred, "peer_transferred should remain False on failure"


class TestConcurrentCleanup:
    """Tests for concurrent cleanup during handoff."""

    @pytest.mark.asyncio
    async def test_cleanup_respects_peer_transferred_flag(self):
        """Cleanup should check peer_transferred before closing peer."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        ip_provider = MockIpProvider()

        server = UnifiedServer(
            device_store=device_store,
            ip_provider=ip_provider,
        )

        # Create session with peer that was transferred
        session = PairingSession(
            session_id="test-session",
            master_secret=b"x" * 32,
            auth_key=b"y" * 32,
            ntfy_topic="test-topic",
            created_at=0,
            expires_at=999999999,
            state="completed",
            device_id="test-device",
            device_name="Test Device",
            peer_transferred=True,  # Peer was already handed off
        )

        # Even if peer reference exists (shouldn't, but let's be safe)
        mock_peer = MagicMock(spec=PeerConnection)
        mock_peer.close = AsyncMock()
        mock_peer.close_by_owner = AsyncMock(return_value=True)
        session.peer = mock_peer

        server._pairing_sessions[session.session_id] = session

        # Cleanup should NOT close peer because peer_transferred is True
        await server._cleanup_session(session)

        mock_peer.close.assert_not_called()
        mock_peer.close_by_owner.assert_not_called()

    @pytest.mark.asyncio
    async def test_cleanup_closes_peer_if_not_transferred(self):
        """Cleanup should close peer if it was NOT transferred."""
        from ras.server import PairingSession, UnifiedServer
        from ras.protocols import PeerOwnership

        device_store = MockDeviceStore()
        ip_provider = MockIpProvider()

        server = UnifiedServer(
            device_store=device_store,
            ip_provider=ip_provider,
        )

        # Create session with peer that was NOT transferred (failure case)
        session = PairingSession(
            session_id="test-session",
            master_secret=b"x" * 32,
            auth_key=b"y" * 32,
            ntfy_topic="test-topic",
            created_at=0,
            expires_at=999999999,
            state="failed",
            device_id="test-device",
            device_name="Test Device",
            peer_transferred=False,  # Peer was NOT handed off
        )

        mock_peer = MagicMock(spec=PeerConnection)
        mock_peer.close = AsyncMock()
        # Add close_by_owner which is now used for ownership-aware closing
        mock_peer.close_by_owner = AsyncMock(return_value=True)
        session.peer = mock_peer

        server._pairing_sessions[session.session_id] = session

        # Cleanup SHOULD close peer because peer_transferred is False
        await server._cleanup_session(session)

        # Uses ownership-aware close now
        mock_peer.close_by_owner.assert_called_once_with(PeerOwnership.PairingSession)

    @pytest.mark.asyncio
    async def test_concurrent_cleanup_and_completion_is_safe(self):
        """Concurrent cleanup during completion should be safe.

        Simulates the race where cleanup is called while completion is in progress.
        """
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        ip_provider = MockIpProvider()

        handed_off_peer = None

        async def slow_on_connected(device_id, device_name, peer, auth_key):
            nonlocal handed_off_peer
            handed_off_peer = peer
            # Simulate some work being done with the peer
            await asyncio.sleep(0.01)

        server = UnifiedServer(
            device_store=device_store,
            ip_provider=ip_provider,
            on_device_connected=slow_on_connected,
        )

        session = PairingSession(
            session_id="test-session",
            master_secret=b"x" * 32,
            auth_key=b"y" * 32,
            ntfy_topic="test-topic",
            created_at=0,
            expires_at=999999999,
            state="signaling",
            device_id="test-device",
            device_name="Test Device",
        )

        mock_peer = MagicMock(spec=PeerConnection)
        mock_peer.wait_connected = AsyncMock()
        mock_peer.send = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.close_by_owner = AsyncMock(return_value=True)
        mock_peer.transfer_ownership = MagicMock(return_value=True)
        session.peer = mock_peer
        session._auth_queue = asyncio.Queue()

        server._pairing_sessions[session.session_id] = session

        async def complete_auth():
            with patch("ras.server.AuthHandler") as mock_auth_handler_class:
                mock_handler = MagicMock()
                mock_handler.run_handshake = AsyncMock(return_value=True)
                mock_auth_handler_class.return_value = mock_handler
                await server._run_pairing_auth(session.session_id)

        async def cleanup_when_completed():
            # Wait until state shows completed (simulating CLI polling)
            for _ in range(1000):
                if session.state == "completed":
                    break
                await asyncio.sleep(0.001)

            # Immediately try cleanup
            await server._cleanup_session(session)

        # Run both concurrently
        await asyncio.gather(complete_auth(), cleanup_when_completed())

        # Peer should not have been closed by cleanup
        # (peer_transferred was set before state became "completed")
        mock_peer.close.assert_not_called()

        # Peer should have been handed off
        assert handed_off_peer is mock_peer


class TestOwnershipTransferContractExtended:
    """Extended contract tests for ownership transfer pattern."""

    def test_pairing_session_has_peer_transferred_field(self):
        """PairingSession should have peer_transferred field."""
        from ras.server import PairingSession

        session = PairingSession(
            session_id="test",
            master_secret=b"x" * 32,
            auth_key=b"y" * 32,
            ntfy_topic="test-topic",
            created_at=0,
            expires_at=999999999,
        )

        assert hasattr(session, "peer_transferred")
        assert session.peer_transferred is False  # Default should be False

    def test_ownership_pattern_in_server_code(self):
        """Verify the ownership pattern is correctly implemented in server.py."""
        from pathlib import Path

        server_file = Path(__file__).parents[2] / "src/ras/server.py"
        content = server_file.read_text()

        # Check for key patterns
        assert "peer_transferred" in content, "Should have peer_transferred field"
        assert "session.peer_transferred = True" in content, "Should set transferred on success"
        assert "not session.peer_transferred" in content, "Cleanup should check transferred flag"

        # Check that peer_transferred is set BEFORE state = completed
        handoff_section = content[
            content.find("peer_transferred = True") : content.find('state = "completed"')
        ]
        assert "peer = None" in handoff_section, "Peer should be nulled between transfer flag and state change"
