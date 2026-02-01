"""Internal types for clipboard handling.

Protocol message types are in ras.proto.ras.clipboard (generated from proto/clipboard.proto).
This module contains runtime state and configuration types.
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional

# Re-export proto types for convenience
from ras.proto.ras.clipboard import (
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
)

__all__ = [
    # Proto types (re-exported)
    "ClipboardMessage",
    "ImageStart",
    "ImageChunk",
    "ImageCancel",
    "TextPaste",
    "TextPasteApproved",
    "Progress",
    "Complete",
    "ApprovalRequired",
    "Error",
    "Cancelled",
    "ErrorCode",
    "ImageFormat",
    "ContentType",
    # Internal types
    "TransferState",
    "ImageTransfer",
    "ClipboardConfig",
    "SUPPORTED_IMAGE_FORMATS",
    "FORMAT_TO_ENUM",
]


class TransferState(Enum):
    """State of an image transfer (internal runtime state)."""

    IDLE = "idle"
    RECEIVING = "receiving"
    ASSEMBLING = "assembling"
    PASTING = "pasting"
    COMPLETE = "complete"
    FAILED = "failed"
    CANCELLED = "cancelled"


# Mapping from string format names to proto enum
FORMAT_TO_ENUM: dict[str, ImageFormat] = {
    "jpeg": ImageFormat.JPEG,
    "jpg": ImageFormat.JPEG,
    "png": ImageFormat.PNG,
    "gif": ImageFormat.GIF,
    "webp": ImageFormat.WEBP,
}

# Supported image format strings
SUPPORTED_IMAGE_FORMATS = frozenset(FORMAT_TO_ENUM.keys())


@dataclass
class ImageTransfer:
    """State of an in-progress image transfer (internal runtime state)."""

    transfer_id: str
    device_id: str
    total_size: int
    format: ImageFormat
    total_chunks: int
    received_chunks: dict[int, bytes] = field(default_factory=dict)
    state: TransferState = TransferState.RECEIVING

    @property
    def received_bytes(self) -> int:
        """Total bytes received so far."""
        return sum(len(chunk) for chunk in self.received_chunks.values())

    @property
    def received_count(self) -> int:
        """Number of chunks received."""
        return len(self.received_chunks)

    @property
    def is_complete(self) -> bool:
        """True if all chunks have been received."""
        return len(self.received_chunks) == self.total_chunks

    @property
    def progress_percent(self) -> float:
        """Transfer progress as percentage (0-100)."""
        if self.total_chunks == 0:
            return 100.0
        return (len(self.received_chunks) / self.total_chunks) * 100


@dataclass
class ClipboardConfig:
    """Configuration for clipboard operations."""

    max_image_size: int = 5 * 1024 * 1024  # 5MB
    text_approval_threshold: int = 100 * 1024  # 100KB
    chunk_size: int = 64 * 1024  # 64KB
    transfer_timeout: int = 30  # seconds
    paste_timeout: int = 10  # seconds
    paste_keystroke: Optional[str] = None  # Auto-detect if None
    clipboard_tool: Optional[str] = None  # Auto-detect if None
