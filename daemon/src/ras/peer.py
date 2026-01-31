"""WebRTC peer connection wrapper."""

import asyncio
import logging
import time
from typing import Awaitable, Callable

from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription

from ras.protocols import PeerOwnership, PeerState
from ras.sdp_validator import validate_sdp
from ras.vpn_candidate_injector import inject_vpn_candidates

logger = logging.getLogger(__name__)


class ConnectionTimer:
    """Track and log connection timing phases for debugging.

    Usage:
        timer = ConnectionTimer("device-123")
        timer.mark("offer_received")
        # ... do work ...
        timer.mark("ice_gathering_complete")
        timer.log_summary()
    """

    def __init__(self, label: str = "connection"):
        self._label = label
        self._start = time.perf_counter()
        self._marks: list[tuple[str, float]] = []

    def mark(self, phase: str) -> float:
        """Record a timing mark and return elapsed ms since start."""
        elapsed_ms = (time.perf_counter() - self._start) * 1000
        self._marks.append((phase, elapsed_ms))
        return elapsed_ms

    def log_mark(self, phase: str) -> None:
        """Record and log a timing mark."""
        elapsed_ms = self.mark(phase)
        logger.info(f"[TIMING] {self._label}: {phase} @ {elapsed_ms:.1f}ms")

    def log_summary(self) -> None:
        """Log a summary of all timing marks."""
        if not self._marks:
            return
        summary = " | ".join(f"{phase}={ms:.0f}ms" for phase, ms in self._marks)
        total = self._marks[-1][1] if self._marks else 0
        logger.info(f"[TIMING] {self._label} summary: {summary} (total={total:.0f}ms)")


class PeerConnectionError(Exception):
    """Peer connection error."""

    pass


class PeerConnection:
    """WebRTC peer connection wrapper with data channel support."""

    DEFAULT_STUN_SERVERS = [
        "stun:stun.l.google.com:19302",
        "stun:stun.cloudflare.com:3478",
    ]

    def __init__(
        self,
        stun_servers: list[str] | None = None,
        pc_factory: Callable[[RTCConfiguration], RTCPeerConnection] | None = None,
        owner: PeerOwnership = PeerOwnership.SignalingHandler,
    ):
        """Initialize peer connection.

        Args:
            stun_servers: List of STUN server URLs.
            pc_factory: Factory to create RTCPeerConnection (for testing).
            owner: Initial owner of this connection.
        """
        self.stun_servers = stun_servers or self.DEFAULT_STUN_SERVERS
        self._pc_factory = pc_factory or self._default_pc_factory
        self._state = PeerState.NEW
        self._owner = owner
        self._pc: RTCPeerConnection | None = None
        self._channel = None
        self._message_callback: Callable[[bytes], Awaitable[None]] | None = None
        self._close_callback: Callable[[], None] | None = None
        self._connected_event = asyncio.Event()
        self._channel_open_event = asyncio.Event()

        # Timing tracker for connection profiling
        self._timer: ConnectionTimer | None = None

    def _default_pc_factory(self, config: RTCConfiguration) -> RTCPeerConnection:
        """Create default RTCPeerConnection."""
        return RTCPeerConnection(configuration=config)

    @property
    def state(self) -> PeerState:
        """Current connection state."""
        return self._state

    def _create_pc(self) -> None:
        """Create the underlying RTCPeerConnection."""
        # Start timing tracker
        self._timer = ConnectionTimer("webrtc")
        self._timer.log_mark("pc_create_start")

        if self.stun_servers:
            config = RTCConfiguration(iceServers=[RTCIceServer(urls=self.stun_servers)])
        else:
            config = RTCConfiguration(iceServers=[])
        self._pc = self._pc_factory(config)

        timer = self._timer  # Capture for closures

        @self._pc.on("connectionstatechange")
        async def on_connection_state_change():
            state = self._pc.connectionState
            if timer:
                timer.log_mark(f"conn_{state}")
            else:
                logger.info(f"Connection state: {state}")

            if state == "connected":
                self._state = PeerState.CONNECTED
                self._connected_event.set()
            elif state == "failed":
                self._state = PeerState.FAILED
                if self._close_callback:
                    self._close_callback()
            elif state == "disconnected":
                self._state = PeerState.DISCONNECTED
            elif state == "closed":
                self._state = PeerState.CLOSED
                if self._close_callback:
                    self._close_callback()

        @self._pc.on("iceconnectionstatechange")
        async def on_ice_connection_state_change():
            state = self._pc.iceConnectionState
            if timer:
                timer.log_mark(f"ice_{state}")
            else:
                logger.info(f"ICE connection state: {state}")

            # Log selected candidate pair when ICE completes
            if state == "completed" and self._pc.sctp:
                try:
                    transport = self._pc.sctp.transport
                    if transport and transport.transport:
                        dtls = transport.transport
                        if hasattr(dtls, "_ice") and dtls._ice:
                            ice = dtls._ice
                            # Try to get the selected candidate pair
                            if hasattr(ice, "_nominated"):
                                pair = ice._nominated.get(1)  # Component 1 = RTP
                                if pair:
                                    local = pair.local_candidate
                                    remote = pair.remote_candidate
                                    logger.info(
                                        f"[ICE] Selected pair: "
                                        f"local={local.type}({local.host}:{local.port}) <-> "
                                        f"remote={remote.type}({remote.host}:{remote.port})"
                                    )
                except Exception as e:
                    logger.debug(f"Could not get ICE candidate pair: {e}")

        @self._pc.on("icegatheringstatechange")
        async def on_ice_gathering_state_change():
            state = self._pc.iceGatheringState
            if timer:
                timer.log_mark(f"gather_{state}")
            else:
                logger.info(f"ICE gathering state: {state}")

    def _setup_channel(self, channel) -> None:
        """Set up data channel handlers."""
        self._channel = channel
        timer = self._timer  # Capture for closures

        @channel.on("open")
        def on_open():
            if timer:
                timer.log_mark("channel_open")
            else:
                logger.info("Data channel open")
            self._channel_open_event.set()

        # If channel is already open (can happen for answerer), set event immediately
        if channel.readyState == "open":
            if timer:
                timer.log_mark("channel_already_open")
            else:
                logger.info("Data channel already open")
            self._channel_open_event.set()

        @channel.on("message")
        async def on_message(message):
            if self._message_callback:
                if isinstance(message, str):
                    message = message.encode()
                await self._message_callback(message)

        @channel.on("close")
        def on_channel_close():
            logger.info("Data channel closed")
            if self._close_callback:
                self._close_callback()

    async def create_offer(self) -> str:
        """Create SDP offer for outgoing connection.

        Returns:
            Raw SDP offer string (starts with "v=0").
        """
        self._create_pc()

        # Create negotiated data channel - must match accept_offer() and Android config
        # Both sides must independently create channel with same id for negotiated=True
        self._channel = self._pc.createDataChannel(
            "ras-control",
            negotiated=True,
            id=0,
            ordered=True,
        )
        self._setup_channel(self._channel)

        offer = await self._pc.createOffer()
        await self._pc.setLocalDescription(offer)

        # Wait for ICE gathering to complete
        await self._wait_ice_gathering()

        self._state = PeerState.CONNECTING
        return self._pc.localDescription.sdp

    async def accept_offer(self, offer_sdp: str) -> str:
        """Accept SDP offer and return answer.

        Args:
            offer_sdp: Raw SDP offer string (starts with "v=0").

        Returns:
            Raw SDP answer string (starts with "v=0").
        """
        self._create_pc()

        # Create negotiated data channel - must match Android's config
        # Both sides must independently create channel with same id for negotiated=True
        self._channel = self._pc.createDataChannel(
            "ras-control",
            negotiated=True,
            id=0,
            ordered=True,
        )
        self._setup_channel(self._channel)

        if self._timer:
            self._timer.log_mark("channel_created")

        # Validate remote offer contains candidates
        validation = validate_sdp(offer_sdp, "Remote offer")
        if not validation.is_valid:
            logger.warning(f"Remote offer validation issues: {validation.errors}")
        logger.info(
            f"Remote offer: {validation.candidate_count} candidates "
            f"(host={validation.has_host}, srflx={validation.has_srflx}, relay={validation.has_relay})"
        )
        # Log candidate IPs for debugging
        for line in offer_sdp.split("\n"):
            if line.startswith("a=candidate:"):
                logger.info(f"  Candidate: {line}")

        offer = RTCSessionDescription(sdp=offer_sdp, type="offer")
        await self._pc.setRemoteDescription(offer)

        if self._timer:
            self._timer.log_mark("remote_desc_set")

        answer = await self._pc.createAnswer()
        await self._pc.setLocalDescription(answer)

        if self._timer:
            self._timer.log_mark("local_desc_set")

        # Wait for ICE gathering to complete
        await self._wait_ice_gathering()

        if self._timer:
            self._timer.log_mark("ice_gathering_done")

        # Get answer SDP and inject VPN candidates (Tailscale, etc.)
        answer_sdp = self._pc.localDescription.sdp
        answer_sdp = inject_vpn_candidates(answer_sdp)

        # Log answer candidates for debugging
        logger.info("Local answer candidates:")
        for line in answer_sdp.split("\n"):
            if line.startswith("a=candidate:"):
                logger.info(f"  {line}")

        self._state = PeerState.CONNECTING

        if self._timer:
            self._timer.log_mark("answer_ready")

        return answer_sdp

    async def set_remote_description(self, sdp: str, sdp_type: str = "answer") -> None:
        """Set remote SDP description.

        Args:
            sdp: Raw SDP string (starts with "v=0").
            sdp_type: SDP type ("offer" or "answer"). Defaults to "answer".
        """
        desc = RTCSessionDescription(sdp=sdp, type=sdp_type)
        await self._pc.setRemoteDescription(desc)

    async def _wait_ice_gathering(self, timeout: float = 10.0) -> None:
        """Wait for ICE gathering to complete."""
        if self._pc.iceGatheringState == "complete":
            return

        done = asyncio.Event()

        @self._pc.on("icegatheringstatechange")
        def on_ice_gathering_state_change():
            if self._pc.iceGatheringState == "complete":
                done.set()

        try:
            await asyncio.wait_for(done.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            logger.warning("ICE gathering timeout, proceeding anyway")

    async def wait_connected(self, timeout: float = 30.0) -> None:
        """Wait for connection and data channel to be established.

        Args:
            timeout: Timeout in seconds.

        Raises:
            PeerConnectionError: If connection times out.
        """
        try:
            await asyncio.wait_for(
                asyncio.gather(
                    self._connected_event.wait(),
                    self._channel_open_event.wait(),
                ),
                timeout=timeout,
            )
            # Log timing summary on successful connection
            if self._timer:
                self._timer.log_mark("fully_connected")
                self._timer.log_summary()
        except asyncio.TimeoutError:
            if self._timer:
                self._timer.log_mark("timeout")
                self._timer.log_summary()
            raise PeerConnectionError("Connection timeout")

    async def send(self, data: bytes) -> None:
        """Send data over the data channel.

        Args:
            data: Data to send.

        Raises:
            PeerConnectionError: If not connected or channel not open.
        """
        if self._state != PeerState.CONNECTED:
            raise PeerConnectionError(f"Cannot send in state {self._state}")
        if not self._channel or self._channel.readyState != "open":
            raise PeerConnectionError("Data channel not open")
        self._channel.send(data)

    def on_message(self, callback: Callable[[bytes], Awaitable[None]]) -> None:
        """Register callback for incoming messages.

        Args:
            callback: Async function called with message bytes.
        """
        self._message_callback = callback

    def on_close(self, callback: Callable[[], None]) -> None:
        """Register callback for connection close.

        Args:
            callback: Function called when connection closes.
        """
        self._close_callback = callback

    @property
    def owner(self) -> PeerOwnership:
        """Current owner of this connection."""
        return self._owner

    def transfer_ownership(self, new_owner: PeerOwnership) -> bool:
        """Transfer ownership of this connection.

        Args:
            new_owner: The new owner.

        Returns:
            True if transfer succeeded, False if already disposed.
        """
        if self._owner == PeerOwnership.Disposed:
            logger.warning("transfer_ownership() called but connection is disposed")
            return False
        if self._state == PeerState.CLOSED:
            logger.warning("transfer_ownership() called but connection is closed")
            return False
        old_owner = self._owner
        self._owner = new_owner
        logger.debug(f"Ownership transferred from {old_owner.value} to {new_owner.value}")
        return True

    async def close_by_owner(self, caller: PeerOwnership) -> bool:
        """Close the connection, but only if caller is the current owner.

        This prevents accidental closes during handoff where cleanup code
        might try to close a connection that has been transferred.

        Args:
            caller: The owner attempting to close.

        Returns:
            True if closed, False if caller is not owner.
        """
        if self._owner != caller:
            logger.warning(
                f"close_by_owner() called by {caller.value} "
                f"but owner is {self._owner.value} - ignoring"
            )
            return False
        self._owner = PeerOwnership.Disposed
        await self._do_close()
        return True

    async def _do_close(self) -> None:
        """Internal close implementation."""
        if self._state == PeerState.CLOSED:
            return
        if self._pc:
            await self._pc.close()
            self._pc = None
        self._state = PeerState.CLOSED
        logger.info("Peer connection closed")

    async def close(self) -> None:
        """Close the peer connection.

        This method is idempotent - calling it multiple times is safe.

        Note: Prefer close_by_owner() when ownership tracking is in use,
        as it prevents accidental closes after ownership transfer.
        """
        self._owner = PeerOwnership.Disposed
        await self._do_close()

    async def __aenter__(self):
        """Enter async context."""
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        """Exit async context."""
        await self.close()
