"""Protocols and enums for RAS daemon."""

from dataclasses import dataclass
from enum import Enum
from typing import Protocol


class StunClientProtocol(Protocol):
    """Protocol for STUN client implementations."""

    async def get_public_ip(self) -> tuple[str, int]:
        """Returns (public_ip, public_port). Raises StunError on failure."""
        ...


class ConnectionState(Enum):
    """State of a P2P connection."""

    IDLE = "idle"
    SIGNALING = "signaling"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    FAILED = "failed"


class PeerState(Enum):
    """State of a WebRTC peer connection."""

    NEW = "new"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    FAILED = "failed"
    CLOSED = "closed"


# ============================================================================
# tmux Data Classes
# ============================================================================


@dataclass(frozen=True)
class TmuxSessionInfo:
    """Information about a tmux session."""

    id: str  # e.g., "$0"
    name: str  # e.g., "claude"
    windows: int  # Number of windows
    attached: bool  # Is someone attached?


@dataclass(frozen=True)
class TmuxWindowInfo:
    """Information about a tmux window."""

    id: str  # e.g., "@0"
    name: str  # e.g., "main"
    active: bool  # Is this the active window?


# ============================================================================
# tmux Events (for control mode)
# ============================================================================


@dataclass
class TmuxEvent:
    """Base class for tmux events."""

    session_id: str


@dataclass
class OutputEvent(TmuxEvent):
    """Output from a tmux pane."""

    pane_id: str
    data: bytes


@dataclass
class WindowAddEvent(TmuxEvent):
    """Window was added."""

    window_id: str


@dataclass
class WindowCloseEvent(TmuxEvent):
    """Window was closed."""

    window_id: str


@dataclass
class WindowRenamedEvent(TmuxEvent):
    """Window was renamed."""

    window_id: str
    name: str


@dataclass
class SessionChangedEvent(TmuxEvent):
    """Session changed."""

    name: str


@dataclass
class ExitEvent(TmuxEvent):
    """tmux control mode exited."""

    pass


# ============================================================================
# tmux Protocols
# ============================================================================


class CommandExecutorProtocol(Protocol):
    """Protocol for executing shell commands (DI for testing)."""

    async def run(
        self, *args: str, check: bool = True
    ) -> tuple[bytes, bytes, int]:
        """Run command and return (stdout, stderr, returncode)."""
        ...


class ControlModeProcessProtocol(Protocol):
    """Protocol for control mode subprocess (DI for testing)."""

    async def start(self) -> None:
        """Start the control mode process."""
        ...

    async def stop(self) -> None:
        """Stop the control mode process."""
        ...

    async def readline(self) -> bytes:
        """Read a line from stdout."""
        ...

    def is_running(self) -> bool:
        """Check if process is still running."""
        ...
