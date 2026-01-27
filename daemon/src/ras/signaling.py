"""HTTP signaling server for WebRTC connection setup."""

import asyncio
import logging
import secrets
from typing import Awaitable, Callable

from aiohttp import web

from ras.peer import PeerConnection

logger = logging.getLogger(__name__)


class SignalingServer:
    """HTTP server for WebRTC signaling (SDP exchange)."""

    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = 8821,
        stun_servers: list[str] | None = None,
        signaling_timeout: float = 30.0,
        connection_timeout: float = 30.0,
        peer_factory: Callable[[], PeerConnection] | None = None,
    ):
        """Initialize signaling server.

        Args:
            host: Host to bind to.
            port: Port to bind to.
            stun_servers: STUN servers to use for peer connections.
            signaling_timeout: Timeout for signaling operations.
            connection_timeout: Timeout waiting for peer connection.
            peer_factory: Factory to create PeerConnection (for testing).
        """
        self.host = host
        self.port = port
        self.stun_servers = stun_servers
        self.signaling_timeout = signaling_timeout
        self.connection_timeout = connection_timeout
        self._peer_factory = peer_factory or self._default_peer_factory

        self._pending_sessions: dict[str, asyncio.Future] = {}
        self._peers: dict[str, PeerConnection] = {}
        self._on_connected: Callable[[str, PeerConnection], Awaitable[None]] | None = None

        self._app = web.Application()
        self._setup_routes()
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None

    def _default_peer_factory(self) -> PeerConnection:
        """Create default PeerConnection."""
        return PeerConnection(stun_servers=self.stun_servers)

    @property
    def app(self) -> web.Application:
        """Get the aiohttp application for testing."""
        return self._app

    def _setup_routes(self) -> None:
        """Set up HTTP routes."""
        self._app.router.add_get("/health", self._health)
        self._app.router.add_post("/signal/{session_id}", self._signal)

    async def _health(self, request: web.Request) -> web.Response:
        """Health check endpoint."""
        return web.json_response({"status": "ok"})

    async def _signal(self, request: web.Request) -> web.Response:
        """Handle signaling request (SDP exchange)."""
        session_id = request.match_info["session_id"]

        if session_id not in self._pending_sessions:
            return web.json_response({"error": "Invalid session"}, status=404)

        try:
            body = await request.json()
            offer = body.get("offer")

            if not offer:
                return web.json_response({"error": "Missing offer"}, status=400)

            # Create peer and generate answer
            peer = self._peer_factory()

            try:
                answer = await asyncio.wait_for(
                    peer.accept_offer(offer), timeout=self.signaling_timeout
                )
            except asyncio.TimeoutError:
                await peer.close()
                return web.json_response({"error": "Signaling timeout"}, status=504)

            self._peers[session_id] = peer
            del self._pending_sessions[session_id]

            # Start waiting for connection in background
            asyncio.create_task(self._wait_for_connection(session_id, peer))

            return web.json_response({"answer": answer})

        except Exception as e:
            logger.exception(f"Signaling error: {e}")
            return web.json_response({"error": str(e)}, status=500)

    async def _wait_for_connection(self, session_id: str, peer: PeerConnection) -> None:
        """Wait for peer to connect and notify callback."""
        try:
            await peer.wait_connected(timeout=self.connection_timeout)
            if self._on_connected:
                await self._on_connected(session_id, peer)
        except Exception as e:
            logger.warning(f"Connection failed for session {session_id}: {e}")
            await peer.close()
            if session_id in self._peers:
                del self._peers[session_id]

    def create_session(self) -> str:
        """Create a new pending session for connection.

        Returns:
            Session ID.
        """
        session_id = secrets.token_hex(8)
        self._pending_sessions[session_id] = asyncio.Future()
        return session_id

    def is_session_valid(self, session_id: str) -> bool:
        """Check if a session is valid (pending).

        Args:
            session_id: Session ID to check.

        Returns:
            True if session is pending.
        """
        return session_id in self._pending_sessions

    def on_connected(self, callback: Callable[[str, PeerConnection], Awaitable[None]]) -> None:
        """Register callback for when a peer connects.

        Args:
            callback: Async function called with (session_id, peer).
        """
        self._on_connected = callback

    @property
    def actual_port(self) -> int:
        """Get the actual bound port (useful when port=0)."""
        if self._site and self._site._server:
            sockets = self._site._server.sockets
            if sockets:
                return sockets[0].getsockname()[1]
        return self.port

    async def start(self) -> None:
        """Start the signaling server."""
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, self.host, self.port)
        await self._site.start()
        logger.info(f"Signaling server started on {self.host}:{self.actual_port}")

    async def close(self) -> None:
        """Close all connections and stop server."""
        for peer in self._peers.values():
            await peer.close()
        self._peers.clear()
        self._pending_sessions.clear()

        if self._runner:
            await self._runner.cleanup()
            self._runner = None

        logger.info("Signaling server closed")
