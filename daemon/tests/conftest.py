"""Pytest configuration and shared fixtures."""

import pytest


@pytest.fixture(autouse=True)
def reset_logging_state():
    """Reset logging state before each test."""
    from ras.logging import reset_logging

    reset_logging()
    yield
    reset_logging()


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
