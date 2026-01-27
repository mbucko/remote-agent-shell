"""Publishes encrypted IP change notifications to ntfy."""

import asyncio
import logging
import os
import time
from typing import Optional

import aiohttp

from ras.ntfy.crypto import NtfyCrypto

logger = logging.getLogger(__name__)


class NtfyPublisher:
    """Publishes encrypted IP change notifications to ntfy.

    Features:
    - Retry with exponential backoff (3 attempts: 1s, 2s, 4s)
    - Graceful handling of ntfy server failures
    - Context manager for session lifecycle
    """

    # Retry configuration
    MAX_RETRIES = 3
    RETRY_DELAYS = [1.0, 2.0, 4.0]  # seconds

    # Request timeout
    REQUEST_TIMEOUT = 10.0  # seconds

    def __init__(
        self,
        crypto: NtfyCrypto,
        topic: str,
        server: str = "https://ntfy.sh",
        http_session: Optional[aiohttp.ClientSession] = None,
    ):
        """Initialize publisher.

        Args:
            crypto: Crypto handler for encrypting messages.
            topic: ntfy topic to publish to.
            server: ntfy server URL.
            http_session: Optional aiohttp session (for testing).
        """
        self._crypto = crypto
        self._topic = topic
        self._server = server.rstrip("/")
        self._session = http_session
        self._owns_session = http_session is None

    async def __aenter__(self):
        """Enter async context, creating session if needed."""
        if self._session is None:
            self._session = aiohttp.ClientSession()
        return self

    async def __aexit__(self, *args):
        """Exit async context, closing owned session."""
        if self._owns_session and self._session:
            await self._session.close()
            self._session = None

    @property
    def topic(self) -> str:
        """The ntfy topic."""
        return self._topic

    @property
    def server(self) -> str:
        """The ntfy server URL."""
        return self._server

    async def publish_ip_change(self, ip: str, port: int) -> bool:
        """Publish IP change notification with retry.

        Args:
            ip: New public IP address.
            port: Signaling port.

        Returns:
            True on success, False after all retries exhausted.
        """
        # Generate nonce using CSPRNG
        nonce = os.urandom(16)
        timestamp = int(time.time())

        # Encrypt notification
        encrypted = self._crypto.encrypt_ip_notification(
            ip=ip,
            port=port,
            timestamp=timestamp,
            nonce=nonce,
        )

        url = f"{self._server}/{self._topic}"

        for attempt in range(self.MAX_RETRIES):
            try:
                success = await self._try_publish(url, encrypted)
                if success:
                    logger.info("Published IP change to topic")
                    logger.debug(f"Published to {self._topic}")
                    return True
            except Exception as e:
                logger.warning(f"Publish attempt {attempt + 1} failed: {e}")

            # Wait before retry (except on last attempt)
            if attempt < self.MAX_RETRIES - 1:
                delay = self.RETRY_DELAYS[attempt]
                logger.debug(f"Retrying in {delay}s...")
                await asyncio.sleep(delay)

        logger.error(f"Failed to publish after {self.MAX_RETRIES} attempts")
        return False

    async def _try_publish(self, url: str, data: str) -> bool:
        """Single publish attempt.

        Args:
            url: Full ntfy URL.
            data: Encrypted data to publish.

        Returns:
            True on success.
        """
        if self._session is None:
            raise RuntimeError("Publisher not initialized - use async context manager")

        async with self._session.post(
            url,
            data=data,
            headers={"Content-Type": "text/plain"},
            timeout=aiohttp.ClientTimeout(total=self.REQUEST_TIMEOUT),
        ) as resp:
            if resp.status == 200:
                return True
            else:
                text = await resp.text()
                logger.warning(f"ntfy returned {resp.status}: {text[:100]}")
                return False

    async def close(self) -> None:
        """Close the HTTP session if owned."""
        if self._owns_session and self._session:
            await self._session.close()
            self._session = None
