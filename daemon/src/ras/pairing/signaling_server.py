"""HTTP signaling server for WebRTC connection setup.

Handles incoming signaling requests from mobile clients,
validates authentication (HMAC + timestamp), and returns
WebRTC SDP answers.
"""

import hmac as hmac_module
import logging
import time
from collections import defaultdict
from typing import TYPE_CHECKING, Dict, Optional

from aiohttp import web

from ras.crypto import compute_signaling_hmac
from ras.proto.ras import (
    SignalRequest,
    SignalResponse,
    SignalError,
    SignalErrorErrorCode,
)

if TYPE_CHECKING:
    from ras.pairing.pairing_manager import PairingManager

logger = logging.getLogger(__name__)


class RateLimiter:
    """Simple sliding window rate limiter."""

    def __init__(self, max_requests: int, window_seconds: int):
        """Initialize rate limiter.

        Args:
            max_requests: Maximum requests allowed in window.
            window_seconds: Window size in seconds.
        """
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self.requests: Dict[str, list] = defaultdict(list)

    def is_allowed(self, key: str) -> bool:
        """Check if request is allowed.

        Args:
            key: Rate limit key (e.g., session ID or IP).

        Returns:
            True if request is allowed, False if rate limited.
        """
        now = time.time()
        cutoff = now - self.window_seconds

        # Clean old requests
        self.requests[key] = [t for t in self.requests[key] if t > cutoff]

        if len(self.requests[key]) >= self.max_requests:
            return False

        self.requests[key].append(now)
        return True


class SignalingServer:
    """HTTP server for WebRTC signaling.

    Handles POST requests to /signal/{session_id} with HMAC authentication.
    Rate limits by session ID and IP address.
    """

    TIMESTAMP_TOLERANCE = 30  # seconds

    def __init__(self, pairing_manager: "PairingManager"):
        """Initialize signaling server.

        Args:
            pairing_manager: Pairing manager to handle signaling.
        """
        self.pairing_manager = pairing_manager
        self.session_limiter = RateLimiter(max_requests=10, window_seconds=60)
        self.ip_limiter = RateLimiter(max_requests=100, window_seconds=60)
        self.app = web.Application()
        self._setup_routes()

    def _setup_routes(self) -> None:
        """Set up HTTP routes."""
        self.app.router.add_get("/health", self._handle_health)
        self.app.router.add_post("/signal/{session_id}", self._handle_signal)

    async def _handle_health(self, request: web.Request) -> web.Response:
        """Handle health check request.

        Args:
            request: HTTP request.

        Returns:
            "OK" response.
        """
        return web.Response(text="OK")

    async def _handle_signal(self, request: web.Request) -> web.Response:
        """Handle signaling request.

        Args:
            request: HTTP request.

        Returns:
            HTTP response with SDP answer or error.
        """
        session_id = request.match_info["session_id"]
        client_ip = request.remote or "unknown"

        # Rate limiting
        if not self.ip_limiter.is_allowed(client_ip):
            return self._error_response(SignalErrorErrorCode.RATE_LIMITED, status=429)

        if not self.session_limiter.is_allowed(session_id):
            return self._error_response(SignalErrorErrorCode.RATE_LIMITED, status=429)

        # Get session (generic error if not found)
        session = self.pairing_manager.get_session(session_id)
        if session is None or session.is_expired():
            logger.warning(f"Invalid session: {session_id[:8]}...")
            return self._error_response(SignalErrorErrorCode.INVALID_SESSION)

        # Validate timestamp
        timestamp_str = request.headers.get("X-RAS-Timestamp")
        if not timestamp_str:
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        try:
            timestamp = int(timestamp_str)
        except ValueError:
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        now = int(time.time())
        if abs(now - timestamp) > self.TIMESTAMP_TOLERANCE:
            logger.warning(f"Timestamp out of range: {timestamp} vs {now}")
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        # Read body
        try:
            body = await request.read()
        except Exception:
            return self._error_response(SignalErrorErrorCode.INVALID_REQUEST)

        # Validate HMAC
        signature_hex = request.headers.get("X-RAS-Signature")
        if not signature_hex:
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        try:
            signature = bytes.fromhex(signature_hex)
        except ValueError:
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        expected_hmac = compute_signaling_hmac(
            session.auth_key,
            session_id,
            timestamp,
            body,
        )

        # Constant-time comparison
        if not hmac_module.compare_digest(signature, expected_hmac):
            logger.warning(f"HMAC verification failed for session {session_id[:8]}...")
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        # Parse protobuf
        try:
            signal_request = SignalRequest().parse(body)
        except Exception:
            return self._error_response(SignalErrorErrorCode.INVALID_REQUEST)

        # Process signaling
        try:
            sdp_answer = await self.pairing_manager.handle_signal(
                session_id=session_id,
                sdp_offer=signal_request.sdp_offer,
                device_id=signal_request.device_id,
                device_name=signal_request.device_name,
            )
        except Exception as e:
            logger.error(f"Signaling error: {e}")
            return self._error_response(SignalErrorErrorCode.INTERNAL_ERROR)

        # Success response
        response = SignalResponse(sdp_answer=sdp_answer)
        return web.Response(
            body=bytes(response),
            content_type="application/x-protobuf",
        )

    def _error_response(
        self, code: SignalErrorErrorCode, status: int = 400
    ) -> web.Response:
        """Create error response with generic message.

        Args:
            code: Error code.
            status: HTTP status code.

        Returns:
            HTTP response with error.
        """
        error = SignalError(code=code)
        return web.Response(
            status=status,
            body=bytes(error),
            content_type="application/x-protobuf",
        )

    async def start(self, host: str, port: int) -> web.AppRunner:
        """Start the signaling server.

        Args:
            host: Host to bind to.
            port: Port to bind to.

        Returns:
            App runner (for cleanup).
        """
        runner = web.AppRunner(self.app)
        await runner.setup()
        site = web.TCPSite(runner, host, port)
        await site.start()
        logger.info(f"Signaling server started on {host}:{port}")
        return runner
