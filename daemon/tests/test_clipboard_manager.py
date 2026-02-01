"""Tests for ClipboardManager."""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.clipboard_types import (
    ClipboardConfig,
    ClipboardMessage,
    ImageStart,
    ImageChunk,
    ImageCancel,
    TextPaste,
    TextPasteApproved,
    Progress,
    Complete,
    ApprovalRequired,
    Error,
    Cancelled,
    ErrorCode,
    ImageFormat,
    ContentType,
    ImageTransfer,
    TransferState,
)
from ras.clipboard_platform import MockClipboard, PlatformInfo, ClipboardUnavailableError


@pytest.fixture
def platform_info():
    """Mock platform info for macOS."""
    return PlatformInfo(
        system="Darwin",
        display_server=None,
        clipboard_tool="pbcopy",
        paste_keystroke="M-v",
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
def config():
    """Default clipboard config."""
    return ClipboardConfig()


@pytest.fixture
def device_id():
    """Test device ID."""
    return "test-device-123"


@pytest.fixture
def manager(config, send_message, send_keys, platform_info, mock_clipboard):
    """Create ClipboardManager with mocked dependencies."""
    from ras.clipboard_manager import ClipboardManager

    return ClipboardManager(
        config=config,
        send_message=send_message,
        send_keys=send_keys,
        platform_info=platform_info,
        clipboard_backend=mock_clipboard,
    )


# ============================================================================
# Image Transfer Start Tests
# ============================================================================


class TestImageTransferStart:
    """Test image transfer start handling."""

    @pytest.mark.asyncio
    async def test_start_transfer_creates_state(self, manager, device_id):
        """Starting transfer creates transfer state."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        )

        await manager.handle_message(device_id, msg)

        assert manager._current_transfer is not None
        assert manager._current_transfer.transfer_id == "test-123"
        assert manager._current_transfer.total_size == 1000
        assert manager._current_transfer.format == ImageFormat.PNG
        assert manager._current_transfer.total_chunks == 1
        assert manager._current_transfer.state == TransferState.RECEIVING

    @pytest.mark.asyncio
    async def test_start_transfer_validates_size(self, manager, send_message, device_id):
        """Rejects images over 5MB."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=6 * 1024 * 1024,  # 6MB
                format=ImageFormat.PNG,
                total_chunks=100,
            )
        )

        await manager.handle_message(device_id, msg)

        # Should send error
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.SIZE_EXCEEDED

    @pytest.mark.asyncio
    async def test_start_transfer_validates_format(self, manager, send_message, device_id):
        """Rejects unknown formats."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.UNSPECIFIED,
                total_chunks=1,
            )
        )

        await manager.handle_message(device_id, msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_FORMAT

    @pytest.mark.asyncio
    async def test_start_while_transfer_in_progress(self, manager, send_message, device_id):
        """Rejects second transfer while one is active."""
        # Start first transfer
        msg1 = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="first",
                total_size=1000,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, msg1)

        # Try to start second
        msg2 = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="second",
                total_size=2000,
                format=ImageFormat.JPEG,
                total_chunks=3,
            )
        )
        await manager.handle_message(device_id, msg2)

        # Should send error for second
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.TRANSFER_IN_PROGRESS

    @pytest.mark.asyncio
    async def test_start_at_exactly_5mb_limit(self, manager, device_id):
        """Accepts image at exactly 5MB limit."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=5 * 1024 * 1024,  # Exactly 5MB
                format=ImageFormat.PNG,
                total_chunks=80,
            )
        )

        await manager.handle_message(device_id, msg)

        assert manager._current_transfer is not None
        assert manager._current_transfer.total_size == 5 * 1024 * 1024

    @pytest.mark.asyncio
    async def test_start_with_zero_chunks(self, manager, send_message, device_id):
        """Rejects transfer with zero chunks."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.PNG,
                total_chunks=0,
            )
        )

        await manager.handle_message(device_id, msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_start_with_zero_size(self, manager, send_message, device_id):
        """Rejects transfer with zero size."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=0,
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        )

        await manager.handle_message(device_id, msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.SIZE_EXCEEDED


# ============================================================================
# Chunk Handling Tests
# ============================================================================


class TestChunkHandling:
    """Test image chunk handling."""

    @pytest.mark.asyncio
    async def test_receive_chunk_stores_data(self, manager, send_message, device_id):
        """Chunks are stored in memory."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=100,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunk
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        assert 0 in manager._current_transfer.received_chunks
        assert manager._current_transfer.received_chunks[0] == b"chunk0data"

    @pytest.mark.asyncio
    async def test_receive_chunk_sends_progress(self, manager, send_message, device_id):
        """Progress update sent after each chunk."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=100,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunk
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        # Should have sent progress
        send_message.assert_called()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.progress is not None
        assert sent_msg.progress.received_chunks == 1
        assert sent_msg.progress.total_chunks == 2

    @pytest.mark.asyncio
    async def test_chunk_without_start(self, manager, send_message, device_id):
        """Rejects chunk before start message."""
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_chunk_wrong_transfer_id(self, manager, send_message, device_id):
        """Ignores chunks for wrong transfer."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=100,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunk with wrong ID
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="wrong-id",
                index=0,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        # Should not store chunk or send progress
        assert 0 not in manager._current_transfer.received_chunks
        send_message.assert_not_called()

    @pytest.mark.asyncio
    async def test_chunk_invalid_index_negative(self, manager, send_message, device_id):
        """Rejects negative chunk index."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=100,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunk with negative index
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=-1,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_chunk_invalid_index_too_large(self, manager, send_message, device_id):
        """Rejects chunk index >= total_chunks."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=100,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunk with too large index
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=2,  # Should be 0 or 1
                data=b"chunk0data",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_duplicate_chunk_overwrites(self, manager, send_message, device_id):
        """Duplicate chunk overwrites previous (idempotent)."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=100,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunk twice
        chunk_msg1 = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"first",
            )
        )
        await manager.handle_message(device_id, chunk_msg1)

        chunk_msg2 = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"second",
            )
        )
        await manager.handle_message(device_id, chunk_msg2)

        # Should have second value
        assert manager._current_transfer.received_chunks[0] == b"second"

    @pytest.mark.asyncio
    async def test_chunk_too_large(self, manager, send_message, device_id):
        """Rejects chunk > 64KB."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=100000,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send oversized chunk
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"x" * (65 * 1024),  # 65KB
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_chunks_out_of_order(self, manager, send_message, device_id):
        """Accepts chunks in any order."""
        # Start transfer - use exact size that matches our chunks
        # "chunk0" + "chunk1" + "chunk2" = 6+6+6 = 18 bytes
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=18,
                format=ImageFormat.PNG,
                total_chunks=3,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunks out of order (but not the last one, to avoid completion)
        for idx in [2, 0]:
            chunk_msg = ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="test-123",
                    index=idx,
                    data=f"chunk{idx}".encode(),
                )
            )
            await manager.handle_message(device_id, chunk_msg)

        # Two chunks stored (not sending last to avoid completion clearing state)
        assert len(manager._current_transfer.received_chunks) == 2
        assert 0 in manager._current_transfer.received_chunks
        assert 2 in manager._current_transfer.received_chunks


# ============================================================================
# Transfer Completion Tests
# ============================================================================


class TestTransferCompletion:
    """Test transfer completion."""

    @pytest.mark.asyncio
    async def test_complete_when_all_chunks_received(
        self, manager, send_message, mock_clipboard, send_keys
    ):
        """Transfer completes when all chunks received."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=10,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send all chunks
        for i in range(2):
            chunk_msg = ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="test-123",
                    index=i,
                    data=b"12345",
                )
            )
            await manager.handle_message(device_id, chunk_msg)

        # Should have set clipboard
        assert mock_clipboard.set_image_call_count == 1

        # Should have sent paste keystroke
        send_keys.assert_called_once_with(device_id, "M-v")

        # Should have sent complete message
        calls = send_message.call_args_list
        complete_call = calls[-1][0][1]
        assert complete_call.complete is not None
        assert complete_call.complete.content_type == ContentType.IMAGE

    @pytest.mark.asyncio
    async def test_reassembly_correct_order(self, manager, mock_clipboard, device_id):
        """Chunks reassembled in correct order."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=15,
                format=ImageFormat.PNG,
                total_chunks=3,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunks out of order
        chunks = [(1, b"BBBBB"), (2, b"CCCCC"), (0, b"AAAAA")]
        for idx, data in chunks:
            chunk_msg = ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="test-123",
                    index=idx,
                    data=data,
                )
            )
            await manager.handle_message(device_id, chunk_msg)

        # Should be reassembled in order
        assert mock_clipboard.last_image_data == b"AAAAABBBBBCCCCC"

    @pytest.mark.asyncio
    async def test_size_mismatch_error(self, manager, send_message, device_id):
        """Error if reassembled size != declared size."""
        # Start transfer with wrong size
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=100,  # Wrong - actual will be 10
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send chunks that don't add up to 100
        for i in range(2):
            chunk_msg = ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="test-123",
                    index=i,
                    data=b"12345",
                )
            )
            await manager.handle_message(device_id, chunk_msg)

        # Should have sent error
        calls = send_message.call_args_list
        error_call = calls[-1][0][1]
        assert error_call.error is not None
        assert error_call.error.code == ErrorCode.CHUNK_MISSING

    @pytest.mark.asyncio
    async def test_state_cleared_after_complete(self, manager, mock_clipboard, device_id):
        """State is cleared after successful completion."""
        # Complete a transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=5,
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        )
        await manager.handle_message(device_id, start_msg)

        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"12345",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        # State should be cleared
        assert manager._current_transfer is None


# ============================================================================
# Cancel Tests
# ============================================================================


class TestTransferCancel:
    """Test transfer cancellation."""

    @pytest.mark.asyncio
    async def test_cancel_clears_state(self, manager, send_message, device_id):
        """Cancel clears transfer state."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.PNG,
                total_chunks=10,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Send some chunks
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"chunk0",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        # Cancel
        cancel_msg = ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="test-123")
        )
        await manager.handle_message(device_id, cancel_msg)

        # State should be cleared
        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_cancel_sends_cancelled_message(self, manager, send_message, device_id):
        """Cancel sends cancelled message."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.PNG,
                total_chunks=10,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Cancel
        cancel_msg = ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="test-123")
        )
        await manager.handle_message(device_id, cancel_msg)

        # Should send cancelled message
        calls = send_message.call_args_list
        cancelled_call = calls[-1][0][1]
        assert cancelled_call.cancelled is not None
        assert cancelled_call.cancelled.transfer_id == "test-123"

    @pytest.mark.asyncio
    async def test_cancel_when_idle(self, manager, send_message, device_id):
        """Cancel when no transfer is harmless."""
        import betterproto

        cancel_msg = ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="nonexistent")
        )
        await manager.handle_message(device_id, cancel_msg)

        # Should not raise, might send cancelled ack
        # No error should be sent
        if send_message.called:
            sent_msg = send_message.call_args[0][1]
            field_name, _ = betterproto.which_one_of(sent_msg, "payload")
            assert field_name != "error"

    @pytest.mark.asyncio
    async def test_chunk_after_cancel_ignored(self, manager, send_message, device_id):
        """Chunks after cancel are ignored."""
        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.PNG,
                total_chunks=10,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # Cancel
        cancel_msg = ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="test-123")
        )
        await manager.handle_message(device_id, cancel_msg)
        send_message.reset_mock()

        # Send chunk after cancel
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"late chunk",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        # Should get error (no transfer in progress)
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK


# ============================================================================
# Text Paste Tests
# ============================================================================


class TestTextPaste:
    """Test text paste handling."""

    @pytest.mark.asyncio
    async def test_small_text_pasted_immediately(
        self, manager, send_message, mock_clipboard, send_keys
    ):
        """Small text < 100KB is pasted immediately."""
        msg = ClipboardMessage(
            text_paste=TextPaste(text="Hello, world!")
        )
        await manager.handle_message(device_id, msg)

        # Should set clipboard
        assert mock_clipboard.last_text == "Hello, world!"

        # Should send paste keystroke
        send_keys.assert_called_once_with(device_id, "M-v")

        # Should send complete message
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.complete is not None
        assert sent_msg.complete.content_type == ContentType.TEXT

    @pytest.mark.asyncio
    async def test_large_text_requires_approval(self, manager, send_message, device_id):
        """Text > 100KB requires approval."""
        large_text = "x" * (101 * 1024)  # 101KB
        msg = ClipboardMessage(
            text_paste=TextPaste(text=large_text)
        )
        await manager.handle_message(device_id, msg)

        # Should request approval
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.approval_required is not None
        assert sent_msg.approval_required.size == len(large_text.encode("utf-8"))
        assert len(sent_msg.approval_required.preview) <= 103  # 100 + "..."

    @pytest.mark.asyncio
    async def test_text_at_exactly_threshold(
        self, manager, send_message, mock_clipboard
    ):
        """Text at exactly 100KB is pasted without approval."""
        exact_text = "x" * (100 * 1024)  # Exactly 100KB
        msg = ClipboardMessage(
            text_paste=TextPaste(text=exact_text)
        )
        await manager.handle_message(device_id, msg)

        # Should paste immediately
        assert mock_clipboard.last_text == exact_text

    @pytest.mark.asyncio
    async def test_approved_text_pasted(
        self, manager, send_message, mock_clipboard, send_keys
    ):
        """Approved large text is pasted."""
        large_text = "x" * (101 * 1024)
        msg = ClipboardMessage(
            text_paste_approved=TextPasteApproved(text=large_text)
        )
        await manager.handle_message(device_id, msg)

        # Should set clipboard
        assert mock_clipboard.last_text == large_text

        # Should send paste keystroke
        send_keys.assert_called_once()

        # Should send complete
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.complete is not None

    @pytest.mark.asyncio
    async def test_text_with_unicode(self, manager, mock_clipboard, device_id):
        """Text with unicode is handled correctly."""
        unicode_text = "Hello, 世界! 🎉 Привет мир"
        msg = ClipboardMessage(
            text_paste=TextPaste(text=unicode_text)
        )
        await manager.handle_message(device_id, msg)

        assert mock_clipboard.last_text == unicode_text

    @pytest.mark.asyncio
    async def test_empty_text_rejected(self, manager, send_message, mock_clipboard, device_id):
        """Empty text is rejected."""
        msg = ClipboardMessage(
            text_paste=TextPaste(text="")
        )
        await manager.handle_message(device_id, msg)

        # Should send error
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.SIZE_EXCEEDED

        # Should not paste
        assert mock_clipboard.last_text is None


# ============================================================================
# Clipboard/Paste Error Tests
# ============================================================================


class TestClipboardErrors:
    """Test clipboard operation errors."""

    @pytest.mark.asyncio
    async def test_clipboard_failure_reports_error(
        self, manager, send_message, mock_clipboard
    ):
        """Clipboard failure is reported."""
        mock_clipboard.fail_with = ClipboardUnavailableError("Clipboard failed")

        msg = ClipboardMessage(
            text_paste=TextPaste(text="test")
        )
        await manager.handle_message(device_id, msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][1]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.CLIPBOARD_FAILED

    @pytest.mark.asyncio
    async def test_send_keys_failure_reports_error(
        self, manager, send_message, send_keys, mock_clipboard, device_id
    ):
        """send_keys failure is reported as paste failed."""
        send_keys.side_effect = Exception("tmux error")

        msg = ClipboardMessage(
            text_paste=TextPaste(text="test")
        )
        await manager.handle_message(device_id, msg)

        # Find the error message (might have progress first)
        # call_args: (device_id, message)
        error_msgs = [
            call[0][1] for call in send_message.call_args_list
            if call[0][1].error is not None
        ]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.PASTE_FAILED


# ============================================================================
# Context Manager Tests
# ============================================================================


class TestContextManager:
    """Test ClipboardManager as async context manager."""

    @pytest.mark.asyncio
    async def test_context_manager_cleanup(
        self, config, send_message, send_keys, platform_info, mock_clipboard, device_id
    ):
        """Context manager cleans up on exit."""
        from ras.clipboard_manager import ClipboardManager

        async with ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info,
            clipboard_backend=mock_clipboard,
        ) as manager:
            # Start a transfer
            msg = ClipboardMessage(
                image_start=ImageStart(
                    transfer_id="test-123",
                    total_size=1000,
                    format=ImageFormat.PNG,
                    total_chunks=10,
                )
            )
            await manager.handle_message(device_id, msg)

            assert manager._current_transfer is not None

        # After exit, should be cleaned up
        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_context_manager_cleanup_on_exception(
        self, config, send_message, send_keys, platform_info, mock_clipboard, device_id
    ):
        """Context manager cleans up even on exception."""
        from ras.clipboard_manager import ClipboardManager

        try:
            async with ClipboardManager(
                config=config,
                send_message=send_message,
                send_keys=send_keys,
                platform_info=platform_info,
                clipboard_backend=mock_clipboard,
            ) as manager:
                # Start a transfer
                msg = ClipboardMessage(
                    image_start=ImageStart(
                        transfer_id="test-123",
                        total_size=1000,
                        format=ImageFormat.PNG,
                        total_chunks=10,
                    )
                )
                await manager.handle_message(device_id, msg)
                raise ValueError("Simulated error")
        except ValueError:
            pass

        # Should still be cleaned up
        assert manager._current_transfer is None


# ============================================================================
# Timeout Tests
# ============================================================================


class TestTimeout:
    """Test transfer timeout handling."""

    @pytest.mark.asyncio
    async def test_timeout_after_no_chunks(self, send_message, send_keys, platform_info, mock_clipboard, device_id):
        """Transfer times out after configured timeout with no chunks."""
        import betterproto
        from ras.clipboard_manager import ClipboardManager

        # Use short timeout for test
        config = ClipboardConfig(transfer_timeout=0.02)

        # Mock asyncio.sleep in clipboard_manager to return immediately
        with patch("ras.clipboard_manager.asyncio.sleep", return_value=None):
            manager = ClipboardManager(
                config=config,
                send_message=send_message,
                send_keys=send_keys,
                platform_info=platform_info,
                clipboard_backend=mock_clipboard,
            )

            # Start transfer
            msg = ClipboardMessage(
                image_start=ImageStart(
                    transfer_id="test-123",
                    total_size=1000,
                    format=ImageFormat.PNG,
                    total_chunks=10,
                )
            )
            await manager.handle_message(device_id, msg)

            # Wait for timeout task to complete
            pending = asyncio.all_tasks() - {asyncio.current_task()}
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)

        # Should have sent timeout error (call_args: (device_id, message))
        error_msgs = [
            call[0][1] for call in send_message.call_args_list
            if betterproto.which_one_of(call[0][1], "payload")[0] == "error"
        ]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.TRANSFER_TIMEOUT

        # State should be cleared
        assert manager._current_transfer is None

        # Cleanup
        manager.cleanup()

    @pytest.mark.asyncio
    async def test_timeout_resets_on_chunk(self, send_message, send_keys, platform_info, mock_clipboard, device_id):
        """Timeout resets when chunk received."""
        import betterproto
        from ras.clipboard_manager import ClipboardManager

        config = ClipboardConfig(transfer_timeout=0.05)

        # Track timeout cancellations to verify timeout was reset
        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info,
            clipboard_backend=mock_clipboard,
        )

        # Track timeout task cancellations
        cancel_count = [0]
        original_cancel_timeout = manager._cancel_timeout

        def tracking_cancel():
            if manager._timeout_task is not None:
                cancel_count[0] += 1
            original_cancel_timeout()

        manager._cancel_timeout = tracking_cancel

        # Start transfer - starts first timeout
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=10,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(device_id, start_msg)

        # First timeout task should be created
        assert manager._timeout_task is not None

        # Send chunk - should cancel old timeout and start new one
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"12345",
            )
        )
        await manager.handle_message(device_id, chunk_msg)

        # Timeout was cancelled and restarted (cancel_count should be 1)
        assert cancel_count[0] >= 1, "Timeout should have been cancelled and reset"

        # Should NOT have timed out (no error events) (call_args: (device_id, message))
        error_msgs = [
            call[0][1] for call in send_message.call_args_list
            if betterproto.which_one_of(call[0][1], "payload")[0] == "error"
        ]
        assert len(error_msgs) == 0

        # Cleanup
        manager.cleanup()


# ============================================================================
# Image Path Callback Tests
# ============================================================================


class TestImagePathCallback:
    """Test send_image_path callback for file-based image delivery."""

    @pytest.fixture
    def send_image_path(self):
        """Mock send_image_path callback."""
        return AsyncMock()

    @pytest.fixture
    def manager_with_image_path(
        self, config, send_message, send_keys, send_image_path, platform_info, mock_clipboard
    ):
        """Create ClipboardManager with send_image_path callback."""
        from ras.clipboard_manager import ClipboardManager

        return ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            send_image_path=send_image_path,
            platform_info=platform_info,
            clipboard_backend=mock_clipboard,
        )

    @pytest.mark.asyncio
    async def test_image_path_callback_called_instead_of_clipboard(
        self, manager_with_image_path, send_message, send_keys, send_image_path, mock_clipboard, device_id
    ):
        """When send_image_path provided, uses file path instead of clipboard."""
        import os
        import betterproto

        # Complete a small image transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=5,
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        )
        await manager_with_image_path.handle_message(device_id, start_msg)

        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"12345",
            )
        )
        await manager_with_image_path.handle_message(device_id, chunk_msg)

        # send_image_path should be called (not send_keys)
        send_image_path.assert_called_once()
        call_args = send_image_path.call_args[0]
        assert call_args[0] == device_id

        # Path should contain transfer_id prefix
        image_path = call_args[1]
        assert "ras-image-test-123" in image_path
        assert image_path.endswith(".png")

        # Clipboard should NOT be called (using file path instead)
        assert mock_clipboard.set_image_call_count == 0

        # send_keys should NOT be called
        send_keys.assert_not_called()

        # Complete message should be sent
        complete_msgs = [
            call[0][1] for call in send_message.call_args_list
            if betterproto.which_one_of(call[0][1], "payload")[0] == "complete"
        ]
        assert len(complete_msgs) == 1
        assert complete_msgs[0].complete.content_type == ContentType.IMAGE

        # Cleanup temp file
        if os.path.exists(image_path):
            os.unlink(image_path)

    @pytest.mark.asyncio
    async def test_image_saved_with_correct_data(
        self, manager_with_image_path, send_image_path, device_id
    ):
        """Image file contains correct reassembled data."""
        import os

        image_data = b"PNG_IMAGE_DATA_123"

        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="abc12345",
                total_size=len(image_data),
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        )
        await manager_with_image_path.handle_message(device_id, start_msg)

        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="abc12345",
                index=0,
                data=image_data,
            )
        )
        await manager_with_image_path.handle_message(device_id, chunk_msg)

        # Get the saved file path
        image_path = send_image_path.call_args[0][1]

        # Verify file contents
        with open(image_path, "rb") as f:
            saved_data = f.read()
        assert saved_data == image_data

        # Cleanup
        os.unlink(image_path)

    @pytest.mark.asyncio
    async def test_image_path_callback_failure_reports_error(
        self, manager_with_image_path, send_message, send_image_path, device_id
    ):
        """send_image_path failure is reported as paste failed."""
        import os
        import tempfile
        import betterproto

        send_image_path.side_effect = Exception("tmux send-keys failed")

        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-err",
                total_size=5,
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        )
        await manager_with_image_path.handle_message(device_id, start_msg)

        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-err",
                index=0,
                data=b"12345",
            )
        )
        await manager_with_image_path.handle_message(device_id, chunk_msg)

        # Should send PASTE_FAILED error
        error_msgs = [
            call[0][1] for call in send_message.call_args_list
            if betterproto.which_one_of(call[0][1], "payload")[0] == "error"
        ]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.PASTE_FAILED
        assert "tmux send-keys failed" in error_msgs[0].error.message

        # Cleanup temp file if it exists
        temp_dir = tempfile.gettempdir()
        image_path = os.path.join(temp_dir, "ras-image-test-err.png")
        if os.path.exists(image_path):
            os.unlink(image_path)

    @pytest.mark.asyncio
    async def test_jpeg_format_uses_jpg_extension(
        self, manager_with_image_path, send_image_path, device_id
    ):
        """JPEG format images get .jpg extension."""
        import os

        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="jpeg-test",
                total_size=5,
                format=ImageFormat.JPEG,
                total_chunks=1,
            )
        )
        await manager_with_image_path.handle_message(device_id, start_msg)

        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="jpeg-test",
                index=0,
                data=b"JFIF!",
            )
        )
        await manager_with_image_path.handle_message(device_id, chunk_msg)

        image_path = send_image_path.call_args[0][1]
        assert image_path.endswith(".jpg")

        # Cleanup
        if os.path.exists(image_path):
            os.unlink(image_path)


# ============================================================================
# Cleanup Function Tests
# ============================================================================


class TestCleanupFunction:
    """Test cleanup_old_image_files function."""

    def test_cleanup_removes_old_files(self, tmp_path):
        """Old ras-image files are removed."""
        import os
        import time
        from ras.clipboard_manager import cleanup_old_image_files

        # Create old file (modify time in the past)
        old_file = tmp_path / "ras-image-old12345.png"
        old_file.write_bytes(b"old image data")

        # Mock tempfile.gettempdir to return our tmp_path
        with patch("ras.clipboard_manager.tempfile.gettempdir", return_value=str(tmp_path)):
            # Set file mtime to 2 hours ago
            old_time = time.time() - 7200
            os.utime(old_file, (old_time, old_time))

            # Cleanup with 1 hour max age
            cleaned = cleanup_old_image_files(max_age_seconds=3600)

        assert cleaned == 1
        assert not old_file.exists()

    def test_cleanup_preserves_new_files(self, tmp_path):
        """Recent ras-image files are preserved."""
        from ras.clipboard_manager import cleanup_old_image_files

        # Create new file
        new_file = tmp_path / "ras-image-new12345.png"
        new_file.write_bytes(b"new image data")

        with patch("ras.clipboard_manager.tempfile.gettempdir", return_value=str(tmp_path)):
            cleaned = cleanup_old_image_files(max_age_seconds=3600)

        assert cleaned == 0
        assert new_file.exists()

    def test_cleanup_ignores_non_ras_files(self, tmp_path):
        """Non-ras-image files are not touched."""
        import os
        import time
        from ras.clipboard_manager import cleanup_old_image_files

        # Create old file with different name
        other_file = tmp_path / "some-other-image.png"
        other_file.write_bytes(b"other data")

        with patch("ras.clipboard_manager.tempfile.gettempdir", return_value=str(tmp_path)):
            old_time = time.time() - 7200
            os.utime(other_file, (old_time, old_time))
            cleaned = cleanup_old_image_files(max_age_seconds=3600)

        assert cleaned == 0
        assert other_file.exists()
