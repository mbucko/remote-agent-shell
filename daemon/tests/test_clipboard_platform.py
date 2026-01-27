"""Tests for clipboard platform detection and backends."""

import asyncio
import platform
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.clipboard_types import ImageFormat


# ============================================================================
# Platform Detection Tests
# ============================================================================


class TestPlatformDetection:
    """Test platform detection logic."""

    def test_detect_macos(self):
        """Detects macOS platform correctly."""
        from ras.clipboard_platform import detect_platform

        with patch("platform.system", return_value="Darwin"):
            info = detect_platform()

        assert info.system == "Darwin"
        assert info.display_server is None
        assert info.clipboard_tool == "pbcopy"
        assert info.paste_keystroke == "M-v"  # Cmd+V in tmux

    def test_detect_linux_x11(self):
        """Detects Linux with X11."""
        from ras.clipboard_platform import detect_platform

        with patch("platform.system", return_value="Linux"):
            with patch.dict("os.environ", {"XDG_SESSION_TYPE": "x11"}, clear=True):
                info = detect_platform()

        assert info.system == "Linux"
        assert info.display_server == "x11"
        assert info.clipboard_tool == "xclip"
        assert info.paste_keystroke == "C-v"  # Ctrl+V in tmux

    def test_detect_linux_wayland(self):
        """Detects Linux with Wayland."""
        from ras.clipboard_platform import detect_platform

        with patch("platform.system", return_value="Linux"):
            with patch.dict(
                "os.environ",
                {"XDG_SESSION_TYPE": "wayland", "WAYLAND_DISPLAY": "wayland-0"},
                clear=True,
            ):
                info = detect_platform()

        assert info.system == "Linux"
        assert info.display_server == "wayland"
        assert info.clipboard_tool == "wl-copy"
        assert info.paste_keystroke == "C-v"

    def test_detect_linux_wayland_by_display(self):
        """Detects Wayland via WAYLAND_DISPLAY even without XDG_SESSION_TYPE."""
        from ras.clipboard_platform import detect_platform

        with patch("platform.system", return_value="Linux"):
            with patch.dict("os.environ", {"WAYLAND_DISPLAY": "wayland-0"}, clear=True):
                info = detect_platform()

        assert info.display_server == "wayland"
        assert info.clipboard_tool == "wl-copy"

    def test_detect_unsupported_platform(self):
        """Raises error for unsupported platform."""
        from ras.clipboard_platform import detect_platform, ClipboardUnavailableError

        with patch("platform.system", return_value="Windows"):
            with pytest.raises(ClipboardUnavailableError) as exc_info:
                detect_platform()

        assert "Unsupported platform" in str(exc_info.value)
        assert "Windows" in str(exc_info.value)


# ============================================================================
# Clipboard Tool Check Tests
# ============================================================================


class TestClipboardToolCheck:
    """Test clipboard tool availability checking."""

    def test_macos_pbcopy_available(self):
        """pbcopy is available on macOS."""
        from ras.clipboard_platform import check_clipboard_tool

        with patch("shutil.which", return_value="/usr/bin/pbcopy"):
            # Should not raise
            check_clipboard_tool("pbcopy")

    def test_macos_pbcopy_missing(self):
        """Error when pbcopy is missing."""
        from ras.clipboard_platform import check_clipboard_tool, ClipboardUnavailableError

        with patch("shutil.which", return_value=None):
            with pytest.raises(ClipboardUnavailableError) as exc_info:
                check_clipboard_tool("pbcopy")

        assert "pbcopy not found" in str(exc_info.value)

    def test_linux_xclip_available(self):
        """xclip is available."""
        from ras.clipboard_platform import check_clipboard_tool

        with patch("shutil.which", return_value="/usr/bin/xclip"):
            check_clipboard_tool("xclip")

    def test_linux_xclip_missing(self):
        """Clear error when xclip missing with install instructions."""
        from ras.clipboard_platform import check_clipboard_tool, ClipboardUnavailableError

        with patch("shutil.which", return_value=None):
            with pytest.raises(ClipboardUnavailableError) as exc_info:
                check_clipboard_tool("xclip")

        error_msg = str(exc_info.value)
        assert "xclip not found" in error_msg
        assert "apt install xclip" in error_msg

    def test_linux_wl_copy_available(self):
        """wl-copy is available."""
        from ras.clipboard_platform import check_clipboard_tool

        with patch("shutil.which", return_value="/usr/bin/wl-copy"):
            check_clipboard_tool("wl-copy")

    def test_linux_wl_copy_missing(self):
        """Clear error when wl-copy missing with install instructions."""
        from ras.clipboard_platform import check_clipboard_tool, ClipboardUnavailableError

        with patch("shutil.which", return_value=None):
            with pytest.raises(ClipboardUnavailableError) as exc_info:
                check_clipboard_tool("wl-copy")

        error_msg = str(exc_info.value)
        assert "wl-copy not found" in error_msg
        assert "wl-clipboard" in error_msg

    def test_unknown_tool(self):
        """Error for unknown clipboard tool."""
        from ras.clipboard_platform import check_clipboard_tool, ClipboardUnavailableError

        with pytest.raises(ClipboardUnavailableError) as exc_info:
            check_clipboard_tool("unknown-tool")

        assert "Unknown clipboard tool" in str(exc_info.value)


# ============================================================================
# Backend Factory Tests
# ============================================================================


class TestGetClipboardBackend:
    """Test clipboard backend factory."""

    def test_get_macos_backend(self):
        """Returns MacOSClipboard for macOS."""
        from ras.clipboard_platform import (
            get_clipboard_backend,
            PlatformInfo,
            MacOSClipboard,
        )

        info = PlatformInfo(
            system="Darwin",
            display_server=None,
            clipboard_tool="pbcopy",
            paste_keystroke="M-v",
        )

        backend = get_clipboard_backend(info)
        assert isinstance(backend, MacOSClipboard)

    def test_get_linux_x11_backend_not_implemented(self):
        """Linux X11 backend raises NotImplementedError."""
        from ras.clipboard_platform import get_clipboard_backend, PlatformInfo

        info = PlatformInfo(
            system="Linux",
            display_server="x11",
            clipboard_tool="xclip",
            paste_keystroke="C-v",
        )

        with pytest.raises(NotImplementedError) as exc_info:
            get_clipboard_backend(info)

        assert "Linux" in str(exc_info.value)

    def test_get_linux_wayland_backend_not_implemented(self):
        """Linux Wayland backend raises NotImplementedError."""
        from ras.clipboard_platform import get_clipboard_backend, PlatformInfo

        info = PlatformInfo(
            system="Linux",
            display_server="wayland",
            clipboard_tool="wl-copy",
            paste_keystroke="C-v",
        )

        with pytest.raises(NotImplementedError) as exc_info:
            get_clipboard_backend(info)

        assert "Linux" in str(exc_info.value)


# ============================================================================
# MockClipboard Tests
# ============================================================================


class TestMockClipboard:
    """Test MockClipboard backend for testing."""

    @pytest.mark.asyncio
    async def test_set_image(self):
        """MockClipboard stores image data."""
        from ras.clipboard_platform import MockClipboard

        backend = MockClipboard()
        data = b"\x89PNG\r\n\x1a\n..."

        await backend.set_image(data, ImageFormat.PNG)

        assert backend.last_image_data == data
        assert backend.last_image_format == ImageFormat.PNG
        assert backend.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_set_text(self):
        """MockClipboard stores text."""
        from ras.clipboard_platform import MockClipboard

        backend = MockClipboard()
        text = "Hello, clipboard!"

        await backend.set_text(text)

        assert backend.last_text == text
        assert backend.set_text_call_count == 1

    @pytest.mark.asyncio
    async def test_multiple_calls_tracked(self):
        """MockClipboard tracks multiple calls."""
        from ras.clipboard_platform import MockClipboard

        backend = MockClipboard()

        await backend.set_text("first")
        await backend.set_text("second")
        await backend.set_image(b"img1", ImageFormat.JPEG)
        await backend.set_image(b"img2", ImageFormat.PNG)

        assert backend.set_text_call_count == 2
        assert backend.set_image_call_count == 2
        assert backend.last_text == "second"
        assert backend.last_image_data == b"img2"

    @pytest.mark.asyncio
    async def test_simulate_failure(self):
        """MockClipboard can simulate failures."""
        from ras.clipboard_platform import MockClipboard, ClipboardUnavailableError

        backend = MockClipboard()
        backend.fail_with = ClipboardUnavailableError("Simulated failure")

        with pytest.raises(ClipboardUnavailableError) as exc_info:
            await backend.set_text("test")

        assert "Simulated failure" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_simulate_delay(self):
        """MockClipboard can simulate delay."""
        from ras.clipboard_platform import MockClipboard

        backend = MockClipboard()
        backend.delay_seconds = 0.1

        start = asyncio.get_event_loop().time()
        await backend.set_text("test")
        elapsed = asyncio.get_event_loop().time() - start

        assert elapsed >= 0.1

    @pytest.mark.asyncio
    async def test_reset(self):
        """MockClipboard can be reset."""
        from ras.clipboard_platform import MockClipboard

        backend = MockClipboard()
        await backend.set_text("test")
        await backend.set_image(b"data", ImageFormat.PNG)

        backend.reset()

        assert backend.last_text is None
        assert backend.last_image_data is None
        assert backend.set_text_call_count == 0
        assert backend.set_image_call_count == 0


# ============================================================================
# MacOSClipboard Tests (with mocked subprocess)
# ============================================================================


class TestMacOSClipboard:
    """Test MacOSClipboard backend."""

    @pytest.mark.asyncio
    async def test_set_text_calls_pbcopy(self):
        """set_text uses pbcopy."""
        from ras.clipboard_platform import MacOSClipboard

        backend = MacOSClipboard()

        mock_proc = AsyncMock()
        mock_proc.returncode = 0
        mock_proc.communicate = AsyncMock(return_value=(b"", b""))

        with patch("asyncio.create_subprocess_exec", return_value=mock_proc) as mock_exec:
            await backend.set_text("Hello, world!")

        mock_exec.assert_called_once()
        call_args = mock_exec.call_args
        assert "pbcopy" in call_args[0]

        # Verify text was passed to stdin
        mock_proc.communicate.assert_called_once()
        input_data = mock_proc.communicate.call_args[1]["input"]
        assert input_data == b"Hello, world!"

    @pytest.mark.asyncio
    async def test_set_text_with_unicode(self):
        """set_text handles unicode correctly."""
        from ras.clipboard_platform import MacOSClipboard

        backend = MacOSClipboard()

        mock_proc = AsyncMock()
        mock_proc.returncode = 0
        mock_proc.communicate = AsyncMock(return_value=(b"", b""))

        with patch("asyncio.create_subprocess_exec", return_value=mock_proc):
            await backend.set_text("Hello, 世界! 🎉")

        input_data = mock_proc.communicate.call_args[1]["input"]
        assert input_data == "Hello, 世界! 🎉".encode("utf-8")

    @pytest.mark.asyncio
    async def test_set_text_failure(self):
        """set_text raises on pbcopy failure."""
        from ras.clipboard_platform import MacOSClipboard, ClipboardUnavailableError

        backend = MacOSClipboard()

        mock_proc = AsyncMock()
        mock_proc.returncode = 1
        mock_proc.communicate = AsyncMock(return_value=(b"", b"pbcopy error"))

        with patch("asyncio.create_subprocess_exec", return_value=mock_proc):
            with pytest.raises(ClipboardUnavailableError) as exc_info:
                await backend.set_text("test")

        assert "pbcopy failed" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_set_image_uses_osascript(self):
        """set_image uses osascript for macOS."""
        from ras.clipboard_platform import MacOSClipboard

        backend = MacOSClipboard()
        image_data = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100

        mock_proc = AsyncMock()
        mock_proc.returncode = 0
        mock_proc.communicate = AsyncMock(return_value=(b"", b""))

        with patch("asyncio.create_subprocess_exec", return_value=mock_proc) as mock_exec:
            await backend.set_image(image_data, ImageFormat.PNG)

        mock_exec.assert_called()
        call_args = mock_exec.call_args
        # Should use osascript or a Swift helper
        args = call_args[0]
        # The implementation should use some macOS-specific tool
        assert any("osascript" in str(arg) or "swift" in str(arg).lower() for arg in args) or "osascript" in str(call_args)

    @pytest.mark.asyncio
    async def test_set_image_failure(self):
        """set_image raises on failure."""
        from ras.clipboard_platform import MacOSClipboard, ClipboardUnavailableError

        backend = MacOSClipboard()

        mock_proc = AsyncMock()
        mock_proc.returncode = 1
        mock_proc.communicate = AsyncMock(return_value=(b"", b"clipboard error"))

        with patch("asyncio.create_subprocess_exec", return_value=mock_proc):
            with pytest.raises(ClipboardUnavailableError):
                await backend.set_image(b"data", ImageFormat.PNG)


# ============================================================================
# ClipboardBackend Protocol Tests
# ============================================================================


class TestClipboardBackendProtocol:
    """Test that backends implement the protocol correctly."""

    @pytest.mark.asyncio
    async def test_mock_backend_implements_protocol(self):
        """MockClipboard implements ClipboardBackend protocol."""
        from ras.clipboard_platform import MockClipboard, ClipboardBackend

        backend = MockClipboard()

        # Should have required methods
        assert hasattr(backend, "set_image")
        assert hasattr(backend, "set_text")
        assert callable(backend.set_image)
        assert callable(backend.set_text)

    @pytest.mark.asyncio
    async def test_macos_backend_implements_protocol(self):
        """MacOSClipboard implements ClipboardBackend protocol."""
        from ras.clipboard_platform import MacOSClipboard, ClipboardBackend

        backend = MacOSClipboard()

        # Should have required methods
        assert hasattr(backend, "set_image")
        assert hasattr(backend, "set_text")
        assert callable(backend.set_image)
        assert callable(backend.set_text)


# ============================================================================
# PlatformInfo Tests
# ============================================================================


class TestPlatformInfo:
    """Test PlatformInfo dataclass."""

    def test_platform_info_creation(self):
        """PlatformInfo can be created with all fields."""
        from ras.clipboard_platform import PlatformInfo

        info = PlatformInfo(
            system="Darwin",
            display_server=None,
            clipboard_tool="pbcopy",
            paste_keystroke="M-v",
        )

        assert info.system == "Darwin"
        assert info.display_server is None
        assert info.clipboard_tool == "pbcopy"
        assert info.paste_keystroke == "M-v"

    def test_platform_info_frozen(self):
        """PlatformInfo is immutable (frozen)."""
        from ras.clipboard_platform import PlatformInfo

        info = PlatformInfo(
            system="Darwin",
            display_server=None,
            clipboard_tool="pbcopy",
            paste_keystroke="M-v",
        )

        with pytest.raises(AttributeError):
            info.system = "Linux"
