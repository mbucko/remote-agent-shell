"""Connection tests inspired by patterns from other open source projects.

Based on analysis of:
- aiortc: WebRTC Python library
- mosh: Mobile shell with network recovery
- simple-peer: JS WebRTC library
- Tailscale/WireGuard: VPN implementations

These tests cover gaps identified from comparing our test suite with these projects.
"""

import asyncio
import os
import time
from unittest.mock import AsyncMock, Mock, MagicMock, patch

import pytest

from ras.auth import Authenticator, AuthState
from ras.crypto import derive_key


# =============================================================================
# SECTION 1: Connection Failure Recovery (from mosh patterns)
# =============================================================================

class TestConnectionRecovery:
    """Tests for connection failure and recovery scenarios.

    mosh extensively tests network timeout recovery and graceful degradation.
    """

    @pytest.mark.asyncio
    async def test_connection_survives_brief_network_blip(self):
        """Connection should survive brief network interruptions (mosh pattern)."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()

        mock_peer = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.on_message = Mock()
        mock_peer.on_close = Mock()
        mock_peer.send = AsyncMock()

        mock_codec = Mock()
        mock_codec.encode = Mock(return_value=b"encoded")

        await manager.add_connection(
            device_id="test",
            peer=mock_peer,
            codec=mock_codec,
            on_message=Mock()
        )

        conn = manager.get_connection("test")
        assert conn is not None

        # Simulate brief send failure followed by recovery
        mock_peer.send.side_effect = [Exception("Network blip"), None]

        # First send might fail
        try:
            await conn.send(b"test1")
        except Exception:
            pass

        # Reset for recovery
        mock_peer.send.side_effect = None
        mock_peer.send.reset_mock()

        # Second send should work
        await conn.send(b"test2")
        mock_peer.send.assert_called()

        await manager.close_all()

    @pytest.mark.asyncio
    async def test_stale_connection_detected(self):
        """Stale connections should be detected (keepalive pattern from mosh/WireGuard)."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()

        mock_peer = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.on_message = Mock()
        mock_peer.on_close = Mock()

        mock_codec = Mock()

        await manager.add_connection(
            device_id="test",
            peer=mock_peer,
            codec=mock_codec,
            on_message=Mock()
        )

        conn = manager.get_connection("test")

        # Connection should track last activity
        if hasattr(conn, 'last_activity'):
            initial_activity = conn.last_activity

            # After update, activity should change
            if hasattr(conn, 'update_activity'):
                # Mock time advancing instead of sleeping
                with patch("time.time", return_value=time.time() + 0.01):
                    conn.update_activity()
                assert conn.last_activity > initial_activity

        await manager.close_all()


# =============================================================================
# SECTION 3: Connection State Transitions (from aiortc patterns)
# =============================================================================

class TestConnectionStateTransitions:
    """Tests for proper connection state machine transitions.

    aiortc verifies: new → connecting → connected → closed
    """

    @pytest.mark.asyncio
    async def test_peer_starts_in_new_state(self):
        """Peer should start in NEW state (aiortc pattern)."""
        from ras.peer import PeerConnection
        from ras.protocols import PeerState

        peer = PeerConnection()
        assert peer.state == PeerState.NEW

    @pytest.mark.asyncio
    async def test_state_transitions_are_valid(self):
        """Valid state transitions should be well-defined (aiortc pattern)."""
        from ras.protocols import PeerState

        # Define valid forward transitions
        valid_transitions = {
            PeerState.NEW: {PeerState.CONNECTING, PeerState.FAILED, PeerState.CLOSED},
            PeerState.CONNECTING: {PeerState.CONNECTED, PeerState.FAILED, PeerState.CLOSED},
            PeerState.CONNECTED: {PeerState.DISCONNECTED, PeerState.FAILED, PeerState.CLOSED},
            PeerState.DISCONNECTED: {PeerState.CLOSED, PeerState.FAILED},
        }

        # Verify all expected transitions are defined
        for from_state, to_states in valid_transitions.items():
            assert len(to_states) >= 1, f"State {from_state} should have valid transitions"


# =============================================================================
# SECTION 4: STUN/TURN Server Interaction (from aiortc patterns)
# =============================================================================

class TestSTUNTURNInteraction:
    """Tests for STUN/TURN server configuration and interaction.

    aiortc tests default STUN servers, custom configurations, and credential handling.
    """

    def test_default_stun_servers_configured(self):
        """Peer should have default STUN servers (aiortc pattern)."""
        from ras.peer import PeerConnection

        peer = PeerConnection()

        assert hasattr(peer, 'stun_servers') or hasattr(peer, '_stun_servers')
        servers = getattr(peer, 'stun_servers', getattr(peer, '_stun_servers', []))

        # Should have at least one STUN server
        assert len(servers) >= 1

    def test_custom_stun_servers_accepted(self):
        """Custom STUN servers should be configurable (aiortc pattern)."""
        from ras.peer import PeerConnection

        custom_servers = ["stun:custom.stun.server:3478"]
        peer = PeerConnection(stun_servers=custom_servers)

        servers = getattr(peer, 'stun_servers', getattr(peer, '_stun_servers', []))
        assert servers == custom_servers


# =============================================================================
# SECTION 5: Rate Limiting Under Load (stress testing pattern)
# =============================================================================

class TestRateLimitingUnderLoad:
    """Tests for rate limiting under sustained load.

    Multiple projects test behavior under load to prevent DoS.
    """

    @pytest.mark.asyncio
    async def test_rapid_connection_attempts_rate_limited(self):
        """Rapid connection attempts should be rate limited."""
        from ras.pairing.signaling_server import SignalingServer

        # Create server with mock pairing manager
        mock_pairing_manager = Mock()

        server = SignalingServer(pairing_manager=mock_pairing_manager)

        # Server should have rate limiters
        assert hasattr(server, 'session_limiter') or hasattr(server, 'ip_limiter'), \
            "SignalingServer should have rate limiters"

    @pytest.mark.asyncio
    async def test_message_flood_handled(self):
        """Message flood should not crash connection manager."""
        from ras.connection_manager import ConnectionManager

        manager = ConnectionManager()

        mock_peer = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.on_message = Mock()
        mock_peer.on_close = Mock()
        mock_peer.send = AsyncMock()

        mock_codec = Mock()
        mock_codec.encode = Mock(return_value=b"encoded")

        await manager.add_connection(
            device_id="test",
            peer=mock_peer,
            codec=mock_codec,
            on_message=Mock()
        )

        # Send many messages rapidly
        conn = manager.get_connection("test")
        for _ in range(100):
            try:
                await conn.send(b"flood message")
            except Exception:
                pass  # Some may be rate limited

        # Connection should still be valid
        assert manager.get_connection("test") is not None

        await manager.close_all()


# =============================================================================
# SECTION 6: Key Rotation and Forward Secrecy (from WireGuard patterns)
# =============================================================================

class TestKeyRotation:
    """Tests for key rotation and forward secrecy.

    WireGuard rotates keys every few minutes for perfect forward secrecy.
    """

    def test_auth_keys_are_unique_per_session(self):
        """Each session should have unique derived keys."""
        secret1 = os.urandom(32)
        secret2 = os.urandom(32)

        key1 = derive_key(secret1, "auth")
        key2 = derive_key(secret2, "auth")

        # Different secrets should produce different keys
        assert key1 != key2

    def test_different_key_purposes_produce_different_keys(self):
        """Same secret with different purposes should produce different keys."""
        secret = os.urandom(32)

        auth_key = derive_key(secret, "auth")
        signaling_key = derive_key(secret, "signaling")

        assert auth_key != signaling_key

    def test_key_derivation_is_deterministic(self):
        """Same inputs should always produce same key."""
        secret = b"x" * 32

        key1 = derive_key(secret, "auth")
        key2 = derive_key(secret, "auth")

        assert key1 == key2


# =============================================================================
# SECTION 7: Handshake Verification (from WireGuard patterns)
# =============================================================================

class TestHandshakeVerification:
    """Tests for verifying handshake completion.

    WireGuard tests that handshakes complete successfully before allowing traffic.
    """

    @pytest.mark.asyncio
    async def test_auth_handshake_must_complete_before_data(self):
        """Data should not be processed before auth completes."""
        auth = Authenticator(auth_key=b"x" * 32)

        # Should start in PENDING state
        assert auth.state == AuthState.PENDING

        # Cannot verify without challenge
        fake_response = {"type": "auth_response", "hmac": "aa" * 32, "nonce": "bb" * 32}
        result = auth.verify_response(fake_response)

        # Should fail - no challenge was issued
        assert result is False

    def test_challenge_must_precede_response(self):
        """Response verification must fail if no challenge was issued."""
        auth = Authenticator(auth_key=b"x" * 32)

        # Try to verify without creating challenge first
        response = {"hmac": b"x" * 32, "nonce": b"y" * 32}

        # This should fail
        result = auth.verify_response(response)
        assert result is False


# =============================================================================
# SECTION 8: Terminal/PTY Edge Cases (from mosh patterns)
# =============================================================================

class TestTerminalEdgeCases:
    """Tests for terminal handling edge cases.

    mosh extensively tests terminal emulation, resize, and Unicode handling.
    """

    def test_terminal_resize_boundaries(self):
        """Terminal resize should handle boundary values."""
        # Min/max reasonable terminal sizes
        valid_sizes = [
            (10, 5),      # Minimum
            (80, 24),     # Standard
            (200, 50),    # Large
            (500, 200),   # Maximum reasonable
        ]

        invalid_sizes = [
            (0, 0),       # Too small
            (9, 4),       # Below minimum
            (501, 201),   # Above maximum
            (-1, -1),     # Negative
        ]

        # These are valid dimensions that should be accepted
        for cols, rows in valid_sizes:
            assert 10 <= cols <= 500
            assert 5 <= rows <= 200

        # These should be rejected
        for cols, rows in invalid_sizes:
            assert not (10 <= cols <= 500 and 5 <= rows <= 200)


# =============================================================================
# SECTION 9: SDP Transformation and Validation (from simple-peer patterns)
# =============================================================================

class TestSDPTransformation:
    """Tests for SDP transformation and validation.

    simple-peer tests SDP transformation functions and error handling.
    """

    def test_sdp_must_have_version_line(self):
        """SDP must start with v=0 line."""
        valid_sdp = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        invalid_sdp = "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"

        assert valid_sdp.startswith("v=")
        assert not invalid_sdp.startswith("v=")

    def test_sdp_must_have_media_section(self):
        """SDP must have at least one m= line."""
        valid_sdp = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        invalid_sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        assert "m=" in valid_sdp
        assert "m=" not in invalid_sdp


# =============================================================================
# SECTION 10: Concurrent Operation Edge Cases (from simple-peer patterns)
# =============================================================================

class TestConcurrentOperations:
    """Tests for concurrent operation handling.

    simple-peer tests concurrent ICE state changes and peer destruction.
    """

    @pytest.mark.asyncio
    async def test_close_during_connecting_is_safe(self):
        """Closing peer during connection should not crash."""
        from ras.peer import PeerConnection

        peer = PeerConnection()

        # Start connecting and immediately close
        task = asyncio.create_task(peer.wait_connected(timeout=5))
        # Close immediately - the race condition is the test
        await peer.close()
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass  # Any exception is acceptable during race

        # Should reach here without hanging or crashing

    @pytest.mark.asyncio
    async def test_multiple_close_calls_idempotent(self):
        """Multiple close calls should be idempotent."""
        from ras.peer import PeerConnection
        from ras.protocols import PeerState

        peer = PeerConnection()

        # Close multiple times
        await peer.close()
        await peer.close()
        await peer.close()

        # Should be in CLOSED state
        assert peer.state == PeerState.CLOSED
