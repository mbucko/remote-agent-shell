"""Encryption/decryption for ntfy messages.

Uses AES-256-GCM for authenticated encryption of IP change notifications.
"""

import base64
import os
from dataclasses import dataclass

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from ras.proto.ras import IpChangeNotification


@dataclass
class IpChangeData:
    """Decrypted IP change notification."""

    ip: str
    port: int
    timestamp: int
    nonce: bytes


class NtfyCrypto:
    """Handles encryption/decryption of ntfy messages.

    Security requirements:
    - Uses os.urandom() for IV generation (CSPRNG)
    - AES-256-GCM for authenticated encryption
    - 12-byte IV, 16-byte auth tag
    """

    # IV size for AES-GCM
    IV_SIZE = 12

    # Auth tag size (included in ciphertext by AESGCM)
    TAG_SIZE = 16

    # Minimum encrypted message size: IV + tag
    MIN_ENCRYPTED_SIZE = IV_SIZE + TAG_SIZE  # 28 bytes

    # Nonce size for replay protection
    NONCE_SIZE = 16

    def __init__(self, ntfy_key: bytes):
        """Initialize crypto handler.

        Args:
            ntfy_key: 32-byte AES key derived from master secret.

        Raises:
            ValueError: If key is not 32 bytes.
        """
        if len(ntfy_key) != 32:
            raise ValueError("ntfy_key must be 32 bytes")
        self._key = ntfy_key
        self._aesgcm = AESGCM(ntfy_key)

    def encrypt_ip_notification(
        self,
        ip: str,
        port: int,
        timestamp: int,
        nonce: bytes,
    ) -> str:
        """Encrypt IP change notification.

        Args:
            ip: Public IP address (IPv4 or IPv6).
            port: Signaling port.
            timestamp: Unix timestamp in seconds.
            nonce: 16 random bytes for replay protection.

        Returns:
            Base64-encoded encrypted payload.

        Raises:
            ValueError: If nonce is not 16 bytes.
        """
        if len(nonce) != self.NONCE_SIZE:
            raise ValueError(f"nonce must be {self.NONCE_SIZE} bytes")

        # Create protobuf message
        msg = IpChangeNotification(
            ip=ip,
            port=port,
            timestamp=timestamp,
            nonce=nonce,
        )
        plaintext = bytes(msg)

        # Generate random IV using CSPRNG
        iv = os.urandom(self.IV_SIZE)

        # Encrypt with AES-GCM
        ciphertext = self._aesgcm.encrypt(iv, plaintext, None)

        # Return base64(IV || ciphertext)
        return base64.b64encode(iv + ciphertext).decode("ascii")

    def decrypt_ip_notification(self, encrypted: str) -> IpChangeData:
        """Decrypt IP change notification.

        Args:
            encrypted: Base64-encoded encrypted payload.

        Returns:
            Decrypted IP change data.

        Raises:
            ValueError: If message is too short or invalid base64.
            cryptography.exceptions.InvalidTag: If decryption fails.
        """
        try:
            data = base64.b64decode(encrypted)
        except Exception as e:
            raise ValueError(f"Invalid base64: {e}")

        if len(data) < self.MIN_ENCRYPTED_SIZE:
            raise ValueError(
                f"Message too short: {len(data)} < {self.MIN_ENCRYPTED_SIZE}"
            )

        # Split IV and ciphertext
        iv = data[: self.IV_SIZE]
        ciphertext = data[self.IV_SIZE :]

        # Decrypt
        plaintext = self._aesgcm.decrypt(iv, ciphertext, None)

        # Parse protobuf
        msg = IpChangeNotification().parse(plaintext)

        return IpChangeData(
            ip=msg.ip,
            port=msg.port,
            timestamp=msg.timestamp,
            nonce=bytes(msg.nonce),
        )
