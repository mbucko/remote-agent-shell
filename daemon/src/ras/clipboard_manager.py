"""Clipboard manager for handling image and text transfers."""

import asyncio
import glob
import logging
import os
import tempfile
import time
from typing import Awaitable, Callable, Optional

from .clipboard_types import (
    ClipboardConfig,
    ClipboardMessage,
    ImageTransfer,
    TransferState,
    ErrorCode,
    ImageFormat,
    ContentType,
    Progress,
    Complete,
    ApprovalRequired,
    Error,
    Cancelled,
)
from .clipboard_platform import (
    ClipboardBackend,
    ClipboardUnavailableError,
    PlatformInfo,
    detect_platform,
    get_clipboard_backend,
    check_clipboard_tool,
)

logger = logging.getLogger(__name__)

# Max age for temp image files (1 hour)
_TEMP_IMAGE_MAX_AGE_SECONDS = 3600


def cleanup_old_image_files(max_age_seconds: int = _TEMP_IMAGE_MAX_AGE_SECONDS) -> int:
    """Clean up old ras-image temp files.

    Removes ras-image-* files from temp directory that are older than max_age_seconds.

    Args:
        max_age_seconds: Maximum age in seconds before cleanup (default: 1 hour).

    Returns:
        Number of files cleaned up.
    """
    temp_dir = tempfile.gettempdir()
    pattern = os.path.join(temp_dir, "ras-image-*")
    now = time.time()
    cleaned = 0

    for filepath in glob.glob(pattern):
        try:
            file_age = now - os.path.getmtime(filepath)
            if file_age > max_age_seconds:
                os.unlink(filepath)
                cleaned += 1
                logger.debug("Cleaned up old image file: %s", filepath)
        except OSError as e:
            logger.debug("Failed to clean up %s: %s", filepath, e)

    if cleaned > 0:
        logger.info("Cleaned up %d old image file(s)", cleaned)

    return cleaned


class ClipboardError(Exception):
    """Clipboard operation error."""

    def __init__(self, code: ErrorCode, message: str):
        self.code = code
        self.message = message
        super().__init__(message)


class ClipboardManager:
    """Manages clipboard transfers (images and text).

    Handles the protocol for receiving chunked images and text from
    the phone, setting the system clipboard, and sending paste keystrokes.

    Usage:
        async with ClipboardManager(...) as manager:
            await manager.handle_message(msg)
    """

    def __init__(
        self,
        config: ClipboardConfig,
        send_message: Callable[[str, ClipboardMessage], Awaitable[None]],
        send_keys: Callable[[str, str], Awaitable[None]],
        send_image_path: Optional[Callable[[str, str], Awaitable[None]]] = None,
        platform_info: Optional[PlatformInfo] = None,
        clipboard_backend: Optional[ClipboardBackend] = None,
    ):
        """Initialize ClipboardManager.

        Args:
            config: Clipboard configuration.
            send_message: Callback to send messages to phone (device_id, message).
            send_keys: Callback to send keystrokes to tmux session (device_id, keystroke).
            send_image_path: Callback to type image path into terminal (device_id, path).
                If provided, images are sent via file path instead of clipboard paste.
            platform_info: Platform info (auto-detected if None).
            clipboard_backend: Clipboard backend (auto-created if None).
        """
        self.config = config
        self._send_message = send_message
        self._send_keys = send_keys
        self._send_image_path = send_image_path

        # Platform and clipboard (allow injection for testing)
        self._platform_info = platform_info or detect_platform()
        self._clipboard = clipboard_backend or get_clipboard_backend(
            self._platform_info
        )

        # Transfer state
        self._current_transfer: Optional[ImageTransfer] = None
        self._timeout_task: Optional[asyncio.Task] = None

        # Clean up old temp image files on startup
        cleanup_old_image_files()

        logger.info(
            "ClipboardManager initialized: platform=%s, clipboard_tool=%s",
            self._platform_info.system,
            self._platform_info.clipboard_tool,
        )

    async def __aenter__(self) -> "ClipboardManager":
        """Enter async context."""
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        """Exit async context, cleanup resources."""
        self.cleanup()

    def cleanup(self) -> None:
        """Clean up any active transfer and tasks."""
        self._cancel_timeout()
        self._current_transfer = None
        logger.debug("ClipboardManager cleanup complete")

    async def verify(self) -> None:
        """Verify clipboard tools are available.

        Raises:
            ClipboardUnavailableError: If tools are not available.
        """
        tool = self.config.clipboard_tool or self._platform_info.clipboard_tool
        check_clipboard_tool(tool)
        logger.info("Clipboard tool verified: %s", tool)

    async def handle_message(self, device_id: str, msg: ClipboardMessage) -> None:
        """Handle a clipboard message.

        Args:
            device_id: The device ID that sent this message.
            msg: The clipboard message to handle.
        """
        import betterproto

        # Determine which field is set (oneof)
        field_name, field_value = betterproto.which_one_of(msg, "payload")

        if not field_name:
            logger.warning("Unknown clipboard message type")
            return

        transfer_id = None
        try:
            # Get transfer_id if available
            if hasattr(field_value, "transfer_id"):
                transfer_id = field_value.transfer_id

            if field_name == "image_start":
                await self._handle_image_start(device_id, field_value)
            elif field_name == "image_chunk":
                await self._handle_image_chunk(device_id, field_value)
            elif field_name == "image_cancel":
                await self._handle_image_cancel(device_id, field_value)
            elif field_name == "text_paste":
                await self._handle_text_paste(device_id, field_value)
            elif field_name == "text_paste_approved":
                await self._handle_text_paste_approved(device_id, field_value)
            else:
                logger.warning("Unhandled clipboard message type: %s", field_name)
        except ClipboardError as e:
            await self._send_error(device_id, transfer_id, e.code, e.message)

    async def _handle_image_start(self, device_id: str, start: "ImageStart") -> None:
        """Handle image transfer start."""
        from .clipboard_types import ImageStart

        logger.info(
            "Image transfer start: id=%s, size=%d, format=%s, chunks=%d",
            start.transfer_id,
            start.total_size,
            start.format.name,
            start.total_chunks,
        )

        # Validate: no transfer in progress
        if self._current_transfer is not None:
            raise ClipboardError(
                ErrorCode.TRANSFER_IN_PROGRESS,
                "Another transfer is in progress",
            )

        # Validate: size > 0
        if start.total_size <= 0:
            raise ClipboardError(
                ErrorCode.SIZE_EXCEEDED,
                "Image size must be greater than 0",
            )

        # Validate: size <= max
        if start.total_size > self.config.max_image_size:
            raise ClipboardError(
                ErrorCode.SIZE_EXCEEDED,
                f"Image exceeds {self.config.max_image_size // 1024 // 1024}MB limit",
            )

        # Validate: format
        if start.format == ImageFormat.UNSPECIFIED:
            raise ClipboardError(
                ErrorCode.INVALID_FORMAT,
                "Image format not specified",
            )

        # Validate: chunks > 0
        if start.total_chunks <= 0:
            raise ClipboardError(
                ErrorCode.INVALID_CHUNK,
                "Total chunks must be greater than 0",
            )

        # Create transfer state
        self._current_transfer = ImageTransfer(
            transfer_id=start.transfer_id,
            device_id=device_id,
            total_size=start.total_size,
            format=start.format,
            total_chunks=start.total_chunks,
            received_chunks={},
            state=TransferState.RECEIVING,
        )

        # Start timeout
        self._start_timeout()

        logger.debug("Image transfer state created: %s", start.transfer_id)

    async def _handle_image_chunk(self, device_id: str, chunk: "ImageChunk") -> None:
        """Handle image chunk."""
        from .clipboard_types import ImageChunk

        # Validate: transfer exists
        if self._current_transfer is None:
            raise ClipboardError(
                ErrorCode.INVALID_CHUNK,
                "No transfer in progress",
            )

        # Check transfer ID matches
        if self._current_transfer.transfer_id != chunk.transfer_id:
            logger.debug(
                "Ignoring chunk for different transfer: expected=%s, got=%s",
                self._current_transfer.transfer_id,
                chunk.transfer_id,
            )
            return

        # Validate: index in range
        if chunk.index < 0 or chunk.index >= self._current_transfer.total_chunks:
            raise ClipboardError(
                ErrorCode.INVALID_CHUNK,
                f"Invalid chunk index: {chunk.index}",
            )

        # Validate: chunk size
        if len(chunk.data) > self.config.chunk_size:
            raise ClipboardError(
                ErrorCode.INVALID_CHUNK,
                f"Chunk exceeds {self.config.chunk_size} bytes",
            )

        # Store chunk
        self._current_transfer.received_chunks[chunk.index] = chunk.data

        logger.debug(
            "Chunk %d/%d received (%d bytes)",
            chunk.index + 1,
            self._current_transfer.total_chunks,
            len(chunk.data),
        )

        # Reset timeout
        self._start_timeout()

        # Send progress
        await self._send_progress(device_id)

        # Check if complete
        if self._current_transfer.is_complete:
            await self._complete_image_transfer(device_id)

    async def _handle_image_cancel(self, device_id: str, cancel: "ImageCancel") -> None:
        """Handle transfer cancellation."""
        from .clipboard_types import ImageCancel

        logger.info("Image transfer cancel request: %s", cancel.transfer_id)

        # Only cancel if transfer ID matches (or no transfer in progress)
        if self._current_transfer is not None:
            if self._current_transfer.transfer_id != cancel.transfer_id:
                logger.debug(
                    "Ignoring cancel for different transfer: expected=%s, got=%s",
                    self._current_transfer.transfer_id,
                    cancel.transfer_id,
                )
                return

        # Clear state
        self._cancel_timeout()
        self._current_transfer = None

        # Send cancelled acknowledgement
        await self._send_message(
            device_id,
            ClipboardMessage(
                cancelled=Cancelled(transfer_id=cancel.transfer_id)
            )
        )

    async def _handle_text_paste(self, device_id: str, text_msg: "TextPaste") -> None:
        """Handle text paste."""
        from .clipboard_types import TextPaste

        text = text_msg.text
        size = len(text.encode("utf-8"))

        logger.info("Text paste: %d bytes", size)

        # Validate: non-empty
        if size == 0:
            raise ClipboardError(
                ErrorCode.SIZE_EXCEEDED,
                "Text cannot be empty",
            )

        # Check if approval needed
        if size > self.config.text_approval_threshold:
            logger.info("Text requires approval: %d > %d", size, self.config.text_approval_threshold)
            preview = text[:100] + "..." if len(text) > 100 else text
            await self._send_message(
                device_id,
                ClipboardMessage(
                    approval_required=ApprovalRequired(
                        size=size,
                        preview=preview,
                    )
                )
            )
            return

        # Paste immediately
        await self._paste_text(device_id, text)

    async def _handle_text_paste_approved(self, device_id: str, text_msg: "TextPasteApproved") -> None:
        """Handle approved text paste."""
        from .clipboard_types import TextPasteApproved

        text = text_msg.text
        logger.info("Approved text paste: %d bytes", len(text.encode("utf-8")))
        await self._paste_text(device_id, text)

    async def _paste_text(self, device_id: str, text: str) -> None:
        """Set text clipboard and send paste keystroke."""
        try:
            await self._clipboard.set_text(text)
            logger.debug("Text set to clipboard")
        except ClipboardUnavailableError as e:
            raise ClipboardError(ErrorCode.CLIPBOARD_FAILED, str(e))

        # Send paste keystroke
        keystroke = self.config.paste_keystroke or self._platform_info.paste_keystroke
        try:
            await self._send_keys(device_id, keystroke)
            logger.debug("Paste keystroke sent: %s", keystroke)
        except Exception as e:
            raise ClipboardError(ErrorCode.PASTE_FAILED, str(e))

        # Send complete
        await self._send_message(
            device_id,
            ClipboardMessage(
                complete=Complete(
                    transfer_id="",
                    content_type=ContentType.TEXT,
                )
            )
        )

    async def _complete_image_transfer(self, device_id: str) -> None:
        """Complete image transfer - reassemble, save to file, type path."""
        transfer = self._current_transfer
        if not transfer:
            return

        logger.info(
            "Completing image transfer: %s (%d chunks)",
            transfer.transfer_id,
            transfer.total_chunks,
        )

        # Cancel timeout
        self._cancel_timeout()

        # Reassemble chunks in order
        transfer.state = TransferState.ASSEMBLING
        chunks = [transfer.received_chunks[i] for i in range(transfer.total_chunks)]
        image_data = b"".join(chunks)

        # Validate size
        if len(image_data) != transfer.total_size:
            logger.error(
                "Size mismatch: expected=%d, got=%d",
                transfer.total_size,
                len(image_data),
            )
            await self._send_error(
                device_id,
                transfer.transfer_id,
                ErrorCode.CHUNK_MISSING,
                f"Size mismatch: expected {transfer.total_size}, got {len(image_data)}",
            )
            return

        transfer.state = TransferState.PASTING

        # Map format to file extension
        ext_map = {
            ImageFormat.JPEG: ".jpg",
            ImageFormat.PNG: ".png",
            ImageFormat.GIF: ".gif",
            ImageFormat.WEBP: ".webp",
        }
        ext = ext_map.get(transfer.format, ".png")

        # Save image to temp file (persistent - not deleted immediately)
        # Use transfer_id prefix for uniqueness to avoid overwrites
        temp_dir = tempfile.gettempdir()
        image_path = os.path.join(temp_dir, f"ras-image-{transfer.transfer_id[:8]}{ext}")

        try:
            with open(image_path, "wb") as f:
                f.write(image_data)
            logger.info("Image saved to: %s (%d bytes)", image_path, len(image_data))
        except Exception as e:
            await self._send_error(
                device_id,
                transfer.transfer_id,
                ErrorCode.CLIPBOARD_FAILED,
                f"Failed to save image: {e}",
            )
            return

        # Send image path to terminal (using tmux send-keys to type it)
        if self._send_image_path:
            try:
                await self._send_image_path(device_id, image_path)
                logger.debug("Image path sent to terminal: %s", image_path)
            except Exception as e:
                await self._send_error(
                    device_id,
                    transfer.transfer_id,
                    ErrorCode.PASTE_FAILED,
                    str(e),
                )
                return
        else:
            # Fallback: set clipboard and try to paste (requires permissions)
            try:
                await asyncio.wait_for(
                    self._clipboard.set_image(image_data, transfer.format),
                    timeout=self.config.paste_timeout,
                )
                logger.debug("Image set to clipboard")
            except asyncio.TimeoutError:
                await self._send_error(
                    device_id,
                    transfer.transfer_id,
                    ErrorCode.CLIPBOARD_FAILED,
                    "Clipboard operation timed out",
                )
                return
            except ClipboardUnavailableError as e:
                await self._send_error(
                    device_id,
                    transfer.transfer_id,
                    ErrorCode.CLIPBOARD_FAILED,
                    str(e),
                )
                return

            # Send paste keystroke
            keystroke = self.config.paste_keystroke or self._platform_info.paste_keystroke
            try:
                await self._send_keys(device_id, keystroke)
                logger.debug("Paste keystroke sent: %s", keystroke)
            except Exception as e:
                await self._send_error(
                    device_id,
                    transfer.transfer_id,
                    ErrorCode.PASTE_FAILED,
                    str(e),
                )
                return

        # Send complete
        transfer.state = TransferState.COMPLETE
        await self._send_message(
            device_id,
            ClipboardMessage(
                complete=Complete(
                    transfer_id=transfer.transfer_id,
                    content_type=ContentType.IMAGE,
                )
            )
        )

        # Cleanup
        self._current_transfer = None
        logger.info("Image transfer complete: %s", transfer.transfer_id)

    async def _send_progress(self, device_id: str) -> None:
        """Send progress update."""
        if not self._current_transfer:
            return

        await self._send_message(
            device_id,
            ClipboardMessage(
                progress=Progress(
                    transfer_id=self._current_transfer.transfer_id,
                    received_chunks=self._current_transfer.received_count,
                    total_chunks=self._current_transfer.total_chunks,
                    received_bytes=self._current_transfer.received_bytes,
                    total_bytes=self._current_transfer.total_size,
                )
            )
        )

    async def _send_error(
        self,
        device_id: str,
        transfer_id: Optional[str],
        code: ErrorCode,
        message: str,
    ) -> None:
        """Send error message and cleanup."""
        logger.error("Clipboard error: code=%s, message=%s", code.name, message)

        await self._send_message(
            device_id,
            ClipboardMessage(
                error=Error(
                    transfer_id=transfer_id or "",
                    code=code,
                    message=message,
                )
            )
        )

        # Cleanup
        self._cancel_timeout()
        self._current_transfer = None

    def _start_timeout(self) -> None:
        """Start or reset transfer timeout."""
        self._cancel_timeout()
        self._timeout_task = asyncio.create_task(self._timeout_handler())

    def _cancel_timeout(self) -> None:
        """Cancel timeout task."""
        if self._timeout_task:
            self._timeout_task.cancel()
            self._timeout_task = None

    async def _timeout_handler(self) -> None:
        """Handle transfer timeout."""
        await asyncio.sleep(self.config.transfer_timeout)

        if self._current_transfer:
            transfer = self._current_transfer
            logger.warning(
                "Transfer timeout: %s (no data for %ds)",
                transfer.transfer_id,
                self.config.transfer_timeout,
            )
            await self._send_error(
                transfer.device_id,
                transfer.transfer_id,
                ErrorCode.TRANSFER_TIMEOUT,
                f"No data received for {self.config.transfer_timeout} seconds",
            )
