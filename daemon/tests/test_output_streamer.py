"""Tests for OutputStreamer."""

import asyncio
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from ras.output_streamer import OutputStreamer


class TestOutputStreamerCreation:
    """Test OutputStreamer creation."""

    def test_create_with_callback(self):
        """Can create OutputStreamer with callback."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback)
        assert streamer._callback is callback

    def test_default_throttle_interval(self):
        """Default throttle interval is 50ms."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback)
        assert streamer._throttle_ms == 50

    def test_custom_throttle_interval(self):
        """Can set custom throttle interval."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback, throttle_ms=100)
        assert streamer._throttle_ms == 100


class TestOutputStreamerBuffer:
    """Test OutputStreamer buffering behavior."""

    @pytest.mark.asyncio
    async def test_write_buffers_data(self):
        """write() buffers data without immediate callback."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback, throttle_ms=1000)

        await streamer.start()
        streamer.write(b"hello")

        # Callback not called immediately
        assert callback.call_count == 0

        await streamer.stop()

    @pytest.mark.asyncio
    async def test_multiple_writes_accumulate(self):
        """Multiple writes accumulate in buffer."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback, throttle_ms=1000)

        await streamer.start()
        streamer.write(b"hello")
        streamer.write(b" ")
        streamer.write(b"world")

        # Force flush
        await streamer.flush()

        # Should receive concatenated data
        callback.assert_called_once_with(b"hello world")

        await streamer.stop()


class TestOutputStreamerThrottle:
    """Test OutputStreamer throttling behavior."""

    @pytest.mark.asyncio
    async def test_throttle_batches_rapid_writes(self):
        """Rapid writes are batched within throttle window."""
        callback = AsyncMock()

        # Mock asyncio.sleep in the output_streamer module to run instantly
        with patch("ras.output_streamer.asyncio.sleep", new_callable=AsyncMock):
            streamer = OutputStreamer(callback=callback, throttle_ms=50)

            await streamer.start()

            # Write rapidly
            for i in range(10):
                streamer.write(f"line{i}\n".encode())

            # Force flush to ensure all data is sent
            await streamer.flush()

            # Should have batched into fewer calls than writes
            # With mocked sleep, all writes happen before any throttle delay
            # so they should all be batched into one call
            assert callback.call_count <= 2  # Initial flush + final flush

            await streamer.stop()

    @pytest.mark.asyncio
    async def test_flush_sends_immediately(self):
        """flush() sends buffer immediately."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback, throttle_ms=1000)

        await streamer.start()
        streamer.write(b"urgent")
        await streamer.flush()

        callback.assert_called_once_with(b"urgent")

        await streamer.stop()

    @pytest.mark.asyncio
    async def test_flush_noop_on_empty_buffer(self):
        """flush() is no-op when buffer is empty."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback, throttle_ms=50)

        await streamer.start()
        await streamer.flush()

        callback.assert_not_called()

        await streamer.stop()


class TestOutputStreamerLifecycle:
    """Test OutputStreamer lifecycle."""

    @pytest.mark.asyncio
    async def test_stop_flushes_remaining(self):
        """stop() flushes remaining buffer."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback, throttle_ms=1000)

        await streamer.start()
        streamer.write(b"remaining")
        await streamer.stop()

        # Should have flushed on stop
        callback.assert_called_with(b"remaining")

    @pytest.mark.asyncio
    async def test_can_restart_after_stop(self):
        """Can restart streamer after stop."""
        callback = AsyncMock()
        streamer = OutputStreamer(callback=callback, throttle_ms=50)

        await streamer.start()
        streamer.write(b"first")
        await streamer.stop()

        await streamer.start()
        streamer.write(b"second")
        await streamer.flush()

        # Last call should be "second"
        assert callback.call_args[0][0] == b"second"

        await streamer.stop()


class TestOutputStreamerErrors:
    """Test OutputStreamer error handling."""

    @pytest.mark.asyncio
    async def test_callback_error_does_not_stop_streamer(self):
        """Callback errors don't stop the streamer."""
        callback = AsyncMock(side_effect=Exception("callback failed"))
        streamer = OutputStreamer(callback=callback, throttle_ms=10)

        await streamer.start()
        streamer.write(b"data1")
        await streamer.flush()

        # Should still be able to write more
        streamer.write(b"data2")
        await streamer.flush()

        # Callback was called multiple times despite errors
        assert callback.call_count >= 1

        await streamer.stop()
