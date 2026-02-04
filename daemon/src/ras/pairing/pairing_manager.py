"""Pairing manager orchestrates the complete pairing flow.

Coordinates QR code generation, HTTP signaling, WebRTC connection
establishment, and mutual authentication handshake.
"""

import asyncio
import logging
import tempfile
import webbrowser
from typing import Awaitable, Callable, Dict, Optional, Protocol

from ras.crypto import derive_key, derive_ntfy_topic, generate_master_secret
from ras.pairing.auth_handler import AuthHandler
from ras.pairing.qr_generator import QrGenerator
from ras.pairing.session import PairingSession, PairingState
from ras.pairing.signaling_server import SignalingServer
from ras.peer import PeerConnection

logger = logging.getLogger(__name__)


class StunClient(Protocol):
    """Protocol for STUN client."""

    async def get_public_ip(self) -> str:
        """Get public IP address via STUN."""
        ...


class DeviceStore(Protocol):
    """Protocol for device storage."""

    async def add_device(
        self,
        device_id: str,
        device_name: str,
        master_secret: bytes,
    ) -> None:
        """Store a newly paired device."""
        ...


class PairingManager:
    """Orchestrates the complete pairing flow.

    Manages pairing sessions, signaling server, and WebRTC connections
    for establishing secure connections with mobile clients.
    """

    def __init__(
        self,
        stun_client: StunClient,
        device_store: DeviceStore,
        host: str = "0.0.0.0",
        port: int = 8821,
    ):
        """Initialize pairing manager.

        Args:
            stun_client: Client for getting public IP via STUN.
            device_store: Storage for paired devices.
            host: Host to bind signaling server to.
            port: Port for signaling server.
        """
        self.stun_client = stun_client
        self.device_store = device_store
        self.host = host
        self.port = port

        self.sessions: Dict[str, PairingSession] = {}
        self.signaling_server: Optional[SignalingServer] = None
        self._server_runner = None
        self._auth_message_queues: Dict[str, asyncio.Queue] = {}
        self._on_pairing_complete: Optional[Callable[[str, str], Awaitable[None]]] = (
            None
        )

    async def start_pairing(
        self,
        display_mode: str = "terminal",
        output_path: Optional[str] = None,
    ) -> PairingSession:
        """Start a new pairing session.

        Args:
            display_mode: How to display QR code ('terminal', 'browser', 'file').
            output_path: Path for 'file' mode.

        Returns:
            The created PairingSession.
        """
        # Get public IP
        public_ip = await self.stun_client.get_public_ip()

        # Generate secrets
        master_secret = generate_master_secret()
        auth_key = derive_key(master_secret, "auth")
        ntfy_topic = derive_ntfy_topic(master_secret)

        # Create session
        session = PairingSession.create(master_secret, auth_key)
        session.transition_to(PairingState.QR_DISPLAYED)
        self.sessions[session.session_id] = session

        # Start signaling server if not running
        if self.signaling_server is None:
            self.signaling_server = SignalingServer(self)
            self._server_runner = await self.signaling_server.start(
                self.host, self.port
            )

        # Generate and display QR code
        # Only master_secret in QR - everything else derived from it
        qr = QrGenerator(master_secret=master_secret)

        if display_mode == "terminal":
            print(qr.to_terminal())
            print(f"\nSession: {session.session_id[:8]}...")
            print("Waiting for phone to scan... (expires in 5 minutes)")
        elif display_mode == "browser":
            html = qr.to_html()
            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".html", delete=False
            ) as f:
                f.write(html)
                webbrowser.open(f"file://{f.name}")
        elif display_mode == "file":
            path = output_path or "pairing_qr.png"
            qr.to_png(path)
            print(f"QR code saved to: {path}")

        # Start expiration timer
        asyncio.create_task(self._session_timeout(session.session_id))

        logger.info(f"Pairing session started: {session.session_id[:8]}...")
        return session

    async def _session_timeout(self, session_id: str) -> None:
        """Handle session expiration."""
        await asyncio.sleep(PairingSession.QR_TIMEOUT)

        session = self.sessions.get(session_id)
        if session and session.state == PairingState.QR_DISPLAYED:
            logger.info(f"Pairing session expired: {session_id[:8]}...")
            await self._cleanup_session(session_id)

    def get_session(self, session_id: str) -> Optional[PairingSession]:
        """Get a pairing session by ID."""
        return self.sessions.get(session_id)

    async def handle_signal(
        self,
        session_id: str,
        sdp_offer: str,
        device_id: str,
        device_name: str,
    ) -> str:
        """Handle incoming signaling request.

        Args:
            session_id: Pairing session ID.
            sdp_offer: SDP offer from client.
            device_id: Client's device ID.
            device_name: Client's device name.

        Returns:
            SDP answer.

        Raises:
            ValueError: If session not found.
        """
        session = self.sessions.get(session_id)
        if not session:
            raise ValueError("Session not found")

        session.device_id = device_id
        session.device_name = device_name
        session.transition_to(PairingState.SIGNALING)

        # Create WebRTC connection and generate answer
        connection = PeerConnection()

        # Create message queue for authentication
        self._auth_message_queues[session_id] = asyncio.Queue()

        # Set up message handler
        async def on_message(message: bytes) -> None:
            await self._on_channel_message(session_id, message)

        connection.on_message(on_message)

        # Accept offer and get answer
        sdp_answer = await connection.accept_offer(sdp_offer)
        session.peer_connection = connection

        session.transition_to(PairingState.CONNECTING)

        # Start authentication flow in background
        asyncio.create_task(self._wait_and_authenticate(session_id))

        return sdp_answer

    async def _wait_and_authenticate(self, session_id: str) -> None:
        """Wait for connection and run authentication."""
        session = self.sessions.get(session_id)
        if not session or not session.peer_connection:
            return

        try:
            # Wait for connection
            await session.peer_connection.wait_connected(timeout=30.0)
            logger.info(f"Data channel open for session {session_id[:8]}...")

            session.transition_to(PairingState.AUTHENTICATING)

            # Run authentication
            await self._run_authentication(session_id)

        except Exception as e:
            logger.error(f"Connection/auth error for {session_id[:8]}...: {e}")
            await self._cleanup_session(session_id)

    async def _run_authentication(self, session_id: str) -> None:
        """Run the authentication handshake."""
        session = self.sessions.get(session_id)
        if not session:
            return

        # Use daemon's device ID (not phone's) - this is what the phone stores
        from ras.system import get_daemon_device_id
        auth_handler = AuthHandler(session.auth_key, get_daemon_device_id())

        async def send_message(data: bytes) -> None:
            await session.peer_connection.send(data)

        async def receive_message() -> bytes:
            queue = self._auth_message_queues.get(session_id)
            if not queue:
                raise RuntimeError("Message queue not found")
            return await queue.get()

        success = await auth_handler.run_handshake(send_message, receive_message)

        if success:
            session.transition_to(PairingState.AUTHENTICATED)
            await self._finalize_pairing(session_id)
        else:
            logger.warning(f"Authentication failed for {session_id[:8]}...")
            await self._cleanup_session(session_id)

    async def _on_channel_message(self, session_id: str, message: bytes) -> None:
        """Handle incoming data channel message."""
        queue = self._auth_message_queues.get(session_id)
        if queue:
            await queue.put(message)

    async def _finalize_pairing(self, session_id: str) -> None:
        """Complete the pairing process."""
        session = self.sessions.get(session_id)
        if not session:
            return

        # Store device
        if session.device_id and session.device_name:
            await self.device_store.add_device(
                device_id=session.device_id,
                device_name=session.device_name,
                master_secret=session.master_secret,
            )

        logger.info(f"Device paired: {session.device_name} ({session.device_id})")

        # Call completion callback if set
        if self._on_pairing_complete:
            await self._on_pairing_complete(
                session.device_id or "", session.device_name or ""
            )

        # Cleanup session (but keep connection for use)
        if session_id in self._auth_message_queues:
            del self._auth_message_queues[session_id]
        del self.sessions[session_id]

    async def _cleanup_session(self, session_id: str) -> None:
        """Cleanup failed/expired session."""
        session = self.sessions.pop(session_id, None)
        if not session:
            return

        if session.state not in (PairingState.FAILED, PairingState.AUTHENTICATED):
            session.transition_to(PairingState.FAILED)

        if session.peer_connection:
            await session.peer_connection.close()

        # Clean up message queue
        if session_id in self._auth_message_queues:
            del self._auth_message_queues[session_id]

        # Zero sensitive data
        session.master_secret = b"\x00" * 32
        session.auth_key = b"\x00" * 32

        logger.info(f"Pairing session cleaned up: {session_id[:8]}...")

    def on_pairing_complete(
        self, callback: Callable[[str, str], Awaitable[None]]
    ) -> None:
        """Register callback for successful pairing.

        Args:
            callback: Async function called with (device_id, device_name).
        """
        self._on_pairing_complete = callback

    async def stop(self) -> None:
        """Stop the pairing manager and cleanup."""
        # Cleanup all sessions
        for session_id in list(self.sessions.keys()):
            await self._cleanup_session(session_id)

        # Stop signaling server
        if self._server_runner:
            await self._server_runner.cleanup()
            self._server_runner = None
            self.signaling_server = None

        logger.info("Pairing manager stopped")
