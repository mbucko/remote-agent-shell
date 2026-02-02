"""Performance tests for notification pattern matching.

These tests verify performance characteristics of the pattern matcher,
such as processing large data volumes or many chunks efficiently.
"""

import time

import pytest

from ras.notifications import PatternMatcher, NotificationType
from ras.notifications.types import NotificationConfig


@pytest.fixture
def matcher():
    """Create a PatternMatcher with default config."""
    config = NotificationConfig(
        approval_patterns=[r"Proceed\? \(y/n\)"],
        error_patterns=[r"Error:"],
        shell_prompt_patterns=[r"\$\s*$"],
        cooldown_seconds=0,  # Disable cooldown for testing
    )
    return PatternMatcher(config)


class TestMatcherPerformance:
    """Performance tests for PatternMatcher."""

    def test_large_chunk_performance(self, matcher):
        """100KB chunk processed efficiently."""
        # 100KB of data with pattern at end
        large_output = b"x" * (100 * 1024) + b"\nError: something went wrong\n"

        start = time.time()
        results = matcher.process_chunk(large_output)
        elapsed = time.time() - start

        # Should complete in < 100ms for 100KB
        assert elapsed < 0.1, f"100KB took {elapsed:.3f}s"
        # Should find the pattern
        error_results = [r for r in results if r.type == NotificationType.ERROR]
        assert len(error_results) >= 1

    def test_rapid_small_chunks_performance(self, matcher):
        """Many small chunks processed efficiently."""
        # 1000 small chunks
        chunks = [b"line of output\n" for _ in range(1000)]
        chunks.append(b"Proceed? (y/n)")

        start = time.time()
        for chunk in chunks:
            matcher.process_chunk(chunk)
        elapsed = time.time() - start

        # 1000 chunks should complete in < 1s
        assert elapsed < 1.0, f"1000 chunks took {elapsed:.3f}s"
