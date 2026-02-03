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
