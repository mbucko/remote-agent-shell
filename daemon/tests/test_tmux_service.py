"""Tests for TmuxService."""

import pytest
from unittest.mock import AsyncMock

from ras.errors import TmuxError, TmuxNotFoundError, TmuxVersionError, TmuxSessionError
from ras.protocols import TmuxSessionInfo, TmuxWindowInfo
from ras.tmux import TmuxService, AsyncCommandExecutor


class MockCommandExecutor:
    """Mock command executor for testing."""

    def __init__(self):
        self.responses: dict[str, tuple[bytes, bytes, int]] = {}
        self.calls: list[tuple[str, ...]] = []
        self.raise_exception: Exception | None = None

    def add_response(
        self,
        command_contains: str,
        stdout: str = "",
        stderr: str = "",
        returncode: int = 0,
    ):
        """Add a canned response for commands containing a string."""
        self.responses[command_contains] = (
            stdout.encode(),
            stderr.encode(),
            returncode,
        )

    async def run(
        self, *args: str, check: bool = True
    ) -> tuple[bytes, bytes, int]:
        """Run command and return canned response."""
        self.calls.append(args)

        if self.raise_exception:
            raise self.raise_exception

        for key, response in self.responses.items():
            if key in " ".join(args):
                stdout, stderr, returncode = response
                if check and returncode != 0:
                    raise TmuxError(f"Command failed: {stderr.decode()}")
                return response

        # Default: success with empty output
        return b"", b"", 0


class TestTmuxServiceCreation:
    """Test TmuxService creation."""

    def test_create_with_executor(self):
        """Can create TmuxService with injected executor."""
        executor = MockCommandExecutor()
        service = TmuxService(executor=executor)
        assert service._executor is executor

    def test_create_default_executor(self):
        """Creates default executor if none provided."""
        service = TmuxService()
        assert isinstance(service._executor, AsyncCommandExecutor)

    def test_default_tmux_path(self):
        """Default tmux path is 'tmux'."""
        service = TmuxService()
        assert service._tmux_path == "tmux"

    def test_custom_tmux_path(self):
        """Can set custom tmux path."""
        service = TmuxService(tmux_path="/usr/local/bin/tmux")
        assert service._tmux_path == "/usr/local/bin/tmux"


class TestTmuxVersionCheck:
    """Test tmux version verification."""

    @pytest.mark.asyncio
    async def test_verify_passes_for_valid_version(self):
        """verify() passes for tmux >= 2.9."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        service = TmuxService(executor=executor)

        await service.verify()  # Should not raise

    @pytest.mark.asyncio
    async def test_verify_fails_for_old_version(self):
        """verify() raises TmuxVersionError for old tmux."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 2.8")
        service = TmuxService(executor=executor)

        with pytest.raises(TmuxVersionError, match="too old"):
            await service.verify()

    @pytest.mark.asyncio
    async def test_verify_parses_version_correctly(self):
        """verify() correctly parses various version formats."""
        test_cases = [
            ("tmux 2.9", True),
            ("tmux 2.8", False),
            ("tmux 3.0", True),
            ("tmux 3.4", True),
            ("tmux next-3.5", True),  # Development version
        ]

        for version_str, should_pass in test_cases:
            executor = MockCommandExecutor()
            executor.add_response("-V", version_str)
            service = TmuxService(executor=executor)

            if should_pass:
                await service.verify()
            else:
                with pytest.raises(TmuxVersionError):
                    await service.verify()

    @pytest.mark.asyncio
    async def test_verify_caches_result(self):
        """verify() only checks version once."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        service = TmuxService(executor=executor)

        await service.verify()
        await service.verify()
        await service.verify()

        # Should only call -V once
        version_calls = [c for c in executor.calls if "-V" in c]
        assert len(version_calls) == 1

    @pytest.mark.asyncio
    async def test_verify_raises_on_parse_error(self):
        """verify() raises TmuxError if version can't be parsed."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "not a version")
        service = TmuxService(executor=executor)

        with pytest.raises(TmuxError, match="version"):
            await service.verify()


class TestListSessions:
    """Test listing tmux sessions."""

    @pytest.mark.asyncio
    async def test_list_sessions_returns_sessions(self):
        """list_sessions() returns list of TmuxSessionInfo."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "list-sessions",
            "$0:claude:2:0\n$1:aider:1:1\n",
        )
        service = TmuxService(executor=executor)

        sessions = await service.list_sessions()

        assert len(sessions) == 2
        assert sessions[0] == TmuxSessionInfo(
            id="$0", name="claude", windows=2, attached=False
        )
        assert sessions[1] == TmuxSessionInfo(
            id="$1", name="aider", windows=1, attached=True
        )

    @pytest.mark.asyncio
    async def test_list_sessions_empty_when_no_server(self):
        """list_sessions() returns empty list when no tmux server."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "list-sessions",
            stdout="",
            stderr="no server running on /tmp/tmux-1000/default",
            returncode=1,
        )
        service = TmuxService(executor=executor)

        sessions = await service.list_sessions()

        assert sessions == []

    @pytest.mark.asyncio
    async def test_list_sessions_empty_when_no_sessions(self):
        """list_sessions() returns empty list when no sessions exist."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "list-sessions",
            stdout="",
            stderr="no sessions",
            returncode=1,
        )
        service = TmuxService(executor=executor)

        sessions = await service.list_sessions()

        assert sessions == []

    @pytest.mark.asyncio
    async def test_list_sessions_builds_correct_command(self):
        """list_sessions() builds correct tmux command."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("list-sessions", "")
        service = TmuxService(executor=executor)

        await service.list_sessions()

        list_call = [c for c in executor.calls if "list-sessions" in c][0]
        assert "list-sessions" in list_call
        assert "-F" in list_call


class TestListWindows:
    """Test listing windows in a session."""

    @pytest.mark.asyncio
    async def test_list_windows_returns_windows(self):
        """list_windows() returns list of TmuxWindowInfo."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "list-windows",
            "@0:main:1\n@1:build:0\n",
        )
        service = TmuxService(executor=executor)

        windows = await service.list_windows("$0")

        assert len(windows) == 2
        assert windows[0] == TmuxWindowInfo(id="@0", name="main", active=True)
        assert windows[1] == TmuxWindowInfo(id="@1", name="build", active=False)

    @pytest.mark.asyncio
    async def test_list_windows_includes_session_target(self):
        """list_windows() targets the correct session."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("list-windows", "")
        service = TmuxService(executor=executor)

        await service.list_windows("$0")

        list_call = [c for c in executor.calls if "list-windows" in c][0]
        assert "-t" in list_call
        assert "$0" in list_call


class TestGetSessionSize:
    """Test getting session terminal size."""

    @pytest.mark.asyncio
    async def test_get_session_size_returns_tuple(self):
        """get_session_size() returns (rows, cols) tuple."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("display-message", "24:80")
        service = TmuxService(executor=executor)

        size = await service.get_session_size("$0")

        assert size == (24, 80)

    @pytest.mark.asyncio
    async def test_get_session_size_targets_session(self):
        """get_session_size() targets the correct session."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("display-message", "24:80")
        service = TmuxService(executor=executor)

        await service.get_session_size("$0")

        display_call = [c for c in executor.calls if "display-message" in c][0]
        assert "-t" in display_call
        assert "$0" in display_call


class TestResizeSession:
    """Test resizing session terminal."""

    @pytest.mark.asyncio
    async def test_resize_session_builds_correct_command(self):
        """resize_session() builds correct resize command."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("resize-window", "")
        service = TmuxService(executor=executor)

        await service.resize_session("$0", rows=40, cols=100)

        resize_call = [c for c in executor.calls if "resize-window" in c][0]
        assert "-t" in resize_call
        assert "$0" in resize_call
        assert "-x" in resize_call
        assert "100" in resize_call
        assert "-y" in resize_call
        assert "40" in resize_call


class TestSendKeys:
    """Test sending keys to tmux."""

    @pytest.mark.asyncio
    async def test_send_keys_builds_correct_command(self):
        """send_keys() builds correct send-keys command."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("send-keys", "")
        service = TmuxService(executor=executor)

        await service.send_keys("$0", "echo hello\n")

        send_call = [c for c in executor.calls if "send-keys" in c][0]
        assert "send-keys" in send_call
        assert "-t" in send_call
        assert "$0" in send_call
        assert "echo hello\n" in send_call

    @pytest.mark.asyncio
    async def test_send_keys_literal_mode(self):
        """send_keys() can send literal keys."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("send-keys", "")
        service = TmuxService(executor=executor)

        await service.send_keys("$0", "C-c", literal=False)

        send_call = [c for c in executor.calls if "send-keys" in c][0]
        assert "C-c" in send_call
        # Should NOT have -l flag for special keys
        assert "-l" not in send_call

    @pytest.mark.asyncio
    async def test_send_keys_with_literal_flag(self):
        """send_keys() uses -l for literal text."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("send-keys", "")
        service = TmuxService(executor=executor)

        await service.send_keys("$0", "hello", literal=True)

        send_call = [c for c in executor.calls if "send-keys" in c][0]
        assert "-l" in send_call


class TestSwitchWindow:
    """Test switching windows."""

    @pytest.mark.asyncio
    async def test_switch_window_builds_correct_command(self):
        """switch_window() builds correct select-window command."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("select-window", "")
        service = TmuxService(executor=executor)

        await service.switch_window("$0", "@1")

        select_call = [c for c in executor.calls if "select-window" in c][0]
        assert "select-window" in select_call
        assert "-t" in select_call
        assert "$0:@1" in select_call


class TestCreateSession:
    """Test session creation."""

    @pytest.mark.asyncio
    async def test_create_session_sets_window_size_latest(self):
        """create_session() should set window-size to latest."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("new-session", "$0")
        executor.add_response("set-option", "")
        service = TmuxService(executor=executor)

        await service.create_session("test-session")

        # Should have called set-option with window-size latest
        set_calls = [c for c in executor.calls if "set-option" in c]
        assert len(set_calls) == 1
        set_call = set_calls[0]
        assert "-t" in set_call
        assert "test-session" in set_call
        assert "window-size" in set_call
        assert "latest" in set_call

    @pytest.mark.asyncio
    async def test_create_session_returns_session_id(self):
        """create_session() should return the session ID."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("new-session", "$0")
        executor.add_response("set-option", "")
        service = TmuxService(executor=executor)

        session_id = await service.create_session("test-session")

        assert session_id == "$0"


class TestCapturePane:
    """Test capturing pane content."""

    @pytest.mark.asyncio
    async def test_capture_pane_returns_lines(self):
        """capture_pane() returns list of lines."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "capture-pane",
            "$ echo hello\nhello\n$ \n",
        )
        service = TmuxService(executor=executor)

        lines = await service.capture_pane("$0", lines=100)

        assert "$ echo hello" in lines
        assert "hello" in lines

    @pytest.mark.asyncio
    async def test_capture_pane_builds_correct_command(self):
        """capture_pane() builds correct capture-pane command."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("capture-pane", "")
        service = TmuxService(executor=executor)

        await service.capture_pane("$0", lines=100)

        capture_call = [c for c in executor.calls if "capture-pane" in c][0]
        assert "capture-pane" in capture_call
        assert "-t" in capture_call
        assert "$0" in capture_call
        assert "-p" in capture_call  # Print to stdout
        assert "-S" in capture_call  # Start line
        assert "-100" in capture_call


class TestSetWindowSizeLatest:
    """Test set_window_size_latest functionality."""

    @pytest.mark.asyncio
    async def test_set_window_size_latest_calls_correct_command(self):
        """set_window_size_latest should call tmux set-option window-size latest."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("set-option", "")
        service = TmuxService(executor=executor)

        await service.set_window_size_latest("ras-test-session")

        set_call = [c for c in executor.calls if "set-option" in c][0]
        assert "set-option" in set_call
        assert "-t" in set_call
        assert "ras-test-session" in set_call
        assert "window-size" in set_call
        assert "latest" in set_call

    @pytest.mark.asyncio
    async def test_set_window_size_latest_handles_error(self):
        """set_window_size_latest should not raise on error."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        service = TmuxService(executor=executor)

        # Verify first, then set exception for subsequent calls
        await service.verify()
        executor.raise_exception = TmuxError("tmux error")

        # Should not raise
        await service.set_window_size_latest("nonexistent-session")

    @pytest.mark.asyncio
    async def test_set_window_size_latest_handles_nonexistent_session(self):
        """set_window_size_latest should handle nonexistent session gracefully."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "set-option",
            stdout="",
            stderr="can't find session: nonexistent",
            returncode=1,
        )
        service = TmuxService(executor=executor)

        # Should not raise
        await service.set_window_size_latest("nonexistent")


class TestAsyncCommandExecutor:
    """Test the real AsyncCommandExecutor."""

    @pytest.mark.asyncio
    async def test_run_returns_output(self):
        """run() returns stdout, stderr, returncode."""
        executor = AsyncCommandExecutor()
        stdout, stderr, code = await executor.run("echo", "hello")
        assert stdout.strip() == b"hello"
        assert code == 0

    @pytest.mark.asyncio
    async def test_run_captures_stderr(self):
        """run() captures stderr."""
        executor = AsyncCommandExecutor()
        stdout, stderr, code = await executor.run(
            "sh", "-c", "echo error >&2"
        )
        assert b"error" in stderr

    @pytest.mark.asyncio
    async def test_run_returns_exit_code(self):
        """run() returns non-zero exit code."""
        executor = AsyncCommandExecutor()
        stdout, stderr, code = await executor.run(
            "sh", "-c", "exit 42", check=False
        )
        assert code == 42
