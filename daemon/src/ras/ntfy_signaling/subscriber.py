"""Subscriber for ntfy signaling messages.

This module provides:
- NtfySignalingSubscriber: Subscribes to ntfy topic for signaling messages
- Processes incoming OFFER messages via NtfySignalingHandler
- Publishes encrypted ANSWER responses back to ntfy

Usage:
    subscriber = NtfySignalingSubscriber(
        master_secret=master_secret,
        session_id="abc123",
        ntfy_topic="ras-abc123",
    )

    # Set callback for when offer is received
    subscriber.on_offer_received = async def callback(device_id, device_name, peer):
        # Handle successful signaling
        pass

    # Start subscription
    await subscriber.start()

    # When done (pairing complete or cancelled)
    await subscriber.close()
"""

import asyncio
import logging
from typing import Any, Callable, Coroutine, Optional

import aiohttp

from ras.ntfy_signaling.handler import NtfySignalingHandler, HandlerResult

logger = logging.getLogger(__name__)


class NtfySignalingSubscriber:
    """Subscribes to ntfy for signaling messages.

    Listens for encrypted OFFER messages via SSE (Server-Sent Events),
    processes them through NtfySignalingHandler, and publishes encrypted
    ANSWER responses back to the same ntfy topic.

    All errors are handled silently (security requirement).
    """

    # Retry configuration for publishing
    MAX_PUBLISH_RETRIES = 3
    PUBLISH_RETRY_DELAYS = [1.0, 2.0, 4.0]  # seconds

    # Request timeout
    REQUEST_TIMEOUT = 10.0  # seconds

    # SSE reconnection delay
    SSE_RECONNECT_DELAY = 5.0  # seconds

    def __init__(
        self,
        master_secret: bytes,
        session_id: str,
        ntfy_topic: str,
        ntfy_server: str = "https://ntfy.sh",
        stun_servers: Optional[list[str]] = None,
        http_session: Optional[aiohttp.ClientSession] = None,
    ):
        """Initialize subscriber.

        Args:
            master_secret: 32-byte master secret from QR code.
            session_id: Expected session ID.
            ntfy_topic: ntfy topic to subscribe to.
            ntfy_server: ntfy server URL.
            stun_servers: STUN servers for WebRTC (optional).
            http_session: Optional aiohttp session (for testing).
        """
        self._handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id=session_id,
            stun_servers=stun_servers,
        )
        self._topic = ntfy_topic
        self._server = ntfy_server.rstrip("/")
        self._session = http_session
        self._owns_session = http_session is None
        self._subscribed = False
        self._subscription_task: Optional[asyncio.Task] = None
        self._stop_event = asyncio.Event()

        # Callback for successful offer processing
        self.on_offer_received: Optional[
            Callable[[str, str, Any], Coroutine[Any, Any, None]]
        ] = None

    def is_subscribed(self) -> bool:
        """Check if currently subscribed to ntfy topic."""
        return self._subscribed

    async def start(self) -> None:
        """Start subscription to ntfy topic.

        Returns immediately after starting background subscription task.
        """
        if self._subscribed:
            return

        # Create session if needed
        if self._session is None:
            self._session = aiohttp.ClientSession()

        self._stop_event.clear()
        self._subscribed = True

        # Start subscription in background
        self._subscription_task = asyncio.create_task(self._run_subscription())
        logger.info(f"Started ntfy signaling subscription to topic {self._topic[:8]}...")

    async def stop(self) -> None:
        """Stop subscription to ntfy topic."""
        if not self._subscribed:
            return

        self._subscribed = False
        self._stop_event.set()

        # Cancel subscription task
        if self._subscription_task:
            self._subscription_task.cancel()
            try:
                await self._subscription_task
            except asyncio.CancelledError:
                pass
            self._subscription_task = None

        logger.info(f"Stopped ntfy signaling subscription to topic {self._topic[:8]}...")

    async def close(self) -> None:
        """Close subscriber and clean up resources.

        Stops subscription, zeros keys, and closes HTTP session.
        """
        await self.stop()

        # Cleanup handler
        await self._handler.close()

        # Close session if we own it
        if self._owns_session and self._session:
            await self._session.close()
            self._session = None

    def get_peer(self) -> Optional[Any]:
        """Get the WebRTC peer connection if one was created."""
        return self._handler.get_peer()

    async def _run_subscription(self) -> None:
        """Run SSE subscription loop.

        Reconnects automatically on connection errors.
        """
        url = f"{self._server}/{self._topic}/sse"

        while self._subscribed and not self._stop_event.is_set():
            try:
                await self._subscribe_sse(url)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"SSE subscription error: {e}")
                if self._subscribed:
                    await asyncio.sleep(self.SSE_RECONNECT_DELAY)

    async def _subscribe_sse(self, url: str) -> None:
        """Subscribe to SSE stream.

        Args:
            url: Full SSE URL.
        """
        if not self._session:
            return

        async with self._session.get(
            url,
            timeout=aiohttp.ClientTimeout(total=None),  # No timeout for SSE
        ) as response:
            if response.status != 200:
                logger.debug(f"SSE subscription failed: {response.status}")
                return

            async for line in response.content:
                if self._stop_event.is_set():
                    break

                line_str = line.decode("utf-8").strip()
                if line_str.startswith("data:"):
                    # Extract message data
                    data = line_str[5:].strip()
                    if data:
                        # Process message in background
                        asyncio.create_task(self._process_message(data))

    async def _process_message(self, encrypted: str) -> None:
        """Process an encrypted signaling message.

        Args:
            encrypted: Base64-encoded encrypted message.
        """
        try:
            result = await self._handler.handle_message(encrypted)

            if result and result.should_respond:
                # Publish answer
                success = await self._publish(result.answer_encrypted)

                if success and self.on_offer_received:
                    # Notify callback
                    await self.on_offer_received(
                        result.device_id,
                        result.device_name,
                        result.peer,
                    )
        except Exception as e:
            # Silent ignore all errors
            logger.debug(f"Error processing signaling message: {e}")

    async def _publish(self, data: str) -> bool:
        """Publish response to ntfy topic with retry.

        Args:
            data: Base64-encoded encrypted response.

        Returns:
            True on success, False after all retries exhausted.
        """
        if not self._session:
            return False

        url = f"{self._server}/{self._topic}"

        for attempt in range(self.MAX_PUBLISH_RETRIES):
            try:
                async with self._session.post(
                    url,
                    data=data,
                    headers={"Content-Type": "text/plain"},
                    timeout=aiohttp.ClientTimeout(total=self.REQUEST_TIMEOUT),
                ) as response:
                    if response.status == 200:
                        logger.debug(f"Published signaling response to {self._topic[:8]}...")
                        return True
                    else:
                        logger.debug(f"Publish failed with status {response.status}")
            except Exception as e:
                logger.debug(f"Publish attempt {attempt + 1} failed: {e}")

            # Wait before retry (except on last attempt)
            if attempt < self.MAX_PUBLISH_RETRIES - 1:
                await asyncio.sleep(self.PUBLISH_RETRY_DELAYS[attempt])

        logger.warning(f"Failed to publish signaling response after {self.MAX_PUBLISH_RETRIES} attempts")
        return False
