"""Subscriber for ntfy signaling messages.

This module provides:
- NtfySignalingSubscriber: Subscribes to ntfy topic for signaling messages
- Processes incoming OFFER messages via NtfySignalingHandler
- Publishes encrypted ANSWER responses back to ntfy
- Supports both pairing mode (session_id) and reconnection mode (device_id)

Usage:
    # For pairing
    subscriber = NtfySignalingSubscriber(
        master_secret=master_secret,
        session_id="abc123",
        ntfy_topic="ras-abc123",
    )

    # For reconnection
    subscriber = NtfySignalingSubscriber(
        master_secret=master_secret,
        session_id="",  # Empty = reconnection mode
        ntfy_topic="ras-abc123",
        device_store=device_store,
    )

    # Set callback for when offer is received
    subscriber.on_offer_received = async def callback(device_id, device_name, peer, is_reconnection):
        # Handle successful signaling
        pass

    # Start subscription
    await subscriber.start()

    # When done (pairing/reconnection complete or cancelled)
    await subscriber.close()
"""

import asyncio
import json
import logging
from typing import Any, Callable, Coroutine, Optional

import aiohttp

from ras.ntfy_signaling.handler import DeviceStoreProtocol, NtfySignalingHandler, HandlerResult

logger = logging.getLogger(__name__)


# Callback type: (device_id, device_name, peer, is_reconnection) -> None
OfferReceivedCallback = Callable[[str, str, Any, bool], Coroutine[Any, Any, None]]


class NtfySignalingSubscriber:
    """Subscribes to ntfy for signaling messages.

    Listens for encrypted OFFER messages via SSE (Server-Sent Events),
    processes them through NtfySignalingHandler, and publishes encrypted
    ANSWER responses back to the same ntfy topic.

    Modes:
    - Pairing mode: session_id is set, validates against it
    - Reconnection mode: session_id is empty, validates device_id against device_store

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
        device_store: Optional[DeviceStoreProtocol] = None,
        http_session: Optional[aiohttp.ClientSession] = None,
        capabilities_provider: Optional[Callable[[], dict]] = None,
    ):
        """Initialize subscriber.

        Args:
            master_secret: 32-byte master secret from QR code.
            session_id: Expected session ID for pairing mode.
                       Empty string ("") enables reconnection mode.
            ntfy_topic: ntfy topic to subscribe to.
            ntfy_server: ntfy server URL.
            stun_servers: STUN servers for WebRTC (optional).
            device_store: Device store for reconnection mode (required if reconnection enabled).
            http_session: Optional aiohttp session (for testing).
            capabilities_provider: Optional callable returning capabilities dict.
                                  Used to include Tailscale info in ANSWER messages.
        """
        self._handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id=session_id,
            stun_servers=stun_servers,
            device_store=device_store,
            capabilities_provider=capabilities_provider,
        )
        self._topic = ntfy_topic
        self._server = ntfy_server.rstrip("/")
        self._session = http_session
        self._owns_session = http_session is None
        self._subscribed = False
        self._subscription_task: Optional[asyncio.Task] = None
        self._stop_event = asyncio.Event()
        self._reconnection_mode = session_id == ""

        # Callback for successful offer processing
        # Signature: (device_id, device_name, peer, is_reconnection) -> None
        self.on_offer_received: Optional[OfferReceivedCallback] = None

        mode = "reconnection" if self._reconnection_mode else "pairing"
        logger.info(f"NtfySignalingSubscriber initialized in {mode} mode")

    def is_subscribed(self) -> bool:
        """Check if currently subscribed to ntfy topic."""
        return self._subscribed

    def is_reconnection_mode(self) -> bool:
        """Check if subscriber is in reconnection mode."""
        return self._reconnection_mode

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
        mode = "reconnection" if self._reconnection_mode else "pairing"
        logger.info(f"Started ntfy signaling subscription ({mode}) to topic {self._topic[:8]}...")

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

    def take_peer(self) -> Optional[Any]:
        """Take ownership of the WebRTC peer connection.

        Returns the peer and clears the internal reference so that
        close() won't close the peer. The caller becomes responsible
        for closing the peer.

        Returns:
            The peer connection, or None if no peer exists.
        """
        return self._handler.take_peer()

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
                logger.warning(f"SSE subscription error: {e}")
                if self._subscribed:
                    logger.info(f"Reconnecting in {self.SSE_RECONNECT_DELAY}s...")
                    await asyncio.sleep(self.SSE_RECONNECT_DELAY)

    async def _subscribe_sse(self, url: str) -> None:
        """Subscribe to SSE stream.

        Args:
            url: Full SSE URL.
        """
        if not self._session:
            return

        logger.info(f"Connecting to SSE: {url}")
        async with self._session.get(
            url,
            timeout=aiohttp.ClientTimeout(total=None),  # No timeout for SSE
        ) as response:
            if response.status != 200:
                logger.warning(f"SSE subscription failed: {response.status}")
                return

            logger.info(f"SSE connected, listening for messages...")
            async for line in response.content:
                if self._stop_event.is_set():
                    break

                line_str = line.decode("utf-8").strip()
                if line_str.startswith("data:"):
                    # Extract JSON data from SSE
                    json_data = line_str[5:].strip()
                    if json_data:
                        # Parse ntfy JSON envelope to extract message
                        encrypted = self._parse_ntfy_message(json_data)
                        if encrypted:
                            logger.info(f"Received ntfy message ({len(encrypted)} bytes)")
                            # Process message in background
                            asyncio.create_task(self._process_message(encrypted))

    def _parse_ntfy_message(self, json_data: str) -> Optional[str]:
        """Parse ntfy JSON envelope and extract message.

        ntfy SSE sends JSON like:
        {"id":"...", "time":..., "event":"message", "topic":"...", "message":"<content>"}

        Args:
            json_data: Raw JSON string from SSE data field.

        Returns:
            The message content if event is "message", None otherwise.
        """
        try:
            data = json.loads(json_data)
            event = data.get("event", "")

            # Only process "message" events (ignore "open", "keepalive", etc.)
            if event != "message":
                logger.debug(f"Ignoring ntfy event: {event}")
                return None

            message = data.get("message", "")
            if not message:
                logger.debug("ntfy message event has empty message")
                return None

            return message
        except json.JSONDecodeError as e:
            logger.debug(f"Failed to parse ntfy JSON: {e}")
            return None

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

                if success and self.on_offer_received and not result.is_capability_exchange:
                    # Notify callback with is_reconnection flag
                    # (Skip for capability exchange - no peer to authenticate)
                    await self.on_offer_received(
                        result.device_id,
                        result.device_name,
                        result.peer,
                        result.is_reconnection,
                    )
        except Exception as e:
            # Log processing errors (but don't expose details that could help attackers)
            logger.warning(f"Error processing signaling message: {type(e).__name__}")

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
