"""Unified HTTP server for daemon.

Single aiohttp server handling all routes:
- /health - Health check
- /api/pair - Start pairing session (for CLI)
- /api/pair/{id} - Get/cancel pairing status (for CLI)
- /signal/{id} - WebRTC signaling for pairing (for phone)
- /reconnect/{id} - Reconnection for paired devices (for phone)
"""

import asyncio
import hmac as hmac_module
import logging
import secrets
import time
from collections import defaultdict
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Awaitable, Callable, Dict, Optional

from aiohttp import web

from ras.crypto import (
    compute_signaling_hmac,
    derive_key,
    derive_ntfy_topic,
    derive_session_id,
    generate_master_secret,
)
from ras.ip_provider import IpProvider
from ras.ntfy_signaling import NtfySignalingSubscriber
from ras.pairing.auth_handler import AuthHandler
from ras.peer import PeerConnection
from ras.proto.ras import (
    SignalError,
    SignalErrorErrorCode,
    SignalRequest,
    SignalResponse,
)

if TYPE_CHECKING:
    from ras.device_store import JsonDeviceStore, PairedDevice

logger = logging.getLogger(__name__)


# =============================================================================
# Rate Limiter
# =============================================================================

class RateLimiter:
    """Simple sliding window rate limiter."""

    def __init__(self, max_requests: int, window_seconds: int):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self.requests: Dict[str, list] = defaultdict(list)

    def is_allowed(self, key: str) -> bool:
        now = time.time()
        cutoff = now - self.window_seconds
        self.requests[key] = [t for t in self.requests[key] if t > cutoff]
        if len(self.requests[key]) >= self.max_requests:
            return False
        self.requests[key].append(now)
        return True


# =============================================================================
# Pairing Session
# =============================================================================

@dataclass
class PairingSession:
    """A pairing session waiting for phone to connect.

    Ownership Transfer Pattern:
    - This session owns `peer` until auth completes successfully
    - On success, `peer` is handed to `on_device_connected` callback
    - `peer_transferred` tracks whether ownership was transferred
    - After transfer, cleanup should NOT close the peer
    """

    session_id: str
    master_secret: bytes
    auth_key: bytes
    ntfy_topic: str
    created_at: float
    expires_at: float
    state: str = "pending"  # pending, signaling, authenticating, completed, failed, expired
    device_id: Optional[str] = None
    device_name: Optional[str] = None
    peer: Optional[PeerConnection] = None
    peer_transferred: bool = False  # True if peer ownership was handed off
    _auth_queue: Optional[asyncio.Queue] = field(default=None, repr=False)
    _ntfy_subscriber: Optional[NtfySignalingSubscriber] = field(default=None, repr=False)

    def is_expired(self) -> bool:
        return time.time() > self.expires_at


# =============================================================================
# Unified Server
# =============================================================================

class UnifiedServer:
    """Unified HTTP server for daemon.

    Handles pairing API, signaling, and reconnection in a single server.
    """

    TIMESTAMP_TOLERANCE = 30  # seconds

    def __init__(
        self,
        device_store: "JsonDeviceStore",
        ip_provider: IpProvider,
        stun_servers: list[str] | None = None,
        pairing_timeout: float = 300.0,  # 5 minutes
        max_pairing_sessions: int = 10,
        ntfy_server: str = "https://ntfy.sh",
        on_device_connected: Optional[
            Callable[[str, str, PeerConnection, bytes], Awaitable[None]]
        ] = None,
        on_pairing_complete: Optional[
            Callable[[str, str], Awaitable[None]]
        ] = None,
        tailscale_capabilities_provider: Optional[Callable[[], dict]] = None,
    ):
        """Initialize unified server.

        Args:
            device_store: Store for paired devices.
            ip_provider: Provider for discovering IP address for QR codes.
            stun_servers: STUN servers for WebRTC.
            pairing_timeout: How long pairing sessions are valid.
            max_pairing_sessions: Maximum concurrent pairing sessions.
            ntfy_server: ntfy server URL for signaling relay.
            on_device_connected: Callback when device connects (paired or new).
            on_pairing_complete: Callback when pairing completes.
            tailscale_capabilities_provider: Callable that returns Tailscale info dict.
        """
        self.device_store = device_store
        self.ip_provider = ip_provider
        self.stun_servers = stun_servers or []
        self.pairing_timeout = pairing_timeout
        self.max_pairing_sessions = max_pairing_sessions
        self.ntfy_server = ntfy_server
        self.tailscale_capabilities_provider = tailscale_capabilities_provider
        self._on_device_connected = on_device_connected
        self._on_pairing_complete = on_pairing_complete

        # Pairing sessions
        self._pairing_sessions: Dict[str, PairingSession] = {}

        # Rate limiters
        self._session_limiter = RateLimiter(max_requests=10, window_seconds=60)
        self._device_limiter = RateLimiter(max_requests=10, window_seconds=60)
        self._ip_limiter = RateLimiter(max_requests=100, window_seconds=60)

        # Pending connections (for reconnection)
        self._pending_reconnections: Dict[str, PeerConnection] = {}
        self._auth_queues: Dict[str, asyncio.Queue] = {}

        # Server state
        self.app = web.Application()
        self._setup_routes()
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None
        self._port: int = 0

    def _setup_routes(self) -> None:
        """Set up all HTTP routes."""
        # Health
        self.app.router.add_get("/health", self._handle_health)

        # Pairing API (for CLI)
        self.app.router.add_post("/api/pair", self._handle_start_pairing)
        self.app.router.add_get("/api/pair/{session_id}", self._handle_pairing_status)
        self.app.router.add_delete("/api/pair/{session_id}", self._handle_cancel_pairing)

        # Signaling (for phone during pairing)
        self.app.router.add_post("/signal/{session_id}", self._handle_signal)

        # Reconnection (for paired phone)
        self.app.router.add_post("/reconnect/{device_id}", self._handle_reconnect)

        # Capability exchange (for paired phone, before connection)
        self.app.router.add_post("/capabilities/{device_id}", self._handle_capabilities)

    # =========================================================================
    # Health
    # =========================================================================

    async def _handle_health(self, request: web.Request) -> web.Response:
        """Health check endpoint."""
        return web.Response(text="OK")

    # =========================================================================
    # Pairing API (for CLI)
    # =========================================================================

    async def _handle_start_pairing(self, request: web.Request) -> web.Response:
        """Start a new pairing session."""
        # Check limit
        if len(self._pairing_sessions) >= self.max_pairing_sessions:
            return web.json_response(
                {"error": "Too many pairing sessions"},
                status=429,
            )

        # Generate session - everything derived from master_secret
        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")
        ntfy_topic = derive_ntfy_topic(master_secret)
        now = time.time()

        session = PairingSession(
            session_id=session_id,
            master_secret=master_secret,
            auth_key=auth_key,
            ntfy_topic=ntfy_topic,
            created_at=now,
            expires_at=now + self.pairing_timeout,
        )
        self._pairing_sessions[session_id] = session

        # Start ntfy signaling subscriber for NAT traversal fallback
        await self._start_ntfy_signaling(session)

        # Start expiration timer
        asyncio.create_task(self._session_expiration_timer(session_id))

        logger.info(f"Pairing session started: {session_id[:8]}...")

        # Get IP from provider (fails fast if IP can't be determined)
        try:
            public_ip = await self.ip_provider.get_ip()
            logger.info(f"Using IP for pairing: {public_ip}")
        except Exception as e:
            logger.error(f"Failed to determine IP for pairing: {e}")
            # Clean up the session we just created
            await self._cleanup_session(session)
            del self._pairing_sessions[session_id]
            return web.json_response(
                {"error": f"Cannot determine IP address: {e}"},
                status=500,
            )

        # Get Tailscale info if available
        tailscale_ip = ""
        tailscale_port = 0
        if self.tailscale_capabilities_provider:
            ts_caps = self.tailscale_capabilities_provider()
            tailscale_ip = ts_caps.get("tailscale_ip", "")
            tailscale_port = ts_caps.get("tailscale_port", 0)

        # Get VPN IP separately (non-Tailscale VPN)
        vpn_ip = ""
        vpn_port = 0
        if hasattr(self.ip_provider, "get_all_ips"):
            all_ips = self.ip_provider.get_all_ips()
            detected_vpn_ip = all_ips.get("vpn")
            if detected_vpn_ip and detected_vpn_ip != public_ip:
                vpn_ip = detected_vpn_ip
                vpn_port = self._port
                logger.info(f"Including VPN IP: {vpn_ip}")

        # QR code contains ONLY the master_secret.
        # Everything else is derived from it:
        # - session_id: derived via HKDF
        # - ntfy_topic: derived via SHA256
        # - daemon IP/port: discovered via mDNS or ntfy DISCOVER
        return web.json_response({
            "session_id": session_id,
            "expires_at": session.expires_at,
            "qr_data": {
                "master_secret": master_secret.hex(),
            },
        })

    async def _handle_pairing_status(self, request: web.Request) -> web.Response:
        """Get pairing session status."""
        session_id = request.match_info["session_id"]

        session = self._pairing_sessions.get(session_id)
        if session is None:
            return web.json_response({"error": "Session not found"}, status=404)

        return web.json_response({
            "state": session.state,
            "device_id": session.device_id,
            "device_name": session.device_name,
            "expires_at": session.expires_at,
        })

    async def _handle_cancel_pairing(self, request: web.Request) -> web.Response:
        """Cancel a pairing session."""
        session_id = request.match_info["session_id"]

        session = self._pairing_sessions.pop(session_id, None)
        if session is None:
            return web.json_response({"error": "Session not found"}, status=404)

        # Cleanup
        await self._cleanup_session(session)

        logger.info(f"Pairing session cancelled: {session_id[:8]}...")

        return web.json_response({"status": "cancelled"})

    async def _session_expiration_timer(self, session_id: str) -> None:
        """Timer to expire pairing session."""
        session = self._pairing_sessions.get(session_id)
        if not session:
            return

        await asyncio.sleep(self.pairing_timeout)

        session = self._pairing_sessions.get(session_id)
        if session and session.state == "pending":
            session.state = "expired"
            # Cleanup ntfy subscriber
            await self._cleanup_session(session)
            logger.info(f"Pairing session expired: {session_id[:8]}...")
            # Keep for a bit so CLI can see "expired" status
            await asyncio.sleep(30)
            self._pairing_sessions.pop(session_id, None)

    # =========================================================================
    # Signaling (for phone during pairing)
    # =========================================================================

    async def _handle_signal(self, request: web.Request) -> web.Response:
        """Handle signaling request from phone during pairing."""
        session_id = request.match_info["session_id"]
        client_ip = request.remote or "unknown"

        # Rate limiting
        if not self._ip_limiter.is_allowed(client_ip):
            return self._error_response(SignalErrorErrorCode.RATE_LIMITED, status=429)

        if not self._session_limiter.is_allowed(session_id):
            return self._error_response(SignalErrorErrorCode.RATE_LIMITED, status=429)

        # Get session
        session = self._pairing_sessions.get(session_id)
        if session is None or session.is_expired():
            logger.warning(f"Invalid pairing session: {session_id[:8]}...")
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
            logger.warning(f"Timestamp out of range for session {session_id[:8]}...")
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

        if not hmac_module.compare_digest(signature, expected_hmac):
            logger.warning(f"HMAC verification failed for session {session_id[:8]}...")
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        # Parse protobuf
        try:
            signal_request = SignalRequest().parse(body)
        except Exception:
            return self._error_response(SignalErrorErrorCode.INVALID_REQUEST)

        # Validate SDP format (must start with version line)
        if not signal_request.sdp_offer or not signal_request.sdp_offer.startswith("v="):
            return self._error_response(SignalErrorErrorCode.INVALID_REQUEST)

        # Process signaling
        try:
            session.state = "signaling"
            session.device_id = signal_request.device_id
            session.device_name = signal_request.device_name

            # Create WebRTC peer with PairingSession ownership
            from ras.protocols import PeerOwnership
            peer = PeerConnection(
                stun_servers=self.stun_servers,
                owner=PeerOwnership.PairingSession,
            )
            session.peer = peer
            session._auth_queue = asyncio.Queue()

            # Set up message handler for auth
            async def on_message(message: bytes) -> None:
                if session._auth_queue:
                    await session._auth_queue.put(message)

            peer.on_message(on_message)

            # Accept offer
            sdp_answer = await peer.accept_offer(signal_request.sdp_offer)

            # Start auth flow in background
            asyncio.create_task(self._run_pairing_auth(session_id))

            # Return answer
            response = SignalResponse(sdp_answer=sdp_answer)
            return web.Response(
                body=bytes(response),
                content_type="application/x-protobuf",
            )

        except Exception as e:
            logger.error(f"Signaling error: {e}")
            session.state = "failed"
            return self._error_response(SignalErrorErrorCode.INTERNAL_ERROR)

    async def _run_pairing_auth(self, session_id: str) -> None:
        """Run authentication handshake for pairing."""
        session = self._pairing_sessions.get(session_id)
        if not session or not session.peer:
            return

        try:
            # Wait for WebRTC connection
            await session.peer.wait_connected(timeout=30.0)
            logger.info(f"Data channel open for pairing session {session_id[:8]}...")

            session.state = "authenticating"

            # Run auth handshake
            auth_handler = AuthHandler(session.auth_key, session.device_id or "unknown")

            async def send_message(data: bytes) -> None:
                await session.peer.send(data)

            async def receive_message() -> bytes:
                if not session._auth_queue:
                    raise RuntimeError("Auth queue not found")
                return await asyncio.wait_for(session._auth_queue.get(), timeout=10.0)

            success = await auth_handler.run_handshake(send_message, receive_message)

            if success:
                logger.info(f"Pairing completed: {session.device_name} ({session.device_id})")

                # Store device
                await self.device_store.add_device(
                    device_id=session.device_id or "",
                    device_name=session.device_name or "",
                    master_secret=session.master_secret,
                )

                # Notify callbacks
                if self._on_pairing_complete:
                    await self._on_pairing_complete(
                        session.device_id or "",
                        session.device_name or "",
                    )

                # Transfer ownership BEFORE calling on_device_connected
                # This ensures the handler's close_by_owner() is a no-op
                from ras.protocols import PeerOwnership
                session.peer.transfer_ownership(PeerOwnership.ConnectionManager)

                if self._on_device_connected:
                    await self._on_device_connected(
                        session.device_id or "",
                        session.device_name or "",
                        session.peer,
                        session.auth_key,
                    )

                # Hand off complete - mark as transferred and null out peer
                # IMPORTANT: Must happen BEFORE setting state to "completed"
                # because CLI polls for state and sends DELETE when complete,
                # which triggers _cleanup_session that would close the peer
                session.peer_transferred = True
                session.peer = None

                # Stop ntfy subscriber (no longer needed)
                # Handler's close_by_owner(SignalingHandler) will be a no-op
                # since ownership was transferred to ConnectionManager
                if session._ntfy_subscriber:
                    await session._ntfy_subscriber.close()
                    session._ntfy_subscriber = None

                # Set state to completed LAST - this signals CLI to send DELETE
                # By this point, peer is already nulled so cleanup won't affect it
                session.state = "completed"
            else:
                session.state = "failed"
                logger.warning(f"Pairing auth failed for session {session_id[:8]}...")
                await session.peer.close()

        except Exception as e:
            logger.error(f"Pairing auth error for {session_id[:8]}...: {e}")
            session.state = "failed"
            if session.peer:
                await session.peer.close()

    # =========================================================================
    # Reconnection (for paired phone)
    # =========================================================================

    async def _handle_reconnect(self, request: web.Request) -> web.Response:
        """Handle reconnection from already-paired device."""
        device_id = request.match_info["device_id"]
        client_ip = request.remote or "unknown"

        # Rate limiting
        if not self._ip_limiter.is_allowed(client_ip):
            return self._error_response(SignalErrorErrorCode.RATE_LIMITED, status=429)

        if not self._device_limiter.is_allowed(device_id):
            return self._error_response(SignalErrorErrorCode.RATE_LIMITED, status=429)

        # Look up device
        device = self.device_store.get(device_id)
        if device is None:
            logger.warning(f"Unknown device attempted reconnection: {device_id[:8]}...")
            return self._error_response(SignalErrorErrorCode.INVALID_SESSION, status=404)

        # Derive auth key
        auth_key = derive_key(device.master_secret, "auth")

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
            logger.warning(f"Timestamp out of range for device {device_id[:8]}...")
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
            auth_key,
            device_id,
            timestamp,
            body,
        )

        if not hmac_module.compare_digest(signature, expected_hmac):
            logger.warning(f"HMAC verification failed for device {device_id[:8]}...")
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        # Parse protobuf
        try:
            signal_request = SignalRequest().parse(body)
        except Exception:
            return self._error_response(SignalErrorErrorCode.INVALID_REQUEST)

        # Validate SDP format (must start with version line)
        if not signal_request.sdp_offer or not signal_request.sdp_offer.startswith("v="):
            return self._error_response(SignalErrorErrorCode.INVALID_REQUEST)

        # Create WebRTC peer
        try:
            peer = PeerConnection(stun_servers=self.stun_servers)
            self._pending_reconnections[device_id] = peer
            self._auth_queues[device_id] = asyncio.Queue()

            async def on_message(message: bytes) -> None:
                queue = self._auth_queues.get(device_id)
                if queue:
                    await queue.put(message)

            peer.on_message(on_message)

            # Accept offer
            sdp_answer = await peer.accept_offer(signal_request.sdp_offer)

            # Start auth flow in background
            asyncio.create_task(self._run_reconnect_auth(device, auth_key, peer))

            # Return answer
            response = SignalResponse(sdp_answer=sdp_answer)
            return web.Response(
                body=bytes(response),
                content_type="application/x-protobuf",
            )

        except Exception as e:
            logger.error(f"Reconnection signaling error: {e}")
            return self._error_response(SignalErrorErrorCode.INTERNAL_ERROR)

    async def _run_reconnect_auth(
        self,
        device: "PairedDevice",
        auth_key: bytes,
        peer: PeerConnection,
    ) -> None:
        """Run authentication handshake for reconnection."""
        device_id = device.device_id
        try:
            # Wait for WebRTC connection
            await peer.wait_connected(timeout=30.0)
            logger.info(f"Data channel open for reconnect {device_id[:8]}...")

            # Run auth handshake
            auth_handler = AuthHandler(auth_key, device_id)

            async def send_message(data: bytes) -> None:
                await peer.send(data)

            async def receive_message() -> bytes:
                queue = self._auth_queues.get(device_id)
                if not queue:
                    raise RuntimeError("Auth queue not found")
                return await asyncio.wait_for(queue.get(), timeout=10.0)

            success = await auth_handler.run_handshake(send_message, receive_message)

            if success:
                logger.info(f"Device reconnected: {device.name} ({device_id[:8]}...)")
                device.update_last_seen()
                await self.device_store.save()

                # Transfer ownership BEFORE calling on_device_connected
                from ras.protocols import PeerOwnership
                peer.transfer_ownership(PeerOwnership.ConnectionManager)

                if self._on_device_connected:
                    await self._on_device_connected(
                        device_id,
                        device.name,
                        peer,
                        auth_key,
                    )
            else:
                logger.warning(f"Reconnect auth failed for device {device_id[:8]}...")
                await peer.close()

        except Exception as e:
            logger.error(f"Reconnect auth error for {device_id[:8]}...: {e}")
            await peer.close()
        finally:
            self._pending_reconnections.pop(device_id, None)
            self._auth_queues.pop(device_id, None)

    # =========================================================================
    # Capability Exchange (for strategy selection before connection)
    # =========================================================================

    async def _handle_capabilities(self, request: web.Request) -> web.Response:
        """Handle capability exchange from paired device.

        This is a quick pre-flight request to discover what connection methods
        the daemon supports (Tailscale IP, WebRTC, etc.) before the phone
        decides which strategy to use.
        """
        device_id = request.match_info["device_id"]
        client_ip = request.remote or "unknown"

        # Rate limiting (same limits as reconnect)
        if not self._ip_limiter.is_allowed(client_ip):
            return self._error_response(SignalErrorErrorCode.RATE_LIMITED, status=429)

        if not self._device_limiter.is_allowed(device_id):
            return self._error_response(SignalErrorErrorCode.RATE_LIMITED, status=429)

        # Look up device
        device = self.device_store.get(device_id)
        if device is None:
            logger.warning(f"Unknown device capability request: {device_id[:8]}...")
            return self._error_response(SignalErrorErrorCode.INVALID_SESSION)

        # Derive auth key
        auth_key = derive_key(device.master_secret, "auth")

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
            auth_key,
            device_id,
            timestamp,
            body,
        )

        if not hmac_module.compare_digest(signature, expected_hmac):
            logger.warning(f"HMAC failed for capability request {device_id[:8]}...")
            return self._error_response(SignalErrorErrorCode.AUTHENTICATION_FAILED)

        # Parse client capabilities (optional - we respond with ours regardless)
        try:
            from ras.proto.ras import ConnectionCapabilities as ProtoCapabilities
            client_caps = ProtoCapabilities().parse(body)
            logger.debug(f"Client capabilities: tailscale={client_caps.tailscale_ip}")
        except Exception:
            # Client capabilities parsing is optional
            pass

        # Build our capabilities response
        our_caps = self._build_capabilities()

        logger.info(f"Capability exchange with {device_id[:8]}...: "
                    f"tailscale={our_caps.tailscale_ip}:{our_caps.tailscale_port}")

        return web.Response(
            body=bytes(our_caps),
            content_type="application/x-protobuf",
        )

    def _build_capabilities(self):
        """Build our current capabilities."""
        from ras.proto.ras import ConnectionCapabilities as ProtoCapabilities

        tailscale_ip = ""
        tailscale_port = 0
        if self.tailscale_capabilities_provider:
            ts_caps = self.tailscale_capabilities_provider()
            tailscale_ip = ts_caps.get("tailscale_ip", "")
            tailscale_port = ts_caps.get("tailscale_port", 0)

        return ProtoCapabilities(
            tailscale_ip=tailscale_ip,
            tailscale_port=tailscale_port,
            supports_webrtc=True,
            supports_turn=False,
            protocol_version=1,
        )

    # =========================================================================
    # Helpers
    # =========================================================================

    def _error_response(
        self, code: SignalErrorErrorCode, status: int = 400
    ) -> web.Response:
        """Create protobuf error response."""
        error = SignalError(code=code)
        return web.Response(
            status=status,
            body=bytes(error),
            content_type="application/x-protobuf",
        )

    async def _cleanup_session(self, session: PairingSession) -> None:
        """Clean up session resources.

        Args:
            session: Session to clean up.

        Note:
            Uses ownership-aware closing. If ownership was transferred to
            ConnectionManager, close_by_owner() will be a no-op.
        """
        from ras.protocols import PeerOwnership

        # Close ntfy subscriber - handler's close_by_owner() will be no-op
        # if ownership was transferred
        if session._ntfy_subscriber:
            await session._ntfy_subscriber.close()
            session._ntfy_subscriber = None

        # Close peer only if we still own it (ownership-aware)
        if session.peer and not session.peer_transferred:
            await session.peer.close_by_owner(PeerOwnership.PairingSession)
            session.peer = None

    # =========================================================================
    # ntfy Signaling (NAT traversal fallback)
    # =========================================================================

    async def _start_ntfy_signaling(self, session: PairingSession) -> None:
        """Start ntfy signaling subscriber for a pairing session.

        Creates subscriber that listens for OFFER messages via ntfy
        when direct HTTP signaling is not possible (NAT traversal).

        Args:
            session: Pairing session to start signaling for.
        """
        subscriber = NtfySignalingSubscriber(
            master_secret=session.master_secret,
            session_id=session.session_id,
            ntfy_topic=session.ntfy_topic,
            ntfy_server=self.ntfy_server,
            stun_servers=self.stun_servers,
        )

        # Set callback for when offer is received
        async def on_offer_received(
            device_id: str, device_name: str, peer: PeerConnection, is_reconnection: bool
        ) -> None:
            # Note: is_reconnection will always be False in pairing mode
            await self._on_ntfy_offer_received(session, device_id, device_name, peer)

        subscriber.on_offer_received = on_offer_received

        # Store and start subscriber
        session._ntfy_subscriber = subscriber
        await subscriber.start()

        logger.debug(f"Started ntfy signaling for session {session.session_id[:8]}...")

    async def _on_ntfy_offer_received(
        self,
        session: PairingSession,
        device_id: str,
        device_name: str,
        peer: PeerConnection,
    ) -> None:
        """Handle OFFER received via ntfy signaling.

        This is called when the phone successfully sends an offer via ntfy
        because direct HTTP signaling was not possible.

        Args:
            session: The pairing session.
            device_id: Device ID from the offer.
            device_name: Device name from the offer.
            peer: WebRTC peer connection created for this session.
        """
        # Check if session is still valid
        if session.is_expired() or session.state != "pending":
            logger.debug(f"Ignoring ntfy offer for invalid session {session.session_id[:8]}...")
            if peer:
                await peer.close()
            return

        # Update session state
        session.state = "signaling"
        session.device_id = device_id
        session.device_name = device_name
        session.peer = peer
        session._auth_queue = asyncio.Queue()

        # Set up message handler for auth
        async def on_message(message: bytes) -> None:
            if session._auth_queue:
                await session._auth_queue.put(message)

        peer.on_message(on_message)

        logger.info(f"Received ntfy offer for session {session.session_id[:8]}... from {device_name}")

        # Start auth flow in background
        asyncio.create_task(self._run_pairing_auth(session.session_id))

    # =========================================================================
    # Pairing completion (called by Daemon after external auth)
    # =========================================================================

    async def complete_pairing(
        self,
        session_id: str,
        device_id: str,
        device_name: str,
    ) -> None:
        """Mark pairing as complete (for testing/external auth)."""
        session = self._pairing_sessions.get(session_id)
        if session:
            session.state = "completed"
            session.device_id = device_id
            session.device_name = device_name

            # Store device
            await self.device_store.add_device(
                device_id=device_id,
                device_name=device_name,
                master_secret=session.master_secret,
            )

    def expire_pairing_session(self, session_id: str) -> None:
        """Manually expire a pairing session (for testing)."""
        session = self._pairing_sessions.get(session_id)
        if session:
            session.state = "expired"
            session.expires_at = 0

    # =========================================================================
    # Server lifecycle
    # =========================================================================

    async def start(self, host: str, port: int) -> web.AppRunner:
        """Start the server.

        Args:
            host: Host to bind to.
            port: Port to bind to (0 for random).

        Returns:
            App runner for cleanup.
        """
        self._runner = web.AppRunner(self.app)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, host, port)
        await self._site.start()

        # Get actual port
        if self._site._server and self._site._server.sockets:
            self._port = self._site._server.sockets[0].getsockname()[1]
        else:
            self._port = port

        logger.info(f"Unified server started on {host}:{self._port}")
        return self._runner

    def get_port(self) -> int:
        """Get the actual bound port."""
        return self._port

    async def close(self) -> None:
        """Close all connections and stop server."""
        # Close pending pairing sessions (including ntfy subscribers)
        for session in self._pairing_sessions.values():
            await self._cleanup_session(session)
        self._pairing_sessions.clear()

        # Close pending reconnections
        for peer in self._pending_reconnections.values():
            await peer.close()
        self._pending_reconnections.clear()
        self._auth_queues.clear()

        # Stop server
        if self._runner:
            await self._runner.cleanup()
            self._runner = None

        logger.info("Unified server closed")
