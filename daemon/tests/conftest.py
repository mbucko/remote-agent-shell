"""Pytest configuration and shared fixtures."""

import pytest


@pytest.fixture(autouse=True)
def reset_logging_state():
    """Reset logging state before each test."""
    from ras.logging import reset_logging

    reset_logging()
    yield
    reset_logging()
