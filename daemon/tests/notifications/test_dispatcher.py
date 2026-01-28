"""Tests for NotificationDispatcher.

Covers:
- Notification dispatch (basic flow)
- Deduplication and cooldown (DD01-DD06)
- Session management
- Error handling
"""

import asyncio
import time
from unittest.mock import AsyncMock

import pytest

from ras.notifications.types import (
    NotificationConfig,
    MatchResult,
    NotificationType,
)
from ras.notifications.dispatcher import NotificationDispatcher
from ras.proto.ras import TerminalEvent, NotificationType as ProtoNotificationType


@pytest.fixture
def config():
    """Notification config with short cooldown for testing."""
    return NotificationConfig(
        approval_patterns=[],
        error_patterns=[],
        shell_prompt_patterns=[],
        cooldown_seconds=0.1,  # 100ms for fast tests
    )


@pytest.fixture
def broadcast_mock():
    """Mock broadcast function."""
    return AsyncMock()


@pytest.fixture
def dispatcher(broadcast_mock, config):
    """NotificationDispatcher with mocked broadcast."""
    return NotificationDispatcher(broadcast_mock, config)


def make_match(
    type_: NotificationType = NotificationType.APPROVAL,
    pattern: str = "test_pattern",
    snippet: str = "Test snippet",
) -> MatchResult:
    """Create a MatchResult for testing."""
    return MatchResult(
        type=type_,
        pattern=pattern,
        snippet=snippet,
        position=0,
    )


# ============================================================================
# Basic Dispatch Tests
# ============================================================================


class TestBasicDispatch:
    """Tests for basic notification dispatch."""

    @pytest.mark.asyncio
    async def test_dispatch_sends_notification(self, dispatcher, broadcast_mock):
        """Dispatch sends notification via broadcast."""
        match = make_match()
        result = await dispatcher.dispatch("session-1", "my-session", match)

        assert result is True
        assert broadcast_mock.called
        # Verify bytes were sent
        call_args = broadcast_mock.call_args[0][0]
        assert isinstance(call_args, bytes)

    @pytest.mark.asyncio
    async def test_dispatch_creates_correct_message(self, dispatcher, broadcast_mock):
        """Dispatch creates correctly formatted TerminalEvent."""
        match = make_match(
            type_=NotificationType.ERROR,
            snippet="Error: something failed",
        )
        await dispatcher.dispatch("sess-123", "my-project", match)

        # Parse the sent message
        sent_bytes = broadcast_mock.call_args[0][0]
        event = TerminalEvent().parse(sent_bytes)

        assert event.notification.session_id == "sess-123"
        assert event.notification.type == ProtoNotificationType.NOTIFICATION_TYPE_ERROR_DETECTED
        assert "my-project" in event.notification.title
        assert "Error" in event.notification.title
        assert event.notification.snippet == "Error: something failed"
        assert event.notification.timestamp > 0

    @pytest.mark.asyncio
    async def test_dispatch_approval_type(self, dispatcher, broadcast_mock):
        """Dispatch sets correct type for approval."""
        match = make_match(type_=NotificationType.APPROVAL)
        await dispatcher.dispatch("s1", "name", match)

        sent_bytes = broadcast_mock.call_args[0][0]
        event = TerminalEvent().parse(sent_bytes)
        assert event.notification.type == ProtoNotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED

    @pytest.mark.asyncio
    async def test_dispatch_completion_type(self, dispatcher, broadcast_mock):
        """Dispatch sets correct type for completion."""
        match = make_match(type_=NotificationType.COMPLETION)
        await dispatcher.dispatch("s1", "name", match)

        sent_bytes = broadcast_mock.call_args[0][0]
        event = TerminalEvent().parse(sent_bytes)
        assert event.notification.type == ProtoNotificationType.NOTIFICATION_TYPE_TASK_COMPLETED

    @pytest.mark.asyncio
    async def test_dispatch_error_type(self, dispatcher, broadcast_mock):
        """Dispatch sets correct type for error."""
        match = make_match(type_=NotificationType.ERROR)
        await dispatcher.dispatch("s1", "name", match)

        sent_bytes = broadcast_mock.call_args[0][0]
        event = TerminalEvent().parse(sent_bytes)
        assert event.notification.type == ProtoNotificationType.NOTIFICATION_TYPE_ERROR_DETECTED


# ============================================================================
# Deduplication and Cooldown Tests (DD01-DD06)
# ============================================================================


class TestDeduplication:
    """Tests for notification deduplication and cooldown."""

    @pytest.mark.asyncio
    async def test_dd01_same_pattern_within_cooldown(self, dispatcher, broadcast_mock):
        """DD01: Same pattern within cooldown triggers only 1 notification."""
        match = make_match(pattern="p1")

        # First dispatch
        result1 = await dispatcher.dispatch("s1", "name", match)
        # Second dispatch immediately (within cooldown)
        result2 = await dispatcher.dispatch("s1", "name", match)

        assert result1 is True
        assert result2 is False  # Suppressed
        assert broadcast_mock.call_count == 1

    @pytest.mark.asyncio
    async def test_dd02_same_pattern_after_cooldown(self, dispatcher, broadcast_mock):
        """DD02: Same pattern after cooldown triggers 2 notifications."""
        match = make_match(pattern="p1")

        # First dispatch
        await dispatcher.dispatch("s1", "name", match)

        # Wait for cooldown
        await asyncio.sleep(0.15)

        # Second dispatch
        result = await dispatcher.dispatch("s1", "name", match)

        assert result is True
        assert broadcast_mock.call_count == 2

    @pytest.mark.asyncio
    async def test_dd03_different_patterns_within_cooldown(
        self, dispatcher, broadcast_mock
    ):
        """DD03: Different patterns within cooldown both trigger."""
        match1 = make_match(type_=NotificationType.APPROVAL, pattern="p1")
        match2 = make_match(type_=NotificationType.ERROR, pattern="p2")

        result1 = await dispatcher.dispatch("s1", "name", match1)
        result2 = await dispatcher.dispatch("s1", "name", match2)

        assert result1 is True
        assert result2 is True
        assert broadcast_mock.call_count == 2

    @pytest.mark.asyncio
    async def test_dd04_rapid_fire(self, dispatcher, broadcast_mock):
        """DD04: Rapid fire (10 in 100ms) triggers only 1."""
        match = make_match(pattern="rapid")

        results = []
        for _ in range(10):
            result = await dispatcher.dispatch("s1", "name", match)
            results.append(result)

        # Only first should succeed
        assert results[0] is True
        assert all(r is False for r in results[1:])
        assert broadcast_mock.call_count == 1

    @pytest.mark.asyncio
    async def test_dd05_different_sessions(self, dispatcher, broadcast_mock):
        """DD05: Different sessions both trigger."""
        match = make_match(pattern="same")

        result1 = await dispatcher.dispatch("session-A", "name-A", match)
        result2 = await dispatcher.dispatch("session-B", "name-B", match)

        assert result1 is True
        assert result2 is True
        assert broadcast_mock.call_count == 2

    @pytest.mark.asyncio
    async def test_dd06_cooldown_partial_wait(self, dispatcher, broadcast_mock):
        """DD06: Match within partial cooldown still suppressed."""
        match = make_match(pattern="p1")

        # First dispatch
        await dispatcher.dispatch("s1", "name", match)

        # Wait 50ms (half of 100ms cooldown)
        await asyncio.sleep(0.05)

        # Second dispatch - should still be suppressed
        result = await dispatcher.dispatch("s1", "name", match)

        assert result is False
        assert broadcast_mock.call_count == 1


# ============================================================================
# Session Management Tests
# ============================================================================


class TestSessionManagement:
    """Tests for session state management."""

    @pytest.mark.asyncio
    async def test_clear_session_allows_immediate_notify(
        self, dispatcher, broadcast_mock
    ):
        """Clearing session allows immediate re-notification."""
        match = make_match()

        # First dispatch
        await dispatcher.dispatch("s1", "name", match)

        # Clear session
        dispatcher.clear_session("s1")

        # Should be able to notify immediately
        result = await dispatcher.dispatch("s1", "name", match)
        assert result is True
        assert broadcast_mock.call_count == 2

    @pytest.mark.asyncio
    async def test_clear_nonexistent_session(self, dispatcher):
        """Clearing nonexistent session doesn't crash."""
        dispatcher.clear_session("nonexistent")
        # Should not raise

    def test_session_count(self, dispatcher):
        """session_count property works."""
        assert dispatcher.session_count == 0

    @pytest.mark.asyncio
    async def test_session_count_increases(self, dispatcher, broadcast_mock):
        """session_count increases with dispatches."""
        await dispatcher.dispatch("s1", "n", make_match())
        assert dispatcher.session_count == 1

        await dispatcher.dispatch("s2", "n", make_match())
        assert dispatcher.session_count == 2

    @pytest.mark.asyncio
    async def test_get_cooldown_remaining(self, dispatcher, broadcast_mock):
        """get_cooldown_remaining returns correct value."""
        # No session yet
        assert dispatcher.get_cooldown_remaining("s1") == 0.0

        # After dispatch
        await dispatcher.dispatch("s1", "name", make_match())
        remaining = dispatcher.get_cooldown_remaining("s1")
        assert 0 < remaining <= 0.1  # Should be within cooldown

        # After cooldown expires
        await asyncio.sleep(0.15)
        remaining = dispatcher.get_cooldown_remaining("s1")
        assert remaining == 0.0


# ============================================================================
# Error Handling Tests
# ============================================================================


class TestErrorHandling:
    """Tests for error handling."""

    @pytest.mark.asyncio
    async def test_broadcast_failure_returns_false(self, config):
        """Broadcast failure returns False."""
        failing_broadcast = AsyncMock(side_effect=Exception("Network error"))
        dispatcher = NotificationDispatcher(failing_broadcast, config)

        result = await dispatcher.dispatch("s1", "name", make_match())

        assert result is False

    @pytest.mark.asyncio
    async def test_broadcast_failure_doesnt_update_cooldown(self, config):
        """Broadcast failure doesn't update cooldown state."""
        failing_broadcast = AsyncMock(side_effect=Exception("Network error"))
        dispatcher = NotificationDispatcher(failing_broadcast, config)

        await dispatcher.dispatch("s1", "name", make_match())

        # Cooldown should not have been set
        assert dispatcher.get_cooldown_remaining("s1") == 0.0


# ============================================================================
# Title and Body Format Tests
# ============================================================================


class TestMessageFormat:
    """Tests for notification message formatting."""

    @pytest.mark.asyncio
    async def test_approval_title_format(self, dispatcher, broadcast_mock):
        """Approval title has correct format."""
        await dispatcher.dispatch(
            "s1", "my-project", make_match(type_=NotificationType.APPROVAL)
        )

        event = TerminalEvent().parse(broadcast_mock.call_args[0][0])
        assert "my-project" in event.notification.title
        assert "Approval" in event.notification.title

    @pytest.mark.asyncio
    async def test_completion_title_format(self, dispatcher, broadcast_mock):
        """Completion title has correct format."""
        await dispatcher.dispatch(
            "s1", "my-project", make_match(type_=NotificationType.COMPLETION)
        )

        event = TerminalEvent().parse(broadcast_mock.call_args[0][0])
        assert "my-project" in event.notification.title
        assert "completed" in event.notification.title

    @pytest.mark.asyncio
    async def test_error_title_format(self, dispatcher, broadcast_mock):
        """Error title has correct format."""
        await dispatcher.dispatch(
            "s1", "my-project", make_match(type_=NotificationType.ERROR)
        )

        event = TerminalEvent().parse(broadcast_mock.call_args[0][0])
        assert "my-project" in event.notification.title
        assert "Error" in event.notification.title

    @pytest.mark.asyncio
    async def test_body_matches_snippet(self, dispatcher, broadcast_mock):
        """Body matches the match snippet."""
        match = make_match(snippet="My test snippet")
        await dispatcher.dispatch("s1", "name", match)

        event = TerminalEvent().parse(broadcast_mock.call_args[0][0])
        assert event.notification.body == "My test snippet"

    @pytest.mark.asyncio
    async def test_timestamp_is_recent(self, dispatcher, broadcast_mock):
        """Timestamp is recent (within last second)."""
        await dispatcher.dispatch("s1", "name", make_match())

        event = TerminalEvent().parse(broadcast_mock.call_args[0][0])
        now_ms = int(time.time() * 1000)
        # Should be within 1 second
        assert abs(event.notification.timestamp - now_ms) < 1000
