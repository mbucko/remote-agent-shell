"""Daemon single-instance lock mechanism.

Ensures only one daemon instance runs at a time using a PID lock file
with fcntl file locking for race-condition safety.
"""

import fcntl
import os
from pathlib import Path


class DaemonAlreadyRunningError(Exception):
    """Raised when trying to start a daemon that's already running."""

    def __init__(self, pid: int | None = None):
        self.pid = pid
        if pid:
            super().__init__(f"Daemon already running with PID {pid}")
        else:
            super().__init__("Daemon already running")


class DaemonLock:
    """Lock to ensure only one daemon runs at a time.

    Uses a PID file with fcntl locking for atomicity:
    - acquire() creates lock file with current PID and holds exclusive lock
    - release() releases lock and removes file
    - Stale locks from crashed processes are detected via PID validation

    Usage:
        lock = DaemonLock(Path("~/.config/ras/daemon.lock"))

        # Manual acquire/release
        lock.acquire()
        try:
            # daemon runs
        finally:
            lock.release()

        # Or as context manager
        with DaemonLock(lock_path):
            # daemon runs
    """

    def __init__(self, lock_file: Path):
        """Initialize daemon lock.

        Args:
            lock_file: Path to the lock file (e.g., ~/.config/ras/daemon.lock)
        """
        self._lock_file = Path(lock_file).expanduser()
        self._fd: int | None = None
        self._held = False

    def acquire(self) -> None:
        """Acquire the lock.

        Creates a lock file with the current PID and holds an exclusive
        file lock. If another process holds the lock, raises an error.

        Raises:
            DaemonAlreadyRunningError: If another daemon is already running.
        """
        if self._held:
            return  # Already held by this instance

        # Check for stale lock first
        existing_pid = self._read_pid_from_file()
        if existing_pid is not None and self._is_process_running(existing_pid):
            raise DaemonAlreadyRunningError(existing_pid)

        # Create parent directory if needed
        self._lock_file.parent.mkdir(parents=True, exist_ok=True)

        # Open/create lock file
        try:
            self._fd = os.open(
                str(self._lock_file),
                os.O_RDWR | os.O_CREAT,
                0o600,  # Secure permissions
            )
        except OSError as e:
            raise DaemonAlreadyRunningError() from e

        # Try to acquire exclusive lock (non-blocking)
        try:
            fcntl.flock(self._fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            # Lock held by another process
            os.close(self._fd)
            self._fd = None
            existing_pid = self._read_pid_from_file()
            raise DaemonAlreadyRunningError(existing_pid)

        # Write our PID
        os.ftruncate(self._fd, 0)
        os.write(self._fd, f"{os.getpid()}\n".encode())
        os.fsync(self._fd)

        # Ensure correct permissions (in case file existed with different perms)
        os.chmod(self._lock_file, 0o600)

        self._held = True

    def release(self) -> None:
        """Release the lock and remove the lock file.

        Safe to call multiple times or without acquiring first.
        """
        if self._fd is not None:
            try:
                fcntl.flock(self._fd, fcntl.LOCK_UN)
            except OSError:
                pass  # Ignore unlock errors

            try:
                os.close(self._fd)
            except OSError:
                pass  # Ignore close errors

            self._fd = None

        self._held = False

        # Remove lock file
        try:
            self._lock_file.unlink()
        except FileNotFoundError:
            pass  # Already gone

    def is_held(self) -> bool:
        """Check if this instance currently holds the lock.

        Returns:
            True if this instance holds the lock, False otherwise.
        """
        return self._held

    def get_owner_pid(self) -> int | None:
        """Get the PID from the lock file.

        Returns:
            PID of the process holding the lock, or None if no lock exists.
        """
        return self._read_pid_from_file()

    def _read_pid_from_file(self) -> int | None:
        """Read PID from lock file if it exists.

        Returns:
            PID from file, or None if file doesn't exist or is invalid.
        """
        try:
            content = self._lock_file.read_text().strip()
            return int(content)
        except (FileNotFoundError, ValueError, OSError):
            return None

    def _is_process_running(self, pid: int) -> bool:
        """Check if a process with the given PID is running.

        Args:
            pid: Process ID to check.

        Returns:
            True if process is running, False otherwise.
        """
        try:
            # Signal 0 doesn't kill, just checks if process exists
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    def __enter__(self) -> "DaemonLock":
        """Enter context manager - acquire lock."""
        self.acquire()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        """Exit context manager - release lock."""
        self.release()
