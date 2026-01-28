"""Tests for message dispatcher module."""

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from ras.message_dispatcher import MessageDispatcher


class TestMessageDispatcher:
    """Tests for MessageDispatcher."""

    @pytest.fixture
    def dispatcher(self):
        """Create message dispatcher."""
        return MessageDispatcher(handler_timeout=1.0)

    # MD01: Register handler
    def test_register_handler(self, dispatcher):
        """Register a message handler."""
        handler = AsyncMock()

        dispatcher.register("session", handler)

        assert "session" in dispatcher._handlers

    # MD02: Dispatch to registered handler
    @pytest.mark.asyncio
    async def test_dispatch_to_registered(self, dispatcher):
        """Dispatch calls registered handler."""
        handler = AsyncMock()
        dispatcher.register("session", handler)

        await dispatcher.dispatch(
            device_id="device1",
            message_type="session",
            message={"test": "data"},
        )

        handler.assert_called_once_with("device1", {"test": "data"})

    # MD03: Dispatch to unregistered type
    @pytest.mark.asyncio
    async def test_dispatch_to_unregistered(self, dispatcher, caplog):
        """Dispatch to unknown type logs warning."""
        await dispatcher.dispatch(
            device_id="device1",
            message_type="unknown",
            message={"test": "data"},
        )

        assert "No handler for message type: unknown" in caplog.text

    # MD04: Handler raises exception
    @pytest.mark.asyncio
    async def test_handler_exception(self, dispatcher, caplog):
        """Handler exception is caught and logged."""
        handler = AsyncMock(side_effect=ValueError("handler error"))
        dispatcher.register("session", handler)

        await dispatcher.dispatch(
            device_id="device1",
            message_type="session",
            message={"test": "data"},
        )

        assert "Handler error for session" in caplog.text

    # MD05: Handler timeout
    @pytest.mark.asyncio
    async def test_handler_timeout(self, caplog):
        """Slow handler is timed out."""
        dispatcher = MessageDispatcher(handler_timeout=0.1)

        async def slow_handler(device_id, message):
            await asyncio.sleep(1.0)

        dispatcher.register("session", slow_handler)

        await dispatcher.dispatch(
            device_id="device1",
            message_type="session",
            message={"test": "data"},
        )

        assert "Handler timeout for session" in caplog.text

    # MD06: Multiple handlers
    @pytest.mark.asyncio
    async def test_multiple_handlers(self, dispatcher):
        """Multiple handlers can be registered."""
        session_handler = AsyncMock()
        terminal_handler = AsyncMock()
        clipboard_handler = AsyncMock()

        dispatcher.register("session", session_handler)
        dispatcher.register("terminal", terminal_handler)
        dispatcher.register("clipboard", clipboard_handler)

        await dispatcher.dispatch("device1", "session", {"s": 1})
        await dispatcher.dispatch("device1", "terminal", {"t": 2})
        await dispatcher.dispatch("device1", "clipboard", {"c": 3})

        session_handler.assert_called_once_with("device1", {"s": 1})
        terminal_handler.assert_called_once_with("device1", {"t": 2})
        clipboard_handler.assert_called_once_with("device1", {"c": 3})

    # MD07: Replace handler
    def test_replace_handler(self, dispatcher):
        """Registering same type replaces handler."""
        handler1 = AsyncMock()
        handler2 = AsyncMock()

        dispatcher.register("session", handler1)
        dispatcher.register("session", handler2)

        assert dispatcher._handlers["session"] is handler2

    # MD08: Get registered types
    def test_get_registered_types(self, dispatcher):
        """Get list of registered message types."""
        dispatcher.register("session", AsyncMock())
        dispatcher.register("terminal", AsyncMock())

        types = dispatcher.get_registered_types()

        assert set(types) == {"session", "terminal"}

    # MD09: Has handler
    def test_has_handler(self, dispatcher):
        """Check if handler exists for type."""
        dispatcher.register("session", AsyncMock())

        assert dispatcher.has_handler("session") is True
        assert dispatcher.has_handler("unknown") is False

    # MD10: Unregister handler
    def test_unregister_handler(self, dispatcher):
        """Remove a registered handler."""
        handler = AsyncMock()
        dispatcher.register("session", handler)

        result = dispatcher.unregister("session")

        assert result is True
        assert "session" not in dispatcher._handlers

    # MD11: Unregister non-existent
    def test_unregister_nonexistent(self, dispatcher):
        """Unregister unknown type returns False."""
        result = dispatcher.unregister("unknown")

        assert result is False

    # MD12: Dispatch passes device_id correctly
    @pytest.mark.asyncio
    async def test_dispatch_passes_device_id(self, dispatcher):
        """Device ID is passed to handler."""
        handler = AsyncMock()
        dispatcher.register("session", handler)

        await dispatcher.dispatch(
            device_id="phone-xyz-123",
            message_type="session",
            message={"data": 1},
        )

        # First arg should be device_id
        call_args = handler.call_args[0]
        assert call_args[0] == "phone-xyz-123"

    # MD13: Sync handler wrapped
    @pytest.mark.asyncio
    async def test_sync_handler_wrapped(self, dispatcher):
        """Synchronous handler is wrapped in async."""
        results = []

        def sync_handler(device_id, message):
            results.append((device_id, message))

        dispatcher.register("sync", sync_handler)

        await dispatcher.dispatch("device1", "sync", {"test": 1})

        assert len(results) == 1
        assert results[0] == ("device1", {"test": 1})
