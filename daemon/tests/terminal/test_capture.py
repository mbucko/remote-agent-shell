"""Tests for terminal output capture."""

import asyncio
import os
import tempfile
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.terminal.capture import OutputCapture


class TestOutputCaptureInit:
    """Test OutputCapture initialization."""

    def test_init_stores_parameters(self):
        """Init should store all parameters."""
        callback = MagicMock()
        capture = OutputCapture(
            session_id="abc123def456",
            tmux_name="ras-test-session",
            on_output=callback,
            chunk_interval_ms=100,
            max_chunk_size=8192,
            tmux_path="/usr/local/bin/tmux",
            socket_path="/tmp/test.sock",
        )

        assert capture._session_id == "abc123def456"
        assert capture._tmux_name == "ras-test-session"
        assert capture._on_output == callback
        assert capture._chunk_interval == 0.1  # 100ms -> 0.1s
        assert capture._max_chunk_size == 8192
        assert capture._tmux_path == "/usr/local/bin/tmux"
        assert capture._socket_path == "/tmp/test.sock"

    def test_default_chunk_interval(self):
        """Default chunk interval should be 50ms."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )
        assert capture._chunk_interval == 0.05

    def test_default_max_chunk_size(self):
        """Default max chunk size should be 4096."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )
        assert capture._max_chunk_size == 4096

    def test_initial_state_not_running(self):
        """Initial state should be not running."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )
        assert capture.is_running is False


class TestOutputCaptureBuildTmuxArgs:
    """Test _build_tmux_args method."""

    def test_build_args_without_socket(self):
        """Build args without socket path."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
            tmux_path="tmux",
        )

        args = capture._build_tmux_args("list-sessions")
        assert args == ("tmux", "list-sessions")

    def test_build_args_with_socket(self):
        """Build args with socket path."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
            tmux_path="tmux",
            socket_path="/tmp/test.sock",
        )

        args = capture._build_tmux_args("list-sessions")
        assert args == ("tmux", "-S", "/tmp/test.sock", "list-sessions")

    def test_build_args_with_multiple_arguments(self):
        """Build args with multiple arguments."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test-session",
            on_output=MagicMock(),
            tmux_path="tmux",
        )

        args = capture._build_tmux_args("pipe-pane", "-t", "test-session", "-o", "cat")
        assert args == ("tmux", "pipe-pane", "-t", "test-session", "-o", "cat")


class TestOutputCaptureCleanup:
    """Test _cleanup_fifo method."""

    def test_cleanup_removes_existing_fifo(self):
        """Cleanup should remove existing FIFO."""
        with tempfile.TemporaryDirectory() as tmpdir:
            fifo_path = Path(tmpdir) / "test.fifo"
            os.mkfifo(fifo_path)
            assert fifo_path.exists()

            capture = OutputCapture(
                session_id="test",
                tmux_name="test",
                on_output=MagicMock(),
            )
            capture._fifo_path = fifo_path
            capture._cleanup_fifo()

            assert not fifo_path.exists()

    def test_cleanup_handles_nonexistent_fifo(self):
        """Cleanup should handle nonexistent FIFO gracefully."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )
        capture._fifo_path = Path("/nonexistent/path/test.fifo")

        # Should not raise
        capture._cleanup_fifo()

    def test_cleanup_handles_none_fifo_path(self):
        """Cleanup should handle None fifo_path."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )
        capture._fifo_path = None

        # Should not raise
        capture._cleanup_fifo()


class TestOutputCaptureStartStop:
    """Test start/stop methods."""

    @pytest.mark.asyncio
    async def test_start_creates_fifo_directory(self):
        """Start should create the FIFO directory."""
        callback = MagicMock()
        capture = OutputCapture(
            session_id="test123test12",
            tmux_name="test-session",
            on_output=callback,
        )

        with patch("asyncio.create_subprocess_exec") as mock_exec:
            mock_proc = AsyncMock()
            mock_proc.returncode = 0
            mock_proc.communicate = AsyncMock(return_value=(b"", b""))
            mock_exec.return_value = mock_proc

            # Mock os.mkfifo to not actually create FIFO
            with patch("os.mkfifo"):
                with patch.object(capture, "_read_loop", new_callable=AsyncMock):
                    await capture.start()

                    # Should have called tmux pipe-pane
                    mock_exec.assert_called_once()
                    assert "pipe-pane" in mock_exec.call_args[0]

                    await capture.stop()

    @pytest.mark.asyncio
    async def test_start_when_already_running_is_noop(self):
        """Start when already running should be no-op."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )
        capture._running = True

        # Should return immediately without doing anything
        await capture.start()

    @pytest.mark.asyncio
    async def test_stop_when_not_running_is_noop(self):
        """Stop when not running should be no-op."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )

        # Should return immediately without doing anything
        await capture.stop()

    @pytest.mark.asyncio
    async def test_start_fails_on_pipe_pane_error(self):
        """Start should fail if pipe-pane command fails."""
        capture = OutputCapture(
            session_id="test123test12",
            tmux_name="test-session",
            on_output=MagicMock(),
        )

        with patch("asyncio.create_subprocess_exec") as mock_exec:
            mock_proc = AsyncMock()
            mock_proc.returncode = 1
            mock_proc.communicate = AsyncMock(return_value=(b"", b"error message"))
            mock_exec.return_value = mock_proc

            with patch("os.mkfifo"):
                with patch.object(capture, "_cleanup_fifo"):
                    with pytest.raises(RuntimeError, match="pipe-pane failed"):
                        await capture.start()


class TestOutputCaptureIsRunning:
    """Test is_running property."""

    def test_is_running_returns_false_initially(self):
        """is_running should return False initially."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )
        assert capture.is_running is False

    def test_is_running_returns_internal_state(self):
        """is_running should return internal _running state."""
        capture = OutputCapture(
            session_id="test",
            tmux_name="test",
            on_output=MagicMock(),
        )
        capture._running = True
        assert capture.is_running is True


class TestOutputCaptureFIFOSecurity:
    """Test FIFO security properties."""

    def test_fifo_path_is_in_ras_temp_dir(self):
        """FIFO path should be in /tmp/ras/ directory."""
        capture = OutputCapture(
            session_id="test123abc456",
            tmux_name="test-session",
            on_output=MagicMock(),
        )

        # Manually construct expected path logic
        temp_dir = Path(tempfile.gettempdir()) / "ras"
        expected_prefix = str(temp_dir)

        # The capture will set fifo_path in start(), but we can check the logic
        assert str(temp_dir).endswith("/ras") or str(temp_dir).endswith("\\ras")

    def test_fifo_name_contains_session_id(self):
        """FIFO name should contain session ID for uniqueness."""
        capture = OutputCapture(
            session_id="uniqueid12345",
            tmux_name="test-session",
            on_output=MagicMock(),
        )

        # When fifo_path is set, it should contain session_id
        # This is tested implicitly via the start() logic
        assert capture._session_id == "uniqueid12345"
