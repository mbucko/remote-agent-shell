"""Contract tests for connection handoff behavior.

These tests verify the critical invariant that peer connections survive
the handoff from ntfy signaling to the ConnectionManager.

Bug this prevents:
- After successful auth via ntfy path, closing the ntfy subscriber
  would close the peer that was already handed off to ConnectionManager,
  causing the connection to die immediately.

Fix (structural with ownership tracking):
- PeerConnection has owner field (PeerOwnership enum)
- Only the current owner can close via close_by_owner()
- Server transfers ownership to ConnectionManager before calling on_device_connected
- Handler's close() uses close_by_owner() which is a no-op after transfer
"""

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from ras.ntfy_signaling.handler import NtfySignalingHandler
from ras.ntfy_signaling.subscriber import NtfySignalingSubscriber
from ras.peer import PeerConnection
from ras.protocols import PeerOwnership, PeerState


class TestPeerOwnershipContract:
    """Contract: PeerConnection ownership prevents accidental closes."""

    @pytest.mark.asyncio
    async def test_transfer_ownership_changes_owner(self):
        """CONTRACT: transfer_ownership() changes the owner."""
        # Arrange
        peer = PeerConnection(owner=PeerOwnership.SignalingHandler)

        # Act
        result = peer.transfer_ownership(PeerOwnership.ConnectionManager)

        # Assert
        assert result is True
        assert peer.owner == PeerOwnership.ConnectionManager

    @pytest.mark.asyncio
    async def test_close_by_owner_fails_after_transfer(self):
        """CONTRACT: close_by_owner() fails when caller is not owner."""
        # Arrange
        peer = PeerConnection(owner=PeerOwnership.SignalingHandler)
        peer.transfer_ownership(PeerOwnership.ConnectionManager)

        # Act
        result = await peer.close_by_owner(PeerOwnership.SignalingHandler)

        # Assert - close should be rejected
        assert result is False
        # Peer should NOT be closed
        assert peer.state != PeerState.CLOSED

    @pytest.mark.asyncio
    async def test_close_by_owner_succeeds_for_owner(self):
        """CONTRACT: close_by_owner() succeeds when caller is owner."""
        # Arrange
        peer = PeerConnection(owner=PeerOwnership.SignalingHandler)

        # Act
        result = await peer.close_by_owner(PeerOwnership.SignalingHandler)

        # Assert
        assert result is True
        assert peer.state == PeerState.CLOSED
        assert peer.owner == PeerOwnership.Disposed

    @pytest.mark.asyncio
    async def test_transfer_fails_after_disposed(self):
        """CONTRACT: transfer_ownership() fails on disposed peer."""
        # Arrange
        peer = PeerConnection(owner=PeerOwnership.SignalingHandler)
        await peer.close()

        # Act
        result = peer.transfer_ownership(PeerOwnership.ConnectionManager)

        # Assert
        assert result is False


class TestHandlerOwnershipIntegration:
    """Tests that handler uses ownership-aware closing."""

    @pytest.fixture
    def handler(self):
        """Create handler with test secret."""
        return NtfySignalingHandler(
            master_secret=b"\x00" * 32,
            pending_session_id="test-session",
        )

    @pytest.mark.asyncio
    async def test_handler_close_uses_ownership(self, handler):
        """CONTRACT: Handler's close() uses close_by_owner()."""
        # Arrange - create real peer
        peer = PeerConnection(owner=PeerOwnership.SignalingHandler)
        handler._peer = peer

        # Transfer ownership (simulating server handoff)
        peer.transfer_ownership(PeerOwnership.ConnectionManager)

        # Act - handler tries to close
        await handler.close()

        # Assert - peer should NOT be closed (ownership was transferred)
        assert peer.state != PeerState.CLOSED

    @pytest.mark.asyncio
    async def test_handler_close_works_when_owner(self, handler):
        """BASELINE: Handler's close() works when still owner."""
        # Arrange - create real peer
        peer = PeerConnection(owner=PeerOwnership.SignalingHandler)
        handler._peer = peer

        # Act - handler closes without transfer
        await handler.close()

        # Assert - peer should be closed
        assert peer.state == PeerState.CLOSED


class TestServerHandoffSequence:
    """Integration test for server handoff sequence with ownership."""

    @pytest.mark.asyncio
    async def test_correct_handoff_with_ownership_transfer(self):
        """Simulates the server's handoff sequence with ownership transfer."""
        # Arrange - create real peer and subscriber
        peer = PeerConnection(owner=PeerOwnership.SignalingHandler)

        subscriber = NtfySignalingSubscriber(
            master_secret=b"\x00" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
        )
        subscriber._handler._peer = peer

        # Act - simulate what server.py does after auth success:
        # 1. Transfer ownership to ConnectionManager
        peer.transfer_ownership(PeerOwnership.ConnectionManager)
        # 2. Close subscriber (handler's close_by_owner will be no-op)
        await subscriber.close()

        # Assert - peer should NOT be closed
        assert peer.state != PeerState.CLOSED
        assert peer.owner == PeerOwnership.ConnectionManager

    @pytest.mark.asyncio
    async def test_handoff_failure_still_closes_peer(self):
        """When handoff fails, peer should be closed (no transfer happened)."""
        # Arrange
        peer = PeerConnection(owner=PeerOwnership.SignalingHandler)

        subscriber = NtfySignalingSubscriber(
            master_secret=b"\x00" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
        )
        subscriber._handler._peer = peer

        # Act - close WITHOUT transferring ownership (simulating failure path)
        await subscriber.close()

        # Assert - peer should be closed
        assert peer.state == PeerState.CLOSED


class TestHandlerTakePeerLegacy:
    """Legacy tests for take_peer() - still works as fallback."""

    @pytest.fixture
    def mock_peer(self):
        """Create a mock peer that tracks close calls."""
        peer = MagicMock()
        peer.close = AsyncMock()
        peer.close_by_owner = AsyncMock(return_value=True)
        return peer

    @pytest.fixture
    def handler(self):
        """Create handler with test secret."""
        return NtfySignalingHandler(
            master_secret=b"\x00" * 32,
            pending_session_id="test-session",
        )

    @pytest.mark.asyncio
    async def test_take_peer_returns_peer(self, handler, mock_peer):
        """take_peer() still works for backward compatibility."""
        # Arrange
        handler._peer = mock_peer

        # Act
        result = handler.take_peer()

        # Assert
        assert result is mock_peer
        assert handler._peer is None
