"""Tests for daemon single-instance lock mechanism.

These tests verify that only one daemon can run at a time.
The lock mechanism should:
1. Prevent two daemons from running simultaneously
2. Clean up the lock file on graceful shutdown
3. Handle stale lock files (crashed daemon)
4. Be race-condition safe
"""

import asyncio
import os
import tempfile
from pathlib import Path
from unittest.mock import patch

import pytest

# The module we're testing (to be implemented)
from ras.daemon_lock import DaemonLock, DaemonAlreadyRunningError


class TestDaemonLockAcquire:
    """Tests for acquiring the daemon lock."""

    def test_acquire_lock_succeeds_when_no_lock_exists(self, tmp_path):
        """First daemon can acquire lock successfully."""
        lock_file = tmp_path / "daemon.lock"

        lock = DaemonLock(lock_file)
        lock.acquire()

        assert lock.is_held()
        assert lock_file.exists()

        lock.release()

    def test_acquire_lock_fails_when_already_held(self, tmp_path):
        """Second daemon fails to acquire lock when first holds it."""
        lock_file = tmp_path / "daemon.lock"

        # First daemon acquires lock
        lock1 = DaemonLock(lock_file)
        lock1.acquire()

        # Second daemon tries to acquire - should fail
        lock2 = DaemonLock(lock_file)
        with pytest.raises(DaemonAlreadyRunningError) as exc_info:
            lock2.acquire()

        assert "already running" in str(exc_info.value).lower()

        # Clean up
        lock1.release()

    def test_acquire_lock_succeeds_after_release(self, tmp_path):
        """Lock can be acquired after previous holder releases it."""
        lock_file = tmp_path / "daemon.lock"

        # First daemon acquires and releases
        lock1 = DaemonLock(lock_file)
        lock1.acquire()
        lock1.release()

        # Second daemon should now succeed
        lock2 = DaemonLock(lock_file)
        lock2.acquire()

        assert lock2.is_held()

        lock2.release()


class TestDaemonLockRelease:
    """Tests for releasing the daemon lock."""

    def test_release_removes_lock_file(self, tmp_path):
        """Release should remove the lock file."""
        lock_file = tmp_path / "daemon.lock"

        lock = DaemonLock(lock_file)
        lock.acquire()
        assert lock_file.exists()

        lock.release()
        assert not lock_file.exists()

    def test_release_without_acquire_is_safe(self, tmp_path):
        """Releasing without acquiring should not raise."""
        lock_file = tmp_path / "daemon.lock"

        lock = DaemonLock(lock_file)
        # Should not raise
        lock.release()

    def test_double_release_is_safe(self, tmp_path):
        """Releasing twice should not raise."""
        lock_file = tmp_path / "daemon.lock"

        lock = DaemonLock(lock_file)
        lock.acquire()
        lock.release()
        # Should not raise
        lock.release()


class TestDaemonLockContextManager:
    """Tests for using DaemonLock as a context manager."""

    def test_context_manager_acquires_and_releases(self, tmp_path):
        """Context manager should acquire on enter and release on exit."""
        lock_file = tmp_path / "daemon.lock"

        with DaemonLock(lock_file) as lock:
            assert lock.is_held()
            assert lock_file.exists()

        # After exiting context, lock should be released
        assert not lock_file.exists()

    def test_context_manager_releases_on_exception(self, tmp_path):
        """Context manager should release lock even if exception occurs."""
        lock_file = tmp_path / "daemon.lock"

        with pytest.raises(ValueError):
            with DaemonLock(lock_file) as lock:
                assert lock.is_held()
                raise ValueError("test error")

        # Lock should still be released
        assert not lock_file.exists()

    def test_nested_context_managers_fail(self, tmp_path):
        """Nested context managers should fail on second acquire."""
        lock_file = tmp_path / "daemon.lock"

        with DaemonLock(lock_file):
            with pytest.raises(DaemonAlreadyRunningError):
                with DaemonLock(lock_file):
                    pass


class TestDaemonLockStaleLock:
    """Tests for handling stale lock files from crashed daemons."""

    def test_stale_lock_from_dead_process_can_be_acquired(self, tmp_path):
        """Lock file from non-existent PID should be acquirable."""
        lock_file = tmp_path / "daemon.lock"

        # Create a stale lock file with a PID that doesn't exist
        # PID 99999999 is unlikely to exist
        lock_file.write_text("99999999\n")

        # Should be able to acquire despite existing lock file
        lock = DaemonLock(lock_file)
        lock.acquire()

        assert lock.is_held()

        lock.release()

    def test_lock_from_running_process_cannot_be_acquired(self, tmp_path):
        """Lock file from running PID should not be acquirable."""
        lock_file = tmp_path / "daemon.lock"

        # Create lock file with current process PID (definitely running)
        lock_file.write_text(f"{os.getpid()}\n")

        lock = DaemonLock(lock_file)
        with pytest.raises(DaemonAlreadyRunningError):
            lock.acquire()


class TestDaemonLockPidFile:
    """Tests for PID file contents."""

    def test_lock_file_contains_pid(self, tmp_path):
        """Lock file should contain the current process PID."""
        lock_file = tmp_path / "daemon.lock"

        lock = DaemonLock(lock_file)
        lock.acquire()

        content = lock_file.read_text().strip()
        assert content == str(os.getpid())

        lock.release()

    def test_get_owner_pid_returns_pid_from_file(self, tmp_path):
        """get_owner_pid should return PID from lock file."""
        lock_file = tmp_path / "daemon.lock"

        lock = DaemonLock(lock_file)
        lock.acquire()

        assert lock.get_owner_pid() == os.getpid()

        lock.release()

    def test_get_owner_pid_returns_none_when_no_lock(self, tmp_path):
        """get_owner_pid should return None when no lock exists."""
        lock_file = tmp_path / "daemon.lock"

        lock = DaemonLock(lock_file)
        assert lock.get_owner_pid() is None


class TestDaemonLockFilePermissions:
    """Tests for lock file security."""

    def test_lock_file_has_secure_permissions(self, tmp_path):
        """Lock file should have restrictive permissions (0o600)."""
        lock_file = tmp_path / "daemon.lock"

        lock = DaemonLock(lock_file)
        lock.acquire()

        # Check permissions (owner read/write only)
        mode = lock_file.stat().st_mode & 0o777
        assert mode == 0o600

        lock.release()

    def test_creates_parent_directory_if_missing(self, tmp_path):
        """Should create parent directory if it doesn't exist."""
        lock_file = tmp_path / "subdir" / "daemon.lock"

        assert not lock_file.parent.exists()

        lock = DaemonLock(lock_file)
        lock.acquire()

        assert lock_file.parent.exists()
        assert lock_file.exists()

        lock.release()


class TestDaemonLockRaceCondition:
    """Tests for race condition safety."""

    def test_concurrent_acquire_only_one_succeeds(self, tmp_path):
        """When multiple processes try to acquire, only one should succeed."""
        lock_file = tmp_path / "daemon.lock"

        results = []

        def try_acquire():
            lock = DaemonLock(lock_file)
            try:
                lock.acquire()
                results.append("acquired")
                # Hold for a bit
                import time
                time.sleep(0.1)
                lock.release()
                results.append("released")
            except DaemonAlreadyRunningError:
                results.append("failed")

        import threading
        threads = [threading.Thread(target=try_acquire) for _ in range(5)]

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        # Exactly one should have acquired (and released)
        assert results.count("acquired") == 1
        assert results.count("released") == 1
        assert results.count("failed") == 4


class TestDaemonLockIntegration:
    """Integration tests simulating real daemon scenarios."""

    @pytest.mark.asyncio
    async def test_two_daemons_cannot_run_simultaneously(self, tmp_path):
        """Simulate two daemon instances - second should fail to start."""
        lock_file = tmp_path / "daemon.lock"

        async def fake_daemon_start(lock: DaemonLock, name: str) -> str:
            """Simulate daemon startup."""
            try:
                lock.acquire()
                # Simulate daemon running
                await asyncio.sleep(0.5)
                return f"{name}: started"
            except DaemonAlreadyRunningError:
                return f"{name}: failed - daemon already running"
            finally:
                lock.release()

        # Start first daemon
        lock1 = DaemonLock(lock_file)
        task1 = asyncio.create_task(fake_daemon_start(lock1, "daemon1"))

        # Give first daemon time to acquire lock
        await asyncio.sleep(0.1)

        # Try to start second daemon while first is running
        lock2 = DaemonLock(lock_file)
        task2 = asyncio.create_task(fake_daemon_start(lock2, "daemon2"))

        result1, result2 = await asyncio.gather(task1, task2)

        assert "started" in result1
        assert "failed" in result2
        assert "already running" in result2

    @pytest.mark.asyncio
    async def test_daemon_can_start_after_previous_stops(self, tmp_path):
        """Second daemon can start after first one stops gracefully."""
        lock_file = tmp_path / "daemon.lock"

        # First daemon runs and stops
        lock1 = DaemonLock(lock_file)
        lock1.acquire()
        await asyncio.sleep(0.1)
        lock1.release()

        # Second daemon should now succeed
        lock2 = DaemonLock(lock_file)
        lock2.acquire()

        assert lock2.is_held()

        lock2.release()
