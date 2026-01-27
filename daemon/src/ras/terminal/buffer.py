"""Circular buffer for terminal output reconnection."""

from collections import deque
from dataclasses import dataclass
from threading import Lock


@dataclass
class BufferChunk:
    """A chunk of buffered data with its sequence number."""

    sequence: int
    data: bytes


class CircularBuffer:
    """Thread-safe circular buffer for terminal output.

    Stores output chunks with sequence numbers to support reconnection.
    When the buffer exceeds max_size_bytes, old chunks are evicted.
    """

    def __init__(self, max_size_bytes: int = 102400):  # 100KB default
        """Initialize the buffer.

        Args:
            max_size_bytes: Maximum buffer size in bytes (default 100KB).
        """
        self._max_size = max_size_bytes
        self._chunks: deque[BufferChunk] = deque()
        self._current_size = 0
        self._next_sequence = 0
        self._lock = Lock()

    @property
    def start_sequence(self) -> int:
        """First sequence number in buffer."""
        with self._lock:
            if self._chunks:
                return self._chunks[0].sequence
            return self._next_sequence

    @property
    def current_sequence(self) -> int:
        """Next sequence number to be assigned."""
        with self._lock:
            return self._next_sequence

    def append(self, data: bytes) -> int:
        """Add data to buffer, returns assigned sequence number.

        Args:
            data: The data to append.

        Returns:
            The sequence number assigned to this chunk.
        """
        with self._lock:
            seq = self._next_sequence
            self._next_sequence += 1

            chunk = BufferChunk(sequence=seq, data=data)
            self._chunks.append(chunk)
            self._current_size += len(data)

            # Evict old chunks if over limit
            while self._current_size > self._max_size and len(self._chunks) > 1:
                old = self._chunks.popleft()
                self._current_size -= len(old.data)

            return seq

    def get_from_sequence(
        self, from_seq: int
    ) -> tuple[list[BufferChunk], int | None]:
        """Get chunks from given sequence.

        Args:
            from_seq: The sequence number to start from.

        Returns:
            Tuple of (chunks, first_missing_seq) where first_missing_seq is
            set if requested sequence is not in buffer (data was dropped).
        """
        with self._lock:
            if not self._chunks:
                return [], None

            start = self._chunks[0].sequence

            # Requested sequence is too old (dropped)
            if from_seq < start:
                return list(self._chunks), from_seq

            # Find starting index
            result = []
            for chunk in self._chunks:
                if chunk.sequence >= from_seq:
                    result.append(chunk)

            return result, None

    def clear(self) -> None:
        """Clear the buffer."""
        with self._lock:
            self._chunks.clear()
            self._current_size = 0
