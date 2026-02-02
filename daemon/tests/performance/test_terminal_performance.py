"""Performance tests for terminal notification system.

These tests verify performance characteristics of the terminal manager,
such as processing large output volumes efficiently.
"""

import asyncio
import time
from unittest.mock import AsyncMock, Mock

import pytest

from ras.terminal.manager import TerminalManager
from ras.notifications.types import NotificationConfig
from ras.proto.ras import TerminalEvent


@pytest.fixture
def mock_session_provider():
    """Mock session provider."""
    provider = Mock()
    provider.get_session.return_value = {
        "tmux_name": "test-session",
        "status": "RUNNING",
        "display_name": "Test Project",
    }
    return provider


@pytest.fixture
def mock_tmux_executor():
    """Mock tmux executor."""
    executor = Mock()
    executor.send_keys = AsyncMock()
    return executor


@pytest.fixture
def send_event_mock():
    """Mock send_event function."""
    return Mock()


@pytest.fixture
def broadcast_mock():
    """Mock broadcast function that captures sent notifications."""
    mock = AsyncMock()
    mock.sent_events = []

    async def capture_broadcast(data: bytes) -> None:
        mock.sent_events.append(data)

    mock.side_effect = capture_broadcast
    return mock


@pytest.fixture
def terminal_manager(
    mock_session_provider, mock_tmux_executor, send_event_mock, broadcast_mock
):
    """Create TerminalManager with mocked dependencies."""
    config = NotificationConfig(
        approval_patterns=[],
        error_patterns=[r"Error:"],
        shell_prompt_patterns=[],
    )
    manager = TerminalManager(
        session_provider=mock_session_provider,
        tmux_executor=mock_tmux_executor,
        send_event=send_event_mock,
        broadcast_notification=broadcast_mock,
        notification_config=config,
    )
    return manager


async def process_pending_tasks():
    """Process all pending asyncio tasks."""
    # Give async tasks time to complete
    await asyncio.sleep(0.01)


class TestTerminalPerformance:
    """Performance tests for TerminalManager."""

    @pytest.mark.performance
    @pytest.mark.asyncio
    async def test_output_flood_performance(self, terminal_manager, broadcast_mock):
        """Output flood - patterns detected without excessive lag.

        1. Send large volume of output
        2. Pattern at end should be detected
        3. Total processing time reasonable
        """
        terminal_manager._buffers["session-1"] = Mock()
        terminal_manager._buffers["session-1"].append.return_value = 1
        terminal_manager._attachments["session-1"] = set()

        start = time.time()

        # Send 1MB of data with pattern at end
        large_output = b"x" * (1024 * 1024) + b"\nError: at the end\n"
        terminal_manager._on_output("session-1", large_output)

        elapsed = time.time() - start

        await process_pending_tasks()

        # Should complete in reasonable time (< 1s for 1MB)
        assert elapsed < 1.0, f"Processing took too long: {elapsed:.2f}s"

        # Pattern should be detected
        assert len(broadcast_mock.sent_events) >= 1
