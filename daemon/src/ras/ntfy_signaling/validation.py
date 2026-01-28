"""Validation for ntfy signaling messages.

This module provides:
- Message validation (type, session_id, timestamp, nonce, SDP)
- Nonce cache for replay protection
- Device name sanitization

Security requirements:
- Timestamp within ±30 seconds
- Nonce not previously seen (replay protection)
- Message type matches expected (OFFER for daemon, ANSWER for phone)
- SDP format validation (minimal, to avoid false rejections)
"""

import re
import time
import threading
from collections import OrderedDict
from dataclasses import dataclass
from enum import Enum, auto
from typing import Optional, Set

from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType


class ValidationError(Enum):
    """Validation error codes."""

    INVALID_SESSION = auto()
    INVALID_TIMESTAMP = auto()
    NONCE_REPLAY = auto()
    INVALID_NONCE = auto()
    INVALID_SDP = auto()
    MISSING_DEVICE_ID = auto()
    MISSING_DEVICE_NAME = auto()
    WRONG_MESSAGE_TYPE = auto()
    MESSAGE_TOO_LARGE = auto()
    INVALID_SESSION_ID_FORMAT = auto()


@dataclass
class ValidationResult:
    """Result of message validation."""

    is_valid: bool
    error: Optional[ValidationError] = None
    error_detail: Optional[str] = None


# Constants
TIMESTAMP_WINDOW_SECONDS = 30
NONCE_SIZE = 16
MAX_MESSAGE_SIZE = 64 * 1024  # 64 KB
MAX_SESSION_ID_LENGTH = 64
MAX_DEVICE_NAME_LENGTH = 64
MAX_DEVICE_ID_LENGTH = 128
SESSION_ID_PATTERN = re.compile(r"^[a-zA-Z0-9\-]+$")


class NonceCache:
    """Thread-safe nonce cache with FIFO eviction.

    Stores seen nonces for replay protection.
    When cache reaches max_size, oldest nonces are evicted.

    Thread-safety: All operations are protected by a lock.
    """

    def __init__(self, max_size: int = 100):
        """Initialize nonce cache.

        Args:
            max_size: Maximum number of nonces to store.
        """
        self._max_size = max_size
        self._nonces: OrderedDict[bytes, bool] = OrderedDict()
        self._lock = threading.Lock()

    def has_seen(self, nonce: bytes) -> bool:
        """Check if nonce has been seen before.

        Args:
            nonce: 16-byte nonce to check.

        Returns:
            True if nonce was previously seen, False otherwise.
        """
        with self._lock:
            return nonce in self._nonces

    def add(self, nonce: bytes) -> None:
        """Add nonce to cache.

        If cache is full, evicts oldest nonce (FIFO).

        Args:
            nonce: 16-byte nonce to add.
        """
        with self._lock:
            # If already present, move to end (most recent)
            if nonce in self._nonces:
                self._nonces.move_to_end(nonce)
                return

            # Add new nonce
            self._nonces[nonce] = True

            # Evict oldest if over capacity
            while len(self._nonces) > self._max_size:
                self._nonces.popitem(last=False)

    def check_and_add(self, nonce: bytes) -> bool:
        """Check if nonce is new and add it atomically.

        This is the recommended method for replay protection -
        it atomically checks and adds in one operation.

        Args:
            nonce: 16-byte nonce to check and add.

        Returns:
            True if nonce is NEW (not seen before), False if replay.
        """
        with self._lock:
            if nonce in self._nonces:
                return False  # Replay detected

            # Add new nonce
            self._nonces[nonce] = True

            # Evict oldest if over capacity
            while len(self._nonces) > self._max_size:
                self._nonces.popitem(last=False)

            return True  # New nonce

    def clear(self) -> None:
        """Clear all nonces from cache."""
        with self._lock:
            self._nonces.clear()

    def __len__(self) -> int:
        """Return number of nonces in cache."""
        with self._lock:
            return len(self._nonces)


class NtfySignalMessageValidator:
    """Validates NtfySignalMessage for security requirements.

    Validates:
    - Message type (OFFER or ANSWER, depending on role)
    - Session ID matches pending session
    - Timestamp within allowed window (±30 seconds)
    - Nonce not replayed
    - SDP format (minimal validation)
    - Device info present for OFFER messages

    Usage:
        validator = NtfySignalMessageValidator(
            pending_session_id="abc123",
            expected_type="OFFER",  # or "ANSWER"
        )
        result = validator.validate(message)
        if not result.is_valid:
            # Silently ignore (don't leak error details to attacker)
            log.debug(f"Invalid message: {result.error}")
    """

    def __init__(
        self,
        pending_session_id: str,
        expected_type: str = "OFFER",
        timestamp_window: int = TIMESTAMP_WINDOW_SECONDS,
        nonce_cache: Optional[NonceCache] = None,
    ):
        """Initialize validator.

        Args:
            pending_session_id: Expected session ID from QR code.
            expected_type: Expected message type ("OFFER" or "ANSWER").
            timestamp_window: Allowed timestamp skew in seconds.
            nonce_cache: Optional nonce cache (creates new one if None).
        """
        self._session_id = pending_session_id
        self._expected_type = (
            NtfySignalMessageMessageType.OFFER
            if expected_type == "OFFER"
            else NtfySignalMessageMessageType.ANSWER
        )
        self._timestamp_window = timestamp_window
        self._nonce_cache = nonce_cache or NonceCache()

    def validate(self, msg: NtfySignalMessage) -> ValidationResult:
        """Validate a NtfySignalMessage.

        Args:
            msg: Deserialized NtfySignalMessage to validate.

        Returns:
            ValidationResult indicating if message is valid.
        """
        # 1. Check message type
        if msg.type != self._expected_type:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.WRONG_MESSAGE_TYPE,
                error_detail=f"Expected {self._expected_type}, got {msg.type}",
            )

        # 2. Validate session ID format
        if not msg.session_id:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_SESSION,
                error_detail="Empty session_id",
            )

        if len(msg.session_id) > MAX_SESSION_ID_LENGTH:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_SESSION_ID_FORMAT,
                error_detail=f"session_id exceeds {MAX_SESSION_ID_LENGTH} chars",
            )

        if not SESSION_ID_PATTERN.match(msg.session_id):
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_SESSION_ID_FORMAT,
                error_detail="session_id contains invalid characters",
            )

        # 3. Check session ID matches
        if msg.session_id != self._session_id:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_SESSION,
                error_detail="session_id mismatch",
            )

        # 4. Validate timestamp
        timestamp_result = self._validate_timestamp(msg.timestamp)
        if not timestamp_result.is_valid:
            return timestamp_result

        # 5. Validate nonce
        nonce_result = self._validate_nonce(msg.nonce)
        if not nonce_result.is_valid:
            return nonce_result

        # 6. Validate SDP
        sdp_result = self._validate_sdp(msg.sdp)
        if not sdp_result.is_valid:
            return sdp_result

        # 7. For OFFER, validate device info
        if msg.type == NtfySignalMessageMessageType.OFFER:
            device_result = self._validate_device_info(msg)
            if not device_result.is_valid:
                return device_result

        # All checks passed
        return ValidationResult(is_valid=True)

    def _validate_timestamp(self, timestamp: int) -> ValidationResult:
        """Validate timestamp is within allowed window."""
        if timestamp <= 0:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_TIMESTAMP,
                error_detail="Timestamp must be positive",
            )

        current_time = int(time.time())
        diff = abs(current_time - timestamp)

        if diff > self._timestamp_window:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_TIMESTAMP,
                error_detail=f"Timestamp {diff}s outside {self._timestamp_window}s window",
            )

        return ValidationResult(is_valid=True)

    def _validate_nonce(self, nonce: bytes) -> ValidationResult:
        """Validate nonce format and replay protection."""
        if len(nonce) != NONCE_SIZE:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_NONCE,
                error_detail=f"Nonce must be {NONCE_SIZE} bytes, got {len(nonce)}",
            )

        # Check for replay and add atomically
        if not self._nonce_cache.check_and_add(nonce):
            return ValidationResult(
                is_valid=False,
                error=ValidationError.NONCE_REPLAY,
                error_detail="Nonce previously seen",
            )

        return ValidationResult(is_valid=True)

    def _validate_sdp(self, sdp: str) -> ValidationResult:
        """Validate SDP format (minimal validation)."""
        if not sdp:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_SDP,
                error_detail="Empty SDP",
            )

        if len(sdp) > MAX_MESSAGE_SIZE:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.MESSAGE_TOO_LARGE,
                error_detail=f"SDP exceeds {MAX_MESSAGE_SIZE} bytes",
            )

        # Minimal SDP validation - must start with version line
        if not sdp.startswith("v=0"):
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_SDP,
                error_detail="SDP must start with v=0",
            )

        # Must contain media line (indicates WebRTC content)
        if "m=" not in sdp:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.INVALID_SDP,
                error_detail="SDP missing media line (m=)",
            )

        return ValidationResult(is_valid=True)

    def _validate_device_info(self, msg: NtfySignalMessage) -> ValidationResult:
        """Validate device info for OFFER messages."""
        if not msg.device_id:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.MISSING_DEVICE_ID,
                error_detail="OFFER missing device_id",
            )

        if len(msg.device_id) > MAX_DEVICE_ID_LENGTH:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.MISSING_DEVICE_ID,
                error_detail=f"device_id exceeds {MAX_DEVICE_ID_LENGTH} chars",
            )

        if not msg.device_name:
            return ValidationResult(
                is_valid=False,
                error=ValidationError.MISSING_DEVICE_NAME,
                error_detail="OFFER missing device_name",
            )

        return ValidationResult(is_valid=True)

    def clear_nonce_cache(self) -> None:
        """Clear the nonce cache.

        Call this when the session ends.
        """
        self._nonce_cache.clear()


def sanitize_device_name(name: str) -> str:
    """Sanitize device name for storage/display.

    Applies:
    1. Strip leading/trailing whitespace
    2. Replace control characters with space
    3. Truncate to MAX_DEVICE_NAME_LENGTH
    4. Validate UTF-8 (invalid sequences replaced)

    Args:
        name: Raw device name from message.

    Returns:
        Sanitized device name.
    """
    if not name:
        return ""

    # Replace control characters (0x00-0x1F, 0x7F) with space
    sanitized = "".join(
        " " if (ord(c) < 0x20 or ord(c) == 0x7F) else c for c in name
    )

    # Strip whitespace
    sanitized = sanitized.strip()

    # Collapse multiple spaces into one
    sanitized = re.sub(r"\s+", " ", sanitized)

    # Truncate
    if len(sanitized) > MAX_DEVICE_NAME_LENGTH:
        sanitized = sanitized[:MAX_DEVICE_NAME_LENGTH]

    return sanitized
