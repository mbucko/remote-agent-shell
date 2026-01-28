"""Contract tests for callback interfaces.

These tests verify that callbacks registered with PeerConnection
match the expected signature (sync vs async).

Bug this prevents:
- ConnectionManager setting a sync callback when PeerConnection awaits it
- Causes "NoneType can't be used in 'await' expression" error
"""

import asyncio
import inspect

import pytest

from ras.connection_manager import ConnectionManager
from ras.peer import PeerConnection


class TestMessageCallbackContract:
    """Contract: Message callbacks must be async (awaitable)."""

    @pytest.mark.asyncio
    async def test_peer_awaits_message_callback(self):
        """PeerConnection awaits the message callback - it must be async."""
        # Verify the peer's on_message handler awaits the callback
        # by checking the source code pattern
        import ras.peer as peer_module
        source = inspect.getsource(peer_module.PeerConnection._setup_channel)

        # The handler should await the callback
        assert "await self._message_callback" in source, (
            "PeerConnection must await the message callback"
        )

    @pytest.mark.asyncio
    async def test_connection_manager_callback_is_async(self):
        """ConnectionManager's message handler must be async."""
        from unittest.mock import MagicMock, AsyncMock

        # Create a mock peer
        mock_peer = MagicMock(spec=PeerConnection)
        registered_callback = None

        def capture_callback(cb):
            nonlocal registered_callback
            registered_callback = cb

        mock_peer.on_message = capture_callback
        mock_peer.on_close = MagicMock()

        # Create connection manager and add a connection
        cm = ConnectionManager(
            on_connection_lost=AsyncMock(),
        )

        mock_codec = MagicMock()
        mock_codec.decode = MagicMock(return_value=b"decrypted")

        await cm.add_connection(
            device_id="test-device",
            peer=mock_peer,
            codec=mock_codec,
            on_message=MagicMock(),
        )

        # The registered callback must be async (coroutine function)
        assert registered_callback is not None, "Callback should be registered"
        assert asyncio.iscoroutinefunction(registered_callback), (
            "ConnectionManager's message handler must be async (coroutine function) "
            "because PeerConnection awaits it"
        )

    @pytest.mark.asyncio
    async def test_callback_can_be_awaited(self):
        """The callback should be awaitable without error."""
        from unittest.mock import MagicMock, AsyncMock

        mock_peer = MagicMock(spec=PeerConnection)
        registered_callback = None

        def capture_callback(cb):
            nonlocal registered_callback
            registered_callback = cb

        mock_peer.on_message = capture_callback
        mock_peer.on_close = MagicMock()

        cm = ConnectionManager(
            on_connection_lost=AsyncMock(),
        )

        mock_codec = MagicMock()
        mock_codec.decode = MagicMock(return_value=b"decrypted")

        await cm.add_connection(
            device_id="test-device",
            peer=mock_peer,
            codec=mock_codec,
            on_message=MagicMock(),
        )

        # Should be able to await the callback without TypeError
        try:
            await registered_callback(b"test data")
        except TypeError as e:
            if "can't be used in 'await'" in str(e):
                pytest.fail(
                    f"Callback is not awaitable: {e}. "
                    "This would cause 'NoneType can't be used in await' error in production."
                )
            raise
