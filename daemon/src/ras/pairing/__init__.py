"""Pairing module for RAS.

Provides QR code pairing functionality including:
- QR code generation
- HTTP signaling server
- Mutual authentication handshake
- Pairing session management
"""

from .auth_handler import AuthHandler
from .pairing_manager import DeviceStore, PairingManager, StunClient
from .qr_generator import QrGenerator
from .session import PairingSession, PairingState
from .signaling_server import RateLimiter, SignalingServer

__all__ = [
    "AuthHandler",
    "DeviceStore",
    "PairingManager",
    "PairingSession",
    "PairingState",
    "QrGenerator",
    "RateLimiter",
    "SignalingServer",
    "StunClient",
]
