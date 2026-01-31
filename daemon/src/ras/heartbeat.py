"""
Heartbeat manager for WebRTC connection keepalive.

Industry-standard pattern for maintaining connection health:
- Sends periodic heartbeats to prevent SCTP timeout (~30s)
- Tracks received heartbeats to detect stale connections
- Provides health metrics (latency, missed beats)

Both daemon and phone should run HeartbeatManager independently.
Heartbeats are fire-and-forget (no response expected).
"""

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Coroutine, Dict, Optional

from ras.proto.ras import Heartbeat, RasEvent

logger = logging.getLogger(__name__)


@dataclass
class HeartbeatConfig:
    """Configuration for heartbeat behavior.

    All intervals should be less than SCTP timeout (~30s).
    """
    send_interval: float = 15.0  # How often to send heartbeats (seconds)
    receive_timeout: float = 60.0  # Consider dead if no data for this long

    def __post_init__(self):
        if self.send_interval >= 30.0:
            logger.warning(
                f"send_interval ({self.send_interval}s) >= SCTP timeout (~30s), "
                "connections may drop"
            )


@dataclass
class ConnectionHealth:
    """Health metrics for a single connection."""
    device_id: str
    last_heartbeat_sent: float = 0.0
    last_heartbeat_received: float = 0.0
    last_activity: float = field(default_factory=time.time)
    heartbeats_sent: int = 0
    heartbeats_received: int = 0

    @property
    def is_healthy(self) -> bool:
        """Connection is healthy if we've had recent activity."""
        return time.time() - self.last_activity < 60.0

    @property
    def seconds_since_activity(self) -> float:
        """Seconds since last activity (sent or received)."""
        return time.time() - self.last_activity


# Type alias for send callback
SendCallback = Callable[[str, bytes], Coroutine[Any, Any, None]]


class HeartbeatManager:
    """Manages heartbeat sending and tracking for all connections.

    Usage:
        manager = HeartbeatManager(config, send_callback)
        await manager.start()

        # When connection established:
        manager.on_connection_added(device_id)

        # When heartbeat received:
        manager.on_heartbeat_received(device_id, heartbeat)

        # When any data received (updates activity):
        manager.on_activity(device_id)

        # When connection closed:
        manager.on_connection_removed(device_id)

        await manager.stop()
    """

    def __init__(
        self,
        config: HeartbeatConfig,
        send_callback: SendCallback,
    ):
        """Initialize the heartbeat manager.

        Args:
            config: Heartbeat configuration
            send_callback: Async function to send bytes to a device
        """
        self._config = config
        self._send = send_callback
        self._sequence = 0
        self._connections: Dict[str, ConnectionHealth] = {}
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self._lock = asyncio.Lock()

    @property
    def config(self) -> HeartbeatConfig:
        """Get the heartbeat configuration."""
        return self._config

    async def start(self) -> None:
        """Start the heartbeat loop."""
        if self._running:
            return

        self._running = True
        self._task = asyncio.create_task(self._heartbeat_loop())
        logger.info(
            f"HeartbeatManager started (interval={self._config.send_interval}s, "
            f"timeout={self._config.receive_timeout}s)"
        )

    async def stop(self) -> None:
        """Stop the heartbeat loop."""
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        logger.info("HeartbeatManager stopped")

    def on_connection_added(self, device_id: str) -> None:
        """Start tracking a new connection."""
        now = time.time()
        self._connections[device_id] = ConnectionHealth(
            device_id=device_id,
            last_activity=now,
        )
        logger.debug(f"Tracking heartbeat for {device_id[:8]}...")

    def on_connection_removed(self, device_id: str) -> None:
        """Stop tracking a connection."""
        if device_id in self._connections:
            del self._connections[device_id]
            logger.debug(f"Stopped tracking heartbeat for {device_id[:8]}...")

    def on_heartbeat_received(self, device_id: str, heartbeat: Heartbeat) -> None:
        """Record a received heartbeat."""
        if device_id not in self._connections:
            return

        now = time.time()
        health = self._connections[device_id]
        health.last_heartbeat_received = now
        health.last_activity = now
        health.heartbeats_received += 1

        # Calculate one-way latency if timestamp is reasonable
        if heartbeat.timestamp > 0:
            latency_ms = (now * 1000) - heartbeat.timestamp
            if 0 < latency_ms < 30000:  # Sanity check
                logger.debug(
                    f"Heartbeat from {device_id[:8]}: seq={heartbeat.sequence}, "
                    f"latency={latency_ms:.0f}ms"
                )

    def on_activity(self, device_id: str) -> None:
        """Record any activity (not just heartbeat) for a connection."""
        if device_id in self._connections:
            self._connections[device_id].last_activity = time.time()

    def get_health(self, device_id: str) -> Optional[ConnectionHealth]:
        """Get health metrics for a connection."""
        return self._connections.get(device_id)

    def get_stale_connections(self) -> list[str]:
        """Get list of connections that have exceeded receive_timeout."""
        now = time.time()
        timeout = self._config.receive_timeout
        return [
            device_id
            for device_id, health in self._connections.items()
            if now - health.last_activity > timeout
        ]

    async def _heartbeat_loop(self) -> None:
        """Main loop that sends heartbeats to all connections."""
        while self._running:
            try:
                await asyncio.sleep(self._config.send_interval)
                await self._send_heartbeats()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Heartbeat loop error: {e}")

    async def _send_heartbeats(self) -> None:
        """Send heartbeat to all tracked connections."""
        now = time.time()
        stale_threshold = self._config.receive_timeout

        for device_id, health in list(self._connections.items()):
            # Check for stale connections
            if now - health.last_activity > stale_threshold:
                logger.warning(
                    f"Connection stale ({health.seconds_since_activity:.0f}s idle): "
                    f"{device_id[:8]}..."
                )
                continue  # Don't send heartbeat to stale connections

            # Send heartbeat
            try:
                self._sequence += 1
                heartbeat = Heartbeat(
                    timestamp=int(now * 1000),
                    sequence=self._sequence,
                )
                event = RasEvent(heartbeat=heartbeat)
                await self._send(device_id, bytes(event))

                health.last_heartbeat_sent = now
                health.heartbeats_sent += 1
                logger.debug(f"Heartbeat sent to {device_id[:8]}: seq={self._sequence}")

            except Exception as e:
                logger.warning(f"Failed to send heartbeat to {device_id[:8]}: {e}")

    async def send_immediate(self, device_id: str) -> None:
        """Send an immediate heartbeat to a specific connection.

        Use this when a connection is first established to prevent
        SCTP timeout before the regular heartbeat loop runs.
        """
        if device_id not in self._connections:
            return

        try:
            self._sequence += 1
            now = time.time()
            heartbeat = Heartbeat(
                timestamp=int(now * 1000),
                sequence=self._sequence,
            )
            event = RasEvent(heartbeat=heartbeat)
            await self._send(device_id, bytes(event))

            health = self._connections[device_id]
            health.last_heartbeat_sent = now
            health.heartbeats_sent += 1
            logger.debug(f"Immediate heartbeat sent to {device_id[:8]}: seq={self._sequence}")

        except Exception as e:
            logger.warning(f"Failed to send immediate heartbeat to {device_id[:8]}: {e}")
