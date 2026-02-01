"""Cryptographic operations for RAS daemon.

This module provides:
- Secure secret generation
- Key derivation using HKDF
- AES-256-GCM authenticated encryption
- HMAC-SHA256 for authentication

Security notes:
- Uses `cryptography` library (well-audited, NIST recommended)
- Keys must be exactly 32 bytes (256 bits)
- Random nonces for encryption (12 bytes for AES-GCM)
- Constant-time comparison for HMAC verification
"""

import hashlib
import hmac
import secrets
import struct
from dataclasses import dataclass

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from ras.errors import CryptoError

# Re-export CryptoError for convenience
__all__ = [
    "CryptoError",
    "KeyBundle",
    "BytesCodec",
    "generate_secret",
    "generate_master_secret",  # Alias for generate_secret
    "derive_keys",
    "derive_key",
    "derive_ntfy_topic",
    "derive_session_id",
    "encrypt",
    "decrypt",
    "compute_hmac",
    "verify_hmac",
    "compute_signaling_hmac",
]

# Constants
KEY_LENGTH = 32  # 256 bits
NONCE_LENGTH = 12  # 96 bits for AES-GCM
TAG_LENGTH = 16  # 128 bits for AES-GCM tag
TOPIC_LENGTH = 12  # 12 hex chars for ntfy topic


def generate_secret() -> bytes:
    """Generate a 32-byte cryptographically secure secret.

    Uses Python's `secrets` module which provides access to the
    most secure source of randomness available on the platform.

    Returns:
        32-byte random secret.
    """
    return secrets.token_bytes(KEY_LENGTH)


@dataclass(frozen=True)
class KeyBundle:
    """Bundle of derived keys from master secret.

    All fields are immutable (frozen dataclass).

    Attributes:
        auth_key: 32-byte key for authentication (HMAC).
        encrypt_key: 32-byte key for message encryption (AES-GCM).
        ntfy_key: 32-byte key for ntfy IP update encryption.
        topic: 12-char hex string for ntfy topic name.
    """

    auth_key: bytes
    encrypt_key: bytes
    ntfy_key: bytes
    topic: str


def derive_keys(master_secret: bytes) -> KeyBundle:
    """Derive all keys from master secret using HKDF.

    Uses HKDF (RFC 5869) with SHA-256 to derive purpose-specific keys.
    Each key is derived with a unique info string to ensure independence.

    Args:
        master_secret: 32-byte master secret (from QR code).

    Returns:
        KeyBundle with all derived keys.

    Raises:
        ValueError: If master_secret is not 32 bytes.
    """
    if len(master_secret) != KEY_LENGTH:
        raise ValueError(f"Master secret must be {KEY_LENGTH} bytes")

    def derive(purpose: str) -> bytes:
        """Derive a key for a specific purpose."""
        hkdf = HKDF(
            algorithm=hashes.SHA256(),
            length=KEY_LENGTH,
            salt=None,  # No salt (secret has enough entropy)
            info=purpose.encode(),
        )
        return hkdf.derive(master_secret)

    # Derive topic from SHA256 hash (first 12 hex chars)
    topic = hashlib.sha256(master_secret).hexdigest()[:TOPIC_LENGTH]

    return KeyBundle(
        auth_key=derive("ras-auth"),
        encrypt_key=derive("ras-encrypt"),
        ntfy_key=derive("ras-ntfy"),
        topic=topic,
    )


def encrypt(key: bytes, plaintext: bytes) -> bytes:
    """Encrypt plaintext using AES-256-GCM.

    Format: nonce (12 bytes) || ciphertext || tag (16 bytes)

    The nonce is randomly generated for each encryption, ensuring
    that the same plaintext produces different ciphertext.

    Args:
        key: 32-byte encryption key.
        plaintext: Data to encrypt (can be empty).

    Returns:
        Encrypted data with prepended nonce.

    Raises:
        ValueError: If key is not 32 bytes.
    """
    if len(key) != KEY_LENGTH:
        raise ValueError(f"Key must be {KEY_LENGTH} bytes")

    nonce = secrets.token_bytes(NONCE_LENGTH)
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(nonce, plaintext, None)

    return nonce + ciphertext


def decrypt(key: bytes, data: bytes) -> bytes:
    """Decrypt data using AES-256-GCM.

    Expects: nonce (12 bytes) || ciphertext || tag (16 bytes)

    Args:
        key: 32-byte encryption key.
        data: Encrypted data with prepended nonce.

    Returns:
        Decrypted plaintext.

    Raises:
        ValueError: If key is not 32 bytes.
        CryptoError: If decryption fails (wrong key, tampered data, etc.).
    """
    if len(key) != KEY_LENGTH:
        raise ValueError(f"Key must be {KEY_LENGTH} bytes")

    min_length = NONCE_LENGTH + TAG_LENGTH
    if len(data) < min_length:
        raise CryptoError(f"Data too short (minimum {min_length} bytes)")

    nonce = data[:NONCE_LENGTH]
    ciphertext = data[NONCE_LENGTH:]

    try:
        aesgcm = AESGCM(key)
        return aesgcm.decrypt(nonce, ciphertext, None)
    except Exception as e:
        raise CryptoError(f"Decryption failed: {e}") from e


def compute_hmac(key: bytes, data: bytes) -> bytes:
    """Compute HMAC-SHA256.

    Args:
        key: HMAC key (any length, but 32 bytes recommended).
        data: Data to authenticate.

    Returns:
        32-byte HMAC digest.
    """
    return hmac.new(key, data, hashlib.sha256).digest()


def verify_hmac(key: bytes, data: bytes, expected: bytes) -> bool:
    """Verify HMAC-SHA256 in constant time.

    Uses `hmac.compare_digest` for timing-safe comparison
    to prevent timing attacks.

    Args:
        key: HMAC key.
        data: Data that was authenticated.
        expected: Expected HMAC value.

    Returns:
        True if HMAC is valid, False otherwise.
    """
    computed = compute_hmac(key, data)
    return hmac.compare_digest(computed, expected)


class BytesCodec:
    """Simple codec for encrypting/decrypting raw bytes.

    This codec matches the CodecProtocol interface expected by ConnectionManager.
    It wraps the encrypt/decrypt functions from this module.

    Usage:
        codec = BytesCodec(auth_key)
        encrypted = codec.encode(data)
        decrypted = codec.decode(encrypted)
    """

    def __init__(self, key: bytes) -> None:
        """Initialize codec with encryption key.

        Args:
            key: 32-byte encryption key.
        """
        if len(key) != KEY_LENGTH:
            raise ValueError(f"Key must be {KEY_LENGTH} bytes, got {len(key)}")
        self._key = key

    def encode(self, data: bytes) -> bytes:
        """Encrypt data.

        Args:
            data: Plaintext bytes to encrypt.

        Returns:
            Encrypted bytes (nonce + ciphertext + tag).
        """
        return encrypt(self._key, data)

    def decode(self, data: bytes) -> bytes:
        """Decrypt data.

        Args:
            data: Encrypted bytes to decrypt.

        Returns:
            Decrypted plaintext bytes.
        """
        return decrypt(self._key, data)


# Alias for compatibility with pairing code
generate_master_secret = generate_secret


def derive_key(master_secret: bytes, purpose: str) -> bytes:
    """Derive a purpose-specific key using HKDF (RFC 5869).

    Uses purpose strings as defined in the pairing protocol:
    - "auth" for authentication key
    - "encrypt" for encryption key
    - "ntfy" for ntfy notification key

    Args:
        master_secret: 32-byte master secret from QR code.
        purpose: Key purpose ("auth", "encrypt", "ntfy").

    Returns:
        32-byte derived key.

    Raises:
        ValueError: If master_secret is not 32 bytes.
    """
    if len(master_secret) != KEY_LENGTH:
        raise ValueError(f"Master secret must be {KEY_LENGTH} bytes")

    hkdf = HKDF(
        algorithm=hashes.SHA256(),
        length=KEY_LENGTH,
        salt=None,
        info=purpose.encode("utf-8"),
    )
    return hkdf.derive(master_secret)


def derive_ntfy_topic(master_secret: bytes) -> str:
    """Derive ntfy topic from master secret.

    The topic is derived as: "ras-" + first 12 hex chars of SHA256(master_secret)

    Args:
        master_secret: 32-byte master secret.

    Returns:
        Topic string like "ras-9f86d081884c".

    Raises:
        ValueError: If master_secret is not 32 bytes.
    """
    if len(master_secret) != KEY_LENGTH:
        raise ValueError(f"Master secret must be {KEY_LENGTH} bytes")

    hash_bytes = hashlib.sha256(master_secret).digest()
    return "ras-" + hash_bytes[:6].hex()


def derive_session_id(master_secret: bytes) -> str:
    """Derive session ID from master secret.

    The session ID is derived deterministically so both daemon and phone
    can compute it from the master_secret without including it in the QR code.

    Uses HKDF with "session" purpose, then takes first 24 hex chars.

    Args:
        master_secret: 32-byte master secret.

    Returns:
        24-character hex session ID.

    Raises:
        ValueError: If master_secret is not 32 bytes.
    """
    if len(master_secret) != KEY_LENGTH:
        raise ValueError(f"Master secret must be {KEY_LENGTH} bytes")

    hkdf = HKDF(
        algorithm=hashes.SHA256(),
        length=KEY_LENGTH,
        salt=None,
        info=b"session",
    )
    derived = hkdf.derive(master_secret)
    return derived[:12].hex()  # 24 hex chars


def compute_signaling_hmac(
    auth_key: bytes,
    session_id: str,
    timestamp: int,
    body: bytes,
) -> bytes:
    """Compute HMAC for HTTP signaling request.

    The HMAC input is constructed as:
        UTF8(session_id) || BigEndian64(timestamp) || body

    Args:
        auth_key: 32-byte authentication key (derived from master secret).
        session_id: Pairing session ID.
        timestamp: Unix timestamp in seconds.
        body: Request body bytes.

    Returns:
        32-byte HMAC.
    """
    hmac_input = (
        session_id.encode("utf-8")
        + struct.pack(">Q", timestamp)
        + body
    )
    return compute_hmac(auth_key, hmac_input)
