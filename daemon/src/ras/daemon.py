"""Main daemon orchestration - ties all components together."""

import asyncio
import logging
import shutil
import signal
import time
from pathlib import Path
from typing import Any, Optional

import betterproto

from ras.config import Config
from ras.connection_manager import ConnectionManager
from ras.device_store import JsonDeviceStore, PairedDevice
from ras.message_dispatcher import MessageDispatcher
from ras.proto.ras import (
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
        self._signaling_server = signaling_server
        self._pairing_manager = pairing_manager
        self._session_manager = session_manager
        self._terminal_manager = terminal_manager
        self._clipboard_manager = clipboard_manager

        # Server state
        self._signaling_runner = None
        self._keepalive_task: Optional[asyncio.Task] = None

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
        logger.debug("Message handlers registered")

    async def _initialize_managers(self) -> None:
        """Initialize managers if not injected."""
        # Session manager initialization would go here
        # Terminal manager initialization would go here
        # Clipboard manager initialization would go here
        pass

    async def _start_signaling_server(self) -> None:
        """Start HTTP signaling server."""
        # This would start the actual signaling server
        # For now, we assume it's injected for testing
        if self._signaling_server is not None:
            self._signaling_runner = await self._signaling_server.start(
                host=self._config.bind_address,
                port=self._config.port,
            )
        logger.debug(f"Signaling server started on port {self._config.port}")

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

        # Stop signaling server
        if self._signaling_runner:
            await self._signaling_runner.cleanup()

        logger.info("Daemon shutdown complete")

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

    # ==================== Event Broadcasting ====================

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

    async def _on_connection_lost(self, device_id: str) -> None:
        """Handle connection lost event."""
        logger.info(f"Connection lost: {device_id}")

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
        # Update device store
        if self._device_store is not None:
            device = self._device_store.get(device_id)
            if device:
                device.update_last_seen()
                await self._device_store.save()
            else:
                # New device
                device = PairedDevice(
                    device_id=device_id,
                    name=device_name,
                    public_key="",  # Not using public key auth yet
                    paired_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                )
                device.update_last_seen()
                await self._device_store.add(device)

        # Add to connection manager
        await self._connection_manager.add_connection(
            device_id=device_id,
            peer=peer,
            codec=codec,
            on_message=lambda data: asyncio.create_task(
                self._on_message(device_id, data)
            ),
        )

        # Send initial state
        await self._send_initial_state(device_id)

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

        logger.debug(f"Sent initial state to {device_id}")
