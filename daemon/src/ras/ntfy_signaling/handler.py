"""Handler for ntfy signaling messages.

This module provides:
- NtfySignalingHandler: Processes incoming OFFER messages from ntfy
- Creates WebRTC peer connections and generates ANSWER responses
- Supports both pairing mode (session_id validation) and reconnection mode (device_id validation)
- All errors are handled silently (security requirement)

Usage:
    # For pairing (validates session_id)
    handler = NtfySignalingHandler(
        master_secret=master_secret,
        pending_session_id="abc123",
    )

    # For reconnection (validates device_id against device_store)
    handler = NtfySignalingHandler(
        master_secret=master_secret,
        pending_session_id="",  # Empty = reconnection mode
        device_store=device_store,
    )

    # When message received from ntfy
    result = await handler.handle_message(encrypted_message)
    if result and result.should_respond:
        # Publish result.answer_encrypted back to ntfy
        pass
"""

import logging
import os
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Optional, Protocol

from ras.crypto import (
    PAIR_NONCE_LENGTH,
    compute_pair_request_hmac,
    compute_pair_response_hmac,
    derive_key,
)
from ras.ntfy_signaling.crypto import (
    DecryptionError,
    NtfySignalingCrypto,
    derive_signaling_key,
)
from ras.ntfy_signaling.validation import (
    NONCE_SIZE,
    NtfySignalMessageValidator,
    sanitize_device_name,
)
from ras.proto.ras.ras import (
    ConnectionCapabilities,
    DiscoveryResponse,
    NtfySignalMessage,
    NtfySignalMessageMessageType,
    PairRequest,
    PairResponse,
)

logger = logging.getLogger(__name__)


class DeviceStoreProtocol(Protocol):
    """Protocol for device store access."""

    def get(self, device_id: str) -> Optional[Any]:
        """Get a device by ID, or None if not found."""
        ...


@dataclass
class HandlerResult:
    """Result from handling an ntfy signaling message."""

    should_respond: bool
    answer_encrypted: Optional[str]
    device_id: Optional[str]
    device_name: Optional[str]
    peer: Optional[Any]  # PeerConnection
    is_reconnection: bool = False  # True if this was a reconnection (not pairing)
    is_capability_exchange: bool = False  # True if this was just a capability exchange
    is_pair_complete: bool = False  # True if this was a PAIR_REQUEST/PAIR_RESPONSE exchange
    timing_ms: dict[str, float] = field(default_factory=dict)  # Phase timing in ms


class NtfySignalingHandler:
    """Handles ntfy signaling messages for NAT traversal.

    Processes incoming OFFER messages, validates them, creates WebRTC
    peer connections, and generates encrypted ANSWER responses.

    Modes:
    - Pairing mode: pending_session_id is set, validates against it
    - Reconnection mode: pending_session_id is empty, validates device_id against device_store

    Security:
    - All errors are silently ignored (no error messages to attacker)
    - Nonce replay protection
    - Timestamp validation (±30 seconds)
    - Message authentication via AES-GCM
    """

    def __init__(
        self,
        master_secret: bytes,
        pending_session_id: str,
        stun_servers: Optional[list[str]] = None,
        device_store: Optional[DeviceStoreProtocol] = None,
        capabilities_provider: Optional[Callable[[], dict]] = None,
        discovery_provider: Optional[Callable[[], dict]] = None,
    ):
        """Initialize handler.

        Args:
            master_secret: 32-byte master secret from QR code.
            pending_session_id: Expected session ID for pairing mode.
                               Empty string ("") enables reconnection mode.
            stun_servers: STUN servers for WebRTC (optional).
            device_store: Device store for reconnection mode (required if reconnection enabled).
            capabilities_provider: Optional callable returning capabilities dict.
                                  Used to include Tailscale info in ANSWER messages.
            discovery_provider: Optional callable returning discovery info dict.
                               Keys: lan_ip, lan_port, vpn_ip, vpn_port, tailscale_ip,
                               tailscale_port, public_ip, public_port, device_id.
        """
        signaling_key = derive_signaling_key(master_secret)
        self._crypto = NtfySignalingCrypto(signaling_key)
        self._session_id = pending_session_id
        self._stun_servers = stun_servers or []
        self._peer: Optional[Any] = None
        self._device_store = device_store
        self._capabilities_provider = capabilities_provider
        self._discovery_provider = discovery_provider

        # Determine mode
        self._reconnection_mode = pending_session_id == ""

        # For pairing mode, derive auth_key for PAIR_REQUEST HMAC validation
        if not self._reconnection_mode:
            self._auth_key = derive_key(master_secret, "auth")
        else:
            self._auth_key = None

        if self._reconnection_mode:
            # Reconnection mode - will validate device_id dynamically
            self._validator = NtfySignalMessageValidator(
                pending_session_id="",  # Will match empty session_id in reconnection requests
                expected_type="OFFER",
            )
            logger.info("NtfySignalingHandler initialized in RECONNECTION mode")
        else:
            # Pairing mode - validate against specific session_id
            self._validator = NtfySignalMessageValidator(
                pending_session_id=pending_session_id,
                expected_type="OFFER",
            )
            logger.info(f"NtfySignalingHandler initialized in PAIRING mode (session={pending_session_id[:8]}...)")

    async def handle_message(self, encrypted: str) -> Optional[HandlerResult]:
        """Handle an encrypted ntfy signaling message.

        Args:
            encrypted: Base64-encoded encrypted message from ntfy.

        Returns:
            HandlerResult if valid OFFER and answer created, None otherwise.
            Returns None silently on any error (security requirement).
        """
        import time as time_module
        start_time = time_module.perf_counter()
        timing: dict[str, float] = {}

        def mark(phase: str) -> None:
            timing[phase] = (time_module.perf_counter() - start_time) * 1000

        try:
            # Decrypt
            plaintext = self._crypto.decrypt(encrypted)
            mark("decrypt")
            logger.info(f"[TIMING] signaling: decrypt @ {timing['decrypt']:.1f}ms ({len(plaintext)} bytes)")
        except DecryptionError as e:
            # Silent ignore - could be wrong key or tampered
            logger.info(f"Decryption failed: {e}")
            return None
        except Exception as e:
            # Silent ignore
            logger.info(f"Unexpected error decrypting: {type(e).__name__}")
            return None

        # Parse protobuf
        try:
            msg = NtfySignalMessage().parse(plaintext)
            mark("parse")
            session_id_display = msg.session_id[:8] if msg.session_id else "(empty)"
            logger.info(f"[TIMING] signaling: parse @ {timing['parse']:.1f}ms (type={msg.type}, session={session_id_display})")
        except Exception as e:
            logger.info(f"Failed to parse protobuf: {type(e).__name__}")
            return None

        # Handle CAPABILITIES messages separately (simpler flow, no WebRTC)
        if msg.type == NtfySignalMessageMessageType.CAPABILITIES:
            return await self._handle_capabilities_message(msg)

        # Handle DISCOVER messages (IP discovery, no WebRTC)
        if msg.type == NtfySignalMessageMessageType.DISCOVER:
            return await self._handle_discover_message(msg)

        # Handle PAIR_REQUEST messages (pairing without WebRTC)
        if msg.type == NtfySignalMessageMessageType.PAIR_REQUEST:
            return await self._handle_pair_request_message(msg)

        # Determine if this is a reconnection request (empty session_id)
        is_reconnection_request = not msg.session_id

        # Handle mode matching
        if self._reconnection_mode:
            # We're in reconnection mode - only accept reconnection requests
            if not is_reconnection_request:
                logger.info("Rejecting pairing request in reconnection mode")
                return None
        else:
            # We're in pairing mode - only accept pairing requests
            if is_reconnection_request:
                logger.info("Rejecting reconnection request in pairing mode")
                return None

        # Validate message (timestamp, nonce, type)
        result = self._validator.validate(msg)
        if not result.is_valid:
            logger.info(f"Validation failed: {result.error}")
            return None
        mark("validate")
        logger.info(f"[TIMING] signaling: validate @ {timing['validate']:.1f}ms")

        # For reconnection mode, validate device_id against device store
        if self._reconnection_mode:
            if not self._device_store:
                logger.warning("Reconnection mode but no device_store configured")
                return None

            if not msg.device_id:
                logger.info("Reconnection request missing device_id")
                return None

            device = self._device_store.get(msg.device_id)
            if not device:
                logger.info(f"Unknown device_id: {msg.device_id[:8]}...")
                return None

            mark("device_lookup")
            logger.info(f"[TIMING] signaling: device_lookup @ {timing['device_lookup']:.1f}ms ({msg.device_id[:8]}...)")

        # Sanitize device name
        device_name = sanitize_device_name(msg.device_name)

        # Create WebRTC peer and accept offer
        try:
            logger.info("Creating WebRTC peer connection...")
            peer = self._create_peer()
            mark("peer_create")
            # PeerConnection uses raw SDP format directly
            answer_sdp = await peer.accept_offer(msg.sdp)
            mark("accept_offer")
            logger.info(f"[TIMING] signaling: accept_offer @ {timing['accept_offer']:.1f}ms")
        except Exception as e:
            logger.warning(f"WebRTC peer creation failed: {e}")
            return None

        self._peer = peer

        # Build capabilities for ANSWER message
        capabilities = self._build_capabilities()

        # Create ANSWER message
        # For reconnection, session_id is empty in the answer too
        answer_msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.ANSWER,
            session_id=self._session_id,  # Empty for reconnection, set for pairing
            sdp=answer_sdp,
            device_id="",  # Not needed for ANSWER
            device_name="",  # Not needed for ANSWER
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
            capabilities=capabilities,
        )

        # Encrypt answer
        try:
            answer_encrypted = self._crypto.encrypt(bytes(answer_msg))
            mark("encrypt_answer")
            logger.info(f"[TIMING] signaling: encrypt_answer @ {timing['encrypt_answer']:.1f}ms ({len(answer_encrypted)} bytes)")
        except Exception as e:
            logger.warning(f"Failed to encrypt answer: {e}")
            return None

        mode = "reconnection" if is_reconnection_request else "pairing"
        mark("complete")

        # Log timing summary
        summary = " | ".join(f"{phase}={ms:.0f}ms" for phase, ms in timing.items())
        logger.info(f"[TIMING] signaling summary ({mode}): {summary}")

        return HandlerResult(
            should_respond=True,
            answer_encrypted=answer_encrypted,
            device_id=msg.device_id,
            device_name=device_name,
            peer=peer,
            is_reconnection=is_reconnection_request,
            timing_ms=timing,
        )

    async def _handle_capabilities_message(
        self, msg: NtfySignalMessage
    ) -> Optional[HandlerResult]:
        """Handle a CAPABILITIES exchange message.

        This is a simpler flow than OFFER - just exchange capabilities without WebRTC.
        Used for discovering connection methods before attempting connection.

        Args:
            msg: The parsed CAPABILITIES message.

        Returns:
            HandlerResult with capability response, or None on error.
        """
        logger.info("Handling CAPABILITIES message")

        # Only allow in reconnection mode (needs device validation)
        if not self._reconnection_mode:
            logger.info("Rejecting CAPABILITIES in pairing mode")
            return None

        # Validate device_id
        if not self._device_store:
            logger.warning("CAPABILITIES request but no device_store configured")
            return None

        if not msg.device_id:
            logger.info("CAPABILITIES request missing device_id")
            return None

        device = self._device_store.get(msg.device_id)
        if not device:
            logger.info(f"Unknown device_id in CAPABILITIES: {msg.device_id[:8]}...")
            return None

        logger.info(f"Device validated for CAPABILITIES: {msg.device_id[:8]}...")

        # Validate timestamp and nonce (reuse validator logic but allow CAPABILITIES type)
        # We do manual validation here since the validator is configured for OFFER
        now = int(time.time())
        if abs(now - msg.timestamp) > 30:
            logger.info(f"CAPABILITIES timestamp too old/new: {msg.timestamp}")
            return None

        if len(msg.nonce) != NONCE_SIZE:
            logger.info(f"Invalid nonce size in CAPABILITIES: {len(msg.nonce)}")
            return None

        # Build our capabilities
        capabilities = self._build_capabilities()

        # Create CAPABILITIES response
        response_msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.CAPABILITIES,
            session_id="",  # Empty for reconnection mode
            device_id="",  # Not needed in response
            device_name="",  # Not needed in response
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
            capabilities=capabilities,
        )

        # Encrypt response
        try:
            response_encrypted = self._crypto.encrypt(bytes(response_msg))
            logger.info(
                f"CAPABILITIES response encrypted: tailscale={capabilities.tailscale_ip}:{capabilities.tailscale_port}"
            )
        except Exception as e:
            logger.warning(f"Failed to encrypt CAPABILITIES response: {e}")
            return None

        return HandlerResult(
            should_respond=True,
            answer_encrypted=response_encrypted,
            device_id=msg.device_id,
            device_name=None,
            peer=None,
            is_reconnection=True,
            is_capability_exchange=True,
        )

    async def _handle_discover_message(
        self, msg: NtfySignalMessage
    ) -> Optional[HandlerResult]:
        """Handle a DISCOVER message for IP discovery.

        Returns all available IPs (LAN, VPN, Tailscale, public) so the phone
        can try multiple connection strategies.

        Args:
            msg: The parsed DISCOVER message.

        Returns:
            HandlerResult with DISCOVER_RESPONSE, or None on error.
        """
        logger.info("Handling DISCOVER message")

        # Only allow in reconnection mode (needs device validation)
        if not self._reconnection_mode:
            logger.info("Rejecting DISCOVER in pairing mode")
            return None

        # Validate device_id
        if not self._device_store:
            logger.warning("DISCOVER request but no device_store configured")
            return None

        if not msg.device_id:
            logger.info("DISCOVER request missing device_id")
            return None

        device = self._device_store.get(msg.device_id)
        if not device:
            logger.info(f"Unknown device_id in DISCOVER: {msg.device_id[:8]}...")
            return None

        logger.info(f"Device validated for DISCOVER: {msg.device_id[:8]}...")

        # Validate timestamp and nonce
        now = int(time.time())
        if abs(now - msg.timestamp) > 30:
            logger.info(f"DISCOVER timestamp too old/new: {msg.timestamp}")
            return None

        if len(msg.nonce) != NONCE_SIZE:
            logger.info(f"Invalid nonce size in DISCOVER: {len(msg.nonce)}")
            return None

        # Build discovery response
        discovery = self._build_discovery_response()

        # Create DISCOVER_RESPONSE message
        response_msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.DISCOVER_RESPONSE,
            session_id="",  # Empty for reconnection mode
            device_id="",  # Not needed in response
            device_name="",  # Not needed in response
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
            discovery=discovery,
        )

        # Encrypt response
        try:
            response_encrypted = self._crypto.encrypt(bytes(response_msg))
            logger.info(
                f"DISCOVER_RESPONSE encrypted: lan={discovery.lan_ip}:{discovery.lan_port}, "
                f"vpn={discovery.vpn_ip}:{discovery.vpn_port}, "
                f"tailscale={discovery.tailscale_ip}:{discovery.tailscale_port}"
            )
        except Exception as e:
            logger.warning(f"Failed to encrypt DISCOVER_RESPONSE: {e}")
            return None

        return HandlerResult(
            should_respond=True,
            answer_encrypted=response_encrypted,
            device_id=msg.device_id,
            device_name=None,
            peer=None,
            is_reconnection=True,
            is_capability_exchange=True,  # Reuse flag for non-WebRTC exchanges
        )

    async def _handle_pair_request_message(
        self, msg: NtfySignalMessage
    ) -> Optional[HandlerResult]:
        """Handle a PAIR_REQUEST message for instant pairing.

        Validates the phone's auth_proof HMAC, builds a PairResponse
        with the daemon's auth_proof, and returns it encrypted.

        No WebRTC involved — just credential exchange.

        Args:
            msg: The parsed NtfySignalMessage containing a PairRequest.

        Returns:
            HandlerResult with encrypted PAIR_RESPONSE, or None on error.
        """
        import hmac as hmac_module

        logger.info("Handling PAIR_REQUEST message")

        # Only allow in pairing mode
        if self._reconnection_mode:
            logger.info("Rejecting PAIR_REQUEST in reconnection mode")
            return None

        if not self._auth_key:
            logger.warning("PAIR_REQUEST but no auth_key available")
            return None

        # Extract PairRequest
        pair_req = msg.pair_request
        if not pair_req or not pair_req.device_id:
            logger.info("PAIR_REQUEST missing pair_request or device_id")
            return None

        # Validate session_id matches
        if pair_req.session_id != self._session_id:
            logger.info(f"Session ID mismatch in PAIR_REQUEST: {pair_req.session_id[:8]}... vs {self._session_id[:8]}...")
            return None

        # Validate nonce length
        if len(pair_req.nonce) != PAIR_NONCE_LENGTH:
            logger.info(f"Invalid nonce length in PAIR_REQUEST: {len(pair_req.nonce)}")
            return None

        # Validate auth_proof HMAC
        expected_hmac = compute_pair_request_hmac(
            self._auth_key,
            pair_req.session_id,
            pair_req.device_id,
            pair_req.nonce,
        )

        if not hmac_module.compare_digest(pair_req.auth_proof, expected_hmac):
            logger.warning(f"HMAC verification failed for PAIR_REQUEST {pair_req.session_id[:8]}...")
            return None

        # Build PairResponse with daemon's auth_proof
        from ras.system import detect_device_type, get_daemon_device_id, get_hostname
        from ras.proto.ras.ras import DeviceType as ProtoDeviceType

        response_hmac = compute_pair_response_hmac(self._auth_key, pair_req.nonce)

        pair_resp = PairResponse(
            daemon_device_id=get_daemon_device_id(),
            hostname=get_hostname(),
            device_type=ProtoDeviceType(detect_device_type()),
            auth_proof=response_hmac,
        )

        # Build NtfySignalMessage with PAIR_RESPONSE
        response_msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.PAIR_RESPONSE,
            session_id=self._session_id,
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
            pair_response=pair_resp,
        )

        # Encrypt response
        try:
            response_encrypted = self._crypto.encrypt(bytes(response_msg))
            logger.info(f"PAIR_RESPONSE encrypted for {pair_req.device_id[:8]}...")
        except Exception as e:
            logger.warning(f"Failed to encrypt PAIR_RESPONSE: {e}")
            return None

        device_name = sanitize_device_name(pair_req.device_name)

        return HandlerResult(
            should_respond=True,
            answer_encrypted=response_encrypted,
            device_id=pair_req.device_id,
            device_name=device_name,
            peer=None,
            is_pair_complete=True,
        )

    def _build_discovery_response(self) -> DiscoveryResponse:
        """Build discovery response with all available IPs.

        Returns:
            DiscoveryResponse with LAN, VPN, Tailscale, and public IPs.
        """
        discovery = DiscoveryResponse(
            timestamp=int(time.time()),
        )

        if self._discovery_provider:
            try:
                info = self._discovery_provider()
                if info:
                    if "lan_ip" in info:
                        discovery.lan_ip = info["lan_ip"]
                    if "lan_port" in info:
                        discovery.lan_port = info["lan_port"]
                    if "vpn_ip" in info:
                        discovery.vpn_ip = info["vpn_ip"]
                    if "vpn_port" in info:
                        discovery.vpn_port = info["vpn_port"]
                    if "tailscale_ip" in info:
                        discovery.tailscale_ip = info["tailscale_ip"]
                    if "tailscale_port" in info:
                        discovery.tailscale_port = info["tailscale_port"]
                    if "public_ip" in info:
                        discovery.public_ip = info["public_ip"]
                    if "public_port" in info:
                        discovery.public_port = info["public_port"]
                    if "device_id" in info:
                        discovery.device_id = info["device_id"]
                    logger.debug(
                        f"Discovery: lan={discovery.lan_ip}, vpn={discovery.vpn_ip}, "
                        f"tailscale={discovery.tailscale_ip}"
                    )
            except Exception as e:
                logger.warning(f"Error getting discovery info: {e}")

        return discovery

    def _build_capabilities(self) -> ConnectionCapabilities:
        """Build connection capabilities for ANSWER message.

        Returns:
            ConnectionCapabilities with Tailscale info if available.
        """
        caps = ConnectionCapabilities(
            supports_webrtc=True,
            supports_turn=False,  # TODO: Add TURN support
            protocol_version=1,
        )

        # Add Tailscale info if provider is configured
        if self._capabilities_provider:
            try:
                caps_dict = self._capabilities_provider()
                if caps_dict:
                    if "tailscale_ip" in caps_dict:
                        caps.tailscale_ip = caps_dict["tailscale_ip"]
                    if "tailscale_port" in caps_dict:
                        caps.tailscale_port = caps_dict["tailscale_port"]
                    logger.debug(
                        f"Capabilities: tailscale={caps.tailscale_ip}:{caps.tailscale_port}"
                    )
            except Exception as e:
                logger.warning(f"Error getting capabilities: {e}")

        return caps

    def _create_peer(self) -> Any:
        """Create WebRTC peer connection.

        This method can be mocked in tests.
        """
        from ras.peer import PeerConnection

        return PeerConnection(stun_servers=self._stun_servers)

    def get_peer(self) -> Optional[Any]:
        """Get the WebRTC peer connection if one was created."""
        return self._peer

    def take_peer(self) -> Optional[Any]:
        """Take ownership of the WebRTC peer connection.

        Returns the peer and clears the internal reference so that
        close() won't close the peer. The caller becomes responsible
        for closing the peer.

        Returns:
            The peer connection, or None if no peer exists.
        """
        peer = self._peer
        self._peer = None
        return peer

    def clear_nonce_cache(self) -> None:
        """Clear the nonce cache.

        Call this when the session ends.
        """
        self._validator.clear_nonce_cache()

    def zero_key(self) -> None:
        """Zero the signaling key from memory.

        Call this when the session ends.
        """
        self._crypto.zero_key()

    async def close(self) -> None:
        """Clean up handler resources.

        Zeros key, clears nonce cache, and closes peer if still owned.

        Note: If ownership was transferred via transfer_ownership() on the peer,
        close_by_owner() will be a no-op and the peer will remain open.
        """
        from ras.protocols import PeerOwnership

        self.zero_key()
        self.clear_nonce_cache()
        if self._peer:
            # Only close if we still own it - prevents double-close after handoff
            await self._peer.close_by_owner(PeerOwnership.SignalingHandler)
            self._peer = None
