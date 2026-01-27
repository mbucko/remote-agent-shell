"""Protocols and enums for RAS daemon."""

from enum import Enum
from typing import Protocol


class StunClientProtocol(Protocol):
    """Protocol for STUN client implementations."""

    async def get_public_ip(self) -> tuple[str, int]:
        """Returns (public_ip, public_port). Raises StunError on failure."""
        ...


class ConnectionState(Enum):
    """State of a P2P connection."""

    IDLE = "idle"
    SIGNALING = "signaling"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    FAILED = "failed"


class PeerState(Enum):
    """State of a WebRTC peer connection."""

    NEW = "new"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    FAILED = "failed"
    CLOSED = "closed"
