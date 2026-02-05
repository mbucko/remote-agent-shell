"""Tests for connection handoff in pairing flow.

Verifies the critical ownership transfer pattern:
1. PairingSession creates and owns peer during pairing
2. After successful auth, ownership transfers to on_device_connected callback
3. Session cleanup does NOT close the connection
4. CLI cancel doesn't close active connection
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


class TestPeerHandoff:
    """Test peer connection handoff during pairing."""

    @pytest.mark.asyncio
    async def test_session_peer_cleared_after_handoff(self):
        """After successful auth, session.peer should be None to prevent double-close."""
        # Import here to avoid issues with module loading
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()

        # Track if on_device_connected was called with peer
        connected_peer = None

        async def on_device_connected(device_id, device_name, peer, auth_key):
            nonlocal connected_peer
            connected_peer = peer

        server = UnifiedServer(
            device_store=device_store,
            on_device_connected=on_device_connected,
        )

        # Create a session with a mock peer
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

        # After successful auth:
        # 1. on_device_connected should have been called with the peer
        assert connected_peer is mock_peer, "Peer should be passed to callback"

        # 2. session.peer should be None (ownership transferred)
        assert session.peer is None, "Session peer should be cleared after handoff"

        # 3. Session state should be completed
        assert session.state == "completed"

    @pytest.mark.asyncio
    async def test_cleanup_does_not_close_transferred_peer(self):
        """Cleanup should not close peer after ownership transfer."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()

        async def on_device_connected(device_id, device_name, peer, auth_key):
            pass  # Just accept the peer

        server = UnifiedServer(
            device_store=device_store,
            on_device_connected=on_device_connected,
        )

        # Create a session
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

        # Now call cleanup (as if CLI sent DELETE /api/pair/{session_id})
        await server._cleanup_session(session)

        # Peer.close() should NOT have been called
        # (it was already handed off, session.peer is None)
        mock_peer.close.assert_not_called()

    @pytest.mark.asyncio
    async def test_cli_cancel_does_not_close_active_connection(self):
        """When CLI cancels after successful pairing, connection remains active."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()

        # Track the peer that was handed off
        active_peer = None

        async def on_device_connected(device_id, device_name, peer, auth_key):
            nonlocal active_peer
            active_peer = peer

        server = UnifiedServer(
            device_store=device_store,
            on_device_connected=on_device_connected,
        )

        # Create a session
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

        # Verify peer was handed off
        assert active_peer is mock_peer

        # Simulate CLI cancelling (DELETE /api/pair/{session_id})
        # This pops the session and calls cleanup
        session = server._pairing_sessions.pop("test-session", None)
        if session:
            await server._cleanup_session(session)

        # Active peer should NOT have been closed
        mock_peer.close.assert_not_called()

    @pytest.mark.asyncio
    async def test_failed_auth_closes_peer(self):
        """When auth fails, the peer should be closed (no handoff)."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()

        # Track if on_device_connected was called
        callback_called = False

        async def on_device_connected(device_id, device_name, peer, auth_key):
            nonlocal callback_called
            callback_called = True

        server = UnifiedServer(
            device_store=device_store,
            on_device_connected=on_device_connected,
        )

        # Create a session
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
        session.peer = mock_peer
        session._auth_queue = asyncio.Queue()

        server._pairing_sessions[session.session_id] = session

        # Mock the auth handler to FAIL
        with patch("ras.server.AuthHandler") as mock_auth_handler_class:
            mock_handler = MagicMock()
            mock_handler.run_handshake = AsyncMock(return_value=False)  # Auth failed
            mock_auth_handler_class.return_value = mock_handler

            # Run the auth flow
            await server._run_pairing_auth(session.session_id)

        # Auth failed, so:
        # 1. on_device_connected should NOT have been called
        assert not callback_called, "Callback should not be called on auth failure"

        # 2. Peer should have been closed
        mock_peer.close.assert_called_once()

        # 3. Session state should be failed
        assert session.state == "failed"


class TestOwnershipTransferContract:
    """Contract tests for ownership transfer pattern."""

    def test_handoff_pattern_documented_in_code(self):
        """Verify the handoff pattern is documented in server.py."""
        from pathlib import Path

        server_file = Path(__file__).parents[2] / "src/ras/server.py"
        content = server_file.read_text()

        # Check for key patterns that indicate proper handoff
        assert "session.peer = None" in content, "Peer should be nulled after handoff"
        assert "on_device_connected" in content, "Should have device connected callback"

        # Check that nulling happens BEFORE state change within _run_pairing_auth
        # Find the section within the WebRTC auth method specifically
        auth_method_start = content.find("async def _run_pairing_auth")
        handoff_start = content.find("Hand off complete", auth_method_start)
        state_completed = content.find('session.state = "completed"', handoff_start)
        handoff_section = content[handoff_start:state_completed]
        assert (
            "session.peer = None" in handoff_section
        ), "Peer should be nulled BEFORE state is set to completed"

    def test_cleanup_checks_for_none_peer(self):
        """Verify cleanup safely handles None peer."""
        from pathlib import Path

        server_file = Path(__file__).parents[2] / "src/ras/server.py"
        content = server_file.read_text()

        # Find the _cleanup_session method
        cleanup_start = content.find("async def _cleanup_session")
        cleanup_end = content.find("async def", cleanup_start + 1)
        cleanup_method = content[cleanup_start:cleanup_end]

        # Should check if peer exists before closing
        assert "if session.peer" in cleanup_method, "Cleanup should check if peer exists"
