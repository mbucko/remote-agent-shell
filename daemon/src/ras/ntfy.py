"""ntfy client for encrypted IP updates.

This module provides:
- NtfyClient: Send/receive encrypted IP updates via ntfy.sh

Security features:
- AES-256-GCM encryption
- Timestamp validation
- Nonce-based replay protection
"""

import json
import secrets
import time
from dataclasses import dataclass

import httpx

from ras.crypto import CryptoError, decrypt, encrypt
from ras.errors import NtfyError

__all__ = [
    "IpUpdate",
    "NtfyClient",
    "NtfyError",
]


@dataclass
class IpUpdate:
    """IP update message.

    Attributes:
        ip: IP address.
        port: Port number.
        timestamp: Unix timestamp.
        nonce: Unique nonce for replay protection.
    """

    ip: str
    port: int
    timestamp: int
    nonce: str


class NtfyClient:
    """Client for encrypted ntfy communications.

    Sends and receives encrypted IP updates via ntfy.sh.

    Attributes:
        server: ntfy server URL.
        topic: Topic name (derived from secret).
        ntfy_key: 32-byte encryption key.
        max_age: Maximum message age in seconds.
    """

    def __init__(
        self,
        server: str,
        topic: str,
        ntfy_key: bytes,
        http_client: httpx.AsyncClient | None = None,
        max_age: int = 300,  # 5 minutes
    ) -> None:
        """Initialize ntfy client.

        Args:
            server: ntfy server URL.
            topic: Topic name.
            ntfy_key: 32-byte encryption key.
            http_client: Optional httpx client (for DI).
            max_age: Maximum message age in seconds.
        """
        self.server = server.rstrip("/")
        self.topic = topic
        self.ntfy_key = ntfy_key
        self.http_client = http_client
        self.max_age = max_age
        self._seen_nonces: set[str] = set()
        self._owns_client = False

    async def __aenter__(self) -> "NtfyClient":
        """Enter async context, creating http client if needed."""
        if self.http_client is None:
            self.http_client = httpx.AsyncClient()
            self._owns_client = True
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        """Exit async context, closing http client if we own it."""
        if self._owns_client and self.http_client:
            await self.http_client.aclose()

    def _encrypt_update(self, ip: str, port: int) -> bytes:
        """Create encrypted IP update.

        Args:
            ip: IP address.
            port: Port number.

        Returns:
            Encrypted update bytes.
        """
        payload = {
            "ip": ip,
            "port": port,
            "timestamp": int(time.time()),
            "nonce": secrets.token_hex(16),
        }
        plaintext = json.dumps(payload).encode()
        return encrypt(self.ntfy_key, plaintext)

    async def send_ip_update(self, ip: str, port: int) -> None:
        """Send encrypted IP update to ntfy.

        Args:
            ip: IP address.
            port: Port number.

        Raises:
            NtfyError: On send failure or missing http client.
        """
        if self.http_client is None:
            raise NtfyError("HTTP client not initialized")

        encrypted = self._encrypt_update(ip, port)
        url = f"{self.server}/{self.topic}"

        try:
            response = await self.http_client.post(
                url,
                content=encrypted,
                headers={"Content-Type": "application/octet-stream"},
            )
            if response.status_code >= 400:
                raise NtfyError(f"ntfy returned {response.status_code}")
        except httpx.HTTPError as e:
            raise NtfyError(f"Failed to send: {e}") from e

    def decrypt_update(self, data: bytes) -> tuple[str, int]:
        """Decrypt IP update.

        Args:
            data: Encrypted update bytes.

        Returns:
            Tuple of (ip, port).

        Raises:
            NtfyError: On decryption failure, expired, or replay.
        """
        try:
            plaintext = decrypt(self.ntfy_key, data)
            payload = json.loads(plaintext)
        except (CryptoError, json.JSONDecodeError) as e:
            raise NtfyError(f"Failed to decrypt: {e}") from e

        # Timestamp validation
        now = int(time.time())
        if abs(now - payload["timestamp"]) > self.max_age:
            raise NtfyError("Update expired")

        # Replay protection
        nonce = payload["nonce"]
        if nonce in self._seen_nonces:
            raise NtfyError("Replay detected")
        self._seen_nonces.add(nonce)

        return payload["ip"], payload["port"]
