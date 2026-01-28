"""Route incoming messages to appropriate handlers."""

import asyncio
import inspect
import logging
from typing import Any, Callable, Coroutine, Union

logger = logging.getLogger(__name__)

# Handler type: async or sync function taking (device_id, message)
Handler = Union[
    Callable[[str, Any], Coroutine[Any, Any, None]],
    Callable[[str, Any], None],
]


class MessageDispatcher:
    """Route messages to registered handlers by type.

    Uses dict-based handler registration (industry standard pattern)
    for O(1) message routing.
    """

    def __init__(self, handler_timeout: float = 10.0):
        """Initialize dispatcher.

        Args:
            handler_timeout: Maximum time for handler to complete (seconds).
        """
        self._handlers: dict[str, Handler] = {}
        self._handler_timeout = handler_timeout

    def register(self, message_type: str, handler: Handler) -> None:
        """Register a handler for a message type.

        Args:
            message_type: Type identifier (e.g., "session", "terminal").
            handler: Async or sync function(device_id, message).
        """
        self._handlers[message_type] = handler
        logger.debug(f"Registered handler for: {message_type}")

    def unregister(self, message_type: str) -> bool:
        """Unregister a handler.

        Args:
            message_type: Type to unregister.

        Returns:
            True if handler was removed, False if not found.
        """
        if message_type in self._handlers:
            del self._handlers[message_type]
            logger.debug(f"Unregistered handler for: {message_type}")
            return True
        return False

    def has_handler(self, message_type: str) -> bool:
        """Check if handler exists for type."""
        return message_type in self._handlers

    def get_registered_types(self) -> list[str]:
        """Get list of registered message types."""
        return list(self._handlers.keys())

    async def dispatch(
        self,
        device_id: str,
        message_type: str,
        message: Any,
    ) -> None:
        """Dispatch message to appropriate handler.

        Args:
            device_id: Source device identifier.
            message_type: Message type for routing.
            message: The message payload.
        """
        handler = self._handlers.get(message_type)

        if handler is None:
            logger.warning(f"No handler for message type: {message_type}")
            return

        try:
            # Wrap sync handlers in async
            if inspect.iscoroutinefunction(handler):
                coro = handler(device_id, message)
            else:
                # Run sync handler
                handler(device_id, message)
                return

            # Apply timeout
            await asyncio.wait_for(coro, timeout=self._handler_timeout)

        except asyncio.TimeoutError:
            logger.error(
                f"Handler timeout for {message_type} "
                f"(device={device_id}, timeout={self._handler_timeout}s)"
            )
        except Exception as e:
            logger.error(
                f"Handler error for {message_type} (device={device_id}): {e}"
            )
