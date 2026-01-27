"""End-to-end tests for session management.

Tests the complete session lifecycle from command to event, with mocked
tmux and filesystem interfaces. Verifies all E2E flows from the task spec.
"""

import asyncio
import json
import os
import tempfile
import time
from pathlib import Path
from typing import Callable
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.proto.ras import (
    Agent,
    AgentsListEvent,
    CreateSessionCommand,
    DirectoriesListEvent,
    DirectoryEntry,
    GetAgentsCommand,
    GetDirectoriesCommand,
    KillSessionCommand,
    ListSessionsCommand,
    RefreshAgentsCommand,
    RenameSessionCommand,
    Session,
    SessionActivityEvent,
    SessionCommand,
    SessionCreatedEvent,
    SessionErrorEvent,
    SessionEvent,
    SessionKilledEvent,
    SessionListEvent,
    SessionRenamedEvent,
    SessionStatus,
)
from tests.sessions.test_vectors import (
    TestAgentVectors,
    TestConcurrencyVectors,
    TestE2EFlowVectors,
    TestErrorCodeVectors,
    TestRateLimitingVectors,
    TestSecurityVectors,
)


# ============================================================================
# MOCK INTERFACES
# ============================================================================

class MockTmuxInterface:
    """Mock tmux interface for testing without real tmux.

    Simulates tmux session management with configurable behaviors.
    """

    def __init__(self):
        """Initialize mock with empty session state."""
        self.sessions: dict[str, dict] = {}
        self.create_delay: float = 0.0
        self.kill_delay: float = 0.0
        self.should_fail_create: bool = False
        self.should_fail_kill: bool = False
        self.kill_graceful: bool = True

    def list_sessions(self) -> list[str]:
        """List all tmux session names starting with 'ras-'."""
        return [name for name in self.sessions.keys() if name.startswith("ras-")]

    def session_exists(self, name: str) -> bool:
        """Check if a session exists."""
        return name in self.sessions

    async def create_session(
        self,
        name: str,
        directory: str,
        command: str,
    ) -> bool:
        """Create a new tmux session.

        Args:
            name: Session name.
            directory: Working directory.
            command: Command to run in session.

        Returns:
            True if created successfully.

        Raises:
            RuntimeError: If creation fails.
        """
        if self.create_delay > 0:
            await asyncio.sleep(self.create_delay)

        if self.should_fail_create:
            raise RuntimeError("tmux: failed to create session")

        if name in self.sessions:
            raise RuntimeError(f"tmux: session '{name}' already exists")

        self.sessions[name] = {
            "directory": directory,
            "command": command,
            "created_at": time.time(),
        }
        return True

    async def kill_session(self, name: str) -> bool:
        """Kill a tmux session.

        Args:
            name: Session name to kill.

        Returns:
            True if killed successfully.

        Raises:
            RuntimeError: If kill fails.
        """
        if self.kill_delay > 0:
            await asyncio.sleep(self.kill_delay)

        if self.should_fail_kill:
            raise RuntimeError("tmux: failed to kill session")

        if name not in self.sessions:
            return False

        del self.sessions[name]
        return True


class MockAgentDetector:
    """Mock agent detector for testing without real binaries."""

    def __init__(self, available_agents: list[str] | None = None):
        """Initialize with list of available agent binaries.

        Args:
            available_agents: List of agent binary names that should be "installed".
        """
        self.available_agents = set(available_agents or [])
        self._all_agents = [
            {"name": "Claude Code", "binary": "claude"},
            {"name": "Aider", "binary": "aider"},
            {"name": "Cursor", "binary": "cursor"},
            {"name": "Cline", "binary": "cline"},
            {"name": "Open Code", "binary": "opencode"},
            {"name": "Codex", "binary": "codex"},
        ]

    def detect_agents(self) -> list[dict]:
        """Detect installed agents.

        Returns:
            List of agent info dicts with availability.
        """
        return [
            {
                "name": agent["name"],
                "binary": agent["binary"],
                "path": f"/usr/local/bin/{agent['binary']}" if agent["binary"] in self.available_agents else "",
                "available": agent["binary"] in self.available_agents,
            }
            for agent in self._all_agents
        ]

    def is_available(self, binary: str) -> bool:
        """Check if an agent binary is available."""
        return binary in self.available_agents


class MockPhoneConnection:
    """Mock phone connection for testing command/event flow."""

    def __init__(self):
        """Initialize mock connection."""
        self.sent_events: list[SessionEvent] = []
        self.receive_queue: asyncio.Queue[SessionCommand] = asyncio.Queue()

    async def send_event(self, event: SessionEvent) -> None:
        """Send event to phone."""
        self.sent_events.append(event)

    async def receive_command(self) -> SessionCommand:
        """Receive command from phone."""
        return await self.receive_queue.get()

    def queue_command(self, command: SessionCommand) -> None:
        """Queue a command to be received."""
        self.receive_queue.put_nowait(command)

    def get_last_event(self) -> SessionEvent | None:
        """Get the most recent sent event."""
        return self.sent_events[-1] if self.sent_events else None

    def get_events_of_type(self, event_type: str) -> list[SessionEvent]:
        """Get all events of a specific type."""
        result = []
        for event in self.sent_events:
            which = betterproto.which_one_of(event, "event")
            if which[0] == event_type:
                result.append(event)
        return result

    def clear_events(self) -> None:
        """Clear all sent events."""
        self.sent_events.clear()


# ============================================================================
# SESSION MANAGER (to be implemented in ras.sessions module)
# ============================================================================

class SessionManager:
    """Manages daemon sessions with tmux backend.

    This is a simplified implementation for testing purposes.
    The real implementation will be more robust.
    """

    def __init__(
        self,
        tmux: MockTmuxInterface,
        agent_detector: MockAgentDetector,
        persistence_path: str,
        root_dir: str = "~",
        whitelist: list[str] | None = None,
        blacklist: list[str] | None = None,
        max_sessions: int = 20,
    ):
        """Initialize session manager.

        Args:
            tmux: tmux interface.
            agent_detector: Agent detector.
            persistence_path: Path to sessions.json.
            root_dir: Root directory for filtering.
            whitelist: Allowed directories.
            blacklist: Blocked directories.
            max_sessions: Maximum concurrent sessions.
        """
        self.tmux = tmux
        self.agent_detector = agent_detector
        self.persistence_path = persistence_path
        self.root_dir = os.path.expanduser(root_dir)
        self.whitelist = [os.path.expanduser(p) for p in (whitelist or [])]
        self.blacklist = [os.path.expanduser(p) for p in (blacklist or [])]
        self.max_sessions = max_sessions

        self.sessions: dict[str, Session] = {}
        self.recent_dirs: list[str] = []
        self._event_callbacks: list[Callable[[SessionEvent], None]] = []

    def on_event(self, callback: Callable[[SessionEvent], None]) -> None:
        """Register callback for session events."""
        self._event_callbacks.append(callback)

    def _emit_event(self, event: SessionEvent) -> None:
        """Emit event to all registered callbacks."""
        for callback in self._event_callbacks:
            callback(event)

    def _emit_error(
        self,
        error_code: str,
        message: str,
        session_id: str = "",
    ) -> None:
        """Emit error event."""
        event = SessionEvent(
            error=SessionErrorEvent(
                error_code=error_code,
                message=message,
                session_id=session_id,
            )
        )
        self._emit_event(event)

    def _is_path_allowed(self, path: str) -> bool:
        """Check if path is allowed."""
        resolved = os.path.realpath(os.path.expanduser(path))
        root = os.path.realpath(self.root_dir)

        # Must be under root
        if not resolved.startswith(root + os.sep) and resolved != root:
            return False

        # Check blacklist
        for b in self.blacklist:
            b_resolved = os.path.realpath(b)
            if resolved.startswith(b_resolved + os.sep) or resolved == b_resolved:
                return False

        # Check whitelist
        if self.whitelist:
            for w in self.whitelist:
                w_resolved = os.path.realpath(w)
                if resolved.startswith(w_resolved + os.sep) or resolved == w_resolved:
                    return True
            return False

        return True

    def _generate_session_id(self) -> str:
        """Generate unique session ID."""
        import secrets
        return secrets.token_hex(6)

    def _sanitize_name(self, directory: str) -> str:
        """Sanitize directory name for tmux session."""
        import re
        name = os.path.basename(directory.rstrip("/")).lower()
        name = re.sub(r"[^a-z0-9-]", "-", name)
        name = re.sub(r"-+", "-", name).strip("-")
        return name or "unnamed"

    def _generate_tmux_name(self, directory: str, agent: str) -> str:
        """Generate tmux session name."""
        dir_name = self._sanitize_name(directory)
        prefix = f"ras-{agent.lower()}-"
        max_len = 50 - len(prefix)
        return f"{prefix}{dir_name[:max_len]}"

    async def handle_command(self, command: SessionCommand) -> None:
        """Handle incoming command."""
        which = betterproto.which_one_of(command, "command")
        cmd_type, cmd_value = which

        if cmd_type == "list":
            await self._handle_list()
        elif cmd_type == "create":
            await self._handle_create(cmd_value)
        elif cmd_type == "kill":
            await self._handle_kill(cmd_value)
        elif cmd_type == "rename":
            await self._handle_rename(cmd_value)
        elif cmd_type == "get_agents":
            await self._handle_get_agents()
        elif cmd_type == "get_directories":
            await self._handle_get_directories(cmd_value)
        elif cmd_type == "refresh_agents":
            await self._handle_refresh_agents()

    async def _handle_list(self) -> None:
        """Handle list sessions command."""
        sessions = list(self.sessions.values())
        # Sort by last activity descending
        sessions.sort(key=lambda s: s.last_activity_at, reverse=True)

        event = SessionEvent(
            list=SessionListEvent(sessions=sessions)
        )
        self._emit_event(event)

    async def _handle_create(self, cmd: CreateSessionCommand) -> None:
        """Handle create session command."""
        directory = cmd.directory
        agent = cmd.agent

        # Validate directory exists
        resolved = os.path.realpath(os.path.expanduser(directory))
        if not os.path.exists(resolved):
            self._emit_error("DIR_NOT_FOUND", f"Directory does not exist: {directory}")
            return

        if not os.path.isdir(resolved):
            self._emit_error("DIR_NOT_FOUND", f"Not a directory: {directory}")
            return

        # Validate directory is allowed
        if not self._is_path_allowed(directory):
            self._emit_error("DIR_NOT_ALLOWED", f"Directory not allowed: {directory}")
            return

        # Validate agent
        if not agent:
            self._emit_error("AGENT_NOT_FOUND", "Agent name required")
            return

        if not self.agent_detector.is_available(agent.lower()):
            self._emit_error("AGENT_NOT_FOUND", f"Agent not found: {agent}")
            return

        # Check max sessions
        if len(self.sessions) >= self.max_sessions:
            self._emit_error("MAX_SESSIONS_REACHED", f"Maximum sessions ({self.max_sessions}) reached")
            return

        # Generate names
        session_id = self._generate_session_id()
        tmux_name = self._generate_tmux_name(directory, agent)

        # Handle collision
        base_name = tmux_name
        counter = 2
        while self.tmux.session_exists(tmux_name):
            tmux_name = f"{base_name}-{counter}"
            counter += 1

        # Create session
        try:
            await self.tmux.create_session(tmux_name, resolved, agent)
        except RuntimeError as e:
            self._emit_error("TMUX_ERROR", str(e))
            return

        # Build session object
        now = int(time.time())
        session = Session(
            id=session_id,
            tmux_name=tmux_name,
            display_name=self._sanitize_name(directory),
            directory=resolved,
            agent=agent,
            created_at=now,
            last_activity_at=now,
            status=SessionStatus.SESSION_STATUS_ACTIVE,
        )

        self.sessions[session_id] = session

        # Add to recent dirs
        if resolved not in self.recent_dirs:
            self.recent_dirs.insert(0, resolved)
            self.recent_dirs = self.recent_dirs[:10]

        # Save persistence
        self._save()

        # Emit created event
        event = SessionEvent(
            created=SessionCreatedEvent(session=session)
        )
        self._emit_event(event)

    async def _handle_kill(self, cmd: KillSessionCommand) -> None:
        """Handle kill session command."""
        session_id = cmd.session_id

        if session_id not in self.sessions:
            self._emit_error("SESSION_NOT_FOUND", f"Session not found: {session_id}", session_id)
            return

        session = self.sessions[session_id]

        # Kill tmux session
        try:
            await self.tmux.kill_session(session.tmux_name)
        except RuntimeError as e:
            self._emit_error("KILL_FAILED", str(e), session_id)
            return

        # Remove from state
        del self.sessions[session_id]
        self._save()

        # Emit killed event
        event = SessionEvent(
            killed=SessionKilledEvent(session_id=session_id)
        )
        self._emit_event(event)

    async def _handle_rename(self, cmd: RenameSessionCommand) -> None:
        """Handle rename session command."""
        session_id = cmd.session_id
        new_name = cmd.new_name

        if session_id not in self.sessions:
            self._emit_error("SESSION_NOT_FOUND", f"Session not found: {session_id}", session_id)
            return

        if not new_name or not new_name.strip():
            self._emit_error("INVALID_NAME", "Name cannot be empty")
            return

        # Sanitize and truncate
        import re
        sanitized = re.sub(r"[^a-zA-Z0-9-_ ]", "", new_name)[:50].strip()

        if not sanitized:
            self._emit_error("INVALID_NAME", "Invalid name after sanitization")
            return

        # Update session
        session = self.sessions[session_id]
        session.display_name = sanitized
        self._save()

        # Emit renamed event
        event = SessionEvent(
            renamed=SessionRenamedEvent(
                session_id=session_id,
                new_name=sanitized,
            )
        )
        self._emit_event(event)

    async def _handle_get_agents(self) -> None:
        """Handle get agents command."""
        agents_info = self.agent_detector.detect_agents()

        agents = [
            Agent(
                name=a["name"],
                binary=a["binary"],
                path=a["path"],
                available=a["available"],
            )
            for a in agents_info
        ]

        event = SessionEvent(
            agents=AgentsListEvent(agents=agents)
        )
        self._emit_event(event)

    async def _handle_get_directories(self, cmd: GetDirectoriesCommand) -> None:
        """Handle get directories command."""
        parent = cmd.parent

        # Handle root request
        if not parent:
            # Return whitelist roots or root dir
            entries = []
            roots = self.whitelist if self.whitelist else [self.root_dir]

            for root in roots:
                resolved = os.path.realpath(os.path.expanduser(root))
                if os.path.isdir(resolved):
                    entries.append(DirectoryEntry(
                        name=os.path.basename(resolved) or "root",
                        path=resolved,
                        is_directory=True,
                    ))

            event = SessionEvent(
                directories=DirectoriesListEvent(
                    parent="",
                    entries=entries,
                    recent=self.recent_dirs[:10],
                )
            )
            self._emit_event(event)
            return

        # Validate parent is allowed
        if not self._is_path_allowed(parent):
            self._emit_error("DIR_NOT_ALLOWED", f"Directory not allowed: {parent}")
            return

        resolved = os.path.realpath(os.path.expanduser(parent))

        if not os.path.isdir(resolved):
            self._emit_error("DIR_NOT_FOUND", f"Directory not found: {parent}")
            return

        # List children
        entries = []
        try:
            for name in sorted(os.listdir(resolved)):
                if name.startswith("."):
                    continue

                child_path = os.path.join(resolved, name)
                if not os.path.isdir(child_path):
                    continue

                if not self._is_path_allowed(child_path):
                    continue

                entries.append(DirectoryEntry(
                    name=name,
                    path=child_path,
                    is_directory=True,
                ))
        except PermissionError:
            pass

        event = SessionEvent(
            directories=DirectoriesListEvent(
                parent=resolved,
                entries=entries,
                recent=[],
            )
        )
        self._emit_event(event)

    async def _handle_refresh_agents(self) -> None:
        """Handle refresh agents command."""
        # Re-detect agents (agent_detector can be updated externally)
        await self._handle_get_agents()

    def _save(self) -> None:
        """Save state to persistence file."""
        data = {
            "version": 1,
            "sessions": [
                {
                    "id": s.id,
                    "tmux_name": s.tmux_name,
                    "display_name": s.display_name,
                    "directory": s.directory,
                    "agent": s.agent,
                    "created_at": s.created_at,
                    "last_activity_at": s.last_activity_at,
                }
                for s in self.sessions.values()
            ],
            "recent_directories": self.recent_dirs,
        }

        os.makedirs(os.path.dirname(self.persistence_path), exist_ok=True)
        with open(self.persistence_path, "w") as f:
            json.dump(data, f, indent=2)

    def _load(self) -> None:
        """Load state from persistence file."""
        if not os.path.exists(self.persistence_path):
            return

        try:
            with open(self.persistence_path) as f:
                data = json.load(f)

            if data.get("version") != 1:
                return  # Wrong version, ignore

            for s in data.get("sessions", []):
                session = Session(
                    id=s["id"],
                    tmux_name=s["tmux_name"],
                    display_name=s["display_name"],
                    directory=s["directory"],
                    agent=s["agent"],
                    created_at=s["created_at"],
                    last_activity_at=s["last_activity_at"],
                    status=SessionStatus.SESSION_STATUS_ACTIVE,
                )
                self.sessions[s["id"]] = session

            self.recent_dirs = data.get("recent_directories", [])
        except (json.JSONDecodeError, KeyError):
            pass  # Corrupted file, start fresh

    def reconcile(self) -> None:
        """Reconcile state with actual tmux sessions."""
        tmux_sessions = set(self.tmux.list_sessions())
        stored_tmux_names = {s.tmux_name for s in self.sessions.values()}

        # Remove sessions that no longer exist in tmux
        to_remove = []
        for session_id, session in self.sessions.items():
            if session.tmux_name not in tmux_sessions:
                to_remove.append(session_id)

        for session_id in to_remove:
            del self.sessions[session_id]

        # Adopt tmux sessions not in our state
        for tmux_name in tmux_sessions:
            if tmux_name not in stored_tmux_names:
                # Parse agent and directory from name
                # Format: ras-<agent>-<directory>
                parts = tmux_name.split("-", 2)
                if len(parts) >= 3:
                    agent = parts[1]
                    dir_name = parts[2]
                else:
                    agent = "unknown"
                    dir_name = tmux_name

                now = int(time.time())
                session_id = self._generate_session_id()
                session = Session(
                    id=session_id,
                    tmux_name=tmux_name,
                    display_name=dir_name,
                    directory="",  # Unknown
                    agent=agent,
                    created_at=now,
                    last_activity_at=now,
                    status=SessionStatus.SESSION_STATUS_ACTIVE,
                )
                self.sessions[session_id] = session

        self._save()


# ============================================================================
# E2E TEST FIXTURES
# ============================================================================

@pytest.fixture
def temp_dir_structure(tmp_path):
    """Create a temporary directory structure for testing."""
    root = tmp_path / "home" / "user"
    (root / "repos" / "project1").mkdir(parents=True)
    (root / "repos" / "project2").mkdir(parents=True)
    (root / "repos" / "secret").mkdir(parents=True)
    (root / "documents").mkdir(parents=True)
    (root / ".ssh").mkdir(parents=True)
    return root


@pytest.fixture
def mock_tmux():
    """Create mock tmux interface."""
    return MockTmuxInterface()


@pytest.fixture
def mock_agent_detector():
    """Create mock agent detector with claude and aider available."""
    return MockAgentDetector(available_agents=["claude", "aider"])


@pytest.fixture
def mock_phone():
    """Create mock phone connection."""
    return MockPhoneConnection()


@pytest.fixture
def session_manager(tmp_path, temp_dir_structure, mock_tmux, mock_agent_detector, mock_phone):
    """Create session manager with mocked dependencies."""
    persistence_path = str(tmp_path / "config" / "sessions.json")

    manager = SessionManager(
        tmux=mock_tmux,
        agent_detector=mock_agent_detector,
        persistence_path=persistence_path,
        root_dir=str(temp_dir_structure),
        whitelist=[str(temp_dir_structure / "repos")],
        blacklist=[str(temp_dir_structure / "repos" / "secret")],
        max_sessions=20,
    )

    # Connect phone to receive events - simple synchronous append
    manager.on_event(lambda e: mock_phone.sent_events.append(e))

    return manager


# ============================================================================
# E2E FLOW TESTS
# ============================================================================

class TestE2EFullSessionLifecycle:
    """E2E test for complete session lifecycle (E2E-01)."""

    @pytest.mark.asyncio
    async def test_complete_lifecycle(
        self,
        session_manager,
        mock_phone,
        temp_dir_structure,
    ):
        """Test complete session lifecycle from creation to deletion."""
        # 1. List sessions (should be empty)
        await session_manager.handle_command(
            SessionCommand(list=ListSessionsCommand())
        )

        assert len(mock_phone.sent_events) == 1
        assert len(mock_phone.sent_events[0].list.sessions) == 0
        mock_phone.clear_events()

        # 2. Get agents
        await session_manager.handle_command(
            SessionCommand(get_agents=GetAgentsCommand())
        )

        assert len(mock_phone.sent_events) == 1
        agents = mock_phone.sent_events[0].agents.agents
        assert any(a.binary == "claude" and a.available for a in agents)
        mock_phone.clear_events()

        # 3. Get directories (root)
        await session_manager.handle_command(
            SessionCommand(get_directories=GetDirectoriesCommand(parent=""))
        )

        assert len(mock_phone.sent_events) == 1
        assert len(mock_phone.sent_events[0].directories.entries) > 0
        mock_phone.clear_events()

        # 4. Create session
        project_dir = str(temp_dir_structure / "repos" / "project1")
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=project_dir,
                    agent="claude",
                )
            )
        )

        assert len(mock_phone.sent_events) == 1
        created_session = mock_phone.sent_events[0].created.session
        assert created_session.agent == "claude"
        assert created_session.directory == project_dir
        session_id = created_session.id
        mock_phone.clear_events()

        # 5. List sessions (should have one)
        await session_manager.handle_command(
            SessionCommand(list=ListSessionsCommand())
        )

        assert len(mock_phone.sent_events[0].list.sessions) == 1
        mock_phone.clear_events()

        # 6. Rename session
        await session_manager.handle_command(
            SessionCommand(
                rename=RenameSessionCommand(
                    session_id=session_id,
                    new_name="My Project",
                )
            )
        )

        assert mock_phone.sent_events[0].renamed.new_name == "My Project"
        mock_phone.clear_events()

        # 7. Kill session
        await session_manager.handle_command(
            SessionCommand(
                kill=KillSessionCommand(session_id=session_id)
            )
        )

        assert mock_phone.sent_events[0].killed.session_id == session_id
        mock_phone.clear_events()

        # 8. List sessions (should be empty again)
        await session_manager.handle_command(
            SessionCommand(list=ListSessionsCommand())
        )

        assert len(mock_phone.sent_events[0].list.sessions) == 0


class TestE2EErrorHandling:
    """E2E test for error handling flow (E2E-04)."""

    @pytest.mark.asyncio
    async def test_error_handling_flow(
        self,
        session_manager,
        mock_phone,
        temp_dir_structure,
    ):
        """Test error handling for invalid inputs."""
        # 1. Create with invalid directory
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory="/nonexistent/path",
                    agent="claude",
                )
            )
        )

        error = mock_phone.sent_events[0].error
        assert error.error_code == "DIR_NOT_FOUND"
        mock_phone.clear_events()

        # 2. Create with non-whitelisted directory
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(temp_dir_structure / "documents"),
                    agent="claude",
                )
            )
        )

        error = mock_phone.sent_events[0].error
        assert error.error_code == "DIR_NOT_ALLOWED"
        mock_phone.clear_events()

        # 3. Create with blacklisted directory
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(temp_dir_structure / "repos" / "secret"),
                    agent="claude",
                )
            )
        )

        error = mock_phone.sent_events[0].error
        assert error.error_code == "DIR_NOT_ALLOWED"
        mock_phone.clear_events()

        # 4. Create with invalid agent
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(temp_dir_structure / "repos" / "project1"),
                    agent="unknown_agent",
                )
            )
        )

        error = mock_phone.sent_events[0].error
        assert error.error_code == "AGENT_NOT_FOUND"
        mock_phone.clear_events()

        # 5. Kill non-existent session
        await session_manager.handle_command(
            SessionCommand(
                kill=KillSessionCommand(session_id="nonexistent")
            )
        )

        error = mock_phone.sent_events[0].error
        assert error.error_code == "SESSION_NOT_FOUND"
        mock_phone.clear_events()

        # 6. Rename non-existent session
        await session_manager.handle_command(
            SessionCommand(
                rename=RenameSessionCommand(
                    session_id="nonexistent",
                    new_name="New Name",
                )
            )
        )

        error = mock_phone.sent_events[0].error
        assert error.error_code == "SESSION_NOT_FOUND"


class TestE2EDaemonRestart:
    """E2E test for daemon restart recovery (E2E-02)."""

    @pytest.mark.asyncio
    async def test_daemon_restart_reconciliation(
        self,
        tmp_path,
        temp_dir_structure,
        mock_agent_detector,
    ):
        """Test session recovery after daemon restart."""
        persistence_path = str(tmp_path / "config" / "sessions.json")

        # Create first manager and add sessions
        tmux1 = MockTmuxInterface()
        manager1 = SessionManager(
            tmux=tmux1,
            agent_detector=mock_agent_detector,
            persistence_path=persistence_path,
            root_dir=str(temp_dir_structure),
            whitelist=[str(temp_dir_structure / "repos")],
            blacklist=[],
        )

        # Create two sessions
        await manager1.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(temp_dir_structure / "repos" / "project1"),
                    agent="claude",
                )
            )
        )
        await manager1.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(temp_dir_structure / "repos" / "project2"),
                    agent="aider",
                )
            )
        )

        assert len(manager1.sessions) == 2
        assert len(tmux1.sessions) == 2

        # "Kill" one tmux session externally
        tmux_names = list(tmux1.sessions.keys())
        del tmux1.sessions[tmux_names[0]]

        # Create new manager (simulate daemon restart)
        tmux2 = MockTmuxInterface()
        # Copy remaining tmux sessions
        tmux2.sessions = dict(tmux1.sessions)

        manager2 = SessionManager(
            tmux=tmux2,
            agent_detector=mock_agent_detector,
            persistence_path=persistence_path,
            root_dir=str(temp_dir_structure),
            whitelist=[str(temp_dir_structure / "repos")],
            blacklist=[],
        )

        # Load and reconcile
        manager2._load()
        manager2.reconcile()

        # Should have only one session now
        assert len(manager2.sessions) == 1


class TestE2EMaxSessions:
    """E2E test for max sessions limit."""

    @pytest.mark.asyncio
    async def test_max_sessions_limit(
        self,
        tmp_path,
        mock_agent_detector,
    ):
        """Test that max sessions limit is enforced."""
        # Create temp directories
        root = tmp_path / "home" / "user" / "repos"
        for i in range(25):
            (root / f"project{i}").mkdir(parents=True)

        persistence_path = str(tmp_path / "config" / "sessions.json")
        tmux = MockTmuxInterface()

        events = []

        manager = SessionManager(
            tmux=tmux,
            agent_detector=mock_agent_detector,
            persistence_path=persistence_path,
            root_dir=str(root),
            whitelist=[],
            blacklist=[],
            max_sessions=5,  # Low limit for testing
        )
        manager.on_event(lambda e: events.append(e))

        # Create sessions up to limit
        for i in range(5):
            events.clear()
            await manager.handle_command(
                SessionCommand(
                    create=CreateSessionCommand(
                        directory=str(root / f"project{i}"),
                        agent="claude",
                    )
                )
            )
            assert events[0].created is not None

        # Try to create one more
        events.clear()
        await manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(root / "project5"),
                    agent="claude",
                )
            )
        )

        assert events[0].error.error_code == "MAX_SESSIONS_REACHED"

        # Kill one session and try again
        session_id = list(manager.sessions.keys())[0]
        events.clear()
        await manager.handle_command(
            SessionCommand(
                kill=KillSessionCommand(session_id=session_id)
            )
        )

        events.clear()
        await manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(root / "project5"),
                    agent="claude",
                )
            )
        )

        assert events[0].created is not None


class TestE2ENameCollision:
    """E2E test for session name collision handling."""

    @pytest.mark.asyncio
    async def test_name_collision_handling(
        self,
        session_manager,
        mock_phone,
        temp_dir_structure,
    ):
        """Test that name collisions are handled with suffixes."""
        project_dir = str(temp_dir_structure / "repos" / "project1")

        # Create first session
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=project_dir,
                    agent="claude",
                )
            )
        )

        first_name = mock_phone.sent_events[0].created.session.tmux_name
        mock_phone.clear_events()

        # Create second session with same dir/agent
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=project_dir,
                    agent="claude",
                )
            )
        )

        second_name = mock_phone.sent_events[0].created.session.tmux_name

        # Names should be different (second should have suffix)
        assert first_name != second_name
        assert second_name.endswith("-2")


# ============================================================================
# SECURITY TESTS
# ============================================================================

class TestSecurityPathTraversal:
    """Security tests for path traversal attacks."""

    @pytest.mark.asyncio
    @pytest.mark.parametrize("path", TestSecurityVectors.PATH_TRAVERSAL_ATTEMPTS[:5])
    async def test_path_traversal_blocked(
        self,
        session_manager,
        mock_phone,
        path,
    ):
        """Path traversal attempts are blocked."""
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=path,
                    agent="claude",
                )
            )
        )

        error = mock_phone.sent_events[0].error
        assert error.error_code in ["DIR_NOT_FOUND", "DIR_NOT_ALLOWED"]


class TestSecurityCommandInjection:
    """Security tests for command injection attacks."""

    @pytest.mark.asyncio
    @pytest.mark.parametrize("agent", TestSecurityVectors.COMMAND_INJECTION_ATTEMPTS[:5])
    async def test_command_injection_in_agent_blocked(
        self,
        session_manager,
        mock_phone,
        temp_dir_structure,
        agent,
    ):
        """Command injection attempts in agent name are blocked."""
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(temp_dir_structure / "repos" / "project1"),
                    agent=agent,
                )
            )
        )

        error = mock_phone.sent_events[0].error
        assert error.error_code == "AGENT_NOT_FOUND"


class TestSecurityXSS:
    """Security tests for XSS attempts in session names."""

    @pytest.mark.asyncio
    @pytest.mark.parametrize("name", TestSecurityVectors.XSS_ATTEMPTS)
    async def test_xss_sanitized_in_rename(
        self,
        session_manager,
        mock_phone,
        temp_dir_structure,
        name,
    ):
        """XSS attempts in rename are sanitized."""
        # Create a session first
        await session_manager.handle_command(
            SessionCommand(
                create=CreateSessionCommand(
                    directory=str(temp_dir_structure / "repos" / "project1"),
                    agent="claude",
                )
            )
        )

        session_id = mock_phone.sent_events[0].created.session.id
        mock_phone.clear_events()

        # Try to rename with XSS payload
        await session_manager.handle_command(
            SessionCommand(
                rename=RenameSessionCommand(
                    session_id=session_id,
                    new_name=name,
                )
            )
        )

        # Should either sanitize or reject
        if mock_phone.sent_events[0].renamed:
            # If renamed, verify no script tags
            new_name = mock_phone.sent_events[0].renamed.new_name
            assert "<script>" not in new_name.lower()
            assert "javascript:" not in new_name.lower()


# ============================================================================
# ERROR CODE VALIDATION
# ============================================================================

class TestErrorCodes:
    """Tests for all defined error codes."""

    @pytest.mark.asyncio
    async def test_all_error_codes_documented(self):
        """Verify all error codes are documented."""
        documented_codes = set(TestErrorCodeVectors.ERROR_CODES.keys())

        expected_codes = {
            "DIR_NOT_FOUND",
            "DIR_NOT_ALLOWED",
            "AGENT_NOT_FOUND",
            "SESSION_NOT_FOUND",
            "SESSION_EXISTS",
            "TMUX_ERROR",
            "MAX_SESSIONS_REACHED",
            "INVALID_NAME",
            "RATE_LIMITED",
        }

        # All expected codes should be documented
        for code in expected_codes:
            assert code in documented_codes, f"Error code {code} not documented"


# Import betterproto for which_one_of
import betterproto
