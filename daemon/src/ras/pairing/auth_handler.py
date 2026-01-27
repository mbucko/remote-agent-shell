"""Authentication handler for pairing.

Implements the server side of the mutual authentication handshake
over WebRTC data channel.

Protocol:
1. Server -> Client: AuthChallenge { nonce }
2. Client -> Server: AuthResponse { hmac, nonce }
3. Server -> Client: AuthVerify { hmac }
4. Server -> Client: AuthSuccess { device_id }
"""

import asyncio
import logging
import secrets
from typing import Awaitable, Callable, Optional

import betterproto

from ras.crypto import compute_hmac, verify_hmac
from ras.proto.ras import (
    AuthEnvelope,
    AuthChallenge,
    AuthResponse,
    AuthVerify,
    AuthSuccess,
    AuthError,
    AuthErrorErrorCode,
)

logger = logging.getLogger(__name__)


class AuthHandler:
    """Handles mutual authentication handshake over WebRTC data channel.

    The server initiates by sending a challenge with a random nonce.
    The client must respond with HMAC(auth_key, server_nonce) and its own nonce.
    The server verifies the client's HMAC, then sends HMAC(auth_key, client_nonce)
    to prove it also knows the shared secret. Finally, it sends success.
    """

    NONCE_SIZE = 32
    TIMEOUT = 10  # seconds

    def __init__(self, auth_key: bytes, device_id: str):
        """Initialize auth handler.

        Args:
            auth_key: 32-byte authentication key (derived from master secret).
            device_id: Device ID to return on successful authentication.
        """
        self.auth_key = auth_key
        self.device_id = device_id
        self.server_nonce: Optional[bytes] = None
        self.client_nonce: Optional[bytes] = None
        self._authenticated = False

    async def run_handshake(
        self,
        send_message: Callable[[bytes], Awaitable[None]],
        receive_message: Callable[[], Awaitable[bytes]],
    ) -> bool:
        """Run the server-side authentication handshake.

        Args:
            send_message: Async function to send bytes over data channel.
            receive_message: Async function to receive bytes from data channel.

        Returns:
            True if authentication succeeded, False otherwise.
        """
        try:
            async with asyncio.timeout(self.TIMEOUT):
                return await self._do_handshake(send_message, receive_message)
        except asyncio.TimeoutError:
            logger.warning("Authentication timeout")
            await self._send_error(send_message, AuthErrorErrorCode.TIMEOUT)
            return False
        except Exception as e:
            logger.error(f"Authentication error: {e}")
            await self._send_error(send_message, AuthErrorErrorCode.PROTOCOL_ERROR)
            return False

    async def _do_handshake(
        self,
        send_message: Callable[[bytes], Awaitable[None]],
        receive_message: Callable[[], Awaitable[bytes]],
    ) -> bool:
        """Execute the handshake protocol.

        Args:
            send_message: Async function to send bytes.
            receive_message: Async function to receive bytes.

        Returns:
            True if authentication succeeded, False otherwise.
        """
        # Step 1: Send challenge
        self.server_nonce = secrets.token_bytes(self.NONCE_SIZE)
        challenge = AuthEnvelope(
            challenge=AuthChallenge(nonce=self.server_nonce)
        )
        await send_message(bytes(challenge))
        logger.debug("Sent auth challenge")

        # Step 2: Receive response
        response_bytes = await receive_message()
        envelope = AuthEnvelope().parse(response_bytes)

        # Check which field is set
        field_name, field_value = betterproto.which_one_of(envelope, "message")

        if field_name != "response":
            logger.warning(f"Expected AuthResponse, got {field_name}")
            await self._send_error(send_message, AuthErrorErrorCode.PROTOCOL_ERROR)
            return False

        response = field_value

        # Validate nonce length
        if len(response.nonce) != self.NONCE_SIZE:
            logger.warning(f"Invalid client nonce length: {len(response.nonce)}")
            await self._send_error(send_message, AuthErrorErrorCode.INVALID_NONCE)
            return False

        # Verify client HMAC (constant-time)
        if not verify_hmac(self.auth_key, self.server_nonce, response.hmac):
            logger.warning("Client HMAC verification failed")
            await self._send_error(send_message, AuthErrorErrorCode.INVALID_HMAC)
            return False

        self.client_nonce = response.nonce
        logger.debug("Client HMAC verified")

        # Step 3: Send verify
        server_hmac = compute_hmac(self.auth_key, self.client_nonce)
        verify = AuthEnvelope(
            verify=AuthVerify(hmac=server_hmac)
        )
        await send_message(bytes(verify))
        logger.debug("Sent auth verify")

        # Step 4: Send success
        success = AuthEnvelope(
            success=AuthSuccess(device_id=self.device_id)
        )
        await send_message(bytes(success))
        logger.info(f"Authentication successful for device {self.device_id}")

        self._authenticated = True
        return True

    async def _send_error(
        self,
        send_message: Callable[[bytes], Awaitable[None]],
        code: AuthErrorErrorCode,
    ) -> None:
        """Send error message.

        Args:
            send_message: Async function to send bytes.
            code: Error code to send.
        """
        error = AuthEnvelope(
            error=AuthError(code=code)
        )
        try:
            await send_message(bytes(error))
        except Exception:
            pass  # Best effort

    @property
    def is_authenticated(self) -> bool:
        """Return whether authentication was successful."""
        return self._authenticated
