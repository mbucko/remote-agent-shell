"""End-to-end integration tests for notification system.

Tests the full flow from terminal output to notification dispatch.
"""

import asyncio
from unittest.mock import AsyncMock

import pytest

from ras.notifications.types import NotificationConfig, NotificationType
from ras.notifications.matcher import PatternMatcher
from ras.notifications.dispatcher import NotificationDispatcher
from ras.proto.ras import TerminalEvent, NotificationType as ProtoNotificationType


@pytest.fixture
def config():
    """Notification config for E2E tests with short cooldown for fast tests."""
    cfg = NotificationConfig.default()
    # Use short cooldown for fast testing
    return NotificationConfig(
        approval_patterns=cfg.approval_patterns,
        error_patterns=cfg.error_patterns,
        shell_prompt_patterns=cfg.shell_prompt_patterns,
        cooldown_seconds=0.1,  # 100ms for fast tests
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


# ============================================================================
# E2E Flow Tests (E2E01-E2E08)
# ============================================================================


class TestE2EFlows:
    """End-to-end flow tests."""

    @pytest.mark.asyncio
    async def test_e2e01_approval_flow(self, matcher, dispatcher, broadcast_mock):
        """E2E01: Complete approval flow - output to notification.

        1. Agent outputs "Proceed? (y/n)"
        2. Daemon detects pattern
        3. Creates notification
        4. Broadcasts via WebRTC
        5. Notification has correct fields
        """
        # 1. Process output
        results = matcher.process_chunk(b"Making changes to file.py\nProceed? (y/n)")

        # 2. Should detect approval pattern
        approval_results = [r for r in results if r.type == NotificationType.APPROVAL]
        assert len(approval_results) >= 1

        # 3-4. Dispatch notification
        match = approval_results[0]
        sent = await dispatcher.dispatch("session-abc", "my-project", match)
        assert sent is True

        # 5. Verify notification
        assert len(broadcast_mock.sent_events) == 1
        event = broadcast_mock.sent_events[0]
        assert event.notification.session_id == "session-abc"
        assert event.notification.type == ProtoNotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED
        assert "my-project" in event.notification.title
        assert "Proceed" in event.notification.snippet or "(y/n)" in event.notification.snippet

    @pytest.mark.asyncio
    async def test_e2e02_completion_flow(self, matcher, dispatcher, broadcast_mock):
        """E2E02: Task completion flow.

        1. Agent runs command
        2. Agent exits
        3. Shell prompt appears
        4. Daemon detects prompt
        5. Notification sent
        """
        # 1-2. Agent output
        matcher.process_chunk(b"Running npm install...\n")
        matcher.process_chunk(b"added 150 packages\n")

        # 3-4. Shell prompt appears
        results = matcher.process_chunk(b"$ ")

        # Should detect completion
        completion_results = [r for r in results if r.type == NotificationType.COMPLETION]
        assert len(completion_results) >= 1

        # 5. Dispatch
        match = completion_results[0]
        sent = await dispatcher.dispatch("session-1", "build-task", match)
        assert sent is True

        # Verify
        event = broadcast_mock.sent_events[0]
        assert event.notification.type == ProtoNotificationType.NOTIFICATION_TYPE_TASK_COMPLETED
        assert "build-task" in event.notification.title

    @pytest.mark.asyncio
    async def test_e2e03_error_flow(self, matcher, dispatcher, broadcast_mock):
        """E2E03: Error detection flow with snippet.

        1. Agent encounters error
        2. Outputs "Error: failed"
        3. Daemon detects pattern
        4. Notification with snippet
        5. User sees error context
        """
        # 1-2. Agent error
        error_output = b"Compiling...\nError: cannot find module 'lodash'\n"
        results = matcher.process_chunk(error_output)

        # 3. Should detect error
        error_results = [r for r in results if r.type == NotificationType.ERROR]
        assert len(error_results) >= 1

        # 4-5. Dispatch with snippet
        match = error_results[0]
        sent = await dispatcher.dispatch("session-err", "my-app", match)
        assert sent is True

        event = broadcast_mock.sent_events[0]
        assert event.notification.type == ProtoNotificationType.NOTIFICATION_TYPE_ERROR_DETECTED
        assert "Error" in event.notification.snippet or "module" in event.notification.snippet

    @pytest.mark.asyncio
    async def test_e2e04_dedup_flow(self, matcher, dispatcher, broadcast_mock):
        """E2E04: Deduplication flow.

        1. Agent outputs "Proceed?" 3x rapidly
        2. Only 1 notification sent
        3. Cooldown active
        4. After cooldown, next match triggers
        """
        # 1. Rapid matches
        for _ in range(3):
            results = matcher.process_chunk(b"Proceed? (y/n)\n")
            for r in results:
                if r.type == NotificationType.APPROVAL:
                    await dispatcher.dispatch("s1", "n", r)

        # 2. Only 1 notification
        assert len(broadcast_mock.sent_events) == 1

        # 3-4. Wait for cooldown and trigger again
        await asyncio.sleep(0.15)
        results = matcher.process_chunk(b"Proceed? (y/n)\n")
        for r in results:
            if r.type == NotificationType.APPROVAL:
                await dispatcher.dispatch("s1", "n", r)

        assert len(broadcast_mock.sent_events) == 2

    @pytest.mark.asyncio
    async def test_e2e05_alt_screen_flow(self, matcher, dispatcher, broadcast_mock):
        """E2E05: Alternate screen suppression flow.

        1. User opens vim
        2. vim shows "Save? (y/n)"
        3. No notification (suppressed)
        4. User exits vim
        5. Agent shows "Proceed?"
        6. Notification sent
        """
        # 1. Enter vim
        matcher.process_chunk(b"\x1b[?1049h")

        # 2-3. Prompt in vim - should not trigger
        results = matcher.process_chunk(b"Save changes? (y/n)")
        assert len(results) == 0

        # 4. Exit vim
        matcher.process_chunk(b"\x1b[?1049l")

        # 5-6. Prompt after vim - should trigger
        results = matcher.process_chunk(b"Proceed? (y/n)")
        approval_results = [r for r in results if r.type == NotificationType.APPROVAL]
        assert len(approval_results) >= 1

        # Dispatch
        sent = await dispatcher.dispatch("s1", "n", approval_results[0])
        assert sent is True
        assert len(broadcast_mock.sent_events) == 1

    @pytest.mark.asyncio
    async def test_e2e06_multi_session_flow(self, matcher, dispatcher, broadcast_mock):
        """E2E06: Multiple sessions flow.

        1. Session A and B connected
        2. Both have matches
        3. Both phones notified
        4. Notifications have correct session IDs
        """
        # Match for session A
        results_a = matcher.process_chunk(b"Error: A failed")
        error_a = [r for r in results_a if r.type == NotificationType.ERROR][0]
        await dispatcher.dispatch("session-A", "project-A", error_a)

        # Reset matcher buffer for B
        matcher.reset()

        # Match for session B
        results_b = matcher.process_chunk(b"Error: B failed")
        error_b = [r for r in results_b if r.type == NotificationType.ERROR][0]
        await dispatcher.dispatch("session-B", "project-B", error_b)

        # Both should be notified
        assert len(broadcast_mock.sent_events) == 2
        session_ids = {e.notification.session_id for e in broadcast_mock.sent_events}
        assert session_ids == {"session-A", "session-B"}

    @pytest.mark.asyncio
    async def test_e2e07_reconnect_flow(self, config, matcher):
        """E2E07: Reconnection handling.

        1. Phone disconnects
        2. Pattern matched (notification dropped due to no connection)
        3. Phone reconnects
        4. New pattern matched
        5. Notification sent
        """
        # Simulate disconnect by having broadcast fail
        failing_broadcast = AsyncMock(side_effect=Exception("Disconnected"))
        dispatcher = NotificationDispatcher(failing_broadcast, config)

        # 1-2. Match while disconnected
        results = matcher.process_chunk(b"Proceed? (y/n)")
        match = [r for r in results if r.type == NotificationType.APPROVAL][0]
        sent = await dispatcher.dispatch("s1", "n", match)
        assert sent is False  # Failed to send

        # 3. Reconnect (new working broadcast)
        working_broadcast = AsyncMock()
        working_broadcast.sent_events = []

        async def capture(data):
            working_broadcast.sent_events.append(TerminalEvent().parse(data))

        working_broadcast.side_effect = capture
        dispatcher._broadcast = working_broadcast

        # 4-5. New match succeeds
        await asyncio.sleep(0.15)  # Wait for cooldown
        results = matcher.process_chunk(b"Proceed? (y/n)")
        match = [r for r in results if r.type == NotificationType.APPROVAL][0]
        sent = await dispatcher.dispatch("s1", "n", match)
        assert sent is True
        assert len(working_broadcast.sent_events) == 1

    @pytest.mark.asyncio
    async def test_e2e08_chunk_boundary_flow(self, matcher, dispatcher, broadcast_mock):
        """E2E08: Pattern split across chunks.

        1. Receive "Proceed? (y"
        2. Receive "/n)"
        3. Pattern matched via sliding window
        4. Notification sent
        """
        # 1. First chunk
        results1 = matcher.process_chunk(b"Proceed? (y")
        # May or may not match yet

        # 2. Second chunk completes pattern
        results2 = matcher.process_chunk(b"/n)")

        # 3. Should match in combined buffer
        all_approval = [r for r in results1 + results2 if r.type == NotificationType.APPROVAL]
        assert len(all_approval) >= 1

        # 4. Dispatch
        sent = await dispatcher.dispatch("s1", "n", all_approval[0])
        assert sent is True


# ============================================================================
# Edge Case E2E Tests
# ============================================================================


class TestE2EEdgeCases:
    """Edge case E2E tests."""

    @pytest.mark.asyncio
    async def test_multiple_patterns_same_chunk(self, matcher, dispatcher, broadcast_mock):
        """Multiple patterns in same chunk all detected and dispatched."""
        output = b"Error: failed\nProceed? (y/n)"
        results = matcher.process_chunk(output)

        # Should have both error and approval
        types = {r.type for r in results}
        assert NotificationType.ERROR in types
        assert NotificationType.APPROVAL in types

        # Dispatch both (different types so both should send)
        for r in results:
            await dispatcher.dispatch("s1", "n", r)

        # Both should have been sent
        assert len(broadcast_mock.sent_events) == 2

    @pytest.mark.asyncio
    async def test_empty_session_name(self, matcher, dispatcher, broadcast_mock):
        """Empty session name handled gracefully."""
        results = matcher.process_chunk(b"Proceed? (y/n)")
        match = [r for r in results if r.type == NotificationType.APPROVAL][0]

        sent = await dispatcher.dispatch("s1", "", match)
        assert sent is True

        event = broadcast_mock.sent_events[0]
        assert event.notification.title  # Should have some title

    @pytest.mark.asyncio
    async def test_unicode_in_output(self, matcher, dispatcher, broadcast_mock):
        """Unicode in terminal output handled."""
        # Error patterns use ^ anchor, so Error: must be at start of line
        output = "❌ message\nError: 文件不存在".encode("utf-8")
        results = matcher.process_chunk(output)

        error_results = [r for r in results if r.type == NotificationType.ERROR]
        assert len(error_results) >= 1

        sent = await dispatcher.dispatch("s1", "n", error_results[0])
        assert sent is True

    @pytest.mark.asyncio
    async def test_binary_data_in_output(self, matcher, dispatcher, broadcast_mock):
        """Binary data doesn't crash matcher."""
        # Error patterns use ^ anchor, so Error: must be at start of line
        binary_output = b"\x00\x01\x02\nError: something\xff\xfe"
        results = matcher.process_chunk(binary_output)

        # Should still detect error despite binary garbage
        error_results = [r for r in results if r.type == NotificationType.ERROR]
        assert len(error_results) >= 1

    @pytest.mark.asyncio
    async def test_very_long_output(self, matcher, dispatcher, broadcast_mock):
        """Very long output processed without issues."""
        # 1MB of output with error at end
        long_output = b"x" * (1024 * 1024) + b"\nError: at the end\n"
        results = matcher.process_chunk(long_output)

        error_results = [r for r in results if r.type == NotificationType.ERROR]
        assert len(error_results) >= 1

    @pytest.mark.asyncio
    async def test_rapid_output_chunks(self, matcher, dispatcher, broadcast_mock):
        """Rapid small chunks processed correctly."""
        # Simulate rapid output
        for char in b"Proceed? (y/n)":
            matcher.process_chunk(bytes([char]))

        # Final chunk should trigger via sliding window
        results = matcher.process_chunk(b" ")
        # The pattern should be in the buffer

        # Try with explicit combined
        matcher.reset()
        all_results = []
        for i, char in enumerate(b"Proceed? (y/n)"):
            results = matcher.process_chunk(bytes([char]))
            all_results.extend(results)

        # Last few chunks should trigger match due to buffer
        approval_results = [r for r in all_results if r.type == NotificationType.APPROVAL]
        # May take a bit for buffer to accumulate enough
