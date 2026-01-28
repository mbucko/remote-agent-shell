"""Handler for ntfy signaling messages.

This module provides:
- NtfySignalingHandler: Processes incoming OFFER messages from ntfy
- Creates WebRTC peer connections and generates ANSWER responses
- All errors are handled silently (security requirement)

Usage:
    handler = NtfySignalingHandler(
        master_secret=master_secret,
        pending_session_id="abc123",
    )

    # When message received from ntfy
    result = await handler.handle_message(encrypted_message)
    if result and result.should_respond:
        # Publish result.answer_encrypted back to ntfy
        pass
"""

import logging
import os
import time
from dataclasses import dataclass
from typing import Any, Optional

from ras.ntfy_signaling.crypto import (
    DecryptionError,
    NtfySignalingCrypto,
    derive_signaling_key,
)
from ras.ntfy_signaling.validation import (
    NONCE_SIZE,
    NtfySignalMessageValidator,
    sanitize_device_name,
)
from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType

logger = logging.getLogger(__name__)


@dataclass
class HandlerResult:
    """Result from handling an ntfy signaling message."""

    should_respond: bool
    answer_encrypted: Optional[str]
    device_id: Optional[str]
    device_name: Optional[str]
    peer: Optional[Any]  # PeerConnection


class NtfySignalingHandler:
    """Handles ntfy signaling messages for NAT traversal.

    Processes incoming OFFER messages, validates them, creates WebRTC
    peer connections, and generates encrypted ANSWER responses.

    Security:
    - All errors are silently ignored (no error messages to attacker)
    - Nonce replay protection
    - Timestamp validation (±30 seconds)
    - Message authentication via AES-GCM
    """

    def __init__(
        self,
        master_secret: bytes,
        pending_session_id: str,
        stun_servers: Optional[list[str]] = None,
    ):
        """Initialize handler.

        Args:
            master_secret: 32-byte master secret from QR code.
            pending_session_id: Expected session ID.
            stun_servers: STUN servers for WebRTC (optional).
        """
        signaling_key = derive_signaling_key(master_secret)
        self._crypto = NtfySignalingCrypto(signaling_key)
        self._validator = NtfySignalMessageValidator(
            pending_session_id=pending_session_id,
            expected_type="OFFER",
        )
        self._session_id = pending_session_id
        self._stun_servers = stun_servers or []
        self._peer: Optional[Any] = None

    async def handle_message(self, encrypted: str) -> Optional[HandlerResult]:
        """Handle an encrypted ntfy signaling message.

        Args:
            encrypted: Base64-encoded encrypted message from ntfy.

        Returns:
            HandlerResult if valid OFFER and answer created, None otherwise.
            Returns None silently on any error (security requirement).
        """
        try:
            # Decrypt
            plaintext = self._crypto.decrypt(encrypted)
        except DecryptionError:
            # Silent ignore - could be wrong key or tampered
            logger.debug("Decryption failed for ntfy message")
            return None
        except Exception:
            # Silent ignore
            logger.debug("Unexpected error decrypting ntfy message")
            return None

        # Parse protobuf
        try:
            msg = NtfySignalMessage().parse(plaintext)
        except Exception:
            logger.debug("Failed to parse ntfy message protobuf")
            return None

        # Validate
        result = self._validator.validate(msg)
        if not result.is_valid:
            logger.debug(f"Validation failed: {result.error}")
            return None

        # Sanitize device name
        device_name = sanitize_device_name(msg.device_name)

        # Create WebRTC peer and accept offer
        try:
            peer = self._create_peer()
            answer_sdp = await peer.accept_offer(msg.sdp)
        except Exception as e:
            logger.debug(f"WebRTC peer creation failed: {e}")
            return None

        self._peer = peer

        # Create ANSWER message
        answer_msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.ANSWER,
            session_id=self._session_id,
            sdp=answer_sdp,
            device_id="",  # Not needed for ANSWER
            device_name="",  # Not needed for ANSWER
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
        )

        # Encrypt answer
        try:
            answer_encrypted = self._crypto.encrypt(bytes(answer_msg))
        except Exception:
            logger.debug("Failed to encrypt answer")
            return None

        return HandlerResult(
            should_respond=True,
            answer_encrypted=answer_encrypted,
            device_id=msg.device_id,
            device_name=device_name,
            peer=peer,
        )

    def _create_peer(self) -> Any:
        """Create WebRTC peer connection.

        This method can be mocked in tests.
        """
        from ras.peer import PeerConnection

        return PeerConnection(stun_servers=self._stun_servers)

    def get_peer(self) -> Optional[Any]:
        """Get the WebRTC peer connection if one was created."""
        return self._peer

    def clear_nonce_cache(self) -> None:
        """Clear the nonce cache.

        Call this when the session ends.
        """
        self._validator.clear_nonce_cache()

    def zero_key(self) -> None:
        """Zero the signaling key from memory.

        Call this when the session ends.
        """
        self._crypto.zero_key()

    async def close(self) -> None:
        """Clean up handler resources.

        Zeros key, clears nonce cache, and closes peer if open.
        """
        self.zero_key()
        self.clear_nonce_cache()
        if self._peer:
            await self._peer.close()
            self._peer = None
