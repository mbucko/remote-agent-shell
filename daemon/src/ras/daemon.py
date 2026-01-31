"""Main daemon orchestration - ties all components together."""

import asyncio
import logging
import secrets
import shutil
import signal
import struct
import time
from pathlib import Path
from typing import Any, Optional

import betterproto

from ras.config import Config
from ras.connection_manager import ConnectionManager
from ras.crypto import BytesCodec, derive_key
from ras.pairing.auth_handler import AuthHandler
from ras.device_store import JsonDeviceStore, PairedDevice
from ras.ip_provider import IpProvider, LocalNetworkIpProvider
from ras.message_dispatcher import MessageDispatcher
from ras.ntfy_signaling.reconnection_manager import NtfyReconnectionManager
from ras.server import UnifiedServer
from ras.tailscale import TailscaleListener, TailscalePeer
from ras.proto.ras import (
    ConnectionReady,
    InitialState,
    Ping,
    Pong,
    RasCommand,
    RasEvent,
    SessionCommand,
    SessionEvent,
    TerminalCommand,
    TerminalEvent,
)

logger = logging.getLogger(__name__)


class StartupError(Exception):
    """Error during daemon startup."""

    pass


class Daemon:
    """Main daemon orchestrating all components.

    Responsibilities:
    - Validate environment (tmux installed, port available)
    - Load device store
    - Initialize session, terminal, clipboard managers
    - Start signaling server for WebRTC setup
    - Manage phone connections
    - Route messages to handlers
    - Broadcast events to connected phones
    - Handle graceful shutdown
    """

    def __init__(
        self,
        config: Config,
        signaling_server: Any = None,
        pairing_manager: Any = None,
        session_manager: Any = None,
        terminal_manager: Any = None,
        clipboard_manager: Any = None,
    ):
        """Initialize daemon.

        Args:
            config: Daemon configuration.
            signaling_server: Optional injected signaling server (for testing).
            pairing_manager: Optional injected pairing manager (for testing).
            session_manager: Optional injected session manager (for testing).
            terminal_manager: Optional injected terminal manager (for testing).
            clipboard_manager: Optional injected clipboard manager (for testing).
        """
        self._config = config
        self._running = False

        # Connection management
        self._connection_manager = ConnectionManager(
            on_connection_lost=self._on_connection_lost,
            send_timeout=config.daemon.send_timeout,
        )

        # Message routing
        self._dispatcher = MessageDispatcher(
            handler_timeout=config.daemon.handler_timeout
        )

        # Stores
        self._device_store: Optional[JsonDeviceStore] = None

        # Managers (injected or created during startup)
        self._unified_server: Optional[UnifiedServer] = signaling_server
        self._pairing_manager = pairing_manager
        self._session_manager = session_manager
        self._terminal_manager = terminal_manager
        self._clipboard_manager = clipboard_manager

        # Server state
        self._server_runner = None
        self._keepalive_task: Optional[asyncio.Task] = None

        # Ntfy reconnection manager for cross-NAT reconnection
        self._ntfy_reconnection_manager: Optional[NtfyReconnectionManager] = None

        # Tailscale listener for direct VPN connections
        self._tailscale_listener: Optional[TailscaleListener] = None

        # Connections pending ConnectionReady handshake
        # Device IDs in this set have connected but not yet sent ConnectionReady
        self._pending_ready: set[str] = set()

    async def start(self) -> None:
        """Start the daemon.

        Raises:
            StartupError: If environment validation fails.
        """
        logger.info("Starting daemon...")

        # Validate environment
        await self._validate_environment()

        # Initialize stores
        await self._initialize_stores()

        # Register message handlers
        self._register_handlers()

        # Initialize managers (if not injected)
        await self._initialize_managers()

        # Start signaling server
        await self._start_signaling_server()

        # Start Tailscale listener for direct VPN connections
        await self._start_tailscale_listener()

        # Start ntfy reconnection manager for cross-NAT reconnection
        await self._start_ntfy_reconnection()

        # Start keepalive loop
        self._keepalive_task = asyncio.create_task(self._keepalive_loop())

        # Set up signal handlers
        self._setup_signals()

        self._running = True
        logger.info("Daemon started successfully")

    async def run_forever(self) -> None:
        """Run daemon until shutdown signal."""
        if not self._running:
            await self.start()

        try:
            while self._running:
                await asyncio.sleep(1)
        except asyncio.CancelledError:
            pass
        finally:
            await self._shutdown()

    async def stop(self) -> None:
        """Stop the daemon gracefully."""
        self._running = False

    async def _validate_environment(self) -> None:
        """Validate required tools and resources."""
        # Check tmux is installed
        if shutil.which("tmux") is None:
            raise StartupError("tmux not found - please install tmux")

        logger.debug("Environment validated")

    async def _initialize_stores(self) -> None:
        """Initialize device and session stores."""
        devices_path = Path(self._config.daemon.devices_file).expanduser()
        self._device_store = JsonDeviceStore(devices_path)
        await self._device_store.load()
        logger.debug(f"Loaded {len(self._device_store)} paired devices")

    def _register_handlers(self) -> None:
        """Register message handlers."""
        self._dispatcher.register("session", self._handle_session_command)
        self._dispatcher.register("terminal", self._handle_terminal_command)
        self._dispatcher.register("clipboard", self._handle_clipboard_message)
        self._dispatcher.register("ping", self._handle_ping)
        self._dispatcher.register("connection_ready", self._handle_connection_ready)
        logger.debug("Message handlers registered")

    async def _initialize_managers(self) -> None:
        """Initialize managers if not injected."""
        # Session manager initialization
        if self._session_manager is None:
            from ras.sessions.manager import SessionManager
            from ras.sessions.persistence import SessionPersistence
            from ras.sessions.agents import AgentDetector
            from ras.sessions.directories import DirectoryBrowser
            from ras.tmux import TmuxService

            # Create dependencies
            persistence = SessionPersistence()
            tmux = TmuxService()
            agents = AgentDetector(config=self._config.agents if hasattr(self._config, 'agents') else None)
            directories = DirectoryBrowser(config=self._config.directories if hasattr(self._config, 'directories') else None)

            # Create session manager
            self._session_manager = SessionManager(
                persistence=persistence,
                tmux=tmux,
                agents=agents,
                directories=directories,
                event_emitter=self,  # Daemon implements emit() for broadcasting
                config={
                    "directories": self._config.directories if hasattr(self._config, 'directories') else {},
                    "sessions": self._config.sessions if hasattr(self._config, 'sessions') else {},
                },
            )

            # Initialize - loads persisted sessions and reconciles with tmux
            await self._session_manager.initialize()
            logger.info(f"Session manager initialized with {len(self._session_manager._sessions)} sessions")

        # Terminal manager initialization via ManagerFactory
        if self._terminal_manager is None:
            from ras.manager_factory import ManagerFactory, ManagerDependencies
            from ras.tmux import TmuxService

            tmux_service = TmuxService()

            deps = ManagerDependencies(
                config=self._config,
                connection_manager=self._connection_manager,
                session_manager=self._session_manager,
                tmux_service=tmux_service,
            )

            factory = ManagerFactory()
            managers = factory.create(deps)
            self._terminal_manager = managers.terminal
            self._clipboard_manager = managers.clipboard

    async def _start_signaling_server(self) -> None:
        """Start unified HTTP server for pairing and reconnection."""
        # Create unified server if not injected (injection is for testing)
        if self._unified_server is None:
            # Create IP provider - uses local network IP by default
            # Could be configured to use STUN for public IP in the future
            ip_provider: IpProvider = LocalNetworkIpProvider()

            self._unified_server = UnifiedServer(
                device_store=self._device_store,
                ip_provider=ip_provider,
                stun_servers=self._config.stun_servers,
                pairing_timeout=self._config.daemon.pairing_timeout,
                max_pairing_sessions=self._config.daemon.max_pairing_sessions,
                on_device_connected=self._on_device_reconnected,
                tailscale_capabilities_provider=self.get_tailscale_capabilities,
            )

        self._server_runner = await self._unified_server.start(
            host=self._config.bind_address,
            port=self._config.port,
        )
        logger.info(f"Unified server started on {self._config.bind_address}:{self._unified_server.get_port()}")

    async def _start_tailscale_listener(self) -> None:
        """Start Tailscale listener for direct VPN connections.

        Detects Tailscale VPN and starts a UDP listener if available.
        This enables direct connections when both phone and daemon are
        on the same Tailscale network, bypassing NAT entirely.
        """
        self._tailscale_listener = TailscaleListener(
            on_connection=self._on_tailscale_connection,
        )

        started = await self._tailscale_listener.start()
        if started:
            logger.info(
                f"Tailscale listener started on "
                f"{self._tailscale_listener.tailscale_ip}:{self._tailscale_listener.port}"
            )
        else:
            logger.info("Tailscale not detected, direct VPN connections disabled")

    async def _on_tailscale_connection(self, transport: Any) -> None:
        """Handle new Tailscale connection from phone.

        Performs simple auth validation:
        1. Receives device_id (length-prefixed) + auth_key from phone
        2. Validates against device store
        3. Sends 0x01 for success, 0x00 for failure

        Args:
            transport: TailscaleTransport for the connection.
        """
        logger.info(f"Tailscale connection from {transport.remote_address}")

        try:
            # Receive auth message with timeout
            # Format: [device_id_len: 4 bytes BE][device_id: N bytes][auth: 32 bytes]
            auth_data = await transport.receive(timeout=10.0)

            if len(auth_data) < 36:  # 4 + min 1 byte device_id + 32 auth
                logger.warning(f"Auth data too short: {len(auth_data)} bytes")
                await transport.send(b'\x00')  # Failure
                return

            # Parse device_id length
            device_id_len = struct.unpack(">I", auth_data[:4])[0]

            if device_id_len < 1 or device_id_len > 100:
                logger.warning(f"Invalid device_id length: {device_id_len}")
                await transport.send(b'\x00')
                return

            if len(auth_data) < 4 + device_id_len + 32:
                logger.warning(f"Auth data incomplete: need {4 + device_id_len + 32}, got {len(auth_data)}")
                await transport.send(b'\x00')
                return

            # Extract device_id and auth_key
            device_id = auth_data[4:4 + device_id_len].decode('utf-8')
            auth_key = auth_data[4 + device_id_len:4 + device_id_len + 32]

            logger.info(f"Tailscale auth request from device: {device_id[:8]}...")

            # Lookup device
            if not self._device_store:
                logger.warning("No device store configured")
                await transport.send(b'\x00')
                return

            device = self._device_store.get(device_id)
            if not device:
                logger.warning(f"Unknown device: {device_id[:8]}...")
                await transport.send(b'\x00')
                return

            # Derive expected auth key and compare
            expected_auth_key = derive_key(device.master_secret, "auth")

            if not secrets.compare_digest(auth_key, expected_auth_key):
                logger.warning(f"Auth key mismatch for device: {device_id[:8]}...")
                await transport.send(b'\x00')
                return

            # Success!
            logger.info(f"Tailscale auth successful for device: {device_id[:8]}...")
            await transport.send(b'\x01')

            # Wrap transport in TailscalePeer (implements PeerProtocol)
            peer = TailscalePeer(transport)

            # Create codec for encrypted communication
            codec = BytesCodec(auth_key)

            # Register with connection manager
            await self.on_new_connection(
                device_id=device_id,
                device_name=device.name,
                peer=peer,
                codec=codec,
            )

            logger.info(f"Tailscale connection registered for device: {device_id[:8]}...")

        except TimeoutError:
            logger.warning(f"Tailscale auth timeout from {transport.remote_address}")
        except Exception as e:
            logger.error(f"Tailscale auth error: {e}")

    def get_tailscale_capabilities(self) -> dict:
        """Get Tailscale capabilities for signaling exchange.

        Returns:
            Dict with tailscale_ip and tailscale_port if available,
            empty dict otherwise.
        """
        if self._tailscale_listener and self._tailscale_listener.is_available:
            return self._tailscale_listener.get_capabilities()
        return {}

    async def _start_ntfy_reconnection(self) -> None:
        """Start ntfy reconnection manager for cross-NAT reconnection.

        Subscribes to ntfy topics for all paired devices to handle
        reconnection requests when direct HTTP is not reachable.
        """
        if self._device_store is None:
            logger.warning("Device store not initialized, skipping ntfy reconnection")
            return

        self._ntfy_reconnection_manager = NtfyReconnectionManager(
            device_store=self._device_store,
            stun_servers=self._config.stun_servers,
            on_reconnection=self._on_device_reconnected,
            capabilities_provider=self.get_tailscale_capabilities,
        )

        await self._ntfy_reconnection_manager.start()
        logger.info("Ntfy reconnection manager started")

    def _setup_signals(self) -> None:
        """Set up signal handlers for graceful shutdown."""
        loop = asyncio.get_running_loop()

        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(
                sig,
                lambda: asyncio.create_task(self.stop()),
            )

    async def _shutdown(self) -> None:
        """Perform graceful shutdown."""
        logger.info("Shutting down daemon...")

        # Cancel keepalive task
        if self._keepalive_task:
            self._keepalive_task.cancel()
            try:
                await self._keepalive_task
            except asyncio.CancelledError:
                pass

        # Close all connections
        await self._connection_manager.close_all()

        # Shutdown terminal manager
        if self._terminal_manager:
            await self._terminal_manager.shutdown()

        # Stop ntfy reconnection manager
        if self._ntfy_reconnection_manager:
            await self._ntfy_reconnection_manager.stop()

        # Stop Tailscale listener
        if self._tailscale_listener:
            await self._tailscale_listener.stop()

        # Stop unified server
        if self._unified_server:
            await self._unified_server.close()

        if self._server_runner:
            await self._server_runner.cleanup()

        logger.info("Daemon shutdown complete")

    # ==================== Server Helpers ====================

    def _get_server_port(self) -> int:
        """Get the port the server is listening on."""
        if self._unified_server:
            return self._unified_server.get_port()
        return self._config.port

    async def _complete_pairing(
        self, session_id: str, device_id: str, device_name: str
    ) -> None:
        """Complete a pairing session (for testing)."""
        if self._unified_server:
            await self._unified_server.complete_pairing(
                session_id, device_id, device_name
            )

    def _expire_pairing_session(self, session_id: str) -> None:
        """Expire a pairing session (for testing)."""
        if self._unified_server:
            self._unified_server.expire_pairing_session(session_id)

    async def _keepalive_loop(self) -> None:
        """Periodically check for stale connections."""
        interval = self._config.daemon.keepalive_interval
        timeout = self._config.daemon.keepalive_timeout

        while self._running:
            try:
                await asyncio.sleep(interval)

                now = time.time()
                for device_id, conn in list(
                    self._connection_manager.connections.items()
                ):
                    if now - conn.last_activity > timeout:
                        logger.warning(f"Connection stale, closing: {device_id}")
                        await conn.close()

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Keepalive loop error: {e}")

    # ==================== Message Handlers ====================

    async def _handle_session_command(
        self, device_id: str, command: SessionCommand
    ) -> None:
        """Handle session command from phone."""
        if self._session_manager is None:
            logger.warning("Session manager not initialized")
            return

        result = await self._session_manager.handle_command(command, device_id)
        if result:
            await self._broadcast_session_event(result)

    async def _handle_terminal_command(
        self, device_id: str, command: TerminalCommand
    ) -> None:
        """Handle terminal command from phone."""
        if self._terminal_manager is None:
            logger.warning("Terminal manager not initialized")
            return

        await self._terminal_manager.handle_command(device_id, command)

    async def _handle_clipboard_message(
        self, device_id: str, message: Any
    ) -> None:
        """Handle clipboard message from phone."""
        if self._clipboard_manager is None:
            logger.warning("Clipboard manager not initialized")
            return

        await self._clipboard_manager.handle_message(message)

    async def _handle_ping(self, device_id: str, ping: Ping) -> None:
        """Handle ping with pong response."""
        pong = Pong(timestamp=ping.timestamp)
        event = RasEvent(pong=pong)

        conn = self._connection_manager.get_connection(device_id)
        if conn:
            await conn.send(bytes(event))

    async def _handle_connection_ready(
        self, device_id: str, ready: ConnectionReady
    ) -> None:
        """Handle ConnectionReady signal from phone.

        The phone sends this after its ConnectionManager is fully set up
        and ready to receive encrypted messages. Only then do we send
        InitialState to avoid the message being lost.
        """
        if device_id in self._pending_ready:
            self._pending_ready.discard(device_id)
            logger.info(f"Received ConnectionReady from {device_id}")
            await self._send_initial_state(device_id)
        else:
            logger.warning(f"Unexpected ConnectionReady from {device_id}")

    # ==================== Event Broadcasting ====================

    async def emit(self, event: SessionEvent) -> None:
        """Emit session event to all connected phones.

        Implements EventEmitter protocol for SessionManager.
        """
        await self._broadcast_session_event(event)

    async def _broadcast_session_event(self, event: SessionEvent) -> None:
        """Broadcast session event to all connected phones."""
        ras_event = RasEvent(session=event)
        await self._connection_manager.broadcast(bytes(ras_event))

    async def _send_terminal_event(
        self, device_id: str, event: TerminalEvent
    ) -> None:
        """Send terminal event to specific connection."""
        conn = self._connection_manager.get_connection(device_id)
        if conn:
            ras_event = RasEvent(terminal=event)
            await conn.send(bytes(ras_event))

    async def _broadcast_notification(self, data: bytes) -> None:
        """Broadcast notification to all connections."""
        await self._connection_manager.broadcast(data)

    # ==================== Connection Handling ====================

    async def _on_device_reconnected(
        self,
        device_id: str,
        device_name: str,
        peer: Any,
        auth_key: bytes,
    ) -> None:
        """Handle device reconnection after auth is complete.

        Called by server.py AFTER authentication has already succeeded.
        Just waits for WebRTC connection and sets up message handling.

        Args:
            device_id: Device identifier.
            device_name: Human-readable device name.
            peer: WebRTC peer connection (already authenticated).
            auth_key: Auth key for message encryption.
        """
        # Wait for WebRTC connection to be established
        # (signaling/auth completes before ICE finishes)
        try:
            await peer.wait_connected(timeout=30.0)
        except Exception as e:
            logger.warning(f"WebRTC connection failed for {device_id[:8]}...: {e}")
            await peer.close()
            return

        logger.info(f"Device connected: {device_name} ({device_id[:8]}...)")

        # Create codec for encrypted communication
        codec = BytesCodec(auth_key)
        await self.on_new_connection(
            device_id=device_id,
            device_name=device_name,
            peer=peer,
            codec=codec,
        )

    async def _on_connection_lost(self, device_id: str) -> None:
        """Handle connection lost event."""
        logger.info(f"Connection lost: {device_id}")

        # Clean up pending ready state
        self._pending_ready.discard(device_id)

        # Clean up terminal attachments
        if self._terminal_manager:
            await self._terminal_manager.on_connection_closed(device_id)

    async def on_new_connection(
        self,
        device_id: str,
        device_name: str,
        peer: Any,
        codec: Any,
    ) -> None:
        """Handle new authenticated connection.

        Called by pairing/auth handler after successful authentication.

        Args:
            device_id: Unique device identifier.
            device_name: Human-readable device name.
            peer: WebRTC peer connection.
            codec: Message codec for encryption/decryption.
        """
        # Update device store - just update last_seen for known devices
        # New devices should be added via pairing flow, not here
        if self._device_store is not None:
            device = self._device_store.get(device_id)
            if device:
                device.update_last_seen()
                await self._device_store.save()
            else:
                logger.warning(f"Unknown device connected: {device_id} - should use pairing flow")

        # Add to connection manager
        await self._connection_manager.add_connection(
            device_id=device_id,
            peer=peer,
            codec=codec,
            on_message=lambda data: asyncio.create_task(
                self._on_message(device_id, data)
            ),
        )

        # Mark as pending - wait for ConnectionReady before sending InitialState
        # This ensures the phone's event listener is set up before we send
        self._pending_ready.add(device_id)

        logger.info(f"New connection: {device_id} ({device_name})")

    async def _on_message(self, device_id: str, data: bytes) -> None:
        """Handle incoming message from phone."""
        try:
            # Parse RasCommand
            cmd = RasCommand().parse(data)

            # Determine message type and dispatch
            field_name, field_value = betterproto.which_one_of(cmd, "command")

            if field_name:
                await self._dispatcher.dispatch(device_id, field_name, field_value)
            else:
                logger.warning(f"Unknown command from {device_id}")

        except Exception as e:
            logger.error(f"Error handling message from {device_id}: {e}")

    async def _send_initial_state(self, device_id: str) -> None:
        """Send initial state to newly connected phone."""
        conn = self._connection_manager.get_connection(device_id)
        if not conn:
            return

        # Get sessions and agents
        sessions = []
        agents = []

        if self._session_manager:
            # Get session list
            result = await self._session_manager.list_sessions()
            if result and result.list:
                sessions = list(result.list.sessions)

            # Get agents
            agents_result = await self._session_manager.get_agents()
            if agents_result and agents_result.agents:
                agents = list(agents_result.agents.agents)

        initial = InitialState(sessions=sessions, agents=agents)
        event = RasEvent(initial_state=initial)
        await conn.send(bytes(event))

        logger.info(f"Sent initial state to {device_id}: {len(sessions)} sessions, {len(agents)} agents")
