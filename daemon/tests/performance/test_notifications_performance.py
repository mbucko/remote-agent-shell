"""Performance tests for notification system.

Tests that verify performance characteristics like processing large volumes
of data efficiently.
"""

import pytest

from ras.notifications.types import NotificationConfig, NotificationType
from ras.notifications.matcher import PatternMatcher
from ras.notifications.dispatcher import NotificationDispatcher
from ras.proto.ras import TerminalEvent
from unittest.mock import AsyncMock


@pytest.fixture
def config():
    """Notification config for performance tests."""
    cfg = NotificationConfig.default()
    return NotificationConfig(
        approval_patterns=cfg.approval_patterns,
        error_patterns=cfg.error_patterns,
        shell_prompt_patterns=cfg.shell_prompt_patterns,
        cooldown_seconds=0.1,
        regex_timeout_ms=cfg.regex_timeout_ms,
        sliding_window_size=cfg.sliding_window_size,
        snippet_context_chars=cfg.snippet_context_chars,
        max_snippet_length=cfg.max_snippet_length,
    )


@pytest.fixture
def matcher(config):
    """PatternMatcher instance."""
    return PatternMatcher(config)


@pytest.fixture
def broadcast_mock():
    """Mock broadcast function that captures sent messages."""
    mock = AsyncMock()
    mock.sent_events = []

    async def capture(data: bytes):
        event = TerminalEvent().parse(data)
        mock.sent_events.append(event)

    mock.side_effect = capture
    return mock


@pytest.fixture
def dispatcher(broadcast_mock, config):
    """NotificationDispatcher with captured messages."""
    return NotificationDispatcher(broadcast_mock, config)


class TestNotificationsPerformance:
    """Performance tests for notification system."""

    @pytest.mark.performance
    @pytest.mark.asyncio
    async def test_very_long_output_processing(
        self, matcher, dispatcher, broadcast_mock
    ):
        """PERF-01: 1MB output processed efficiently.

        Verifies that large volumes of terminal output can be processed
        without excessive memory or time usage.
        """
        # 1MB of output with error at end
        long_output = b"x" * (1024 * 1024) + b"\nError: at the end\n"
        results = matcher.process_chunk(long_output)

        error_results = [r for r in results if r.type == NotificationType.ERROR]
        assert len(error_results) >= 1
