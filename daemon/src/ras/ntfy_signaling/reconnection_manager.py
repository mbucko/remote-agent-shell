"""Manager for ntfy-based reconnection subscriptions.

This module provides:
- NtfyReconnectionManager: Manages ntfy subscriptions for all paired devices
- Enables reconnection via ntfy when direct HTTP is not reachable

Each paired device has its own master_secret and thus its own ntfy topic.
The manager maintains a subscriber for each paired device to handle
reconnection requests.

Usage:
    manager = NtfyReconnectionManager(
        device_store=device_store,
        stun_servers=stun_servers,
        on_reconnection=callback,
    )

    # Start subscriptions for all paired devices
    await manager.start()

    # When a new device is paired
    await manager.add_device(device)

    # When a device is removed
    await manager.remove_device(device_id)

    # Cleanup
    await manager.stop()
"""

import asyncio
import logging
from typing import Any, Callable, Coroutine, Optional

from ras.crypto import derive_ntfy_topic
from ras.device_store import JsonDeviceStore, PairedDevice
from ras.ntfy_signaling.subscriber import NtfySignalingSubscriber

logger = logging.getLogger(__name__)


# Callback type: (device_id, device_name, peer, auth_key) -> None
ReconnectionCallback = Callable[[str, str, Any, bytes], Coroutine[Any, Any, None]]


class NtfyReconnectionManager:
    """Manages ntfy subscriptions for device reconnection.

    For each paired device, maintains an ntfy subscriber listening for
    reconnection requests. When a device can't reach the daemon directly
    (e.g., due to NAT), it sends a reconnection OFFER via ntfy.

    The manager:
    - Starts subscribers for all paired devices on startup
    - Adds subscribers when new devices are paired
    - Removes subscribers when devices are unpaired
    - Routes successful reconnections to a callback
    """

    def __init__(
        self,
        device_store: JsonDeviceStore,
        stun_servers: Optional[list[str]] = None,
        ntfy_server: str = "https://ntfy.sh",
        on_reconnection: Optional[ReconnectionCallback] = None,
        capabilities_provider: Optional[Callable[[], dict]] = None,
        discovery_provider: Optional[Callable[[], dict]] = None,
    ):
        """Initialize manager.

        Args:
            device_store: Store of paired devices.
            stun_servers: STUN servers for WebRTC.
            ntfy_server: ntfy server URL.
            on_reconnection: Callback when reconnection succeeds.
                            Receives (device_id, device_name, peer, auth_key).
            capabilities_provider: Optional callable returning capabilities dict.
                                  Used to include Tailscale info in ANSWER messages.
            discovery_provider: Optional callable returning discovery info dict.
                               Keys: lan_ip, lan_port, vpn_ip, vpn_port, tailscale_ip,
                               tailscale_port, public_ip, public_port, device_id.
        """
        self._device_store = device_store
        self._stun_servers = stun_servers or []
        self._ntfy_server = ntfy_server
        self._on_reconnection = on_reconnection
        self._capabilities_provider = capabilities_provider
        self._discovery_provider = discovery_provider

        # Map of device_id -> subscriber
        self._subscribers: dict[str, NtfySignalingSubscriber] = {}
        self._running = False

    async def start(self) -> None:
        """Start reconnection subscriptions for all paired devices.

        Creates an ntfy subscriber for each paired device. Returns
        immediately after starting background subscription tasks.
        """
        if self._running:
            return

        self._running = True
        devices = self._device_store.all()

        logger.info(f"Starting ntfy reconnection manager for {len(devices)} devices")

        for device in devices:
            await self._add_subscriber(device)

        logger.info("Ntfy reconnection manager started")

    async def stop(self) -> None:
        """Stop all reconnection subscriptions."""
        if not self._running:
            return

        self._running = False

        logger.info("Stopping ntfy reconnection manager")

        # Stop all subscribers
        for device_id, subscriber in list(self._subscribers.items()):
            try:
                await subscriber.close()
            except Exception as e:
                logger.warning(f"Error closing subscriber for {device_id[:8]}...: {e}")

        self._subscribers.clear()
        logger.info("Ntfy reconnection manager stopped")

    async def add_device(self, device: PairedDevice) -> None:
        """Add a subscriber for a newly paired device.

        Args:
            device: The paired device.
        """
        if not self._running:
            return

        if device.device_id in self._subscribers:
            logger.warning(f"Subscriber already exists for {device.device_id[:8]}...")
            return

        await self._add_subscriber(device)

    async def remove_device(self, device_id: str) -> None:
        """Remove the subscriber for an unpaired device.

        Args:
            device_id: The device ID.
        """
        subscriber = self._subscribers.pop(device_id, None)
        if subscriber:
            try:
                await subscriber.close()
                logger.info(f"Removed reconnection subscriber for {device_id[:8]}...")
            except Exception as e:
                logger.warning(f"Error closing subscriber for {device_id[:8]}...: {e}")

    async def _add_subscriber(self, device: PairedDevice) -> None:
        """Add a subscriber for a device.

        Args:
            device: The paired device.
        """
        try:
            # Compute ntfy topic from master secret
            topic = derive_ntfy_topic(device.master_secret)

            # Create subscriber in reconnection mode (session_id = "")
            subscriber = NtfySignalingSubscriber(
                master_secret=device.master_secret,
                session_id="",  # Empty = reconnection mode
                ntfy_topic=topic,
                ntfy_server=self._ntfy_server,
                stun_servers=self._stun_servers,
                device_store=self._device_store,
                capabilities_provider=self._capabilities_provider,
                discovery_provider=self._discovery_provider,
            )

            # Set callback
            subscriber.on_offer_received = self._on_offer_received

            # Start subscription
            await subscriber.start()

            self._subscribers[device.device_id] = subscriber
            logger.info(f"Started reconnection subscriber for {device.device_id[:8]}... on topic {topic[:8]}...")

        except Exception as e:
            logger.error(f"Failed to start subscriber for {device.device_id[:8]}...: {e}")

    async def _on_offer_received(
        self,
        device_id: str,
        device_name: str,
        peer: Any,
        is_reconnection: bool,
    ) -> None:
        """Handle successful offer processing.

        Args:
            device_id: The device ID.
            device_name: The device name.
            peer: The WebRTC peer connection.
            is_reconnection: Should always be True for this manager.
        """
        if not is_reconnection:
            logger.warning(f"Received non-reconnection offer in reconnection manager")
            return

        # Get the device to retrieve auth key
        device = self._device_store.get(device_id)
        if not device:
            logger.warning(f"Device not found for reconnection: {device_id[:8]}...")
            if peer:
                await peer.close()
            return

        # Derive auth key from master secret
        from ras.crypto import derive_key
        auth_key = derive_key(device.master_secret, "auth")

        # Set up message queue for auth (peer uses callback pattern, not receive())
        auth_queue: asyncio.Queue[bytes] = asyncio.Queue()

        async def on_message(message: bytes) -> None:
            await auth_queue.put(message)

        peer.on_message(on_message)

        # Wait for WebRTC connection before auth
        try:
            await peer.wait_connected(timeout=30.0)
            logger.info(f"Data channel open for reconnect {device_id[:8]}...")
        except Exception as e:
            logger.warning(f"Data channel failed for {device_id[:8]}...: {e}")
            await peer.close()
            return

        # Run authentication handshake
        from ras.pairing.auth_handler import AuthHandler
        auth_handler = AuthHandler(auth_key, device_id)

        async def send_message(data: bytes) -> None:
            await peer.send(data)

        async def receive_message() -> bytes:
            return await asyncio.wait_for(auth_queue.get(), timeout=10.0)

        try:
            success = await auth_handler.run_handshake(send_message, receive_message)
            if not success:
                logger.warning(f"Ntfy reconnection auth failed for {device_id[:8]}...")
                await peer.close()
                return
            logger.info(f"Authentication successful for device {device_id}")
        except asyncio.TimeoutError:
            logger.warning(f"Ntfy reconnection auth timeout for {device_id[:8]}...")
            await peer.close()
            return
        except Exception as e:
            logger.error(f"Ntfy reconnection auth error for {device_id[:8]}...: {e}")
            await peer.close()
            return

        logger.info(f"Ntfy reconnection successful for {device_id[:8]}...")

        # Notify callback
        if self._on_reconnection:
            try:
                await self._on_reconnection(
                    device_id,
                    device_name,
                    peer,
                    auth_key,
                )
            except Exception as e:
                logger.error(f"Reconnection callback failed: {e}")
                # Clean up peer if callback failed
                if peer:
                    await peer.close()
