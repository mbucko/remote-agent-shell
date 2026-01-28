"""ntfy signaling relay for NAT traversal during pairing.

This module provides encrypted SDP exchange via ntfy when direct HTTP fails.
"""

from ras.ntfy_signaling.crypto import (
    NtfySignalingCrypto,
    DecryptionError,
    derive_signaling_key,
)
from ras.ntfy_signaling.handler import (
    NtfySignalingHandler,
    HandlerResult,
)
from ras.ntfy_signaling.subscriber import (
    NtfySignalingSubscriber,
)
from ras.ntfy_signaling.validation import (
    NtfySignalMessageValidator,
    ValidationResult,
    ValidationError,
    NonceCache,
    sanitize_device_name,
)

__all__ = [
    "NtfySignalingCrypto",
    "DecryptionError",
    "derive_signaling_key",
    "NtfySignalingHandler",
    "HandlerResult",
    "NtfySignalingSubscriber",
    "NtfySignalMessageValidator",
    "ValidationResult",
    "ValidationError",
    "NonceCache",
    "sanitize_device_name",
]
