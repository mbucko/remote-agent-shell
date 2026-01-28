"""Cryptographic operations for ntfy signaling relay.

This module provides:
- Signaling key derivation using HKDF
- AES-256-GCM encryption/decryption for ntfy messages
- Base64 encoding for wire format

Security requirements:
- Uses CSPRNG (os.urandom) for IV generation
- Separate signaling_key derived from master_secret
- IV is unique per message (12 bytes random)
- Tag is 16 bytes (128-bit authentication)
"""

import base64
import os
from dataclasses import dataclass

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


class DecryptionError(Exception):
    """Raised when decryption fails."""

    pass


# Constants
KEY_LENGTH = 32  # 256 bits
IV_LENGTH = 12  # 96 bits for AES-GCM
TAG_LENGTH = 16  # 128 bits
MIN_ENCRYPTED_SIZE = IV_LENGTH + TAG_LENGTH  # 28 bytes minimum
MAX_MESSAGE_SIZE = 64 * 1024  # 64 KB limit


def derive_signaling_key(master_secret: bytes) -> bytes:
    """Derive signaling key from master secret using HKDF.

    Uses HKDF-SHA256 with info="signaling" to derive a unique key
    for ntfy signaling relay encryption.

    Args:
        master_secret: 32-byte master secret from QR code.

    Returns:
        32-byte signaling key.

    Raises:
        ValueError: If master_secret is not 32 bytes.
    """
    if len(master_secret) != KEY_LENGTH:
        raise ValueError(f"master_secret must be {KEY_LENGTH} bytes")

    hkdf = HKDF(
        algorithm=hashes.SHA256(),
        length=KEY_LENGTH,
        salt=None,  # No salt needed (secret has sufficient entropy)
        info=b"signaling",
    )
    return hkdf.derive(master_secret)


@dataclass(frozen=True)
class EncryptedMessage:
    """Encrypted message ready for wire transmission."""

    base64_encoded: str
    raw_bytes: bytes


class NtfySignalingCrypto:
    """Handles encryption/decryption for ntfy signaling messages.

    Wire format: base64(IV || ciphertext || tag)
    - IV: 12 bytes (random, unique per message)
    - ciphertext: variable length
    - tag: 16 bytes (included by AESGCM)

    Security:
    - Uses os.urandom() for IV generation (CSPRNG required)
    - AES-256-GCM provides authenticated encryption
    - New random IV for each message ensures semantic security
    """

    def __init__(self, signaling_key: bytes):
        """Initialize crypto handler.

        Args:
            signaling_key: 32-byte key derived from derive_signaling_key().

        Raises:
            ValueError: If key is not 32 bytes.
        """
        if len(signaling_key) != KEY_LENGTH:
            raise ValueError(f"signaling_key must be {KEY_LENGTH} bytes")
        self._key = signaling_key
        self._aesgcm = AESGCM(signaling_key)

    def encrypt(self, plaintext: bytes) -> str:
        """Encrypt plaintext for transmission via ntfy.

        Args:
            plaintext: Data to encrypt (typically serialized protobuf).

        Returns:
            Base64-encoded encrypted message (standard RFC 4648 with padding).

        Raises:
            ValueError: If plaintext exceeds maximum size.
        """
        if len(plaintext) > MAX_MESSAGE_SIZE:
            raise ValueError(f"plaintext exceeds {MAX_MESSAGE_SIZE} byte limit")

        # Generate random IV using CSPRNG
        iv = os.urandom(IV_LENGTH)

        # Encrypt with AES-256-GCM (tag is appended to ciphertext)
        ciphertext = self._aesgcm.encrypt(iv, plaintext, None)

        # Wire format: IV || ciphertext (includes tag)
        encrypted = iv + ciphertext

        # Base64 encode for ntfy transport (standard encoding with padding)
        return base64.b64encode(encrypted).decode("ascii")

    def encrypt_raw(self, plaintext: bytes) -> EncryptedMessage:
        """Encrypt and return both base64 and raw bytes.

        Useful for testing and when raw bytes are needed.

        Args:
            plaintext: Data to encrypt.

        Returns:
            EncryptedMessage with both formats.
        """
        if len(plaintext) > MAX_MESSAGE_SIZE:
            raise ValueError(f"plaintext exceeds {MAX_MESSAGE_SIZE} byte limit")

        iv = os.urandom(IV_LENGTH)
        ciphertext = self._aesgcm.encrypt(iv, plaintext, None)
        encrypted = iv + ciphertext
        return EncryptedMessage(
            base64_encoded=base64.b64encode(encrypted).decode("ascii"),
            raw_bytes=encrypted,
        )

    def decrypt(self, encrypted: str) -> bytes:
        """Decrypt a base64-encoded message from ntfy.

        Args:
            encrypted: Base64-encoded encrypted message.

        Returns:
            Decrypted plaintext.

        Raises:
            DecryptionError: If decryption fails (invalid base64, wrong key,
                           tampered data, message too short/large).
        """
        # Validate input size
        if len(encrypted) > MAX_MESSAGE_SIZE * 2:  # Base64 expands ~4/3
            raise DecryptionError("Message too large")

        # Decode base64
        try:
            data = base64.b64decode(encrypted)
        except Exception as e:
            raise DecryptionError(f"Invalid base64: {e}") from e

        # Validate minimum size
        if len(data) < MIN_ENCRYPTED_SIZE:
            raise DecryptionError(
                f"Message too short: {len(data)} < {MIN_ENCRYPTED_SIZE} bytes"
            )

        # Split IV and ciphertext
        iv = data[:IV_LENGTH]
        ciphertext = data[IV_LENGTH:]

        # Decrypt and verify authentication tag
        try:
            return self._aesgcm.decrypt(iv, ciphertext, None)
        except Exception as e:
            raise DecryptionError(f"Decryption failed: {e}") from e

    def decrypt_raw(self, data: bytes) -> bytes:
        """Decrypt raw bytes (no base64 decoding).

        Args:
            data: Raw encrypted bytes (IV || ciphertext || tag).

        Returns:
            Decrypted plaintext.

        Raises:
            DecryptionError: If decryption fails.
        """
        if len(data) < MIN_ENCRYPTED_SIZE:
            raise DecryptionError(
                f"Message too short: {len(data)} < {MIN_ENCRYPTED_SIZE} bytes"
            )

        if len(data) > MAX_MESSAGE_SIZE + IV_LENGTH + TAG_LENGTH:
            raise DecryptionError("Message too large")

        iv = data[:IV_LENGTH]
        ciphertext = data[IV_LENGTH:]

        try:
            return self._aesgcm.decrypt(iv, ciphertext, None)
        except Exception as e:
            raise DecryptionError(f"Decryption failed: {e}") from e

    def zero_key(self) -> None:
        """Zero the key from memory.

        Call this when the session ends to minimize key exposure.
        Note: Python doesn't guarantee memory zeroing, but this
        overwrites the reference.
        """
        # Create new bytes object filled with zeros
        zero = bytes(KEY_LENGTH)
        # Can't actually zero the internal AESGCM key, but we can
        # clear our reference
        self._key = zero
        # Recreate with zero key (makes it unusable)
        self._aesgcm = AESGCM(zero)
