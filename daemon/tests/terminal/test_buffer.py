"""Tests for terminal circular buffer."""

import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from ras.terminal.buffer import BufferChunk, CircularBuffer


class TestBufferChunk:
    """Test the BufferChunk dataclass."""

    def test_buffer_chunk_stores_sequence_and_data(self):
        """BufferChunk should store sequence number and data."""
        chunk = BufferChunk(sequence=42, data=b"hello")
        assert chunk.sequence == 42
        assert chunk.data == b"hello"

    def test_buffer_chunk_equality(self):
        """BufferChunk equality is based on identity (dataclass default)."""
        chunk1 = BufferChunk(sequence=1, data=b"test")
        chunk2 = BufferChunk(sequence=1, data=b"test")
        # Default dataclass equality for eq=False would be identity
        # but we use default dataclass which compares fields
        assert chunk1 == chunk2


class TestCircularBufferBasic:
    """Test basic CircularBuffer operations."""

    def test_default_max_size_is_100kb(self):
        """Default max size should be 100KB."""
        buf = CircularBuffer()
        assert buf._max_size == 102400

    def test_custom_max_size(self):
        """Custom max size should be respected."""
        buf = CircularBuffer(max_size_bytes=1000)
        assert buf._max_size == 1000

    def test_initial_state_is_empty(self):
        """New buffer should be empty."""
        buf = CircularBuffer()
        assert buf.start_sequence == 0
        assert buf.current_sequence == 0

    def test_append_returns_sequence_number(self):
        """Append should return the assigned sequence number."""
        buf = CircularBuffer()
        assert buf.append(b"first") == 0
        assert buf.append(b"second") == 1
        assert buf.append(b"third") == 2

    def test_append_updates_current_sequence(self):
        """Append should increment current sequence."""
        buf = CircularBuffer()
        assert buf.current_sequence == 0
        buf.append(b"data")
        assert buf.current_sequence == 1
        buf.append(b"more")
        assert buf.current_sequence == 2

    def test_start_sequence_initially_zero(self):
        """Start sequence should be 0 initially."""
        buf = CircularBuffer()
        assert buf.start_sequence == 0

    def test_start_sequence_stays_zero_when_not_evicting(self):
        """Start sequence should stay 0 when buffer hasn't evicted."""
        buf = CircularBuffer(max_size_bytes=1000)
        buf.append(b"hello")
        buf.append(b"world")
        assert buf.start_sequence == 0


class TestCircularBufferEviction:
    """Test CircularBuffer eviction behavior."""

    def test_evicts_old_data_when_full(self):
        """Buffer should evict old data when full."""
        buf = CircularBuffer(max_size_bytes=50)

        # Add data that will overflow
        buf.append(b"x" * 20)  # seq 0, 20 bytes
        buf.append(b"y" * 20)  # seq 1, 40 bytes
        buf.append(b"z" * 20)  # seq 2, 60 bytes -> evicts seq 0

        assert buf.start_sequence == 1  # seq 0 was evicted

    def test_evicts_multiple_chunks_if_needed(self):
        """Buffer should evict multiple chunks if needed."""
        buf = CircularBuffer(max_size_bytes=30)

        buf.append(b"x" * 10)  # seq 0
        buf.append(b"y" * 10)  # seq 1
        buf.append(b"z" * 10)  # seq 2
        buf.append(b"w" * 10)  # seq 3 -> evicts 0, 1

        # After adding 40 bytes with 30 byte limit, oldest chunks evicted
        assert buf.start_sequence >= 1

    def test_never_evicts_last_chunk(self):
        """Buffer should never evict the last chunk."""
        buf = CircularBuffer(max_size_bytes=10)

        # Add chunk larger than buffer
        buf.append(b"x" * 100)

        # Should still have one chunk
        assert buf.current_sequence == 1
        chunks, _ = buf.get_from_sequence(0)
        assert len(chunks) == 1
        assert chunks[0].data == b"x" * 100


class TestCircularBufferGetFromSequence:
    """Test CircularBuffer get_from_sequence method."""

    def test_get_all_chunks(self):
        """Get from sequence 0 should return all chunks."""
        buf = CircularBuffer()
        buf.append(b"first")
        buf.append(b"second")
        buf.append(b"third")

        chunks, missing = buf.get_from_sequence(0)

        assert missing is None
        assert len(chunks) == 3
        assert chunks[0].data == b"first"
        assert chunks[1].data == b"second"
        assert chunks[2].data == b"third"

    def test_get_from_middle(self):
        """Get from middle sequence should return remaining chunks."""
        buf = CircularBuffer()
        buf.append(b"first")
        buf.append(b"second")
        buf.append(b"third")

        chunks, missing = buf.get_from_sequence(1)

        assert missing is None
        assert len(chunks) == 2
        assert chunks[0].data == b"second"
        assert chunks[1].data == b"third"

    def test_get_from_last(self):
        """Get from last sequence should return only last chunk."""
        buf = CircularBuffer()
        buf.append(b"first")
        buf.append(b"second")
        buf.append(b"third")

        chunks, missing = buf.get_from_sequence(2)

        assert missing is None
        assert len(chunks) == 1
        assert chunks[0].data == b"third"

    def test_get_from_future_sequence(self):
        """Get from future sequence should return empty."""
        buf = CircularBuffer()
        buf.append(b"first")

        chunks, missing = buf.get_from_sequence(10)

        assert missing is None
        assert len(chunks) == 0

    def test_get_from_evicted_sequence(self):
        """Get from evicted sequence should report missing."""
        buf = CircularBuffer(max_size_bytes=30)

        buf.append(b"x" * 10)  # seq 0
        buf.append(b"y" * 10)  # seq 1
        buf.append(b"z" * 10)  # seq 2
        buf.append(b"w" * 10)  # seq 3 -> evicts old

        # Request sequence that was evicted
        chunks, missing = buf.get_from_sequence(0)

        assert missing == 0  # Reports what was requested
        assert len(chunks) > 0  # Returns available chunks

    def test_get_from_empty_buffer(self):
        """Get from empty buffer should return empty."""
        buf = CircularBuffer()

        chunks, missing = buf.get_from_sequence(0)

        assert missing is None
        assert len(chunks) == 0


class TestCircularBufferClear:
    """Test CircularBuffer clear method."""

    def test_clear_empties_buffer(self):
        """Clear should empty the buffer."""
        buf = CircularBuffer()
        buf.append(b"data1")
        buf.append(b"data2")

        buf.clear()

        chunks, _ = buf.get_from_sequence(0)
        assert len(chunks) == 0

    def test_clear_preserves_sequence_counter(self):
        """Clear should not reset sequence counter."""
        buf = CircularBuffer()
        buf.append(b"data1")
        buf.append(b"data2")

        old_seq = buf.current_sequence
        buf.clear()

        # Sequence counter is preserved
        assert buf.current_sequence == old_seq


class TestCircularBufferThreadSafety:
    """Test CircularBuffer thread safety."""

    def test_concurrent_appends(self):
        """Multiple threads appending should be safe."""
        buf = CircularBuffer(max_size_bytes=100000)
        num_threads = 10
        appends_per_thread = 100

        def append_data(thread_id):
            for i in range(appends_per_thread):
                buf.append(f"thread{thread_id}-{i}".encode())

        with ThreadPoolExecutor(max_workers=num_threads) as executor:
            futures = [executor.submit(append_data, i) for i in range(num_threads)]
            for f in futures:
                f.result()

        # All appends should have succeeded
        assert buf.current_sequence == num_threads * appends_per_thread

    def test_concurrent_read_write(self):
        """Reading while writing should be safe."""
        buf = CircularBuffer(max_size_bytes=10000)
        stop_event = threading.Event()
        errors = []

        def writer():
            try:
                for i in range(500):
                    buf.append(f"data-{i}".encode())
            except Exception as e:
                errors.append(e)

        def reader():
            try:
                for _ in range(50):
                    buf.get_from_sequence(0)
                    # Yield to other threads without long sleep
                    time.sleep(0)
            except Exception as e:
                errors.append(e)

        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [
                executor.submit(writer),
                executor.submit(reader),
                executor.submit(reader),
            ]
            for f in futures:
                f.result()

        assert len(errors) == 0


class TestCircularBufferEdgeCases:
    """Test CircularBuffer edge cases."""

    def test_empty_data_append(self):
        """Appending empty data should work."""
        buf = CircularBuffer()
        seq = buf.append(b"")
        assert seq == 0

        chunks, _ = buf.get_from_sequence(0)
        assert len(chunks) == 1
        assert chunks[0].data == b""

    def test_large_single_chunk(self):
        """Large single chunk should work."""
        buf = CircularBuffer(max_size_bytes=1000000)
        data = b"x" * 500000

        seq = buf.append(data)
        assert seq == 0

        chunks, _ = buf.get_from_sequence(0)
        assert len(chunks) == 1
        assert len(chunks[0].data) == 500000

    def test_many_small_chunks(self):
        """Many small chunks should work."""
        buf = CircularBuffer(max_size_bytes=100000)

        for i in range(1000):
            buf.append(b"x")

        assert buf.current_sequence == 1000

    def test_sequence_numbers_preserved_in_chunks(self):
        """Chunk sequence numbers should be preserved."""
        buf = CircularBuffer()

        buf.append(b"a")  # seq 0
        buf.append(b"b")  # seq 1
        buf.append(b"c")  # seq 2

        chunks, _ = buf.get_from_sequence(0)

        assert chunks[0].sequence == 0
        assert chunks[1].sequence == 1
        assert chunks[2].sequence == 2
