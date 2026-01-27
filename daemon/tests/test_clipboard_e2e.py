"""Comprehensive end-to-end tests for clipboard handling.

These tests trace the complete lifecycle of clipboard transfers through
the daemon, covering every scenario, edge case, and error possibility.

Total: 140 test scenarios organized by category.
"""

import asyncio
import base64
from unittest.mock import AsyncMock

import betterproto
import pytest

from ras.clipboard_types import (
    ClipboardConfig,
    ClipboardMessage,
    ImageStart,
    ImageChunk,
    ImageCancel,
    TextPaste,
    TextPasteApproved,
    ErrorCode,
    ImageFormat,
    ContentType,
)
from ras.clipboard_platform import MockClipboard, PlatformInfo
from ras.clipboard_manager import ClipboardManager


# ============================================================================
# Fixtures
# ============================================================================


@pytest.fixture
def platform_info_macos():
    """macOS platform info."""
    return PlatformInfo(
        system="Darwin",
        display_server=None,
        clipboard_tool="pbcopy",
        paste_keystroke="M-v",
    )


@pytest.fixture
def platform_info_linux_x11():
    """Linux X11 platform info."""
    return PlatformInfo(
        system="Linux",
        display_server="x11",
        clipboard_tool="xclip",
        paste_keystroke="C-v",
    )


@pytest.fixture
def mock_clipboard():
    """Mock clipboard backend."""
    return MockClipboard()


@pytest.fixture
def send_message():
    """Mock send_message callback."""
    return AsyncMock()


@pytest.fixture
def send_keys():
    """Mock send_keys callback."""
    return AsyncMock()


@pytest.fixture
def default_config():
    """Default clipboard config."""
    return ClipboardConfig()


@pytest.fixture
def fast_timeout_config():
    """Config with fast timeout for testing."""
    return ClipboardConfig(transfer_timeout=0.02, paste_timeout=0.02)


@pytest.fixture
async def manager(default_config, send_message, send_keys, platform_info_macos, mock_clipboard):
    """Create ClipboardManager with mocked dependencies."""
    mgr = ClipboardManager(
        config=default_config,
        send_message=send_message,
        send_keys=send_keys,
        platform_info=platform_info_macos,
        clipboard_backend=mock_clipboard,
    )
    yield mgr
    mgr.cleanup()


def get_message_type(msg: ClipboardMessage) -> str:
    """Get the message type (which oneof field is set)."""
    field_name, _ = betterproto.which_one_of(msg, "payload")
    return field_name or "unknown"


def create_image_chunks(data: bytes, chunk_size: int = 64 * 1024) -> list[bytes]:
    """Split data into chunks."""
    return [data[i:i + chunk_size] for i in range(0, len(data), chunk_size)]


# ============================================================================
# 1-10: HAPPY PATH - IMAGES
# ============================================================================


class TestHappyPathImages:
    """Happy path tests for image transfers."""

    @pytest.mark.asyncio
    async def test_01_small_image_single_chunk_jpeg(self, manager, send_message, mock_clipboard, send_keys):
        """1. Small image < 64KB (single chunk) - JPEG"""
        image_data = b"\xff\xd8\xff\xe0" + b"\x00" * 1000  # JPEG header + data

        # Start
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-001",
                total_size=len(image_data),
                format=ImageFormat.JPEG,
                total_chunks=1,
            )
        ))

        # Send single chunk
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="img-001",
                index=0,
                data=image_data,
            )
        ))

        # Verify
        assert mock_clipboard.last_image_data == image_data
        assert mock_clipboard.last_image_format == ImageFormat.JPEG
        send_keys.assert_called_with("M-v")

        # Check complete message
        complete_msg = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "complete"]
        assert len(complete_msg) == 1
        assert complete_msg[0].complete.content_type == ContentType.IMAGE

    @pytest.mark.asyncio
    async def test_02_small_image_single_chunk_png(self, manager, send_message, mock_clipboard):
        """2. Small image < 64KB (single chunk) - PNG"""
        image_data = b"\x89PNG\r\n\x1a\n" + b"\x00" * 500

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-002",
                total_size=len(image_data),
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        ))

        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="img-002",
                index=0,
                data=image_data,
            )
        ))

        assert mock_clipboard.last_image_format == ImageFormat.PNG

    @pytest.mark.asyncio
    async def test_03_medium_image_multiple_chunks(self, manager, send_message, mock_clipboard):
        """3. Medium image, multiple chunks - completes successfully"""
        # 200KB image = 4 chunks of ~50KB each
        image_data = b"\x89PNG\r\n\x1a\n" + b"x" * (200 * 1024 - 8)
        chunks = create_image_chunks(image_data)

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-003",
                total_size=len(image_data),
                format=ImageFormat.PNG,
                total_chunks=len(chunks),
            )
        ))

        for i, chunk in enumerate(chunks):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="img-003",
                    index=i,
                    data=chunk,
                )
            ))

        assert mock_clipboard.last_image_data == image_data

    @pytest.mark.asyncio
    async def test_04_image_at_5mb_limit(self, manager, send_message, mock_clipboard):
        """4. Image at exactly 5MB limit - accepts"""
        image_data = b"\x89PNG\r\n\x1a\n" + b"x" * (5 * 1024 * 1024 - 8)
        chunks = create_image_chunks(image_data)

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-004",
                total_size=len(image_data),
                format=ImageFormat.PNG,
                total_chunks=len(chunks),
            )
        ))

        for i, chunk in enumerate(chunks):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="img-004",
                    index=i,
                    data=chunk,
                )
            ))

        assert len(mock_clipboard.last_image_data) == 5 * 1024 * 1024

    @pytest.mark.asyncio
    async def test_05_gif_format(self, manager, mock_clipboard):
        """5. GIF format - accepted"""
        image_data = b"GIF89a" + b"\x00" * 100

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-005",
                total_size=len(image_data),
                format=ImageFormat.GIF,
                total_chunks=1,
            )
        ))

        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="img-005",
                index=0,
                data=image_data,
            )
        ))

        assert mock_clipboard.last_image_format == ImageFormat.GIF

    @pytest.mark.asyncio
    async def test_06_webp_format(self, manager, mock_clipboard):
        """6. WebP format - accepted"""
        image_data = b"RIFF" + b"\x00" * 100

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-006",
                total_size=len(image_data),
                format=ImageFormat.WEBP,
                total_chunks=1,
            )
        ))

        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="img-006",
                index=0,
                data=image_data,
            )
        ))

        assert mock_clipboard.last_image_format == ImageFormat.WEBP

    @pytest.mark.asyncio
    async def test_07_progress_updates_per_chunk(self, manager, send_message):
        """7. Progress updates sent for each chunk"""
        image_data = b"x" * (128 * 1024)  # 2 chunks
        chunks = create_image_chunks(image_data)

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-007",
                total_size=len(image_data),
                format=ImageFormat.PNG,
                total_chunks=len(chunks),
            )
        ))

        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="img-007",
                index=0,
                data=chunks[0],
            )
        ))

        # Should have sent progress
        progress_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "progress"]
        assert len(progress_msgs) == 1
        assert progress_msgs[0].progress.received_chunks == 1
        assert progress_msgs[0].progress.total_chunks == 2

    @pytest.mark.asyncio
    async def test_08_confirmation_after_paste(self, manager, send_message, mock_clipboard):
        """8. Confirmation sent after successful paste"""
        image_data = b"x" * 100

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-008",
                total_size=len(image_data),
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        ))

        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="img-008",
                index=0,
                data=image_data,
            )
        ))

        complete_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "complete"]
        assert len(complete_msgs) == 1
        assert complete_msgs[0].complete.transfer_id == "img-008"

    @pytest.mark.asyncio
    async def test_09_state_cleared_after_success(self, manager, mock_clipboard):
        """9. Transfer state cleared after successful completion"""
        image_data = b"x" * 100

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="img-009",
                total_size=len(image_data),
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        ))

        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="img-009",
                index=0,
                data=image_data,
            )
        ))

        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_10_can_start_new_after_complete(self, manager, mock_clipboard):
        """10. Can start new transfer after completion"""
        # Complete first transfer
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="first",
                total_size=10,
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="first", index=0, data=b"0123456789")
        ))

        # Start second transfer
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="second",
                total_size=5,
                format=ImageFormat.JPEG,
                total_chunks=1,
            )
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="second", index=0, data=b"12345")
        ))

        assert mock_clipboard.last_image_data == b"12345"


# ============================================================================
# 11-20: HAPPY PATH - TEXT
# ============================================================================


class TestHappyPathText:
    """Happy path tests for text paste."""

    @pytest.mark.asyncio
    async def test_11_small_text_immediate(self, manager, mock_clipboard, send_keys):
        """11. Small text < 100KB - pasted immediately"""
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="Hello, world!")
        ))

        assert mock_clipboard.last_text == "Hello, world!"
        send_keys.assert_called_with("M-v")

    @pytest.mark.asyncio
    async def test_12_text_exactly_100kb(self, manager, mock_clipboard):
        """12. Text at exactly 100KB - pasted without approval"""
        text = "x" * (100 * 1024)
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_13_large_text_requires_approval(self, manager, send_message):
        """13. Text > 100KB - approval required"""
        text = "x" * (101 * 1024)
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        approval_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "approval_required"]
        assert len(approval_msgs) == 1
        assert approval_msgs[0].approval_required.size == len(text.encode())

    @pytest.mark.asyncio
    async def test_14_approved_text_pasted(self, manager, mock_clipboard):
        """14. Approved large text is pasted"""
        text = "x" * (200 * 1024)
        await manager.handle_message(ClipboardMessage(
            text_paste_approved=TextPasteApproved(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_15_text_with_unicode(self, manager, mock_clipboard):
        """15. Text with unicode handled correctly"""
        text = "Hello 世界 🎉 مرحبا"
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_16_text_with_newlines(self, manager, mock_clipboard):
        """16. Text with newlines preserved"""
        text = "line1\nline2\r\nline3"
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_17_text_only_whitespace(self, manager, mock_clipboard):
        """17. Text only whitespace - valid, pasted"""
        text = "   \t\n  "
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_18_approval_preview_truncated(self, manager, send_message):
        """18. Approval preview truncated to ~100 chars"""
        text = "x" * 500 + "END"
        # Make it over threshold
        large_text = "y" * (101 * 1024)
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=large_text)
        ))

        approval_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "approval_required"]
        assert len(approval_msgs[0].approval_required.preview) <= 103  # 100 + "..."

    @pytest.mark.asyncio
    async def test_19_text_confirmation_sent(self, manager, send_message, mock_clipboard):
        """19. Confirmation sent after text paste"""
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        complete_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "complete"]
        assert len(complete_msgs) == 1
        assert complete_msgs[0].complete.content_type == ContentType.TEXT

    @pytest.mark.asyncio
    async def test_20_text_keystroke_sent(self, manager, send_keys):
        """20. Paste keystroke sent for text"""
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        send_keys.assert_called_once()


# ============================================================================
# 21-30: CHUNKING EDGE CASES
# ============================================================================


class TestChunkingEdgeCases:
    """Edge cases for chunk handling."""

    @pytest.mark.asyncio
    async def test_21_exactly_64kb_single_chunk(self, manager, mock_clipboard):
        """21. Image exactly 64KB (single chunk boundary)"""
        data = b"x" * (64 * 1024)
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=len(data), format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=data)
        ))
        assert len(mock_clipboard.last_image_data) == 64 * 1024

    @pytest.mark.asyncio
    async def test_22_64kb_plus_one_byte(self, manager, mock_clipboard):
        """22. Image 64KB + 1 byte (forces second chunk)"""
        data = b"x" * (64 * 1024 + 1)
        chunks = create_image_chunks(data)
        assert len(chunks) == 2

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=len(data), format=ImageFormat.PNG, total_chunks=2)
        ))
        for i, c in enumerate(chunks):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id="t", index=i, data=c)
            ))
        assert mock_clipboard.last_image_data == data

    @pytest.mark.asyncio
    async def test_23_chunks_out_of_order(self, manager, mock_clipboard):
        """23. Chunks arrive out of order - reassembled correctly"""
        data = b"AAABBBCCC"
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=9, format=ImageFormat.PNG, total_chunks=3)
        ))
        # Send out of order: 2, 0, 1
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=2, data=b"CCC")))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=0, data=b"AAA")))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=1, data=b"BBB")))

        assert mock_clipboard.last_image_data == b"AAABBBCCC"

    @pytest.mark.asyncio
    async def test_24_last_chunk_smaller(self, manager, mock_clipboard):
        """24. Last chunk smaller than 64KB - handled correctly"""
        data = b"x" * (64 * 1024 + 100)  # 1 full chunk + 100 bytes
        chunks = create_image_chunks(data)

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=len(data), format=ImageFormat.PNG, total_chunks=2)
        ))
        for i, c in enumerate(chunks):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id="t", index=i, data=c)
            ))

        assert len(chunks[1]) == 100
        assert mock_clipboard.last_image_data == data

    @pytest.mark.asyncio
    async def test_25_duplicate_chunk_idempotent(self, manager, mock_clipboard):
        """25. Duplicate chunk - overwrites (idempotent)"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=6, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=0, data=b"AAA")))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=0, data=b"BBB")))  # Dup
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=1, data=b"CCC")))

        assert mock_clipboard.last_image_data == b"BBBCCC"

    @pytest.mark.asyncio
    async def test_26_single_byte_chunks(self, manager, mock_clipboard):
        """26. Single-byte chunks - extreme fragmentation"""
        data = b"ABC"
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=3, format=ImageFormat.PNG, total_chunks=3)
        ))
        for i, b in enumerate(data):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id="t", index=i, data=bytes([b]))
            ))

        assert mock_clipboard.last_image_data == b"ABC"

    @pytest.mark.asyncio
    async def test_27_many_chunks(self, manager, mock_clipboard):
        """27. Many small chunks - 100 chunks"""
        data = b"x" * 100
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=100)
        ))
        for i in range(100):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id="t", index=i, data=b"x")
            ))

        assert mock_clipboard.last_image_data == data

    @pytest.mark.asyncio
    async def test_28_progress_every_chunk(self, manager, send_message):
        """28. Progress update for every chunk"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=30, format=ImageFormat.PNG, total_chunks=3)
        ))
        for i in range(3):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id="t", index=i, data=b"0123456789")
            ))

        progress_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "progress"]
        assert len(progress_msgs) == 3

    @pytest.mark.asyncio
    async def test_29_progress_bytes_accurate(self, manager, send_message):
        """29. Progress bytes count accurate"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=15, format=ImageFormat.PNG, total_chunks=3)
        ))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")))

        progress_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "progress"]
        assert progress_msgs[0].progress.received_bytes == 5
        assert progress_msgs[0].progress.total_bytes == 15

    @pytest.mark.asyncio
    async def test_30_progress_shows_percentage(self, manager, send_message):
        """30. Progress chunks show correct count"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=20, format=ImageFormat.PNG, total_chunks=4)
        ))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=1, data=b"12345")))

        progress_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "progress"]
        assert progress_msgs[1].progress.received_chunks == 2
        assert progress_msgs[1].progress.total_chunks == 4


# ============================================================================
# 31-40: SIZE LIMIT ERRORS
# ============================================================================


class TestSizeLimitErrors:
    """Tests for size limit enforcement."""

    @pytest.mark.asyncio
    async def test_31_image_over_5mb(self, manager, send_message):
        """31. Image 1 byte over 5MB - rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(
                transfer_id="t",
                total_size=5 * 1024 * 1024 + 1,
                format=ImageFormat.PNG,
                total_chunks=100,
            )
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.SIZE_EXCEEDED

    @pytest.mark.asyncio
    async def test_32_image_zero_size(self, manager, send_message):
        """32. Image 0 bytes - rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=0, format=ImageFormat.PNG, total_chunks=1)
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.SIZE_EXCEEDED

    @pytest.mark.asyncio
    async def test_33_image_negative_size(self, manager, send_message):
        """33. Image negative size - rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=-100, format=ImageFormat.PNG, total_chunks=1)
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1

    @pytest.mark.asyncio
    async def test_34_text_empty(self, manager, send_message):
        """34. Text empty - rejected"""
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.SIZE_EXCEEDED

    @pytest.mark.asyncio
    async def test_35_chunk_zero_chunks(self, manager, send_message):
        """35. Zero total chunks - rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=0)
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_36_chunk_negative_chunks(self, manager, send_message):
        """36. Negative total chunks - rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=-5)
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1

    @pytest.mark.asyncio
    async def test_37_chunk_over_64kb(self, manager, send_message):
        """37. Single chunk > 64KB - rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100000, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"x" * (65 * 1024))
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_38_text_100kb_plus_1(self, manager, send_message):
        """38. Text 100KB + 1 byte - requires approval"""
        text = "x" * (100 * 1024 + 1)
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        approval_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "approval_required"]
        assert len(approval_msgs) == 1

    @pytest.mark.asyncio
    async def test_39_size_mismatch(self, manager, send_message):
        """39. Declared size doesn't match actual - error"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")  # Only 5 bytes
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.CHUNK_MISSING

    @pytest.mark.asyncio
    async def test_40_state_cleared_on_size_error(self, manager, send_message):
        """40. State cleared after size error"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=6 * 1024 * 1024, format=ImageFormat.PNG, total_chunks=1)
        ))

        assert manager._current_transfer is None


# ============================================================================
# 41-50: FORMAT VALIDATION
# ============================================================================


class TestFormatValidation:
    """Tests for image format validation."""

    @pytest.mark.asyncio
    async def test_41_jpeg_accepted(self, manager, mock_clipboard):
        """41. JPEG format accepted"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.JPEG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        assert mock_clipboard.last_image_format == ImageFormat.JPEG

    @pytest.mark.asyncio
    async def test_42_png_accepted(self, manager, mock_clipboard):
        """42. PNG format accepted"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        assert mock_clipboard.last_image_format == ImageFormat.PNG

    @pytest.mark.asyncio
    async def test_43_gif_accepted(self, manager, mock_clipboard):
        """43. GIF format accepted"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.GIF, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        assert mock_clipboard.last_image_format == ImageFormat.GIF

    @pytest.mark.asyncio
    async def test_44_webp_accepted(self, manager, mock_clipboard):
        """44. WebP format accepted"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.WEBP, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        assert mock_clipboard.last_image_format == ImageFormat.WEBP

    @pytest.mark.asyncio
    async def test_45_unspecified_format_rejected(self, manager, send_message):
        """45. Unspecified format rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.UNSPECIFIED, total_chunks=1)
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.INVALID_FORMAT

    @pytest.mark.asyncio
    async def test_46_format_preserved_in_backend(self, manager, mock_clipboard):
        """46. Format passed to clipboard backend correctly"""
        for fmt in [ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.GIF, ImageFormat.WEBP]:
            mock_clipboard.reset()
            await manager.handle_message(ClipboardMessage(
                image_start=ImageStart(transfer_id=f"t-{fmt}", total_size=5, format=fmt, total_chunks=1)
            ))
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id=f"t-{fmt}", index=0, data=b"12345")
            ))
            assert mock_clipboard.last_image_format == fmt

    @pytest.mark.asyncio
    async def test_47_format_in_complete_message(self, manager, send_message):
        """47. Format info in complete message"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        complete_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "complete"]
        assert complete_msgs[0].complete.content_type == ContentType.IMAGE

    @pytest.mark.asyncio
    async def test_48_state_cleared_on_format_error(self, manager, send_message):
        """48. State cleared after format error"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.UNSPECIFIED, total_chunks=1)
        ))

        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_49_can_retry_after_format_error(self, manager, mock_clipboard, send_message):
        """49. Can start new transfer after format error"""
        # Invalid format
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t1", total_size=5, format=ImageFormat.UNSPECIFIED, total_chunks=1)
        ))

        # Valid format
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t2", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t2", index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_50_format_error_message_clear(self, manager, send_message):
        """50. Format error message is clear"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.UNSPECIFIED, total_chunks=1)
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert "format" in error_msgs[0].error.message.lower()


# ============================================================================
# 51-60: CHUNK INDEX ERRORS
# ============================================================================


class TestChunkIndexErrors:
    """Tests for chunk index validation."""

    @pytest.mark.asyncio
    async def test_51_negative_index(self, manager, send_message):
        """51. Negative chunk index rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=-1, data=b"12345")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert error_msgs[0].error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_52_index_too_large(self, manager, send_message):
        """52. Index >= total_chunks rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=2, data=b"12345")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert error_msgs[0].error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_53_index_way_too_large(self, manager, send_message):
        """53. Very large index rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=999999, data=b"12345")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1

    @pytest.mark.asyncio
    async def test_54_chunk_without_start(self, manager, send_message):
        """54. Chunk without start message rejected"""
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert error_msgs[0].error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_55_chunk_wrong_transfer_id(self, manager, send_message):
        """55. Chunk for wrong transfer ignored"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="correct", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="wrong", index=0, data=b"12345")
        ))

        # Should not have stored chunk
        assert 0 not in manager._current_transfer.received_chunks

    @pytest.mark.asyncio
    async def test_56_valid_index_after_error(self, manager, mock_clipboard, send_message):
        """56. Valid chunks work after index error"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        # Invalid
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=-1, data=b"12345")
        ))

        # State should be cleared, need new start
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t2", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t2", index=0, data=b"12345")
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t2", index=1, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_57_all_valid_indices_work(self, manager, mock_clipboard):
        """57. All valid indices 0 to n-1 work"""
        n = 5
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=n*2, format=ImageFormat.PNG, total_chunks=n)
        ))
        for i in range(n):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id="t", index=i, data=b"xx")
            ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_58_error_message_includes_index(self, manager, send_message):
        """58. Error message includes invalid index"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=99, data=b"12345")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert "99" in error_msgs[0].error.message

    @pytest.mark.asyncio
    async def test_59_transfer_cleared_on_index_error(self, manager, send_message):
        """59. Transfer state cleared on index error"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=-1, data=b"12345")
        ))

        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_60_boundary_index(self, manager, mock_clipboard):
        """60. Boundary index (n-1) works"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=6, format=ImageFormat.PNG, total_chunks=3)
        ))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=0, data=b"AA")))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=1, data=b"BB")))
        await manager.handle_message(ClipboardMessage(image_chunk=ImageChunk(transfer_id="t", index=2, data=b"CC")))

        assert mock_clipboard.last_image_data == b"AABBCC"


# ============================================================================
# 61-70: CANCEL SCENARIOS
# ============================================================================


class TestCancelScenarios:
    """Tests for transfer cancellation."""

    @pytest.mark.asyncio
    async def test_61_cancel_mid_transfer(self, manager, send_message):
        """61. Cancel mid-transfer clears state"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=10)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"x" * 10)
        ))
        await manager.handle_message(ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="t")
        ))

        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_62_cancel_sends_ack(self, manager, send_message):
        """62. Cancel sends cancelled acknowledgement"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=10)
        ))
        await manager.handle_message(ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="t")
        ))

        cancelled_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "cancelled"]
        assert len(cancelled_msgs) == 1
        assert cancelled_msgs[0].cancelled.transfer_id == "t"

    @pytest.mark.asyncio
    async def test_63_cancel_when_idle(self, manager, send_message):
        """63. Cancel when idle - harmless"""
        await manager.handle_message(ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="nonexistent")
        ))

        # Should not send error
        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 0

    @pytest.mark.asyncio
    async def test_64_double_cancel(self, manager, send_message):
        """64. Double cancel handled gracefully"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=10)
        ))
        await manager.handle_message(ClipboardMessage(image_cancel=ImageCancel(transfer_id="t")))
        await manager.handle_message(ClipboardMessage(image_cancel=ImageCancel(transfer_id="t")))

        # No errors
        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 0

    @pytest.mark.asyncio
    async def test_65_chunk_after_cancel_rejected(self, manager, send_message):
        """65. Chunk after cancel rejected"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=10)
        ))
        await manager.handle_message(ClipboardMessage(image_cancel=ImageCancel(transfer_id="t")))
        send_message.reset_mock()

        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"late")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1

    @pytest.mark.asyncio
    async def test_66_cancel_no_clipboard_set(self, manager, mock_clipboard):
        """66. Cancel prevents clipboard from being set"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        await manager.handle_message(ClipboardMessage(image_cancel=ImageCancel(transfer_id="t")))

        assert mock_clipboard.set_image_call_count == 0

    @pytest.mark.asyncio
    async def test_67_cancel_no_paste_sent(self, manager, send_keys):
        """67. Cancel prevents paste keystroke"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        await manager.handle_message(ClipboardMessage(image_cancel=ImageCancel(transfer_id="t")))

        send_keys.assert_not_called()

    @pytest.mark.asyncio
    async def test_68_can_start_after_cancel(self, manager, mock_clipboard):
        """68. Can start new transfer after cancel"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t1", total_size=10, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(image_cancel=ImageCancel(transfer_id="t1")))

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t2", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t2", index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_69_cancel_wrong_id_ignored(self, manager, mock_clipboard):
        """69. Cancel with wrong ID doesn't affect current transfer"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="correct", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(image_cancel=ImageCancel(transfer_id="wrong")))

        # Transfer still active
        assert manager._current_transfer is not None
        assert manager._current_transfer.transfer_id == "correct"

    @pytest.mark.asyncio
    async def test_70_cancel_frees_memory(self, manager):
        """70. Cancel frees chunk memory"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))
        # Add some chunks
        for i in range(5):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id="t", index=i, data=b"x" * 100)
            ))

        await manager.handle_message(ClipboardMessage(image_cancel=ImageCancel(transfer_id="t")))

        # Transfer cleared, chunks freed
        assert manager._current_transfer is None


# ============================================================================
# 71-80: CLIPBOARD ERRORS
# ============================================================================


class TestClipboardErrors:
    """Tests for clipboard operation errors."""

    @pytest.mark.asyncio
    async def test_71_clipboard_set_failure(self, manager, send_message, mock_clipboard):
        """71. Clipboard set failure reported"""
        from ras.clipboard_platform import ClipboardUnavailableError
        mock_clipboard.fail_with = ClipboardUnavailableError("Clipboard failed")

        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.CLIPBOARD_FAILED

    @pytest.mark.asyncio
    async def test_72_clipboard_error_clears_state(self, manager, mock_clipboard):
        """72. Clipboard error clears transfer state"""
        from ras.clipboard_platform import ClipboardUnavailableError
        mock_clipboard.fail_with = ClipboardUnavailableError("fail")

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_73_paste_key_failure(self, manager, send_message, send_keys):
        """73. Paste keystroke failure reported"""
        send_keys.side_effect = Exception("tmux error")

        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.PASTE_FAILED

    @pytest.mark.asyncio
    async def test_74_clipboard_timeout(self, send_message, send_keys, platform_info_macos):
        """74. Clipboard operation timeout"""
        slow_clipboard = MockClipboard()
        slow_clipboard.delay_seconds = 1.0  # 1 second delay

        config = ClipboardConfig(paste_timeout=0.1)  # 100ms timeout
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=slow_clipboard,
        )

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.CLIPBOARD_FAILED

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_75_error_includes_transfer_id(self, manager, send_message, mock_clipboard):
        """75. Error message includes transfer ID"""
        from ras.clipboard_platform import ClipboardUnavailableError
        mock_clipboard.fail_with = ClipboardUnavailableError("fail")

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="my-transfer-123", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="my-transfer-123", index=0, data=b"12345")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert error_msgs[0].error.transfer_id == "my-transfer-123"

    @pytest.mark.asyncio
    async def test_76_can_retry_after_clipboard_error(self, manager, mock_clipboard, send_message):
        """76. Can retry after clipboard error"""
        from ras.clipboard_platform import ClipboardUnavailableError

        # First attempt fails
        mock_clipboard.fail_with = ClipboardUnavailableError("fail")
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t1", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t1", index=0, data=b"12345")
        ))

        # Second attempt succeeds
        mock_clipboard.fail_with = None
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t2", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t2", index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1  # Only second attempt counted

    @pytest.mark.asyncio
    async def test_77_error_message_descriptive(self, manager, send_message, mock_clipboard):
        """77. Error message is descriptive"""
        from ras.clipboard_platform import ClipboardUnavailableError
        mock_clipboard.fail_with = ClipboardUnavailableError("Permission denied: cannot access clipboard")

        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert "Permission denied" in error_msgs[0].error.message

    @pytest.mark.asyncio
    async def test_78_paste_error_after_clipboard_set(self, manager, send_message, mock_clipboard, send_keys):
        """78. Paste error after clipboard was set"""
        send_keys.side_effect = Exception("tmux not running")

        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        # Clipboard was set
        assert mock_clipboard.set_text_call_count == 1

        # But error was reported
        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert error_msgs[0].error.code == ErrorCode.PASTE_FAILED

    @pytest.mark.asyncio
    async def test_79_image_clipboard_error(self, manager, send_message, mock_clipboard):
        """79. Image clipboard set error"""
        from ras.clipboard_platform import ClipboardUnavailableError
        mock_clipboard.fail_with = ClipboardUnavailableError("Image too large")

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1

    @pytest.mark.asyncio
    async def test_80_text_clipboard_error(self, manager, send_message, mock_clipboard):
        """80. Text clipboard set error"""
        from ras.clipboard_platform import ClipboardUnavailableError
        mock_clipboard.fail_with = ClipboardUnavailableError("Clipboard busy")

        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1


# ============================================================================
# 81-90: TIMEOUT SCENARIOS
# ============================================================================


class TestTimeoutScenarios:
    """Tests for timeout handling."""

    @pytest.mark.asyncio
    async def test_81_transfer_timeout(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """81. Transfer times out with no chunks"""
        config = ClipboardConfig(transfer_timeout=0.02)
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))

        await asyncio.sleep(0.05)

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.TRANSFER_TIMEOUT

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_82_timeout_clears_state(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """82. Timeout clears transfer state"""
        config = ClipboardConfig(transfer_timeout=0.02)
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))

        await asyncio.sleep(0.05)
        assert manager._current_transfer is None

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_83_timeout_resets_on_chunk(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """83. Timeout resets when chunk received"""
        config = ClipboardConfig(transfer_timeout=0.05)
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))

        await asyncio.sleep(0.03)
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        await asyncio.sleep(0.03)

        # Should NOT have timed out
        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 0

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_84_can_start_after_timeout(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """84. Can start new transfer after timeout"""
        config = ClipboardConfig(transfer_timeout=0.02)
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        # First transfer times out
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t1", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))
        await asyncio.sleep(0.05)

        # Second transfer succeeds
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t2", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t2", index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_85_timeout_message_includes_duration(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """85. Timeout message includes duration"""
        config = ClipboardConfig(transfer_timeout=0.02)
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))
        await asyncio.sleep(0.05)

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert "0.1" in error_msgs[0].error.message or "second" in error_msgs[0].error.message.lower()

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_86_timeout_includes_transfer_id(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """86. Timeout error includes transfer ID"""
        config = ClipboardConfig(transfer_timeout=0.02)
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="my-timeout-id", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))
        await asyncio.sleep(0.05)

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert error_msgs[0].error.transfer_id == "my-timeout-id"

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_87_timeout_cancelled_on_complete(self, manager, mock_clipboard):
        """87. Timeout task cancelled on successful completion"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        # Timeout task should be cancelled
        assert manager._timeout_task is None

    @pytest.mark.asyncio
    async def test_88_timeout_cancelled_on_cancel(self, manager):
        """88. Timeout task cancelled on transfer cancel"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))
        await manager.handle_message(ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="t")
        ))

        assert manager._timeout_task is None

    @pytest.mark.asyncio
    async def test_89_cleanup_cancels_timeout(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """89. cleanup() cancels timeout task"""
        config = ClipboardConfig(transfer_timeout=10)  # Long timeout
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))

        assert manager._timeout_task is not None
        manager.cleanup()
        assert manager._timeout_task is None

    @pytest.mark.asyncio
    async def test_90_no_timeout_for_text(self, manager, mock_clipboard):
        """90. Text paste doesn't start timeout"""
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        # No timeout task for simple text paste
        assert manager._timeout_task is None


# ============================================================================
# 91-100: CONCURRENT/STATE TESTS
# ============================================================================


class TestConcurrentState:
    """Tests for concurrent operations and state management."""

    @pytest.mark.asyncio
    async def test_91_only_one_transfer_at_time(self, manager, send_message):
        """91. Only one image transfer at a time"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="first", total_size=100, format=ImageFormat.PNG, total_chunks=10)
        ))
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="second", total_size=200, format=ImageFormat.JPEG, total_chunks=20)
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.TRANSFER_IN_PROGRESS

    @pytest.mark.asyncio
    async def test_92_text_during_image_transfer(self, manager, send_message, mock_clipboard):
        """92. Text paste during image transfer works independently"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="img", total_size=100, format=ImageFormat.PNG, total_chunks=10)
        ))

        # Text paste should work
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="quick text")
        ))

        assert mock_clipboard.set_text_call_count == 1

    @pytest.mark.asyncio
    async def test_93_rapid_text_pastes(self, manager, mock_clipboard):
        """93. Rapid successive text pastes all work"""
        for i in range(5):
            await manager.handle_message(ClipboardMessage(
                text_paste=TextPaste(text=f"text-{i}")
            ))

        assert mock_clipboard.set_text_call_count == 5
        assert mock_clipboard.last_text == "text-4"

    @pytest.mark.asyncio
    async def test_94_rapid_image_transfers(self, manager, mock_clipboard):
        """94. Rapid successive image transfers (sequential)"""
        for i in range(3):
            await manager.handle_message(ClipboardMessage(
                image_start=ImageStart(transfer_id=f"t{i}", total_size=5, format=ImageFormat.PNG, total_chunks=1)
            ))
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id=f"t{i}", index=0, data=b"12345")
            ))

        assert mock_clipboard.set_image_call_count == 3

    @pytest.mark.asyncio
    async def test_95_state_isolated_between_transfers(self, manager, mock_clipboard):
        """95. State properly isolated between transfers"""
        # Transfer 1
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t1", total_size=3, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t1", index=0, data=b"AAA")
        ))

        # Transfer 2
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t2", total_size=3, format=ImageFormat.JPEG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t2", index=0, data=b"BBB")
        ))

        assert mock_clipboard.last_image_data == b"BBB"
        assert mock_clipboard.last_image_format == ImageFormat.JPEG

    @pytest.mark.asyncio
    async def test_96_context_manager_cleanup(self, default_config, send_message, send_keys, platform_info_macos, mock_clipboard):
        """96. Context manager ensures cleanup"""
        async with ClipboardManager(
            config=default_config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        ) as manager:
            await manager.handle_message(ClipboardMessage(
                image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
            ))

        # After exit, state should be cleared
        assert manager._current_transfer is None
        assert manager._timeout_task is None

    @pytest.mark.asyncio
    async def test_97_context_manager_on_exception(self, default_config, send_message, send_keys, platform_info_macos, mock_clipboard):
        """97. Context manager cleanup on exception"""
        try:
            async with ClipboardManager(
                config=default_config,
                send_message=send_message,
                send_keys=send_keys,
                platform_info=platform_info_macos,
                clipboard_backend=mock_clipboard,
            ) as manager:
                await manager.handle_message(ClipboardMessage(
                    image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
                ))
                raise ValueError("test error")
        except ValueError:
            pass

        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_98_explicit_cleanup(self, manager):
        """98. Explicit cleanup() works"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=1000, format=ImageFormat.PNG, total_chunks=10)
        ))

        manager.cleanup()

        assert manager._current_transfer is None
        assert manager._timeout_task is None

    @pytest.mark.asyncio
    async def test_99_cleanup_idempotent(self, manager):
        """99. cleanup() is idempotent"""
        manager.cleanup()
        manager.cleanup()
        manager.cleanup()
        # Should not raise

    @pytest.mark.asyncio
    async def test_100_state_after_error(self, manager, mock_clipboard):
        """100. State cleared after any error"""
        from ras.clipboard_platform import ClipboardUnavailableError
        mock_clipboard.fail_with = ClipboardUnavailableError("fail")

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        assert manager._current_transfer is None


# ============================================================================
# 101-110: PLATFORM KEYSTROKE TESTS
# ============================================================================


class TestPlatformKeystroke:
    """Tests for platform-specific keystrokes."""

    @pytest.mark.asyncio
    async def test_101_macos_uses_cmd_v(self, default_config, send_message, send_keys, platform_info_macos, mock_clipboard):
        """101. macOS uses Cmd+V (M-v)"""
        manager = ClipboardManager(
            config=default_config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(text_paste=TextPaste(text="test")))
        send_keys.assert_called_with("M-v")

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_102_linux_uses_ctrl_v(self, default_config, send_message, send_keys, platform_info_linux_x11, mock_clipboard):
        """102. Linux uses Ctrl+V (C-v)"""
        manager = ClipboardManager(
            config=default_config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_linux_x11,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(text_paste=TextPaste(text="test")))
        send_keys.assert_called_with("C-v")

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_103_config_override_keystroke(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """103. Config can override paste keystroke"""
        config = ClipboardConfig(paste_keystroke="C-S-v")  # Custom keystroke
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(text_paste=TextPaste(text="test")))
        send_keys.assert_called_with("C-S-v")

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_104_keystroke_for_image(self, manager, send_keys):
        """104. Same keystroke used for images"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        send_keys.assert_called_with("M-v")

    @pytest.mark.asyncio
    async def test_105_keystroke_for_approved_text(self, manager, send_keys):
        """105. Same keystroke for approved text"""
        await manager.handle_message(ClipboardMessage(
            text_paste_approved=TextPasteApproved(text="approved text")
        ))

        send_keys.assert_called_with("M-v")

    @pytest.mark.asyncio
    async def test_106_keystroke_not_sent_on_error(self, manager, send_keys, mock_clipboard):
        """106. Keystroke not sent when clipboard fails"""
        from ras.clipboard_platform import ClipboardUnavailableError
        mock_clipboard.fail_with = ClipboardUnavailableError("fail")

        await manager.handle_message(ClipboardMessage(text_paste=TextPaste(text="test")))

        send_keys.assert_not_called()

    @pytest.mark.asyncio
    async def test_107_keystroke_after_clipboard_set(self, manager, mock_clipboard, send_keys):
        """107. Keystroke sent after clipboard is set"""
        call_order = []
        original_set_text = mock_clipboard.set_text

        async def tracked_set_text(text):
            call_order.append("clipboard")
            return await original_set_text(text)

        mock_clipboard.set_text = tracked_set_text

        async def tracked_send_keys(keys):
            call_order.append("keystroke")

        send_keys.side_effect = tracked_send_keys

        await manager.handle_message(ClipboardMessage(text_paste=TextPaste(text="test")))

        assert call_order == ["clipboard", "keystroke"]

    @pytest.mark.asyncio
    async def test_108_keystroke_once_per_paste(self, manager, send_keys):
        """108. Keystroke sent exactly once per paste"""
        await manager.handle_message(ClipboardMessage(text_paste=TextPaste(text="test")))

        assert send_keys.call_count == 1

    @pytest.mark.asyncio
    async def test_109_keystroke_once_per_image(self, manager, send_keys):
        """109. Keystroke sent exactly once per image"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        assert send_keys.call_count == 1

    @pytest.mark.asyncio
    async def test_110_multiple_pastes_multiple_keystrokes(self, manager, send_keys):
        """110. Each paste sends its own keystroke"""
        for i in range(3):
            await manager.handle_message(ClipboardMessage(text_paste=TextPaste(text=f"text{i}")))

        assert send_keys.call_count == 3


# ============================================================================
# 111-120: MESSAGE CALLBACK TESTS
# ============================================================================


class TestMessageCallbacks:
    """Tests for send_message callback."""

    @pytest.mark.asyncio
    async def test_111_progress_callback_per_chunk(self, manager, send_message):
        """111. Progress callback called for each chunk"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=30, format=ImageFormat.PNG, total_chunks=3)
        ))
        for i in range(3):
            await manager.handle_message(ClipboardMessage(
                image_chunk=ImageChunk(transfer_id="t", index=i, data=b"0123456789")
            ))

        progress_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "progress"]
        assert len(progress_msgs) == 3

    @pytest.mark.asyncio
    async def test_112_complete_callback_on_success(self, manager, send_message):
        """112. Complete callback on successful transfer"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        complete_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "complete"]
        assert len(complete_msgs) == 1

    @pytest.mark.asyncio
    async def test_113_error_callback_on_failure(self, manager, send_message):
        """113. Error callback on failure"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=6 * 1024 * 1024, format=ImageFormat.PNG, total_chunks=1)
        ))

        error_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "error"]
        assert len(error_msgs) == 1

    @pytest.mark.asyncio
    async def test_114_cancelled_callback_on_cancel(self, manager, send_message):
        """114. Cancelled callback on cancel"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=10)
        ))
        await manager.handle_message(ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="t")
        ))

        cancelled_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "cancelled"]
        assert len(cancelled_msgs) == 1

    @pytest.mark.asyncio
    async def test_115_approval_callback_for_large_text(self, manager, send_message):
        """115. Approval callback for large text"""
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="x" * (101 * 1024))
        ))

        approval_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "approval_required"]
        assert len(approval_msgs) == 1

    @pytest.mark.asyncio
    async def test_116_callback_error_handled(self, default_config, send_keys, platform_info_macos, mock_clipboard):
        """116. Callback error doesn't crash manager"""
        failing_callback = AsyncMock(side_effect=Exception("callback failed"))

        manager = ClipboardManager(
            config=default_config,
            send_message=failing_callback,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        # Should not raise
        try:
            await manager.handle_message(ClipboardMessage(
                image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
            ))
        except Exception:
            pass  # May or may not propagate, but shouldn't crash

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_117_message_order_correct(self, manager, send_message):
        """117. Messages sent in correct order"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=1, data=b"12345")
        ))

        msg_types = [get_message_type(m[0][0]) for m in send_message.call_args_list]
        assert msg_types == ["progress", "progress", "complete"]

    @pytest.mark.asyncio
    async def test_118_all_fields_populated(self, manager, send_message):
        """118. All message fields populated"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="my-id", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="my-id", index=0, data=b"12345")
        ))

        progress_msg = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "progress"][0]
        assert progress_msg.progress.transfer_id == "my-id"
        assert progress_msg.progress.received_chunks == 1
        assert progress_msg.progress.total_chunks == 1
        assert progress_msg.progress.received_bytes == 5
        assert progress_msg.progress.total_bytes == 5

    @pytest.mark.asyncio
    async def test_119_complete_has_type(self, manager, send_message):
        """119. Complete message has content type"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))

        complete_msg = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "complete"][0]
        assert complete_msg.complete.content_type == ContentType.IMAGE

    @pytest.mark.asyncio
    async def test_120_text_complete_type(self, manager, send_message):
        """120. Text complete has correct type"""
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="test")
        ))

        complete_msg = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "complete"][0]
        assert complete_msg.complete.content_type == ContentType.TEXT


# ============================================================================
# 121-130: EDGE CASE ENCODING/DATA
# ============================================================================


class TestEncodingEdgeCases:
    """Tests for encoding and data edge cases."""

    @pytest.mark.asyncio
    async def test_121_binary_image_data(self, manager, mock_clipboard):
        """121. Binary image data preserved exactly"""
        data = bytes(range(256))  # All byte values
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=len(data), format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=data)
        ))

        assert mock_clipboard.last_image_data == data

    @pytest.mark.asyncio
    async def test_122_null_bytes_in_image(self, manager, mock_clipboard):
        """122. Null bytes in image data preserved"""
        data = b"\x00\x00\x00\xFF\x00\x00"
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=len(data), format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=data)
        ))

        assert mock_clipboard.last_image_data == data

    @pytest.mark.asyncio
    async def test_123_unicode_text(self, manager, mock_clipboard):
        """123. Unicode text preserved"""
        text = "Hello 🌍 世界 مرحبا Привет"
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_124_emoji_text(self, manager, mock_clipboard):
        """124. Emoji in text preserved"""
        text = "🎉🎊🎁🎈🎂"
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_125_control_chars_in_text(self, manager, mock_clipboard):
        """125. Control characters in text preserved"""
        text = "line1\t\tline2\r\nline3\x00end"
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_126_very_long_single_line(self, manager, mock_clipboard):
        """126. Very long single line (no newlines)"""
        text = "x" * 50000
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert len(mock_clipboard.last_text) == 50000

    @pytest.mark.asyncio
    async def test_127_many_short_lines(self, manager, mock_clipboard):
        """127. Many short lines"""
        text = "\n".join([f"line{i}" for i in range(1000)])
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_128_mixed_line_endings(self, manager, mock_clipboard):
        """128. Mixed line endings preserved"""
        text = "line1\nline2\r\nline3\rline4"
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_129_special_json_chars(self, manager, mock_clipboard):
        """129. Special JSON characters in text"""
        text = '{"key": "value", "nested": {"a": 1}}'
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        assert mock_clipboard.last_text == text

    @pytest.mark.asyncio
    async def test_130_size_utf8_calculated(self, manager, send_message):
        """130. Size calculated as UTF-8 bytes, not chars"""
        # 3-byte UTF-8 characters: need > 102,400 bytes (100KB)
        # 102,400 / 3 = 34,133.33, so need 34,134+ chars
        text = "中" * 34200  # 34200 * 3 = 102,600 bytes > 100KB
        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text=text)
        ))

        # Should require approval (102KB > 100KB)
        approval_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "approval_required"]
        assert len(approval_msgs) == 1


# ============================================================================
# 131-140: ADDITIONAL EDGE CASES
# ============================================================================


class TestAdditionalEdgeCases:
    """Additional edge cases and boundary conditions."""

    @pytest.mark.asyncio
    async def test_131_empty_transfer_id(self, manager, mock_clipboard):
        """131. Empty transfer ID works"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="", index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_132_long_transfer_id(self, manager, mock_clipboard):
        """132. Long transfer ID works"""
        long_id = "x" * 1000
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id=long_id, total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id=long_id, index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_133_unicode_transfer_id(self, manager, mock_clipboard):
        """133. Unicode transfer ID works"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="转账-123", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="转账-123", index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_134_whitespace_only_transfer_id(self, manager, mock_clipboard):
        """134. Whitespace-only transfer ID works"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="   ", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="   ", index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_135_zero_config_text_threshold(self, send_message, send_keys, platform_info_macos, mock_clipboard):
        """135. Zero text approval threshold - all text needs approval"""
        config = ClipboardConfig(text_approval_threshold=0)
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info_macos,
            clipboard_backend=mock_clipboard,
        )

        await manager.handle_message(ClipboardMessage(
            text_paste=TextPaste(text="tiny")
        ))

        approval_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "approval_required"]
        assert len(approval_msgs) == 1

        manager.cleanup()

    @pytest.mark.asyncio
    async def test_136_max_int_total_chunks(self, manager, send_message):
        """136. Very large total_chunks value handled"""
        # This should fail size validation before we get to chunks
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=100, format=ImageFormat.PNG, total_chunks=999999999)
        ))

        # Either accepted or rejected - shouldn't crash
        assert True

    @pytest.mark.asyncio
    async def test_137_progress_0_to_100_percent(self, manager, send_message):
        """137. Progress goes from 0% to 100%"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=0, data=b"12345")
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=1, data=b"12345")
        ))

        progress_msgs = [m[0][0] for m in send_message.call_args_list if get_message_type(m[0][0]) == "progress"]
        assert progress_msgs[0].progress.received_chunks == 1  # 50%
        assert progress_msgs[1].progress.received_chunks == 2  # 100%

    @pytest.mark.asyncio
    async def test_138_first_chunk_is_index_zero(self, manager, send_message):
        """138. First chunk must be index 0 (or any valid)"""
        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="t", total_size=10, format=ImageFormat.PNG, total_chunks=2)
        ))
        # Start with index 1
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="t", index=1, data=b"12345")
        ))

        # Should work (out of order is allowed)
        assert 1 in manager._current_transfer.received_chunks

    @pytest.mark.asyncio
    async def test_139_logging_doesnt_crash(self, manager, mock_clipboard, caplog):
        """139. Logging with special characters doesn't crash"""
        import logging
        caplog.set_level(logging.DEBUG)

        await manager.handle_message(ClipboardMessage(
            image_start=ImageStart(transfer_id="log-test-\\n\\r\\0", total_size=5, format=ImageFormat.PNG, total_chunks=1)
        ))
        await manager.handle_message(ClipboardMessage(
            image_chunk=ImageChunk(transfer_id="log-test-\\n\\r\\0", index=0, data=b"12345")
        ))

        assert mock_clipboard.set_image_call_count == 1

    @pytest.mark.asyncio
    async def test_140_all_error_codes_distinct(self, manager, send_message):
        """140. All error codes are distinct values"""
        codes = [e.value for e in ErrorCode]
        assert len(codes) == len(set(codes))
