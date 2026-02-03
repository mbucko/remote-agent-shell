"""Pytest configuration and shared fixtures."""

import asyncio

import pytest
import pytest_asyncio


@pytest.fixture(autouse=True)
def reset_logging_state():
    """Reset logging state before each test."""
    from ras.logging import reset_logging

    reset_logging()
    yield
    reset_logging()


@pytest_asyncio.fixture(autouse=True, loop_scope="function")
async def cleanup_aiohttp_sessions():
    """Give aiohttp sessions time to clean up their connectors.

    aiohttp's ClientSession.close() doesn't wait for the underlying
    connector to fully close. This can cause "Unclosed client session"
    warnings when the event loop closes before cleanup completes.
    """
    yield
    # Give connector time to close (prevents "Unclosed client session" warnings)
    await asyncio.sleep(0)


@pytest.fixture(autouse=True)
def fast_ice_connectivity():
    """Speed up ICE connectivity checks by reducing retry timeouts.

    This follows the same pattern used by aiortc's own test suite.
    Without this, ICE checks on unreachable candidates take ~10 seconds.
    """
    import aioice.stun

    old_retry_max = aioice.stun.RETRY_MAX
    old_retry_rto = aioice.stun.RETRY_RTO

    # Shorten timers for fast test execution
    aioice.stun.RETRY_MAX = 1
    aioice.stun.RETRY_RTO = 0.1

    yield

    # Restore original values
    aioice.stun.RETRY_MAX = old_retry_max
    aioice.stun.RETRY_RTO = old_retry_rto


@pytest.fixture(autouse=True, scope="session")
def cleanup_stale_test_processes():
    """Kill any stale test processes before and after test session.

    Tests that start the daemon (with mDNS) can leave orphaned processes
    if interrupted (Ctrl+C) or if they crash. These processes hog mDNS
    port 5353 and prevent subsequent daemon starts from working.

    This fixture ensures:
    1. No stale pytest processes are running before tests
    2. Cleanup happens after all tests complete
    """
    import os
    import signal
    import subprocess

    def kill_stale_pytest():
        """Kill any pytest processes that aren't the current one."""
        current_pid = os.getpid()
        try:
            # Find all pytest processes
            result = subprocess.run(
                ["pgrep", "-f", "pytest"], capture_output=True, text=True
            )
            if result.returncode == 0:
                for pid_str in result.stdout.strip().split("\n"):
                    try:
                        pid = int(pid_str.strip())
                        # Don't kill ourselves or our parent
                        if pid != current_pid and pid != os.getppid():
                            os.kill(pid, signal.SIGTERM)
                    except (ValueError, OSError, ProcessLookupError):
                        pass
        except Exception:
            pass  # Ignore errors in cleanup

    # Clean up before tests
    kill_stale_pytest()

    yield

    # Clean up after tests
    kill_stale_pytest()
