"""End-to-end tests for tmux integration.

These tests verify the full integration of all tmux components:
- TmuxHandler
- TmuxService
- SessionRegistry
- AttachedSession
- ControlModeClient
- OutputStreamer

Only external boundaries are mocked:
- CommandExecutor (simulates tmux CLI responses)
- ControlModeProcess (simulates tmux -CC subprocess)
"""

import asyncio
import base64
import pytest
from unittest.mock import AsyncMock

from ras.tmux import TmuxService
from ras.tmux_handler import TmuxHandler
from ras.session_registry import SessionRegistry
from ras.attached_session import AttachedSession
from ras.control_mode import ControlModeClient
from ras.output_streamer import OutputStreamer
from ras.protocols import OutputEvent, ExitEvent
from ras.errors import TmuxError


class MockCommandExecutor:
    """Mock command executor simulating tmux CLI responses."""

    def __init__(self):
        self.responses: dict[str, tuple[bytes, bytes, int]] = {}
        self.calls: list[tuple[str, ...]] = []

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

        for key, response in self.responses.items():
            if key in " ".join(args):
                stdout, stderr, returncode = response
                if check and returncode != 0:
                    raise TmuxError(f"Command failed: {stderr.decode()}")
                return response

        # Default: success with empty output
        return b"", b"", 0


class MockControlModeProcess:
    """Mock control mode process simulating tmux -CC subprocess."""

    def __init__(self):
        self.lines: list[bytes] = []
        self._index = 0
        self._running = False

    def add_line(self, line: str):
        """Add a line to be returned by readline."""
        self.lines.append(line.encode() + b"\n")

    def add_output(self, pane_id: str, data: bytes):
        """Add output event line."""
        b64_data = base64.b64encode(data).decode()
        self.add_line(f"%output {pane_id} {b64_data}")

    async def start(self) -> None:
        self._running = True

    async def stop(self) -> None:
        self._running = False

    async def readline(self) -> bytes:
        if self._index >= len(self.lines):
            self._running = False
            return b""
        line = self.lines[self._index]
        self._index += 1
        return line

    def is_running(self) -> bool:
        return self._running


class TestE2EListSessions:
    """E2E tests for listing sessions."""

    @pytest.mark.asyncio
    async def test_list_sessions_full_flow(self):
        """Full flow: Handler -> Service -> Executor."""
        # Setup mock executor with tmux responses
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "list-sessions",
            "$0:dev:3:0\n$1:prod:1:1\n",
        )

        # Create real components with mocked executor
        service = TmuxService(executor=executor)
        registry = SessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        # Execute
        sessions = await handler.list_sessions()

        # Verify
        assert len(sessions) == 2
        assert sessions[0]["id"] == "$0"
        assert sessions[0]["name"] == "dev"
        assert sessions[0]["windows"] == 3
        assert sessions[0]["attached"] is False
        assert sessions[1]["id"] == "$1"
        assert sessions[1]["name"] == "prod"
        assert sessions[1]["attached"] is True


class TestE2ESendKeys:
    """E2E tests for sending keys."""

    @pytest.mark.asyncio
    async def test_send_keys_full_flow(self):
        """Full flow: Handler -> Service -> Executor."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response("send-keys", "")

        service = TmuxService(executor=executor)
        registry = SessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        # Execute
        await handler.send_keys("$0", "echo hello\n")

        # Verify the command was called correctly
        send_calls = [c for c in executor.calls if "send-keys" in c]
        assert len(send_calls) == 1
        assert "send-keys" in send_calls[0]
        assert "-t" in send_calls[0]
        assert "$0" in send_calls[0]
        assert "echo hello\n" in send_calls[0]


class TestE2EAttachedSession:
    """E2E tests for attached sessions with output streaming."""

    @pytest.mark.asyncio
    async def test_attached_session_streams_output(self):
        """Full flow: AttachedSession -> ControlClient -> OutputStreamer."""
        # Mock control mode process that sends output
        process = MockControlModeProcess()
        process.add_output("%0", b"$ echo hello\n")
        process.add_output("%0", b"hello\n")
        process.add_output("%0", b"$ ")
        process.add_line("%exit")

        # Track output received
        received_output = []

        async def output_callback(data: bytes):
            received_output.append(data)

        # Create real components with mocked process
        client = ControlModeClient(session_id="$0", process=process)
        streamer = OutputStreamer(callback=output_callback, throttle_ms=10)

        session = AttachedSession(
            session_id="$0",
            output_callback=output_callback,
            control_client=client,
            output_streamer=streamer,
        )

        # Execute
        async with session:
            await session.wait()

        # Verify output was streamed
        all_output = b"".join(received_output)
        assert b"echo hello" in all_output
        assert b"hello" in all_output

    @pytest.mark.asyncio
    async def test_attached_session_handles_window_events(self):
        """Session handles window events without errors."""
        process = MockControlModeProcess()
        process.add_line("%window-add @1")
        process.add_line("%window-renamed @1 new-window")
        process.add_line("%window-close @1")
        process.add_line("%exit")

        async def output_callback(data: bytes):
            pass

        client = ControlModeClient(session_id="$0", process=process)
        streamer = OutputStreamer(callback=output_callback, throttle_ms=10)

        session = AttachedSession(
            session_id="$0",
            output_callback=output_callback,
            control_client=client,
            output_streamer=streamer,
        )

        # Should complete without errors
        async with session:
            await session.wait()


class TestE2EFullWorkflow:
    """E2E tests for complete workflows."""

    @pytest.mark.asyncio
    async def test_list_then_interact_workflow(self):
        """Full workflow: list sessions, then interact with one."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "list-sessions",
            "$0:main:2:0\n",
        )
        executor.add_response(
            "list-windows",
            "@0:editor:1\n@1:terminal:0\n",
        )
        executor.add_response("send-keys", "")
        executor.add_response("display-message", "24:80")
        executor.add_response("resize-window", "")

        service = TmuxService(executor=executor)
        registry = SessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        # List sessions
        sessions = await handler.list_sessions()
        assert len(sessions) == 1
        session_id = sessions[0]["id"]

        # List windows
        windows = await handler.list_windows(session_id)
        assert len(windows) == 2

        # Get size
        size = await handler.get_session_size(session_id)
        assert size["rows"] == 24
        assert size["cols"] == 80

        # Resize
        await handler.resize_session(session_id, rows=40, cols=120)
        resize_calls = [c for c in executor.calls if "resize-window" in c]
        assert len(resize_calls) == 1

        # Send command
        await handler.send_keys(session_id, "ls -la\n")
        send_calls = [c for c in executor.calls if "send-keys" in c]
        assert len(send_calls) == 1

    @pytest.mark.asyncio
    async def test_error_handling_in_workflow(self):
        """Errors propagate correctly through the stack."""
        executor = MockCommandExecutor()
        executor.add_response("-V", "tmux 3.4")
        executor.add_response(
            "list-sessions",
            stdout="",
            stderr="error connecting to /tmp/tmux-1000/default (No such file or directory)",
            returncode=1,
        )

        service = TmuxService(executor=executor)
        registry = SessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        # list_sessions handles "no server" gracefully
        sessions = await handler.list_sessions()
        assert sessions == []


class TestE2ESessionRegistry:
    """E2E tests for session registry integration."""

    @pytest.mark.asyncio
    async def test_registry_tracks_attached_sessions(self):
        """Registry tracks attached sessions correctly."""
        process = MockControlModeProcess()
        process.add_line("%exit")

        async def output_callback(data: bytes):
            pass

        client = ControlModeClient(session_id="$0", process=process)
        streamer = OutputStreamer(callback=output_callback, throttle_ms=10)

        session = AttachedSession(
            session_id="$0",
            output_callback=output_callback,
            control_client=client,
            output_streamer=streamer,
        )

        registry = SessionRegistry()

        # Add session to registry
        registry.add(session)
        assert "$0" in registry
        assert registry.get("$0") is session

        # Session runs and exits
        async with session:
            await session.wait()

        # Remove from registry
        removed = registry.remove("$0")
        assert removed is session
        assert "$0" not in registry
