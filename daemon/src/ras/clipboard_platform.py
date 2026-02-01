"""Platform-specific clipboard handling.

Provides platform detection and clipboard backends for macOS.
Linux backends are not yet implemented (raise NotImplementedError).
"""

import asyncio
import logging
import os
import platform
import shutil
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional

from .clipboard_types import ImageFormat

logger = logging.getLogger(__name__)


class ClipboardUnavailableError(Exception):
    """Clipboard tool not available or operation failed."""

    pass


@dataclass(frozen=True)
class PlatformInfo:
    """Information about the current platform."""

    system: str  # "Darwin" or "Linux"
    display_server: Optional[str]  # "x11", "wayland", or None
    clipboard_tool: str  # "pbcopy", "xclip", "wl-copy"
    paste_keystroke: str  # "M-v" (Cmd+V) or "C-v" (Ctrl+V) for tmux


def detect_platform() -> PlatformInfo:
    """Detect platform and appropriate clipboard tool.

    Returns:
        PlatformInfo with detected settings.

    Raises:
        ClipboardUnavailableError: If platform is not supported.
    """
    system = platform.system()

    if system == "Darwin":
        logger.debug("Detected macOS platform")
        return PlatformInfo(
            system="Darwin",
            display_server=None,
            clipboard_tool="pbcopy",
            paste_keystroke="M-v",  # Cmd+V in tmux
        )
    elif system == "Linux":
        # Detect display server
        session_type = os.environ.get("XDG_SESSION_TYPE", "").lower()
        wayland_display = os.environ.get("WAYLAND_DISPLAY")

        if session_type == "wayland" or wayland_display:
            logger.debug("Detected Linux Wayland platform")
            return PlatformInfo(
                system="Linux",
                display_server="wayland",
                clipboard_tool="wl-copy",
                paste_keystroke="C-v",
            )
        else:
            logger.debug("Detected Linux X11 platform")
            return PlatformInfo(
                system="Linux",
                display_server="x11",
                clipboard_tool="xclip",
                paste_keystroke="C-v",
            )
    else:
        raise ClipboardUnavailableError(f"Unsupported platform: {system}")


def check_clipboard_tool(tool: str) -> None:
    """Check if clipboard tool is available.

    Args:
        tool: Name of the clipboard tool to check.

    Raises:
        ClipboardUnavailableError: If tool is not available.
    """
    if tool == "pbcopy":
        if not shutil.which("pbcopy"):
            raise ClipboardUnavailableError("pbcopy not found")
    elif tool == "xclip":
        if not shutil.which("xclip"):
            raise ClipboardUnavailableError(
                "xclip not found. Install with: sudo apt install xclip"
            )
    elif tool == "wl-copy":
        if not shutil.which("wl-copy"):
            raise ClipboardUnavailableError(
                "wl-copy not found. Install with: sudo apt install wl-clipboard"
            )
    else:
        raise ClipboardUnavailableError(f"Unknown clipboard tool: {tool}")

    logger.debug("Clipboard tool '%s' is available", tool)


class ClipboardBackend(ABC):
    """Abstract base class for clipboard backends."""

    @abstractmethod
    async def set_image(self, data: bytes, format: ImageFormat) -> None:
        """Set image data to clipboard.

        Args:
            data: Raw image bytes.
            format: Image format (JPEG, PNG, etc.).

        Raises:
            ClipboardUnavailableError: If clipboard operation fails.
        """
        pass

    @abstractmethod
    async def set_text(self, text: str) -> None:
        """Set text to clipboard.

        Args:
            text: Text to copy.

        Raises:
            ClipboardUnavailableError: If clipboard operation fails.
        """
        pass


class MacOSClipboard(ClipboardBackend):
    """Clipboard backend for macOS using pbcopy and osascript."""

    async def set_text(self, text: str) -> None:
        """Set text using pbcopy."""
        logger.debug("Setting text to clipboard (%d bytes)", len(text.encode("utf-8")))

        proc = await asyncio.create_subprocess_exec(
            "pbcopy",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate(input=text.encode("utf-8"))

        if proc.returncode != 0:
            raise ClipboardUnavailableError(f"pbcopy failed: {stderr.decode()}")

        logger.debug("Text copied to clipboard successfully")

    async def set_image(self, data: bytes, format: ImageFormat) -> None:
        """Set image using osascript with AppleScript.

        Uses a temporary file approach since piping binary data to
        osascript is unreliable.
        """
        import tempfile

        logger.debug(
            "Setting image to clipboard (%d bytes, format=%s)",
            len(data),
            format.name,
        )

        # Map format to file extension
        ext_map = {
            ImageFormat.JPEG: ".jpg",
            ImageFormat.PNG: ".png",
            ImageFormat.GIF: ".gif",
            ImageFormat.WEBP: ".webp",
        }
        ext = ext_map.get(format, ".png")

        # Write to temp file, set clipboard, delete file
        with tempfile.NamedTemporaryFile(suffix=ext, delete=False) as f:
            f.write(data)
            temp_path = f.name

        try:
            # Map format to AppleScript class
            # PNG uses «class PNGf», JPEG uses «class JPEG»
            format_class_map = {
                ImageFormat.PNG: "«class PNGf»",
                ImageFormat.JPEG: "«class JPEG»",
                ImageFormat.GIF: "«class GIFf»",
            }
            format_class = format_class_map.get(format, "«class PNGf»")

            # Use osascript to set clipboard from file
            script = f'''
            set theFile to POSIX file "{temp_path}"
            set theImage to read theFile as {format_class}
            set the clipboard to theImage
            '''

            proc = await asyncio.create_subprocess_exec(
                "osascript",
                "-e",
                script,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            _, stderr = await proc.communicate()

            if proc.returncode != 0:
                raise ClipboardUnavailableError(
                    f"osascript failed: {stderr.decode()}"
                )

            logger.debug("Image copied to clipboard successfully")
        finally:
            # Clean up temp file
            try:
                os.unlink(temp_path)
            except OSError:
                pass


class MockClipboard(ClipboardBackend):
    """Mock clipboard backend for testing."""

    def __init__(self) -> None:
        self.last_text: Optional[str] = None
        self.last_image_data: Optional[bytes] = None
        self.last_image_format: Optional[ImageFormat] = None
        self.set_text_call_count: int = 0
        self.set_image_call_count: int = 0
        self.fail_with: Optional[Exception] = None
        self.delay_seconds: float = 0.0

    async def set_text(self, text: str) -> None:
        """Store text and optionally simulate delay/failure."""
        if self.delay_seconds > 0:
            await asyncio.sleep(self.delay_seconds)

        if self.fail_with:
            raise self.fail_with

        self.last_text = text
        self.set_text_call_count += 1

    async def set_image(self, data: bytes, format: ImageFormat) -> None:
        """Store image and optionally simulate delay/failure."""
        if self.delay_seconds > 0:
            await asyncio.sleep(self.delay_seconds)

        if self.fail_with:
            raise self.fail_with

        self.last_image_data = data
        self.last_image_format = format
        self.set_image_call_count += 1

    def reset(self) -> None:
        """Reset all state."""
        self.last_text = None
        self.last_image_data = None
        self.last_image_format = None
        self.set_text_call_count = 0
        self.set_image_call_count = 0
        self.fail_with = None
        self.delay_seconds = 0.0


def get_clipboard_backend(info: PlatformInfo) -> ClipboardBackend:
    """Get appropriate clipboard backend for platform.

    Args:
        info: Platform information.

    Returns:
        ClipboardBackend implementation.

    Raises:
        NotImplementedError: If platform is not yet implemented.
        ClipboardUnavailableError: If clipboard tool is unknown.
    """
    if info.clipboard_tool == "pbcopy":
        return MacOSClipboard()
    elif info.clipboard_tool in ("xclip", "wl-copy"):
        raise NotImplementedError(
            f"Linux clipboard support not yet implemented. "
            f"Platform: {info.system}, display: {info.display_server}"
        )
    else:
        raise ClipboardUnavailableError(
            f"Unknown clipboard tool: {info.clipboard_tool}"
        )
