"""Tests for TmuxHandler."""

import pytest
from unittest.mock import AsyncMock, MagicMock

from ras.tmux_handler import TmuxHandler
from ras.protocols import TmuxSessionInfo, TmuxWindowInfo
from ras.errors import TmuxError, TmuxSessionError


class MockTmuxService:
    """Mock TmuxService for testing."""

    def __init__(self):
        self.sessions: list[TmuxSessionInfo] = []
        self.windows: dict[str, list[TmuxWindowInfo]] = {}
        self.session_sizes: dict[str, tuple[int, int]] = {}
        self.sent_keys: list[tuple[str, str, bool]] = []
        self.resize_calls: list[tuple[str, int, int]] = []
        self.switch_calls: list[tuple[str, str]] = []
        self.capture_results: dict[str, str] = {}

    async def verify(self):
        """Verify tmux is available."""
        pass

    async def list_sessions(self) -> list[TmuxSessionInfo]:
        """List sessions."""
        return self.sessions

    async def list_windows(self, session_id: str) -> list[TmuxWindowInfo]:
        """List windows."""
        return self.windows.get(session_id, [])

    async def get_session_size(self, session_id: str) -> tuple[int, int]:
        """Get session size."""
        return self.session_sizes.get(session_id, (24, 80))

    async def resize_session(self, session_id: str, rows: int, cols: int):
        """Resize session."""
        self.resize_calls.append((session_id, rows, cols))

    async def send_keys(self, session_id: str, keys: str, literal: bool = True):
        """Send keys."""
        self.sent_keys.append((session_id, keys, literal))

    async def switch_window(self, session_id: str, window_id: str):
        """Switch window."""
        self.switch_calls.append((session_id, window_id))

    async def capture_pane(self, session_id: str, lines: int = 100) -> str:
        """Capture pane."""
        return self.capture_results.get(session_id, "")


class MockSessionRegistry:
    """Mock SessionRegistry for testing."""

    def __init__(self):
        self.sessions: dict[str, MagicMock] = {}

    def add(self, session):
        self.sessions[session.session_id] = session

    def get(self, session_id: str):
        return self.sessions.get(session_id)

    def remove(self, session_id: str):
        return self.sessions.pop(session_id, None)

    def list_all(self):
        return list(self.sessions.values())

    def __contains__(self, session_id: str):
        return session_id in self.sessions


class TestTmuxHandlerCreation:
    """Test TmuxHandler creation."""

    def test_create_with_dependencies(self):
        """Can create TmuxHandler with injected dependencies."""
        service = MockTmuxService()
        registry = MockSessionRegistry()

        handler = TmuxHandler(
            tmux_service=service,
            session_registry=registry,
        )

        assert handler._tmux_service is service
        assert handler._session_registry is registry


class TestListSessions:
    """Test list_sessions handler."""

    @pytest.mark.asyncio
    async def test_list_sessions_returns_sessions(self):
        """list_sessions returns list of session info."""
        service = MockTmuxService()
        service.sessions = [
            TmuxSessionInfo(id="$0", name="claude", windows=2, attached=False),
            TmuxSessionInfo(id="$1", name="aider", windows=1, attached=True),
        ]
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        result = await handler.list_sessions()

        assert len(result) == 2
        assert result[0]["id"] == "$0"
        assert result[0]["name"] == "claude"
        assert result[0]["windows"] == 2
        assert result[0]["attached"] is False

    @pytest.mark.asyncio
    async def test_list_sessions_empty(self):
        """list_sessions returns empty list when no sessions."""
        service = MockTmuxService()
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        result = await handler.list_sessions()

        assert result == []


class TestListWindows:
    """Test list_windows handler."""

    @pytest.mark.asyncio
    async def test_list_windows_returns_windows(self):
        """list_windows returns list of window info."""
        service = MockTmuxService()
        service.windows["$0"] = [
            TmuxWindowInfo(id="@0", name="main", active=True),
            TmuxWindowInfo(id="@1", name="build", active=False),
        ]
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        result = await handler.list_windows("$0")

        assert len(result) == 2
        assert result[0]["id"] == "@0"
        assert result[0]["name"] == "main"
        assert result[0]["active"] is True


class TestSendKeys:
    """Test send_keys handler."""

    @pytest.mark.asyncio
    async def test_send_keys_forwards_to_service(self):
        """send_keys forwards to TmuxService."""
        service = MockTmuxService()
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        await handler.send_keys("$0", "echo hello\n")

        assert ("$0", "echo hello\n", True) in service.sent_keys

    @pytest.mark.asyncio
    async def test_send_keys_literal_false(self):
        """send_keys respects literal=False."""
        service = MockTmuxService()
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        await handler.send_keys("$0", "C-c", literal=False)

        assert ("$0", "C-c", False) in service.sent_keys


class TestResizeSession:
    """Test resize_session handler."""

    @pytest.mark.asyncio
    async def test_resize_session_forwards_to_service(self):
        """resize_session forwards to TmuxService."""
        service = MockTmuxService()
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        await handler.resize_session("$0", rows=40, cols=100)

        assert ("$0", 40, 100) in service.resize_calls


class TestSwitchWindow:
    """Test switch_window handler."""

    @pytest.mark.asyncio
    async def test_switch_window_forwards_to_service(self):
        """switch_window forwards to TmuxService."""
        service = MockTmuxService()
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        await handler.switch_window("$0", "@1")

        assert ("$0", "@1") in service.switch_calls


class TestCapture:
    """Test capture handler."""

    @pytest.mark.asyncio
    async def test_capture_returns_content(self):
        """capture returns pane content."""
        service = MockTmuxService()
        service.capture_results["$0"] = "$ echo hello\nhello\n$ "
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        result = await handler.capture("$0", lines=50)

        assert "hello" in result


class TestGetSessionSize:
    """Test get_session_size handler."""

    @pytest.mark.asyncio
    async def test_get_session_size_returns_size(self):
        """get_session_size returns rows and cols."""
        service = MockTmuxService()
        service.session_sizes["$0"] = (40, 120)
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        result = await handler.get_session_size("$0")

        assert result == {"rows": 40, "cols": 120}


class TestErrorHandling:
    """Test error handling in handlers."""

    @pytest.mark.asyncio
    async def test_tmux_error_propagates(self):
        """TmuxError from service propagates to caller."""
        service = MockTmuxService()
        service.list_sessions = AsyncMock(side_effect=TmuxError("tmux failed"))
        registry = MockSessionRegistry()
        handler = TmuxHandler(tmux_service=service, session_registry=registry)

        with pytest.raises(TmuxError, match="tmux failed"):
            await handler.list_sessions()
