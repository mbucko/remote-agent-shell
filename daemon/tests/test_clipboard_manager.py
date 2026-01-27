"""Tests for ClipboardManager."""

import asyncio
from unittest.mock import AsyncMock, MagicMock

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
    async def test_start_transfer_creates_state(self, manager):
        """Starting transfer creates transfer state."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        )

        await manager.handle_message(msg)

        assert manager._current_transfer is not None
        assert manager._current_transfer.transfer_id == "test-123"
        assert manager._current_transfer.total_size == 1000
        assert manager._current_transfer.format == ImageFormat.PNG
        assert manager._current_transfer.total_chunks == 1
        assert manager._current_transfer.state == TransferState.RECEIVING

    @pytest.mark.asyncio
    async def test_start_transfer_validates_size(self, manager, send_message):
        """Rejects images over 5MB."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=6 * 1024 * 1024,  # 6MB
                format=ImageFormat.PNG,
                total_chunks=100,
            )
        )

        await manager.handle_message(msg)

        # Should send error
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.SIZE_EXCEEDED

    @pytest.mark.asyncio
    async def test_start_transfer_validates_format(self, manager, send_message):
        """Rejects unknown formats."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.UNSPECIFIED,
                total_chunks=1,
            )
        )

        await manager.handle_message(msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_FORMAT

    @pytest.mark.asyncio
    async def test_start_while_transfer_in_progress(self, manager, send_message):
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
        await manager.handle_message(msg1)

        # Try to start second
        msg2 = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="second",
                total_size=2000,
                format=ImageFormat.JPEG,
                total_chunks=3,
            )
        )
        await manager.handle_message(msg2)

        # Should send error for second
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.TRANSFER_IN_PROGRESS

    @pytest.mark.asyncio
    async def test_start_at_exactly_5mb_limit(self, manager):
        """Accepts image at exactly 5MB limit."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=5 * 1024 * 1024,  # Exactly 5MB
                format=ImageFormat.PNG,
                total_chunks=80,
            )
        )

        await manager.handle_message(msg)

        assert manager._current_transfer is not None
        assert manager._current_transfer.total_size == 5 * 1024 * 1024

    @pytest.mark.asyncio
    async def test_start_with_zero_chunks(self, manager, send_message):
        """Rejects transfer with zero chunks."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=1000,
                format=ImageFormat.PNG,
                total_chunks=0,
            )
        )

        await manager.handle_message(msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_start_with_zero_size(self, manager, send_message):
        """Rejects transfer with zero size."""
        msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=0,
                format=ImageFormat.PNG,
                total_chunks=1,
            )
        )

        await manager.handle_message(msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.SIZE_EXCEEDED


# ============================================================================
# Chunk Handling Tests
# ============================================================================


class TestChunkHandling:
    """Test image chunk handling."""

    @pytest.mark.asyncio
    async def test_receive_chunk_stores_data(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send chunk
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(chunk_msg)

        assert 0 in manager._current_transfer.received_chunks
        assert manager._current_transfer.received_chunks[0] == b"chunk0data"

    @pytest.mark.asyncio
    async def test_receive_chunk_sends_progress(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send chunk
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(chunk_msg)

        # Should have sent progress
        send_message.assert_called()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.progress is not None
        assert sent_msg.progress.received_chunks == 1
        assert sent_msg.progress.total_chunks == 2

    @pytest.mark.asyncio
    async def test_chunk_without_start(self, manager, send_message):
        """Rejects chunk before start message."""
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(chunk_msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_chunk_wrong_transfer_id(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send chunk with wrong ID
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="wrong-id",
                index=0,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(chunk_msg)

        # Should not store chunk or send progress
        assert 0 not in manager._current_transfer.received_chunks
        send_message.assert_not_called()

    @pytest.mark.asyncio
    async def test_chunk_invalid_index_negative(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send chunk with negative index
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=-1,
                data=b"chunk0data",
            )
        )
        await manager.handle_message(chunk_msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_chunk_invalid_index_too_large(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send chunk with too large index
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=2,  # Should be 0 or 1
                data=b"chunk0data",
            )
        )
        await manager.handle_message(chunk_msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_duplicate_chunk_overwrites(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send chunk twice
        chunk_msg1 = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"first",
            )
        )
        await manager.handle_message(chunk_msg1)

        chunk_msg2 = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"second",
            )
        )
        await manager.handle_message(chunk_msg2)

        # Should have second value
        assert manager._current_transfer.received_chunks[0] == b"second"

    @pytest.mark.asyncio
    async def test_chunk_too_large(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send oversized chunk
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"x" * (65 * 1024),  # 65KB
            )
        )
        await manager.handle_message(chunk_msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.INVALID_CHUNK

    @pytest.mark.asyncio
    async def test_chunks_out_of_order(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send chunks out of order (but not the last one, to avoid completion)
        for idx in [2, 0]:
            chunk_msg = ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="test-123",
                    index=idx,
                    data=f"chunk{idx}".encode(),
                )
            )
            await manager.handle_message(chunk_msg)

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
        await manager.handle_message(start_msg)

        # Send all chunks
        for i in range(2):
            chunk_msg = ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="test-123",
                    index=i,
                    data=b"12345",
                )
            )
            await manager.handle_message(chunk_msg)

        # Should have set clipboard
        assert mock_clipboard.set_image_call_count == 1

        # Should have sent paste keystroke
        send_keys.assert_called_once_with("M-v")

        # Should have sent complete message
        calls = send_message.call_args_list
        complete_call = calls[-1][0][0]
        assert complete_call.complete is not None
        assert complete_call.complete.content_type == ContentType.IMAGE

    @pytest.mark.asyncio
    async def test_reassembly_correct_order(self, manager, mock_clipboard):
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
        await manager.handle_message(start_msg)

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
            await manager.handle_message(chunk_msg)

        # Should be reassembled in order
        assert mock_clipboard.last_image_data == b"AAAAABBBBBCCCCC"

    @pytest.mark.asyncio
    async def test_size_mismatch_error(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send chunks that don't add up to 100
        for i in range(2):
            chunk_msg = ClipboardMessage(
                image_chunk=ImageChunk(
                    transfer_id="test-123",
                    index=i,
                    data=b"12345",
                )
            )
            await manager.handle_message(chunk_msg)

        # Should have sent error
        calls = send_message.call_args_list
        error_call = calls[-1][0][0]
        assert error_call.error is not None
        assert error_call.error.code == ErrorCode.CHUNK_MISSING

    @pytest.mark.asyncio
    async def test_state_cleared_after_complete(self, manager, mock_clipboard):
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
        await manager.handle_message(start_msg)

        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"12345",
            )
        )
        await manager.handle_message(chunk_msg)

        # State should be cleared
        assert manager._current_transfer is None


# ============================================================================
# Cancel Tests
# ============================================================================


class TestTransferCancel:
    """Test transfer cancellation."""

    @pytest.mark.asyncio
    async def test_cancel_clears_state(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Send some chunks
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"chunk0",
            )
        )
        await manager.handle_message(chunk_msg)

        # Cancel
        cancel_msg = ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="test-123")
        )
        await manager.handle_message(cancel_msg)

        # State should be cleared
        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_cancel_sends_cancelled_message(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Cancel
        cancel_msg = ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="test-123")
        )
        await manager.handle_message(cancel_msg)

        # Should send cancelled message
        calls = send_message.call_args_list
        cancelled_call = calls[-1][0][0]
        assert cancelled_call.cancelled is not None
        assert cancelled_call.cancelled.transfer_id == "test-123"

    @pytest.mark.asyncio
    async def test_cancel_when_idle(self, manager, send_message):
        """Cancel when no transfer is harmless."""
        import betterproto

        cancel_msg = ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="nonexistent")
        )
        await manager.handle_message(cancel_msg)

        # Should not raise, might send cancelled ack
        # No error should be sent
        if send_message.called:
            sent_msg = send_message.call_args[0][0]
            field_name, _ = betterproto.which_one_of(sent_msg, "payload")
            assert field_name != "error"

    @pytest.mark.asyncio
    async def test_chunk_after_cancel_ignored(self, manager, send_message):
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
        await manager.handle_message(start_msg)

        # Cancel
        cancel_msg = ClipboardMessage(
            image_cancel=ImageCancel(transfer_id="test-123")
        )
        await manager.handle_message(cancel_msg)
        send_message.reset_mock()

        # Send chunk after cancel
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"late chunk",
            )
        )
        await manager.handle_message(chunk_msg)

        # Should get error (no transfer in progress)
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
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
        await manager.handle_message(msg)

        # Should set clipboard
        assert mock_clipboard.last_text == "Hello, world!"

        # Should send paste keystroke
        send_keys.assert_called_once_with("M-v")

        # Should send complete message
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.complete is not None
        assert sent_msg.complete.content_type == ContentType.TEXT

    @pytest.mark.asyncio
    async def test_large_text_requires_approval(self, manager, send_message):
        """Text > 100KB requires approval."""
        large_text = "x" * (101 * 1024)  # 101KB
        msg = ClipboardMessage(
            text_paste=TextPaste(text=large_text)
        )
        await manager.handle_message(msg)

        # Should request approval
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
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
        await manager.handle_message(msg)

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
        await manager.handle_message(msg)

        # Should set clipboard
        assert mock_clipboard.last_text == large_text

        # Should send paste keystroke
        send_keys.assert_called_once()

        # Should send complete
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.complete is not None

    @pytest.mark.asyncio
    async def test_text_with_unicode(self, manager, mock_clipboard):
        """Text with unicode is handled correctly."""
        unicode_text = "Hello, 世界! 🎉 Привет мир"
        msg = ClipboardMessage(
            text_paste=TextPaste(text=unicode_text)
        )
        await manager.handle_message(msg)

        assert mock_clipboard.last_text == unicode_text

    @pytest.mark.asyncio
    async def test_empty_text_rejected(self, manager, send_message, mock_clipboard):
        """Empty text is rejected."""
        msg = ClipboardMessage(
            text_paste=TextPaste(text="")
        )
        await manager.handle_message(msg)

        # Should send error
        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
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
        await manager.handle_message(msg)

        send_message.assert_called_once()
        sent_msg = send_message.call_args[0][0]
        assert sent_msg.error is not None
        assert sent_msg.error.code == ErrorCode.CLIPBOARD_FAILED

    @pytest.mark.asyncio
    async def test_send_keys_failure_reports_error(
        self, manager, send_message, send_keys, mock_clipboard
    ):
        """send_keys failure is reported as paste failed."""
        send_keys.side_effect = Exception("tmux error")

        msg = ClipboardMessage(
            text_paste=TextPaste(text="test")
        )
        await manager.handle_message(msg)

        # Find the error message (might have progress first)
        error_msgs = [
            call[0][0] for call in send_message.call_args_list
            if call[0][0].error is not None
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
        self, config, send_message, send_keys, platform_info, mock_clipboard
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
            await manager.handle_message(msg)

            assert manager._current_transfer is not None

        # After exit, should be cleaned up
        assert manager._current_transfer is None

    @pytest.mark.asyncio
    async def test_context_manager_cleanup_on_exception(
        self, config, send_message, send_keys, platform_info, mock_clipboard
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
                await manager.handle_message(msg)
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
    async def test_timeout_after_no_chunks(self, send_message, send_keys, platform_info, mock_clipboard):
        """Transfer times out after configured timeout with no chunks."""
        import betterproto
        from ras.clipboard_manager import ClipboardManager

        # Use short timeout for test
        config = ClipboardConfig(transfer_timeout=0.1)

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
        await manager.handle_message(msg)

        # Wait for timeout
        await asyncio.sleep(0.2)

        # Should have sent timeout error
        error_msgs = [
            call[0][0] for call in send_message.call_args_list
            if betterproto.which_one_of(call[0][0], "payload")[0] == "error"
        ]
        assert len(error_msgs) == 1
        assert error_msgs[0].error.code == ErrorCode.TRANSFER_TIMEOUT

        # State should be cleared
        assert manager._current_transfer is None

        # Cleanup
        manager.cleanup()

    @pytest.mark.asyncio
    async def test_timeout_resets_on_chunk(self, send_message, send_keys, platform_info, mock_clipboard):
        """Timeout resets when chunk received."""
        import betterproto
        from ras.clipboard_manager import ClipboardManager

        config = ClipboardConfig(transfer_timeout=0.2)

        manager = ClipboardManager(
            config=config,
            send_message=send_message,
            send_keys=send_keys,
            platform_info=platform_info,
            clipboard_backend=mock_clipboard,
        )

        # Start transfer
        start_msg = ClipboardMessage(
            image_start=ImageStart(
                transfer_id="test-123",
                total_size=10,
                format=ImageFormat.PNG,
                total_chunks=2,
            )
        )
        await manager.handle_message(start_msg)

        # Wait partial timeout
        await asyncio.sleep(0.1)

        # Send chunk (resets timeout)
        chunk_msg = ClipboardMessage(
            image_chunk=ImageChunk(
                transfer_id="test-123",
                index=0,
                data=b"12345",
            )
        )
        await manager.handle_message(chunk_msg)

        # Wait another partial timeout
        await asyncio.sleep(0.1)

        # Should NOT have timed out (timeout was reset)
        error_msgs = [
            call[0][0] for call in send_message.call_args_list
            if betterproto.which_one_of(call[0][0], "payload")[0] == "error"
        ]
        assert len(error_msgs) == 0

        # Cleanup
        manager.cleanup()
