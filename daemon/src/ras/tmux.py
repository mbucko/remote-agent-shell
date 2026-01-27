"""tmux service for executing tmux commands."""

import asyncio
import re
from typing import Optional

from .errors import TmuxError, TmuxVersionError
from .protocols import (
    CommandExecutorProtocol,
    TmuxSessionInfo,
    TmuxWindowInfo,
)


class AsyncCommandExecutor:
    """Execute shell commands asynchronously."""

    async def run(
        self, *args: str, check: bool = True
    ) -> tuple[bytes, bytes, int]:
        """Run command and return (stdout, stderr, returncode).

        Args:
            *args: Command and arguments to run.
            check: If True, raise TmuxError on non-zero exit.

        Returns:
            Tuple of (stdout, stderr, returncode).

        Raises:
            TmuxError: If check=True and command fails.
        """
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await proc.communicate()
        returncode = proc.returncode or 0

        if check and returncode != 0:
            raise TmuxError(f"Command failed: {stderr.decode()}")

        return stdout, stderr, returncode


class TmuxService:
    """Service for executing tmux commands.

    This is a stateless service that wraps tmux CLI commands.
    Use dependency injection for the command executor to enable testing.
    """

    # Minimum supported tmux version
    MIN_VERSION = (2, 1)

    def __init__(
        self,
        executor: Optional[CommandExecutorProtocol] = None,
        tmux_path: str = "tmux",
        socket_path: Optional[str] = None,
    ):
        """Initialize TmuxService.

        Args:
            executor: Command executor for running tmux. Defaults to
                AsyncCommandExecutor.
            tmux_path: Path to tmux binary. Defaults to "tmux".
            socket_path: Path to tmux socket for isolated server.
                If None, uses default tmux socket.
        """
        self._executor = executor or AsyncCommandExecutor()
        self._tmux_path = tmux_path
        self._socket_path = socket_path
        self._verified = False

    async def _run(
        self, *args: str, check: bool = True
    ) -> tuple[bytes, bytes, int]:
        """Run tmux command.

        Args:
            *args: tmux subcommand and arguments.
            check: If True, raise on non-zero exit.

        Returns:
            Tuple of (stdout, stderr, returncode).
        """
        if self._socket_path:
            full_args = (self._tmux_path, "-S", self._socket_path, *args)
        else:
            full_args = (self._tmux_path, *args)
        return await self._executor.run(*full_args, check=check)

    async def verify(self) -> None:
        """Verify tmux is available and version is sufficient.

        Raises:
            TmuxError: If version cannot be parsed.
            TmuxVersionError: If version is too old.
        """
        if self._verified:
            return

        stdout, _, _ = await self._run("-V")
        version_str = stdout.decode().strip()

        # Parse version from strings like "tmux 3.4" or "tmux next-3.5"
        match = re.search(r"(\d+)\.(\d+)", version_str)
        if not match:
            raise TmuxError(f"Could not parse tmux version from: {version_str}")

        major = int(match.group(1))
        minor = int(match.group(2))

        if (major, minor) < self.MIN_VERSION:
            raise TmuxVersionError(
                f"tmux {major}.{minor} is too old. "
                f"Minimum required: {self.MIN_VERSION[0]}.{self.MIN_VERSION[1]}"
            )

        self._verified = True

    async def list_sessions(self) -> list[TmuxSessionInfo]:
        """List all tmux sessions.

        Returns:
            List of TmuxSessionInfo objects.
        """
        await self.verify()

        # Format: session_id:session_name:window_count:attached_flag
        format_str = "#{session_id}:#{session_name}:#{session_windows}:#{session_attached}"

        stdout, stderr, returncode = await self._run(
            "list-sessions", "-F", format_str, check=False
        )

        # No server or no sessions is not an error
        if returncode != 0:
            stderr_str = stderr.decode()
            if any(
                msg in stderr_str
                for msg in ["no server", "no sessions", "error connecting"]
            ):
                return []
            raise TmuxError(f"list-sessions failed: {stderr_str}")

        sessions = []
        for line in stdout.decode().strip().split("\n"):
            if not line:
                continue
            parts = line.split(":")
            if len(parts) >= 4:
                sessions.append(
                    TmuxSessionInfo(
                        id=parts[0],
                        name=parts[1],
                        windows=int(parts[2]),
                        attached=parts[3] == "1",
                    )
                )

        return sessions

    async def list_windows(self, session_id: str) -> list[TmuxWindowInfo]:
        """List windows in a session.

        Args:
            session_id: Session ID (e.g., "$0").

        Returns:
            List of TmuxWindowInfo objects.
        """
        await self.verify()

        # Format: window_id:window_name:active_flag
        format_str = "#{window_id}:#{window_name}:#{window_active}"

        stdout, _, _ = await self._run(
            "list-windows", "-t", session_id, "-F", format_str
        )

        windows = []
        for line in stdout.decode().strip().split("\n"):
            if not line:
                continue
            parts = line.split(":")
            if len(parts) >= 3:
                windows.append(
                    TmuxWindowInfo(
                        id=parts[0],
                        name=parts[1],
                        active=parts[2] == "1",
                    )
                )

        return windows

    async def get_session_size(self, session_id: str) -> tuple[int, int]:
        """Get terminal size of a session.

        Args:
            session_id: Session ID (e.g., "$0").

        Returns:
            Tuple of (rows, cols).
        """
        await self.verify()

        # Format: height:width
        format_str = "#{window_height}:#{window_width}"

        stdout, _, _ = await self._run(
            "display-message", "-t", session_id, "-p", format_str
        )

        parts = stdout.decode().strip().split(":")
        return int(parts[0]), int(parts[1])

    async def resize_session(
        self, session_id: str, rows: int, cols: int
    ) -> None:
        """Resize session terminal.

        Args:
            session_id: Session ID (e.g., "$0").
            rows: Number of rows.
            cols: Number of columns.
        """
        await self.verify()

        await self._run(
            "resize-window",
            "-t",
            session_id,
            "-x",
            str(cols),
            "-y",
            str(rows),
        )

    async def send_keys(
        self, session_id: str, keys: str, literal: bool = True
    ) -> None:
        """Send keys to a session.

        Args:
            session_id: Session ID (e.g., "$0").
            keys: Keys to send.
            literal: If True, send as literal text. If False, interpret
                special keys like C-c.
        """
        await self.verify()

        args = ["send-keys", "-t", session_id]
        if literal:
            args.append("-l")
        args.append(keys)

        await self._run(*args)

    async def switch_window(self, session_id: str, window_id: str) -> None:
        """Switch to a window in a session.

        Args:
            session_id: Session ID (e.g., "$0").
            window_id: Window ID (e.g., "@1").
        """
        await self.verify()

        await self._run("select-window", "-t", f"{session_id}:{window_id}")

    async def capture_pane(self, session_id: str, lines: int = 100) -> str:
        """Capture pane content.

        Args:
            session_id: Session ID (e.g., "$0").
            lines: Number of lines to capture from history.

        Returns:
            Captured content as string.
        """
        await self.verify()

        stdout, _, _ = await self._run(
            "capture-pane",
            "-t",
            session_id,
            "-p",  # Print to stdout
            "-S",
            f"-{lines}",  # Start line (negative = from history)
        )

        return stdout.decode()

    async def create_session(
        self, name: str, detached: bool = True
    ) -> str:
        """Create a new tmux session.

        Args:
            name: Session name.
            detached: If True, create detached (default).

        Returns:
            Session ID (e.g., "$0").
        """
        await self.verify()

        args = ["new-session", "-s", name, "-P", "-F", "#{session_id}"]
        if detached:
            args.insert(1, "-d")

        stdout, _, _ = await self._run(*args)
        return stdout.decode().strip()

    async def kill_session(self, session_id: str) -> None:
        """Kill a tmux session.

        Args:
            session_id: Session ID or name to kill.
        """
        await self._run("kill-session", "-t", session_id, check=False)

    async def kill_server(self) -> None:
        """Kill the tmux server (useful for cleanup)."""
        await self._run("kill-server", check=False)

    async def new_window(
        self, session_id: str, name: Optional[str] = None
    ) -> str:
        """Create a new window in a session.

        Args:
            session_id: Session ID (e.g., "$0").
            name: Optional window name.

        Returns:
            Window ID (e.g., "@1").
        """
        await self.verify()

        args = ["new-window", "-t", session_id, "-P", "-F", "#{window_id}"]
        if name:
            args.extend(["-n", name])

        stdout, _, _ = await self._run(*args)
        return stdout.decode().strip()
