"""Platform integration tests for actual clipboard operations.

These tests run actual clipboard commands on the current platform.
They are skipped on CI or when clipboard tools are not available.

Run with: pytest tests/test_clipboard_integration.py -v
Skip with: pytest -m "not integration"
"""

import asyncio
import platform
import shutil
import sys

import pytest

from ras.clipboard_platform import (
    MacOSClipboard,
    detect_platform,
    ClipboardUnavailableError,
)
from ras.clipboard_types import ImageFormat


# Skip all tests if not on macOS or pbcopy not available
pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        platform.system() != "Darwin",
        reason="macOS clipboard tests only run on macOS",
    ),
    pytest.mark.skipif(
        shutil.which("pbcopy") is None,
        reason="pbcopy not available",
    ),
]


class TestMacOSClipboardIntegration:
    """Integration tests for actual macOS clipboard operations."""

    @pytest.mark.asyncio
    async def test_set_and_read_text(self):
        """Text can be written and read back from clipboard."""
        clipboard = MacOSClipboard()
        test_text = f"Test clipboard content: {asyncio.get_event_loop().time()}"

        # Set text
        await clipboard.set_text(test_text)

        # Read back using pbpaste
        proc = await asyncio.create_subprocess_exec(
            "pbpaste",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await proc.communicate()

        assert stdout.decode("utf-8") == test_text

    @pytest.mark.asyncio
    async def test_set_unicode_text(self):
        """Unicode text is handled correctly."""
        clipboard = MacOSClipboard()
        test_text = "Hello, 世界! 🎉 émojis"

        await clipboard.set_text(test_text)

        proc = await asyncio.create_subprocess_exec(
            "pbpaste",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await proc.communicate()

        assert stdout.decode("utf-8") == test_text

    @pytest.mark.asyncio
    async def test_set_multiline_text(self):
        """Multiline text preserves line breaks."""
        clipboard = MacOSClipboard()
        test_text = "Line 1\nLine 2\nLine 3"

        await clipboard.set_text(test_text)

        proc = await asyncio.create_subprocess_exec(
            "pbpaste",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await proc.communicate()

        assert stdout.decode("utf-8") == test_text

    @pytest.mark.asyncio
    async def test_set_large_text(self):
        """Large text blocks work correctly."""
        clipboard = MacOSClipboard()
        # 1MB of text
        test_text = "A" * (1024 * 1024)

        await clipboard.set_text(test_text)

        proc = await asyncio.create_subprocess_exec(
            "pbpaste",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await proc.communicate()

        assert len(stdout.decode("utf-8")) == len(test_text)


@pytest.mark.skipif(
    shutil.which("osascript") is None,
    reason="osascript not available",
)
class TestMacOSImageClipboardIntegration:
    """Integration tests for macOS image clipboard operations."""

    @pytest.fixture
    def png_data(self) -> bytes:
        """Minimal valid PNG image (1x1 transparent pixel)."""
        # Minimal 1x1 transparent PNG
        return bytes([
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  # PNG signature
            0x00, 0x00, 0x00, 0x0D,  # IHDR length
            0x49, 0x48, 0x44, 0x52,  # IHDR type
            0x00, 0x00, 0x00, 0x01,  # width = 1
            0x00, 0x00, 0x00, 0x01,  # height = 1
            0x08, 0x06,  # bit depth = 8, color type = 6 (RGBA)
            0x00, 0x00, 0x00,  # compression, filter, interlace
            0x1F, 0x15, 0xC4, 0x89,  # CRC
            0x00, 0x00, 0x00, 0x0A,  # IDAT length
            0x49, 0x44, 0x41, 0x54,  # IDAT type
            0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,  # compressed data
            0x0D, 0x0A, 0x2D, 0xB4,  # CRC
            0x00, 0x00, 0x00, 0x00,  # IEND length
            0x49, 0x45, 0x4E, 0x44,  # IEND type
            0xAE, 0x42, 0x60, 0x82,  # CRC
        ])

    @pytest.mark.asyncio
    async def test_set_image_runs_without_error(self, png_data):
        """Image can be set to clipboard without error."""
        clipboard = MacOSClipboard()

        # Should not raise
        await clipboard.set_image(png_data, ImageFormat.PNG)


class TestPlatformDetection:
    """Integration tests for platform detection."""

    def test_detect_current_platform(self):
        """Platform detection works on current system."""
        info = detect_platform()

        assert info.system == platform.system()
        if platform.system() == "Darwin":
            assert info.clipboard_tool == "pbcopy"
            assert info.paste_keystroke == "M-v"
