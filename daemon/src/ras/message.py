"""Encrypted message protocol for RAS daemon.

This module provides:
- Message: Typed message with sequence number and timestamp
- MessageCodec: Encode/decode messages with encryption

Security features:
- AES-256-GCM encryption
- Sequence number tracking (replay protection)
- Timestamp validation (reject old messages)
- Sliding window for out-of-order delivery
"""

import json
import time
from dataclasses import dataclass
from typing import Any

from ras.crypto import CryptoError, decrypt, encrypt
from ras.errors import MessageError

__all__ = [
    "Message",
    "MessageCodec",
    "MessageError",
]


@dataclass
class Message:
    """Encrypted message with metadata.

    Attributes:
        type: Message type identifier (e.g., "output", "ping").
        payload: Message payload as dict.
        seq: Sequence number (assigned by codec if 0).
        timestamp: Unix timestamp (assigned by codec if 0).
    """

    type: str
    payload: dict[str, Any]
    seq: int = 0
    timestamp: int = 0

    def to_dict(self) -> dict:
        """Convert message to dict for JSON serialization."""
        return {
            "type": self.type,
            "seq": self.seq,
            "timestamp": self.timestamp,
            "payload": self.payload,
        }

    @classmethod
    def from_dict(cls, d: dict) -> "Message":
        """Create message from dict."""
        return cls(
            type=d["type"],
            seq=d["seq"],
            timestamp=d["timestamp"],
            payload=d["payload"],
        )


class MessageCodec:
    """Encodes and decodes encrypted messages.

    Features:
    - AES-256-GCM encryption
    - Automatic sequence number assignment
    - Automatic timestamp assignment
    - Replay protection via sliding window
    - Timestamp validation

    Attributes:
        encrypt_key: 32-byte encryption key.
        max_age: Maximum message age in seconds.
        window_size: Replay protection window size.
    """

    def __init__(
        self,
        encrypt_key: bytes,
        max_age: int = 60,
        window_size: int = 1000,
    ) -> None:
        """Initialize codec.

        Args:
            encrypt_key: 32-byte key for AES-GCM encryption.
            max_age: Maximum message age in seconds (default 60).
            window_size: Replay protection window size (default 1000).
        """
        self.encrypt_key = encrypt_key
        self.max_age = max_age
        self.window_size = window_size
        self._seq = 0
        self._highest_seen = 0
        self._seen_seqs: set[int] = set()

    def encode(self, msg: Message) -> bytes:
        """Encode and encrypt a message.

        Assigns sequence number and timestamp if not set.

        Args:
            msg: Message to encode.

        Returns:
            Encrypted message bytes.
        """
        # Assign seq if not set
        if msg.seq == 0:
            self._seq += 1
            msg.seq = self._seq

        # Assign timestamp if not set
        if msg.timestamp == 0:
            msg.timestamp = int(time.time())

        plaintext = json.dumps(msg.to_dict()).encode()
        return encrypt(self.encrypt_key, plaintext)

    def decode(self, data: bytes) -> Message:
        """Decrypt and decode a message.

        Validates timestamp and checks for replay.

        Args:
            data: Encrypted message bytes.

        Returns:
            Decoded Message.

        Raises:
            MessageError: On decryption failure, invalid format,
                         expired timestamp, or replay detected.
        """
        # Decrypt
        try:
            plaintext = decrypt(self.encrypt_key, data)
        except CryptoError as e:
            raise MessageError(f"Decryption failed: {e}") from e

        # Parse JSON
        try:
            d = json.loads(plaintext)
            msg = Message.from_dict(d)
        except (json.JSONDecodeError, KeyError) as e:
            raise MessageError(f"Invalid message format: {e}") from e

        # Validate timestamp
        now = int(time.time())
        if abs(now - msg.timestamp) > self.max_age:
            raise MessageError("Message expired")

        # Replay protection using sliding window
        self._check_replay(msg.seq)

        return msg

    def _check_replay(self, seq: int) -> None:
        """Check for replay attack.

        Uses IPsec/DTLS-style sliding window algorithm.

        Args:
            seq: Sequence number to check.

        Raises:
            MessageError: If replay detected or seq too old.
        """
        # Calculate window floor
        floor = max(0, self._highest_seen - self.window_size)

        # Reject if too old (below window)
        if seq < floor:
            raise MessageError("Replay detected (seq too old)")

        # Reject if already seen
        if seq in self._seen_seqs:
            raise MessageError("Replay detected (duplicate seq)")

        # Accept and track
        self._seen_seqs.add(seq)
        if seq > self._highest_seen:
            self._highest_seen = seq

        # Prune old sequence numbers
        new_floor = max(0, self._highest_seen - self.window_size)
        self._seen_seqs = {s for s in self._seen_seqs if s >= new_floor}
