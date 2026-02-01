"""Session management for RAS daemon."""

import logging
import time
from dataclasses import dataclass
from typing import Awaitable, Callable, Protocol

import betterproto

from ras.proto.ras.ras import (
    AgentsListEvent,
    DirectoriesListEvent,
    Session,
    SessionCommand,
    SessionCreatedEvent,
    SessionErrorEvent,
    SessionEvent,
    SessionKilledEvent,
    SessionListEvent,
    SessionRenamedEvent,
    SessionStatus,
)
from ras.sessions.agents import AgentDetector
from ras.sessions.directories import DirectoryBrowser
from ras.sessions.naming import generate_session_name, sanitize_name
from ras.sessions.persistence import PersistedSession, SessionPersistence
from ras.sessions.validation import (
    generate_session_id,
    validate_agent,
    validate_directory,
    validate_name,
)

logger = logging.getLogger(__name__)


@dataclass
class SessionData:
    """Internal session representation."""

    id: str
    tmux_name: str
    display_name: str
    directory: str
    agent: str
    created_at: int
    last_activity_at: int
    status: SessionStatus = SessionStatus.ACTIVE

    def to_persisted(self) -> PersistedSession:
        """Convert to persistence format."""
        return PersistedSession(
            id=self.id,
            tmux_name=self.tmux_name,
            display_name=self.display_name,
            directory=self.directory,
            agent=self.agent,
            created_at=self.created_at,
            last_activity_at=self.last_activity_at,
            status=self.status.value,
        )

    @classmethod
    def from_persisted(cls, p: PersistedSession) -> "SessionData":
        """Create from persistence format."""
        return cls(
            id=p.id,
            tmux_name=p.tmux_name,
            display_name=p.display_name,
            directory=p.directory,
            agent=p.agent,
            created_at=p.created_at,
            last_activity_at=p.last_activity_at,
            status=SessionStatus(p.status),
        )


class TmuxExecutor(Protocol):
    """Protocol for tmux operations."""

    async def list_sessions(self) -> list:
        """List all tmux sessions."""
        ...

    async def create_session(
        self, name: str, detached: bool = True, directory: str = "", command: str = ""
    ) -> str:
        """Create a new session."""
        ...

    async def kill_session(self, session_id: str) -> None:
        """Kill a session."""
        ...

    async def send_keys(
        self, session_id: str, keys: str, literal: bool = True
    ) -> None:
        """Send keys to a session."""
        ...


class EventEmitter(Protocol):
    """Protocol for emitting events to connected phones."""

    async def emit(self, event: SessionEvent) -> None:
        """Emit a session event."""
        ...


class SessionManager:
    """Manages all session operations.

    Coordinates between persistence, tmux, agents, and directories
    to provide session lifecycle management.
    """

    def __init__(
        self,
        persistence: SessionPersistence,
        tmux: TmuxExecutor,
        agents: AgentDetector,
        directories: DirectoryBrowser,
        event_emitter: EventEmitter | None = None,
        config: dict | None = None,
    ):
        """Initialize session manager.

        Args:
            persistence: Session persistence handler.
            tmux: tmux executor for creating/killing sessions.
            agents: Agent detector.
            directories: Directory browser.
            event_emitter: Optional event emitter for broadcasting changes.
            config: Configuration dict.
        """
        self._persistence = persistence
        self._tmux = tmux
        self._agents = agents
        self._directories = directories
        self._emitter = event_emitter
        self._config = config or {}
        self._sessions: dict[str, SessionData] = {}
        self._rate_limiter: dict[str, list[float]] = {}

    async def initialize(self) -> None:
        """Load persisted sessions and reconcile with tmux."""
        # Load from JSON
        persisted = await self._persistence.load()

        # Get existing tmux sessions
        tmux_sessions = await self._tmux.list_sessions()
        tmux_names = {s.name for s in tmux_sessions}

        # Reconcile
        for p in persisted:
            session = SessionData.from_persisted(p)
            if session.tmux_name in tmux_names:
                self._sessions[session.id] = session
                logger.debug(f"Restored session: {session.id}")
            else:
                logger.info(f"Session {session.id} no longer in tmux, removing")

        # Adopt orphaned tmux sessions (any tmux session not already tracked)
        known_tmux_names = {s.tmux_name for s in self._sessions.values()}
        for tmux_session in tmux_sessions:
            if tmux_session.name not in known_tmux_names:
                session = self._adopt_orphan(tmux_session.name)
                self._sessions[session.id] = session
                logger.info(f"Adopted orphan tmux session: {tmux_session.name}")

        # Save reconciled state
        await self._save()

        # Initialize agents
        await self._agents.initialize()

    def _adopt_orphan(self, tmux_name: str) -> SessionData:
        """Create SessionData for an orphaned tmux session."""
        return SessionData(
            id=generate_session_id(),
            tmux_name=tmux_name,
            display_name=tmux_name,
            directory="",  # Unknown for orphans
            agent="unknown",
            created_at=int(time.time()),
            last_activity_at=int(time.time()),
        )

    async def _save(self) -> None:
        """Persist current state to JSON."""
        sessions = [s.to_persisted() for s in self._sessions.values()]
        await self._persistence.save(sessions)

    async def _emit(self, event: SessionEvent) -> None:
        """Emit event to all connected phones."""
        if self._emitter:
            await self._emitter.emit(event)

    def _check_rate_limit(self, phone_id: str) -> bool:
        """Check if request is allowed under rate limit.

        Returns:
            True if allowed, False if rate limited.
        """
        window = self._config.get("rate_limit_window", 60)  # seconds
        max_requests = self._config.get("rate_limit_max", 10)

        now = time.time()

        if phone_id not in self._rate_limiter:
            self._rate_limiter[phone_id] = []

        # Clean old entries
        self._rate_limiter[phone_id] = [
            t for t in self._rate_limiter[phone_id] if now - t < window
        ]

        if len(self._rate_limiter[phone_id]) >= max_requests:
            return False

        self._rate_limiter[phone_id].append(now)
        return True

    # ==================== Commands ====================

    async def list_sessions(self) -> SessionEvent:
        """List all sessions sorted by last activity.

        Validates sessions against tmux, removing dead ones and adopting
        any new tmux sessions that aren't tracked yet.

        Returns:
            SessionEvent with SessionListEvent.
        """
        tmux_sessions = await self._tmux.list_sessions()
        tmux_names = {s.name for s in tmux_sessions}

        # Remove dead sessions from memory
        dead_sessions = [
            sid
            for sid, session in self._sessions.items()
            if session.tmux_name not in tmux_names
        ]
        for sid in dead_sessions:
            logger.info(f"Session {sid} no longer in tmux, removing")
            del self._sessions[sid]

        # Adopt new tmux sessions not yet tracked
        known_tmux_names = {s.tmux_name for s in self._sessions.values()}
        for tmux_session in tmux_sessions:
            if tmux_session.name not in known_tmux_names:
                session = self._adopt_orphan(tmux_session.name)
                self._sessions[session.id] = session
                logger.info(f"Adopted new tmux session: {tmux_session.name}")

        # Persist if any changes
        if dead_sessions or len(self._sessions) != len(known_tmux_names):
            await self._save()

        # Return updated list
        sessions = sorted(
            self._sessions.values(),
            key=lambda s: s.last_activity_at,
            reverse=True,
        )
        return SessionEvent(
            list=SessionListEvent(sessions=[self._to_proto(s) for s in sessions])
        )

    async def create_session(
        self,
        directory: str,
        agent: str,
        phone_id: str = "",
    ) -> SessionEvent:
        """Create a new session.

        Args:
            directory: Working directory for the session.
            agent: Agent binary name.
            phone_id: Phone identifier for rate limiting.

        Returns:
            SessionEvent with created session or error.
        """
        # Rate limit
        if phone_id and not self._check_rate_limit(phone_id):
            return self._error_event("RATE_LIMITED", "Too many requests")

        # Get directory config
        dir_config = self._config.get("directories", {})
        root = dir_config.get("root", "~")
        whitelist = dir_config.get("whitelist", [])
        blacklist = dir_config.get("blacklist", [])

        # Validate directory
        dir_error = validate_directory(directory, root, whitelist, blacklist)
        if dir_error:
            if "allowed" in dir_error.lower():
                return self._error_event("DIR_NOT_ALLOWED", dir_error)
            return self._error_event("DIR_NOT_FOUND", dir_error)

        # Validate agent
        available_agents = await self._agents.get_available()
        agent_error = validate_agent(agent, available_agents)
        if agent_error:
            return self._error_event("AGENT_NOT_FOUND", agent_error)

        # Check max sessions
        max_sessions = self._config.get("sessions", {}).get("max_sessions", 20)
        if len(self._sessions) >= max_sessions:
            return self._error_event("MAX_SESSIONS_REACHED", "Maximum sessions reached")

        # Generate unique name
        tmux_name = generate_session_name(
            directory,
            agent,
            existing=[s.tmux_name for s in self._sessions.values()],
        )

        # Create session ID (12 alphanumeric characters)
        session_id = generate_session_id()

        # Create SessionData (status = CREATING)
        session = SessionData(
            id=session_id,
            tmux_name=tmux_name,
            display_name=tmux_name.replace("ras-", ""),
            directory=directory,
            agent=agent,
            created_at=int(time.time()),
            last_activity_at=int(time.time()),
            status=SessionStatus.CREATING,
        )
        self._sessions[session_id] = session

        # Create tmux session
        try:
            agent_info = available_agents[agent]
            await self._tmux.create_session(
                name=tmux_name,
                detached=True,
                directory=directory,
                command=agent_info.path,
            )
            session.status = SessionStatus.ACTIVE
            await self._save()

            # Track recent directory
            self._persistence.add_recent_directory(directory)

            # Emit created event
            event = SessionEvent(
                created=SessionCreatedEvent(session=self._to_proto(session))
            )
            await self._emit(event)
            return event

        except Exception as e:
            # Rollback
            del self._sessions[session_id]
            logger.error(f"Failed to create tmux session: {e}")
            return self._error_event("TMUX_ERROR", f"Failed to create session: {e}")

    async def kill_session(
        self,
        session_id: str,
        phone_id: str = "",
    ) -> SessionEvent:
        """Kill a session.

        Args:
            session_id: ID of session to kill.
            phone_id: Phone identifier for rate limiting.

        Returns:
            SessionEvent with killed confirmation or error.
        """
        # Rate limit
        if phone_id and not self._check_rate_limit(phone_id):
            return self._error_event("RATE_LIMITED", "Too many requests")

        # Validate session exists
        if session_id not in self._sessions:
            return self._error_event(
                "SESSION_NOT_FOUND", "Session not found", session_id
            )

        session = self._sessions[session_id]

        # Check if already killing
        if session.status == SessionStatus.KILLING:
            return self._error_event(
                "SESSION_NOT_FOUND", "Session is being killed", session_id
            )

        # Mark as killing
        session.status = SessionStatus.KILLING

        try:
            # Kill tmux session (graceful with Ctrl-C then force)
            await self._tmux.send_keys(session.tmux_name, "C-c", literal=False)

            # Give it a moment to exit gracefully
            import asyncio

            await asyncio.sleep(0.5)

            # Force kill
            await self._tmux.kill_session(session.tmux_name)

            # Remove from state
            del self._sessions[session_id]
            await self._save()

            # Emit killed event
            event = SessionEvent(killed=SessionKilledEvent(session_id=session_id))
            await self._emit(event)
            return event

        except Exception as e:
            # Restore status on failure
            session.status = SessionStatus.ACTIVE
            logger.error(f"Failed to kill session: {e}")
            return self._error_event(
                "KILL_FAILED", f"Failed to kill session: {e}", session_id
            )

    async def rename_session(
        self,
        session_id: str,
        new_name: str,
        phone_id: str = "",
    ) -> SessionEvent:
        """Rename a session's display name.

        Args:
            session_id: ID of session to rename.
            new_name: New display name.
            phone_id: Phone identifier for rate limiting.

        Returns:
            SessionEvent with renamed confirmation or error.
        """
        # Rate limit
        if phone_id and not self._check_rate_limit(phone_id):
            return self._error_event("RATE_LIMITED", "Too many requests")

        # Validate session exists
        if session_id not in self._sessions:
            return self._error_event(
                "SESSION_NOT_FOUND", "Session not found", session_id
            )

        # Validate name
        name_error = validate_name(new_name)
        if name_error:
            return self._error_event("INVALID_NAME", name_error, session_id)

        # Sanitize
        sanitized = sanitize_name(new_name)

        # Check for duplicates
        for sid, s in self._sessions.items():
            if sid != session_id and s.display_name == sanitized:
                return self._error_event(
                    "SESSION_EXISTS", "Name already exists", session_id
                )

        # Update
        session = self._sessions[session_id]
        session.display_name = sanitized
        await self._save()

        # Emit renamed event
        event = SessionEvent(
            renamed=SessionRenamedEvent(session_id=session_id, new_name=sanitized)
        )
        await self._emit(event)
        return event

    async def get_agents(self) -> SessionEvent:
        """Get list of all agents.

        Returns:
            SessionEvent with AgentsListEvent.
        """
        agents = await self._agents.get_all()
        return SessionEvent(agents=agents)

    async def refresh_agents(self) -> SessionEvent:
        """Re-scan for installed agents.

        Returns:
            SessionEvent with updated AgentsListEvent.
        """
        await self._agents.refresh()
        return await self.get_agents()

    async def get_directories(self, parent: str = "") -> SessionEvent:
        """Get directory listing.

        Args:
            parent: Parent directory path (empty for root).

        Returns:
            SessionEvent with DirectoriesListEvent.
        """
        recent = self._persistence.get_recent_directories() if not parent else []
        result = await self._directories.list(parent=parent, recent=recent)
        return SessionEvent(directories=result)

    def get_session(self, session_id: str) -> SessionData | None:
        """Get session by ID.

        Args:
            session_id: Session ID.

        Returns:
            SessionData or None if not found.
        """
        return self._sessions.get(session_id)

    def update_activity(self, session_id: str) -> None:
        """Update last activity timestamp for a session.

        Args:
            session_id: Session ID.
        """
        if session_id in self._sessions:
            self._sessions[session_id].last_activity_at = int(time.time())

    def _to_proto(self, session: SessionData) -> Session:
        """Convert internal data to protobuf."""
        return Session(
            id=session.id,
            tmux_name=session.tmux_name,
            display_name=session.display_name,
            directory=session.directory,
            agent=session.agent,
            created_at=session.created_at,
            last_activity_at=session.last_activity_at,
            status=session.status,
        )

    async def handle_command(
        self, command: SessionCommand, device_id: str = ""
    ) -> SessionEvent | None:
        """Handle incoming session command.

        Dispatches to appropriate handler based on command type.

        Args:
            command: The session command to handle.
            device_id: Device identifier for rate limiting.

        Returns:
            SessionEvent response or None.
        """
        field_name, field_value = betterproto.which_one_of(command, "command")
        logger.debug(f"handle_command: field_name={field_name}")

        if field_name == "list":
            logger.info("handle_command: listing sessions")
            result = await self.list_sessions()
            logger.info(f"handle_command: returning {len(result.list.sessions)} sessions")
            return result
        elif field_name == "create":
            return await self.create_session(
                directory=field_value.directory,
                agent=field_value.agent,
                phone_id=device_id,
            )
        elif field_name == "kill":
            return await self.kill_session(field_value.session_id)
        elif field_name == "rename":
            return await self.rename_session(
                session_id=field_value.session_id,
                new_name=field_value.new_name,
            )
        elif field_name == "get_agents":
            return await self.get_agents()
        elif field_name == "get_directories":
            return await self.get_directories(parent=field_value.parent)
        elif field_name == "refresh_agents":
            return await self.refresh_agents()
        else:
            logger.warning(f"Unknown session command type: {field_name}")
            return None

    def _error_event(
        self,
        code: str,
        message: str,
        session_id: str = "",
    ) -> SessionEvent:
        """Create error event."""
        return SessionEvent(
            error=SessionErrorEvent(
                error_code=code,
                message=message,
                session_id=session_id,
            )
        )
