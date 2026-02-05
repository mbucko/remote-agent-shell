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
import time
from typing import Any, Callable, Coroutine, Optional

import aiohttp

from ras.ntfy_signaling.handler import (
    DeviceStoreProtocol,
    NtfySignalingHandler,
    HandlerResult,
)

logger = logging.getLogger(__name__)


# Callback type: (device_id, device_name, peer, is_reconnection) -> None
OfferReceivedCallback = Callable[[str, str, Any, bool], Coroutine[Any, Any, None]]

# Callback type for pairing: (device_id, device_name) -> None
PairCompleteCallback = Callable[[str, str], Coroutine[Any, Any, None]]


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

    # Health check thresholds
    # ntfy sends keepalives every 45 seconds, so we should see events regularly
    SSE_HEALTH_TIMEOUT = (
        120.0  # seconds - consider unhealthy if no events for 2+ minutes
    )
    SSE_RECONNECT_TIMEOUT = (
        180.0  # seconds - force reconnect if no events for 3+ minutes
    )

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
        discovery_provider: Optional[Callable[[], dict]] = None,
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
            discovery_provider: Optional callable returning discovery info dict.
                               Keys: lan_ip, lan_port, vpn_ip, vpn_port, tailscale_ip,
                               tailscale_port, public_ip, public_port, device_id.
        """
        self._handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id=session_id,
            stun_servers=stun_servers,
            device_store=device_store,
            capabilities_provider=capabilities_provider,
            discovery_provider=discovery_provider,
        )
        self._topic = ntfy_topic
        self._server = ntfy_server.rstrip("/")
        self._session = http_session
        self._owns_session = http_session is None
        self._subscribed = False
        self._subscription_task: Optional[asyncio.Task] = None
        self._health_task: Optional[asyncio.Task] = None
        self._stop_event = asyncio.Event()
        self._reconnection_mode = session_id == ""

        # Callback for successful offer processing
        # Signature: (device_id, device_name, peer, is_reconnection) -> None
        self.on_offer_received: Optional[OfferReceivedCallback] = None

        # Callback for pairing completion (PAIR_REQUEST/PAIR_RESPONSE exchange)
        # Signature: (device_id, device_name) -> None
        self.on_pair_complete: Optional[PairCompleteCallback] = None

        # Health tracking
        self._last_event_time: float = 0.0  # Last time any SSE event was received
        self._last_message_time: float = 0.0  # Last time an actual message was received
        self._sse_connected: bool = False
        self._reconnect_count: int = 0

        mode = "reconnection" if self._reconnection_mode else "pairing"
        logger.info(f"NtfySignalingSubscriber initialized in {mode} mode")

    def is_subscribed(self) -> bool:
        """Check if currently subscribed to ntfy topic."""
        return self._subscribed

    def is_reconnection_mode(self) -> bool:
        """Check if subscriber is in reconnection mode."""
        return self._reconnection_mode

    def is_healthy(self) -> bool:
        """Check if SSE connection is healthy.

        Returns False if:
        - Not connected
        - No events received for SSE_HEALTH_TIMEOUT seconds

        Returns:
            True if connection appears healthy.
        """
        if not self._subscribed or not self._sse_connected:
            return False

        if self._last_event_time == 0:
            # Just connected, give it time
            return True

        time_since_event = time.time() - self._last_event_time
        return time_since_event < self.SSE_HEALTH_TIMEOUT

    def get_health_stats(self) -> dict:
        """Get health statistics for debugging.

        Returns:
            Dict with health metrics.
        """
        now = time.time()
        return {
            "subscribed": self._subscribed,
            "sse_connected": self._sse_connected,
            "last_event_age_s": now - self._last_event_time
            if self._last_event_time > 0
            else None,
            "last_message_age_s": now - self._last_message_time
            if self._last_message_time > 0
            else None,
            "reconnect_count": self._reconnect_count,
            "is_healthy": self.is_healthy(),
        }

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
        # Start health monitoring
        self._health_task = asyncio.create_task(self._run_health_monitor())
        mode = "reconnection" if self._reconnection_mode else "pairing"
        logger.info(
            f"Started ntfy signaling subscription ({mode}) to topic {self._topic[:8]}..."
        )

    async def stop(self) -> None:
        """Stop subscription to ntfy topic."""
        if not self._subscribed:
            return

        self._subscribed = False
        self._stop_event.set()

        # Cancel health task
        if self._health_task:
            self._health_task.cancel()
            try:
                await self._health_task
            except asyncio.CancelledError:
                pass
            self._health_task = None

        # Cancel subscription task
        if self._subscription_task:
            self._subscription_task.cancel()
            try:
                await self._subscription_task
            except asyncio.CancelledError:
                pass
            self._subscription_task = None

        logger.info(
            f"Stopped ntfy signaling subscription to topic {self._topic[:8]}..."
        )

    async def _run_health_monitor(self) -> None:
        """Monitor SSE connection health and log warnings.

        Runs in background, logging warnings when health degrades.
        """
        # Check health every 30 seconds
        CHECK_INTERVAL = 30.0

        while self._subscribed and not self._stop_event.is_set():
            try:
                await asyncio.sleep(CHECK_INTERVAL)

                if not self._subscribed:
                    break

                stats = self.get_health_stats()
                if not stats["is_healthy"] and self._sse_connected:
                    logger.warning(
                        f"SSE health degraded: no events for {stats['last_event_age_s']:.0f}s "
                        f"(reconnects: {stats['reconnect_count']})"
                    )
                elif stats["is_healthy"] and self._reconnect_count > 0:
                    # Log recovery
                    logger.info(
                        f"SSE health recovered after {self._reconnect_count} reconnects"
                    )

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"Health monitor error: {e}")

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
            # Allow event loop to clean up connector (prevents "Unclosed client session" warning)
            await asyncio.sleep(0)
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

        Reconnects automatically on connection errors or health timeout.
        """
        url = f"{self._server}/{self._topic}/sse"

        while self._subscribed and not self._stop_event.is_set():
            try:
                await self._subscribe_sse(url)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.warning(f"SSE subscription error: {e}")
                self._sse_connected = False
                self._reconnect_count += 1
                if self._subscribed:
                    logger.info(
                        f"Reconnecting in {self.SSE_RECONNECT_DELAY}s... (reconnect #{self._reconnect_count})"
                    )
                    await asyncio.sleep(self.SSE_RECONNECT_DELAY)

    async def _subscribe_sse(self, url: str) -> None:
        """Subscribe to SSE stream.

        Args:
            url: Full SSE URL.

        Raises:
            Exception: On connection error or health timeout.
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
            self._sse_connected = True
            self._last_event_time = time.time()

            async for line in response.content:
                if self._stop_event.is_set():
                    break

                # Check for health timeout - force reconnect if no events for too long
                time_since_event = time.time() - self._last_event_time
                if time_since_event > self.SSE_RECONNECT_TIMEOUT:
                    logger.warning(
                        f"SSE health timeout: no events for {time_since_event:.0f}s, forcing reconnect"
                    )
                    self._sse_connected = False
                    raise Exception("SSE health timeout")

                line_str = line.decode("utf-8").strip()
                if line_str.startswith("data:"):
                    # Track that we received an event
                    self._last_event_time = time.time()

                    # Extract JSON data from SSE
                    json_data = line_str[5:].strip()
                    logger.warning(
                        f"[NTFY_RAW] SSE data received: {json_data[:200]}..."
                    )  # Log first 200 chars
                    if json_data:
                        # Parse ntfy JSON envelope to extract message
                        encrypted = self._parse_ntfy_message(json_data)
                        if encrypted:
                            self._last_message_time = time.time()
                            logger.warning(
                                f"[NTFY_MSG] Received ntfy MESSAGE event ({len(encrypted)} bytes)"
                            )
                            # Process message in background
                            asyncio.create_task(self._process_message(encrypted))
                        else:
                            # Parse to see what event type it was
                            try:
                                data = json.loads(json_data)
                                event = data.get("event", "unknown")
                                logger.warning(
                                    f"[NTFY_EVENT] Received ntfy event type: {event} (not a message)"
                                )
                            except:
                                logger.warning(
                                    f"[NTFY_EVENT] Failed to parse ntfy JSON"
                                )
                elif line_str:
                    logger.warning(f"[NTFY_SSE] Non-data SSE line: {line_str[:100]}")

            # If we exit the loop normally, mark as disconnected
            self._sse_connected = False

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

                if success and result.is_pair_complete:
                    # Pairing completed — notify pair callback
                    if self.on_pair_complete:
                        await self.on_pair_complete(
                            result.device_id,
                            result.device_name,
                        )
                elif (
                    success
                    and self.on_offer_received
                    and not result.is_capability_exchange
                ):
                    # WebRTC offer/answer — notify offer callback
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
        import time

        if not self._session:
            return False

        url = f"{self._server}/{self._topic}"

        for attempt in range(self.MAX_PUBLISH_RETRIES):
            try:
                publish_start = time.time()
                logger.info(f"[TIMING] ntfy: publish_start (attempt {attempt + 1})")
                async with self._session.post(
                    url,
                    data=data,
                    headers={"Content-Type": "text/plain"},
                    timeout=aiohttp.ClientTimeout(total=self.REQUEST_TIMEOUT),
                ) as response:
                    publish_ms = (time.time() - publish_start) * 1000
                    if response.status == 200:
                        logger.info(
                            f"[TIMING] ntfy: publish_complete @ {publish_ms:.1f}ms"
                        )
                        logger.debug(
                            f"Published signaling response to {self._topic[:8]}..."
                        )
                        return True
                    else:
                        logger.info(
                            f"[TIMING] ntfy: publish_failed @ {publish_ms:.1f}ms (status {response.status})"
                        )
                        logger.debug(f"Publish failed with status {response.status}")
            except Exception as e:
                logger.debug(f"Publish attempt {attempt + 1} failed: {e}")

            # Wait before retry (except on last attempt)
            if attempt < self.MAX_PUBLISH_RETRIES - 1:
                await asyncio.sleep(self.PUBLISH_RETRY_DELAYS[attempt])

        logger.warning(
            f"Failed to publish signaling response after {self.MAX_PUBLISH_RETRIES} attempts"
        )
        return False
