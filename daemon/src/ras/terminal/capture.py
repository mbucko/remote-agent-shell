"""Terminal output capture via tmux pipe-pane."""

import asyncio
import logging
import os
import stat
import tempfile
from pathlib import Path
from typing import Callable, Optional

logger = logging.getLogger(__name__)


class OutputCapture:
    """Captures output from a tmux session via pipe-pane.

    Uses a FIFO (named pipe) to capture output from tmux pipe-pane command.
    The captured output is chunked and delivered via callback.
    """

    def __init__(
        self,
        session_id: str,
        tmux_name: str,
        on_output: Callable[[bytes], None],
        chunk_interval_ms: int = 50,
        max_chunk_size: int = 4096,
        tmux_path: str = "tmux",
        socket_path: Optional[str] = None,
    ):
        """Initialize the output capture.

        Args:
            session_id: Unique session identifier.
            tmux_name: The tmux session name to capture from.
            on_output: Callback for captured output chunks.
            chunk_interval_ms: Maximum time between emitting chunks.
            max_chunk_size: Maximum size of a single output chunk.
            tmux_path: Path to tmux binary.
            socket_path: Optional tmux socket path for isolated server.
        """
        self._session_id = session_id
        self._tmux_name = tmux_name
        self._on_output = on_output
        self._chunk_interval = chunk_interval_ms / 1000.0
        self._max_chunk_size = max_chunk_size
        self._tmux_path = tmux_path
        self._socket_path = socket_path

        self._fifo_path: Optional[Path] = None
        self._running = False
        self._task: Optional[asyncio.Task] = None

    @property
    def is_running(self) -> bool:
        """Return True if capture is currently running."""
        return self._running

    async def start(self) -> None:
        """Start capturing output.

        Raises:
            RuntimeError: If pipe-pane setup fails.
        """
        if self._running:
            return

        # Create FIFO in secure temp directory
        temp_dir = Path(tempfile.gettempdir()) / "ras"
        temp_dir.mkdir(mode=0o700, exist_ok=True)

        self._fifo_path = temp_dir / f"term-{self._session_id}.fifo"

        # Remove existing FIFO if present
        if self._fifo_path.exists():
            self._fifo_path.unlink()

        # Create FIFO with restricted permissions
        os.mkfifo(self._fifo_path, mode=stat.S_IRUSR | stat.S_IWUSR)

        # Setup tmux pipe-pane
        args = self._build_tmux_args(
            "pipe-pane", "-t", self._tmux_name, "-o", f"cat >> {self._fifo_path}"
        )
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate()

        if proc.returncode != 0:
            self._cleanup_fifo()
            raise RuntimeError(f"pipe-pane failed: {stderr.decode()}")

        self._running = True
        self._task = asyncio.create_task(self._read_loop())

    async def stop(self) -> None:
        """Stop capturing output."""
        if not self._running:
            return

        self._running = False

        # Disable pipe-pane
        args = self._build_tmux_args("pipe-pane", "-t", self._tmux_name)
        await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )

        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

        self._cleanup_fifo()

    def _build_tmux_args(self, *args: str) -> tuple[str, ...]:
        """Build tmux command with socket path if configured."""
        if self._socket_path:
            return (self._tmux_path, "-S", self._socket_path, *args)
        return (self._tmux_path, *args)

    def _cleanup_fifo(self) -> None:
        """Remove the FIFO file."""
        if self._fifo_path and self._fifo_path.exists():
            try:
                self._fifo_path.unlink()
            except OSError:
                pass

    async def _read_loop(self) -> None:
        """Read from FIFO and emit chunks."""
        try:
            # Open FIFO in non-blocking mode
            fd = os.open(self._fifo_path, os.O_RDONLY | os.O_NONBLOCK)
            try:
                buffer = bytearray()
                last_emit = asyncio.get_event_loop().time()

                while self._running:
                    try:
                        data = os.read(fd, self._max_chunk_size)
                        if data:
                            buffer.extend(data)
                    except BlockingIOError:
                        pass  # No data available

                    now = asyncio.get_event_loop().time()
                    should_emit = len(buffer) >= self._max_chunk_size or (
                        buffer and now - last_emit >= self._chunk_interval
                    )

                    if should_emit and buffer:
                        self._on_output(bytes(buffer))
                        buffer.clear()
                        last_emit = now

                    await asyncio.sleep(0.01)  # Small sleep to prevent busy loop

            finally:
                os.close(fd)

        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.error(f"Output capture error for {self._session_id}: {e}")
            raise
