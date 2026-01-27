"""Output streamer for buffered, throttled output delivery."""

import asyncio
import logging
from typing import Awaitable, Callable, Optional

logger = logging.getLogger(__name__)


class OutputStreamer:
    """Buffers and throttles output for efficient delivery.

    Batches rapid writes within a throttle window to avoid sending
    too many small updates over the network.
    """

    def __init__(
        self,
        callback: Callable[[bytes], Awaitable[None]],
        throttle_ms: int = 50,
    ):
        """Initialize output streamer.

        Args:
            callback: Async function to call with buffered output.
            throttle_ms: Minimum interval between sends in milliseconds.
        """
        self._callback = callback
        self._throttle_ms = throttle_ms
        self._buffer = bytearray()
        self._flush_task: Optional[asyncio.Task] = None
        self._flush_event = asyncio.Event()
        self._running = False
        self._lock = asyncio.Lock()

    async def start(self) -> None:
        """Start the output streamer."""
        self._running = True
        self._buffer.clear()
        self._flush_event.clear()
        self._flush_task = asyncio.create_task(self._flush_loop())

    async def stop(self) -> None:
        """Stop the streamer and flush remaining buffer."""
        self._running = False

        # Flush any remaining data
        await self.flush()

        if self._flush_task:
            self._flush_task.cancel()
            try:
                await self._flush_task
            except asyncio.CancelledError:
                pass
            self._flush_task = None

    def write(self, data: bytes) -> None:
        """Write data to the buffer.

        Args:
            data: Data to buffer.
        """
        self._buffer.extend(data)
        self._flush_event.set()

    async def flush(self) -> None:
        """Flush buffer immediately."""
        async with self._lock:
            if not self._buffer:
                return

            data = bytes(self._buffer)
            self._buffer.clear()

        await self._send(data)

    async def _send(self, data: bytes) -> None:
        """Send data via callback.

        Args:
            data: Data to send.
        """
        try:
            await self._callback(data)
        except Exception as e:
            logger.error(f"Output callback error: {e}")

    async def _flush_loop(self) -> None:
        """Background loop that flushes buffer at throttle interval."""
        try:
            while self._running:
                # Wait for data or timeout
                try:
                    await asyncio.wait_for(
                        self._flush_event.wait(),
                        timeout=self._throttle_ms / 1000.0,
                    )
                except asyncio.TimeoutError:
                    pass

                self._flush_event.clear()

                # Flush if we have data
                async with self._lock:
                    if self._buffer:
                        data = bytes(self._buffer)
                        self._buffer.clear()
                    else:
                        continue

                await self._send(data)

                # Wait for throttle interval before next send
                await asyncio.sleep(self._throttle_ms / 1000.0)

        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.error(f"Flush loop error: {e}")
