"""Authentication module for RAS daemon.

This module implements mutual authentication using HMAC-based challenge-response:
1. Daemon sends challenge (nonce) to phone
2. Phone responds with HMAC(nonce) + own nonce
3. Daemon verifies HMAC, sends HMAC of phone's nonce
4. Phone verifies, both are authenticated

Security features:
- HMAC-SHA256 for challenge-response
- 32-byte random nonces
- Replay protection via nonce tracking
- Rate limiting after failed attempts
- Constant-time HMAC comparison
"""

import secrets
from enum import Enum, auto

from ras.crypto import compute_hmac, verify_hmac
from ras.errors import AuthError

__all__ = [
    "AuthError",
    "AuthState",
    "Authenticator",
]


class AuthState(Enum):
    """Authentication state machine states."""

    PENDING = auto()  # Initial state
    CHALLENGED = auto()  # Challenge sent, waiting for response
    RESPONDED = auto()  # Response sent, waiting for verification
    AUTHENTICATED = auto()  # Successfully authenticated
    FAILED = auto()  # Authentication failed


class Authenticator:
    """Handles mutual authentication between daemon and phone.

    Implements a challenge-response protocol with HMAC-SHA256.

    Attributes:
        auth_key: 32-byte authentication key (from KeyBundle).
        role: Optional role identifier ("daemon" or "phone").
        state: Current authentication state.
    """

    MAX_FAILED_ATTEMPTS = 5  # Rate limit threshold
    NONCE_LENGTH = 32  # Bytes

    def __init__(self, auth_key: bytes, role: str | None = None) -> None:
        """Initialize authenticator.

        Args:
            auth_key: 32-byte key for HMAC operations.
            role: Optional role identifier for debugging.

        Raises:
            ValueError: If auth_key is not 32 bytes.
        """
        if len(auth_key) != 32:
            raise ValueError("auth_key must be 32 bytes")

        self.auth_key = auth_key
        self.role = role
        self._state = AuthState.PENDING
        self._our_nonce: bytes | None = None
        self._their_nonce: bytes | None = None
        self._used_nonces: set[bytes] = set()
        self._failed_attempts = 0

    @property
    def state(self) -> AuthState:
        """Current authentication state."""
        return self._state

    def create_challenge(self) -> dict:
        """Create an authentication challenge.

        Generates a random nonce and transitions to CHALLENGED state.

        Returns:
            Challenge dict with type and nonce fields.

        Raises:
            AuthError: If rate limited.
        """
        self._check_rate_limit()

        self._our_nonce = secrets.token_bytes(self.NONCE_LENGTH)
        self._state = AuthState.CHALLENGED

        return {
            "type": "auth_challenge",
            "nonce": self._our_nonce.hex(),
        }

    def respond_to_challenge(self, challenge: dict) -> dict:
        """Respond to an authentication challenge.

        Computes HMAC of the challenge nonce and includes own nonce
        for mutual authentication.

        Args:
            challenge: Challenge dict with nonce field.

        Returns:
            Response dict with type, hmac, and nonce fields.

        Raises:
            AuthError: If rate limited.
        """
        self._check_rate_limit()

        # Extract and store their nonce
        their_nonce = bytes.fromhex(challenge["nonce"])

        # Generate our nonce for mutual auth
        self._our_nonce = secrets.token_bytes(self.NONCE_LENGTH)

        # Compute HMAC of their nonce
        response_hmac = compute_hmac(self.auth_key, their_nonce)

        self._state = AuthState.RESPONDED

        return {
            "type": "auth_response",
            "hmac": response_hmac.hex(),
            "nonce": self._our_nonce.hex(),
        }

    def verify_response(self, response: dict) -> bool:
        """Verify an authentication response.

        Checks that the HMAC matches our challenge nonce.

        Args:
            response: Response dict with hmac and nonce fields.

        Returns:
            True if response is valid, False otherwise.
        """
        # Must be in CHALLENGED state (we sent a challenge)
        if self._state != AuthState.CHALLENGED or self._our_nonce is None:
            self._state = AuthState.FAILED
            return False

        # Check for replay attack - reject if this challenge nonce was already verified
        if self._our_nonce in self._used_nonces:
            self._failed_attempts += 1
            self._state = AuthState.FAILED
            return False

        # Verify HMAC
        expected_hmac = bytes.fromhex(response["hmac"])
        if not verify_hmac(self.auth_key, self._our_nonce, expected_hmac):
            self._failed_attempts += 1
            self._state = AuthState.FAILED
            return False

        # Success - mark our challenge nonce as used, store their nonce for verification
        their_nonce = bytes.fromhex(response["nonce"])
        self._their_nonce = their_nonce
        self._used_nonces.add(self._our_nonce)
        self._failed_attempts = 0  # Reset on success

        return True

    def create_verify(self, their_nonce_hex: str) -> dict:
        """Create verification message for mutual authentication.

        Sends HMAC of their nonce to prove we have the key.

        Args:
            their_nonce_hex: Their nonce as hex string.

        Returns:
            Verification dict with type and hmac fields.
        """
        their_nonce = bytes.fromhex(their_nonce_hex)
        verify_hmac_value = compute_hmac(self.auth_key, their_nonce)

        self._state = AuthState.AUTHENTICATED

        return {
            "type": "auth_verified",
            "hmac": verify_hmac_value.hex(),
        }

    def verify_verify(self, verify: dict) -> bool:
        """Verify the verification message from peer.

        Checks that peer proved knowledge of the key by
        correctly computing HMAC of our nonce.

        Args:
            verify: Verification dict with hmac field.

        Returns:
            True if verification is valid, False otherwise.
        """
        if self._our_nonce is None:
            self._state = AuthState.FAILED
            return False

        expected_hmac = bytes.fromhex(verify["hmac"])
        if not verify_hmac(self.auth_key, self._our_nonce, expected_hmac):
            self._state = AuthState.FAILED
            return False

        self._state = AuthState.AUTHENTICATED
        return True

    def _check_rate_limit(self) -> None:
        """Check if rate limited due to failed attempts.

        Raises:
            AuthError: If too many failed attempts.
        """
        if self._failed_attempts >= self.MAX_FAILED_ATTEMPTS:
            raise AuthError("Too many failed authentication attempts")
