"""WebRTC peer connection wrapper."""

import asyncio
import json
import logging
from typing import Awaitable, Callable

from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription

from ras.protocols import PeerState

logger = logging.getLogger(__name__)


class PeerConnectionError(Exception):
    """Peer connection error."""

    pass


def _sdp_to_string(desc: RTCSessionDescription) -> str:
    """Serialize SDP to JSON string."""
    return json.dumps({"type": desc.type, "sdp": desc.sdp})


def _sdp_from_string(s: str) -> RTCSessionDescription:
    """Deserialize SDP from JSON string."""
    d = json.loads(s)
    return RTCSessionDescription(sdp=d["sdp"], type=d["type"])


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
    ):
        """Initialize peer connection.

        Args:
            stun_servers: List of STUN server URLs.
            pc_factory: Factory to create RTCPeerConnection (for testing).
        """
        self.stun_servers = stun_servers or self.DEFAULT_STUN_SERVERS
        self._pc_factory = pc_factory or self._default_pc_factory
        self._state = PeerState.NEW
        self._pc: RTCPeerConnection | None = None
        self._channel = None
        self._message_callback: Callable[[bytes], Awaitable[None]] | None = None
        self._connected_event = asyncio.Event()
        self._channel_open_event = asyncio.Event()

    def _default_pc_factory(self, config: RTCConfiguration) -> RTCPeerConnection:
        """Create default RTCPeerConnection."""
        return RTCPeerConnection(configuration=config)

    @property
    def state(self) -> PeerState:
        """Current connection state."""
        return self._state

    def _create_pc(self) -> None:
        """Create the underlying RTCPeerConnection."""
        if self.stun_servers:
            config = RTCConfiguration(iceServers=[RTCIceServer(urls=self.stun_servers)])
        else:
            config = RTCConfiguration(iceServers=[])
        self._pc = self._pc_factory(config)

        @self._pc.on("connectionstatechange")
        async def on_connection_state_change():
            state = self._pc.connectionState
            logger.info(f"Connection state: {state}")

            if state == "connected":
                self._state = PeerState.CONNECTED
                self._connected_event.set()
            elif state == "failed":
                self._state = PeerState.FAILED
            elif state == "disconnected":
                self._state = PeerState.DISCONNECTED

    def _setup_channel(self, channel) -> None:
        """Set up data channel handlers."""
        self._channel = channel

        @channel.on("open")
        def on_open():
            logger.info("Data channel open")
            self._channel_open_event.set()

        # If channel is already open (can happen for answerer), set event immediately
        if channel.readyState == "open":
            logger.info("Data channel already open")
            self._channel_open_event.set()

        @channel.on("message")
        async def on_message(message):
            if self._message_callback:
                if isinstance(message, str):
                    message = message.encode()
                await self._message_callback(message)

        @channel.on("close")
        def on_close():
            logger.info("Data channel closed")

    async def create_offer(self) -> str:
        """Create SDP offer for outgoing connection.

        Returns:
            SDP offer as JSON string.
        """
        self._create_pc()

        self._channel = self._pc.createDataChannel("ras")
        self._setup_channel(self._channel)

        offer = await self._pc.createOffer()
        await self._pc.setLocalDescription(offer)

        # Wait for ICE gathering to complete
        await self._wait_ice_gathering()

        self._state = PeerState.CONNECTING
        return _sdp_to_string(self._pc.localDescription)

    async def accept_offer(self, offer_sdp: str) -> str:
        """Accept SDP offer and return answer.

        Args:
            offer_sdp: SDP offer as JSON string.

        Returns:
            SDP answer as JSON string.
        """
        self._create_pc()

        @self._pc.on("datachannel")
        def on_datachannel(channel):
            self._setup_channel(channel)

        offer = _sdp_from_string(offer_sdp)
        await self._pc.setRemoteDescription(offer)

        answer = await self._pc.createAnswer()
        await self._pc.setLocalDescription(answer)

        # Wait for ICE gathering to complete
        await self._wait_ice_gathering()

        self._state = PeerState.CONNECTING
        return _sdp_to_string(self._pc.localDescription)

    async def set_remote_description(self, sdp: str) -> None:
        """Set remote SDP (answer).

        Args:
            sdp: SDP answer as JSON string.
        """
        desc = _sdp_from_string(sdp)
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
        except asyncio.TimeoutError:
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

    async def close(self) -> None:
        """Close the peer connection."""
        if self._pc:
            await self._pc.close()
        self._state = PeerState.CLOSED
        logger.info("Peer connection closed")

    async def __aenter__(self):
        """Enter async context."""
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        """Exit async context."""
        await self.close()
