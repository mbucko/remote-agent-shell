"""Integration tests for notification system with TerminalManager.

Tests the complete flow from terminal output capture through pattern
matching to notification dispatch.

Covers:
- IT01: Full flow - output → match → dispatch → WebRTC
- IT02: Session lifecycle - attach → match → detach → match
- IT03: Multiple sessions - both notify independently
- IT04: Session kill cleanup - no stale state
"""

import asyncio
from unittest.mock import AsyncMock, Mock

import pytest


from ras.terminal.manager import TerminalManager
from ras.notifications.types import NotificationConfig, NotificationType
from ras.proto.ras import TerminalEvent, NotificationType as ProtoNotificationType


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
def broadcast_mock():
    """Mock broadcast function that captures sent notifications."""
    mock = AsyncMock()
    mock.sent_events = []

    async def capture(data: bytes):
        event = TerminalEvent().parse(data)
        mock.sent_events.append(event)

    mock.side_effect = capture
    return mock


@pytest.fixture
def notification_config():
    """Notification config with short cooldown for fast tests."""
    cfg = NotificationConfig.default()
    return NotificationConfig(
        approval_patterns=cfg.approval_patterns,
        error_patterns=cfg.error_patterns,
        shell_prompt_patterns=cfg.shell_prompt_patterns,
        cooldown_seconds=0.1,  # Fast cooldown for tests
        regex_timeout_ms=cfg.regex_timeout_ms,
        sliding_window_size=cfg.sliding_window_size,
        snippet_context_chars=cfg.snippet_context_chars,
        max_snippet_length=cfg.max_snippet_length,
    )


@pytest.fixture
def send_event_mock():
    """Mock for send_event callback."""
    return Mock()


@pytest.fixture
def terminal_manager(
    mock_session_provider,
    mock_tmux_executor,
    send_event_mock,
    broadcast_mock,
    notification_config,
):
    """TerminalManager with notification support enabled."""
    return TerminalManager(
        session_provider=mock_session_provider,
        tmux_executor=mock_tmux_executor,
        send_event=send_event_mock,
        broadcast_notification=broadcast_mock,
        notification_config=notification_config,
    )


async def process_pending_tasks():
    """Allow pending async tasks to complete."""
    pending = asyncio.all_tasks() - {asyncio.current_task()}
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)


# ============================================================================
# Integration Tests (IT01-IT05)
# ============================================================================


class TestTerminalManagerIntegration:
    """Integration tests for TerminalManager notification support."""

    @pytest.mark.asyncio
    async def test_it01_full_flow_output_to_notification(
        self, terminal_manager, broadcast_mock
    ):
        """IT01: Full flow - terminal output triggers notification.

        1. Terminal outputs "Proceed? (y/n)"
        2. Manager processes output
        3. Pattern matcher detects prompt
        4. Dispatcher creates notification
        5. Broadcast receives notification bytes
        """
        # Simulate terminal output callback
        terminal_manager._buffers["session-1"] = Mock()
        terminal_manager._buffers["session-1"].append.return_value = 1
        terminal_manager._attachments["session-1"] = set()

        # Process output with approval prompt
        terminal_manager._on_output("session-1", b"Making changes...\nProceed? (y/n)")

        # Allow async tasks to complete
        await process_pending_tasks()

        # Verify notification was broadcast
        assert len(broadcast_mock.sent_events) >= 1
        event = broadcast_mock.sent_events[0]
        assert event.notification.session_id == "session-1"
        assert event.notification.type == ProtoNotificationType.APPROVAL_NEEDED
        assert "Test Project" in event.notification.title

    @pytest.mark.asyncio
    async def test_it02_session_lifecycle(self, terminal_manager, broadcast_mock):
        """IT02: Session lifecycle - attach, match, kill, no stale state.

        1. Session attaches
        2. Output matches pattern
        3. Notification sent
        4. Session killed
        5. State cleared
        """
        # Setup session
        terminal_manager._buffers["session-1"] = Mock()
        terminal_manager._buffers["session-1"].append.return_value = 1
        terminal_manager._attachments["session-1"] = set()

        # Match before kill
        terminal_manager._on_output("session-1", b"Error: something failed\n")

        await process_pending_tasks()

        assert len(broadcast_mock.sent_events) >= 1

        # Kill session
        await terminal_manager.on_session_killed("session-1")

        # Verify state cleared
        assert "session-1" not in terminal_manager._notification_matchers
        # Dispatcher state should also be cleared
        assert terminal_manager._notification_dispatcher.session_count == 0

    @pytest.mark.asyncio
    async def test_it03_multiple_sessions_independent(
        self, terminal_manager, broadcast_mock, mock_session_provider
    ):
        """IT03: Multiple sessions notify independently.

        1. Session A and B both active
        2. Both have pattern matches
        3. Both notifications sent
        4. Notifications have correct session IDs
        """
        # Setup sessions
        for session_id in ["session-A", "session-B"]:
            terminal_manager._buffers[session_id] = Mock()
            terminal_manager._buffers[session_id].append.return_value = 1
            terminal_manager._attachments[session_id] = set()

        # Configure different names for sessions
        def get_session(sid):
            return {
                "tmux_name": f"tmux-{sid}",
                "status": "RUNNING",
                "display_name": f"Project-{sid[-1]}",
            }

        mock_session_provider.get_session.side_effect = get_session

        # Both sessions have errors
        terminal_manager._on_output("session-A", b"Error: A failed\n")
        terminal_manager._on_output("session-B", b"Error: B failed\n")

        await process_pending_tasks()

        # Both should have notifications
        assert len(broadcast_mock.sent_events) >= 2
        session_ids = {e.notification.session_id for e in broadcast_mock.sent_events}
        assert session_ids == {"session-A", "session-B"}

    @pytest.mark.asyncio
    async def test_it04_session_kill_clears_state(
        self, terminal_manager, broadcast_mock
    ):
        """IT04: Session kill clears all notification state.

        1. Session matches pattern
        2. Cooldown active
        3. Session killed
        4. Cooldown state cleared
        5. New session can notify immediately
        """
        terminal_manager._buffers["session-1"] = Mock()
        terminal_manager._buffers["session-1"].append.return_value = 1
        terminal_manager._attachments["session-1"] = set()

        # First match
        terminal_manager._on_output("session-1", b"Proceed? (y/n)")

        await process_pending_tasks()

        initial_count = len(broadcast_mock.sent_events)
        assert initial_count >= 1

        # Verify cooldown is active
        remaining = terminal_manager._notification_dispatcher.get_cooldown_remaining(
            "session-1"
        )
        assert remaining > 0

        # Kill session
        await terminal_manager.on_session_killed("session-1")

        # State should be cleared
        assert "session-1" not in terminal_manager._notification_matchers

        # Re-create session and match immediately
        terminal_manager._buffers["session-1"] = Mock()
        terminal_manager._buffers["session-1"].append.return_value = 1
        terminal_manager._attachments["session-1"] = set()

        terminal_manager._on_output("session-1", b"Proceed? (y/n)")
        await process_pending_tasks()

        # Should have sent new notification immediately (no cooldown)
        assert len(broadcast_mock.sent_events) > initial_count


# ============================================================================
# Notification Disabled Tests
# ============================================================================


class TestNotificationDisabled:
    """Tests for when notifications are disabled."""

    def test_no_broadcast_no_notifications(
        self, mock_session_provider, mock_tmux_executor, send_event_mock
    ):
        """No broadcast function means no notifications."""
        manager = TerminalManager(
            session_provider=mock_session_provider,
            tmux_executor=mock_tmux_executor,
            send_event=send_event_mock,
            broadcast_notification=None,  # Disabled
        )

        # Process output
        manager._buffers["session-1"] = Mock()
        manager._buffers["session-1"].append.return_value = 1
        manager._attachments["session-1"] = set()

        # Should not crash (no event loop needed since no async)
        manager._on_output("session-1", b"Proceed? (y/n)")

        # No notification matchers should be created
        assert "session-1" not in manager._notification_matchers


# ============================================================================
# Per-Session Matcher Tests
# ============================================================================


class TestPerSessionMatcher:
    """Tests for per-session matcher isolation."""

    @pytest.mark.asyncio
    async def test_sessions_have_independent_alt_screen_state(
        self, terminal_manager, broadcast_mock
    ):
        """Each session tracks alternate screen independently.

        1. Session A enters vim (alt screen)
        2. Session B not in alt screen
        3. Session A prompt suppressed
        4. Session B prompt notifies
        """
        for sid in ["session-A", "session-B"]:
            terminal_manager._buffers[sid] = Mock()
            terminal_manager._buffers[sid].append.return_value = 1
            terminal_manager._attachments[sid] = set()

        # Session A enters alt screen
        terminal_manager._on_output("session-A", b"\x1b[?1049h")

        await process_pending_tasks()

        # Both have prompts
        terminal_manager._on_output("session-A", b"Save? (y/n)")  # Suppressed
        terminal_manager._on_output("session-B", b"Proceed? (y/n)")  # Should notify

        await process_pending_tasks()

        # Only session B should have notification
        assert len(broadcast_mock.sent_events) >= 1
        for event in broadcast_mock.sent_events:
            assert event.notification.session_id == "session-B"

    @pytest.mark.asyncio
    async def test_sessions_have_independent_buffers(
        self, terminal_manager, broadcast_mock, mock_session_provider
    ):
        """Each session has independent sliding window buffer.

        1. Session A gets first part of error
        2. Session B gets second part
        3. No match (buffers are separate)
        """

        def get_session(sid):
            return {
                "tmux_name": f"tmux-{sid}",
                "status": "RUNNING",
                "display_name": f"Project-{sid[-1]}",
            }

        mock_session_provider.get_session.side_effect = get_session

        for sid in ["session-A", "session-B"]:
            terminal_manager._buffers[sid] = Mock()
            terminal_manager._buffers[sid].append.return_value = 1
            terminal_manager._attachments[sid] = set()

        # Split error pattern across sessions (should NOT match)
        # "Error:" requires both parts to be in same buffer
        terminal_manager._on_output("session-A", b"\nErr")
        terminal_manager._on_output("session-B", b"or: something failed")

        await process_pending_tasks()

        # Should NOT have matched - "Err" alone doesn't match, and
        # "or: something failed" doesn't match error patterns either
        error_events = [
            e
            for e in broadcast_mock.sent_events
            if e.notification.type == ProtoNotificationType.ERROR_DETECTED
        ]
        assert len(error_events) == 0


# ============================================================================
# Edge Cases
# ============================================================================


class TestEdgeCases:
    """Edge case tests for terminal notification integration."""

    @pytest.mark.asyncio
    async def test_session_not_found_graceful(
        self, terminal_manager, broadcast_mock, mock_session_provider
    ):
        """Missing session info handled gracefully."""
        mock_session_provider.get_session.return_value = None

        terminal_manager._buffers["session-1"] = Mock()
        terminal_manager._buffers["session-1"].append.return_value = 1
        terminal_manager._attachments["session-1"] = set()

        # Should not crash
        terminal_manager._on_output("session-1", b"Proceed? (y/n)")

        await process_pending_tasks()

        # Should still notify (using session_id as name)
        assert len(broadcast_mock.sent_events) >= 1
        event = broadcast_mock.sent_events[0]
        assert "session-1" in event.notification.title

    @pytest.mark.asyncio
    async def test_broadcast_failure_no_crash(
        self,
        mock_session_provider,
        mock_tmux_executor,
        send_event_mock,
        notification_config,
    ):
        """Broadcast failure doesn't crash the manager."""
        failing_broadcast = AsyncMock(side_effect=Exception("Network error"))

        manager = TerminalManager(
            session_provider=mock_session_provider,
            tmux_executor=mock_tmux_executor,
            send_event=send_event_mock,
            broadcast_notification=failing_broadcast,
            notification_config=notification_config,
        )

        manager._buffers["session-1"] = Mock()
        manager._buffers["session-1"].append.return_value = 1
        manager._attachments["session-1"] = set()

        # Should not crash
        manager._on_output("session-1", b"Proceed? (y/n)")

        await process_pending_tasks()

        # No crash means success

    @pytest.mark.asyncio
    async def test_empty_output_no_crash(self, terminal_manager, broadcast_mock):
        """Empty output doesn't crash."""
        terminal_manager._buffers["session-1"] = Mock()
        terminal_manager._buffers["session-1"].append.return_value = 1
        terminal_manager._attachments["session-1"] = set()

        # Should not crash
        terminal_manager._on_output("session-1", b"")

        await process_pending_tasks()

        # No notifications expected
        assert len(broadcast_mock.sent_events) == 0

    @pytest.mark.asyncio
    async def test_shutdown_clears_notification_state(
        self, terminal_manager, broadcast_mock
    ):
        """Shutdown clears all notification state."""
        terminal_manager._buffers["session-1"] = Mock()
        terminal_manager._buffers["session-1"].append.return_value = 1
        terminal_manager._attachments["session-1"] = set()

        # Create matcher
        terminal_manager._on_output("session-1", b"Proceed? (y/n)")

        await process_pending_tasks()

        assert "session-1" in terminal_manager._notification_matchers

        await terminal_manager.shutdown()

        # Should clear matchers
        assert len(terminal_manager._notification_matchers) == 0
