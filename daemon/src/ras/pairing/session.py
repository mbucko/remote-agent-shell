"""Pairing session state machine.

Represents an active pairing session with state transitions
and timeout handling.
"""

import secrets
import time
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Any, Optional


class PairingState(Enum):
    """Pairing session states."""

    IDLE = auto()
    QR_DISPLAYED = auto()
    SIGNALING = auto()
    CONNECTING = auto()
    AUTHENTICATING = auto()
    AUTHENTICATED = auto()
    FAILED = auto()


@dataclass
class PairingSession:
    """Represents an active pairing session.

    Attributes:
        session_id: Unique session identifier (32 hex chars).
        master_secret: 32-byte shared secret from QR code.
        auth_key: 32-byte derived authentication key.
        created_at: Unix timestamp when session was created.
        state: Current pairing state.
        device_id: Connected device ID (set during signaling).
        device_name: Connected device name (set during signaling).
        peer_connection: WebRTC peer connection (set during connecting).
    """

    session_id: str
    master_secret: bytes
    auth_key: bytes
    created_at: float = field(default_factory=time.time)
    state: PairingState = PairingState.IDLE

    # Populated during pairing
    device_id: Optional[str] = None
    device_name: Optional[str] = None
    peer_connection: Optional[Any] = None  # RTCPeerConnection

    # Timeouts (in seconds)
    QR_TIMEOUT: int = 300  # 5 minutes
    SIGNALING_TIMEOUT: int = 30
    CONNECTING_TIMEOUT: int = 30
    AUTH_TIMEOUT: int = 10

    @classmethod
    def create(cls, master_secret: bytes, auth_key: bytes) -> "PairingSession":
        """Create a new pairing session.

        Args:
            master_secret: 32-byte shared secret.
            auth_key: 32-byte derived authentication key.

        Returns:
            New PairingSession instance.
        """
        session_id = secrets.token_hex(16)  # 32 hex chars
        return cls(
            session_id=session_id,
            master_secret=master_secret,
            auth_key=auth_key,
        )

    def is_expired(self) -> bool:
        """Check if session has expired based on current state.

        Returns:
            True if session has expired, False otherwise.
        """
        elapsed = time.time() - self.created_at

        if self.state == PairingState.QR_DISPLAYED:
            return elapsed > self.QR_TIMEOUT
        elif self.state == PairingState.SIGNALING:
            return elapsed > self.QR_TIMEOUT + self.SIGNALING_TIMEOUT
        elif self.state == PairingState.CONNECTING:
            return elapsed > (
                self.QR_TIMEOUT + self.SIGNALING_TIMEOUT + self.CONNECTING_TIMEOUT
            )
        elif self.state == PairingState.AUTHENTICATING:
            return elapsed > (
                self.QR_TIMEOUT
                + self.SIGNALING_TIMEOUT
                + self.CONNECTING_TIMEOUT
                + self.AUTH_TIMEOUT
            )

        # IDLE, AUTHENTICATED, FAILED don't expire via this method
        return False

    def transition_to(self, new_state: PairingState) -> None:
        """Transition to a new state with validation.

        Args:
            new_state: Target state.

        Raises:
            ValueError: If transition is not valid from current state.
        """
        valid_transitions = {
            PairingState.IDLE: {PairingState.QR_DISPLAYED},
            PairingState.QR_DISPLAYED: {PairingState.SIGNALING, PairingState.FAILED},
            PairingState.SIGNALING: {PairingState.CONNECTING, PairingState.FAILED},
            PairingState.CONNECTING: {PairingState.AUTHENTICATING, PairingState.FAILED},
            PairingState.AUTHENTICATING: {
                PairingState.AUTHENTICATED,
                PairingState.FAILED,
            },
            PairingState.AUTHENTICATED: set(),
            PairingState.FAILED: {PairingState.IDLE},
        }

        if new_state not in valid_transitions.get(self.state, set()):
            raise ValueError(f"Invalid transition: {self.state} -> {new_state}")

        self.state = new_state
