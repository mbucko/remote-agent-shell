"""Agent detection for AI coding tools."""

import asyncio
import logging
import shutil
from dataclasses import dataclass
from typing import Callable

from ras.proto.ras.ras import Agent, AgentsListEvent

logger = logging.getLogger(__name__)


@dataclass
class AgentInfo:
    """Internal agent information."""

    name: str
    binary: str
    path: str
    available: bool


class AgentDetector:
    """Detects installed AI coding agents.

    Scans PATH for known agent binaries and reports availability.
    """

    # Default agents to detect
    BUILTIN_AGENTS: dict[str, str] = {
        "claude": "Claude Code",
        "aider": "Aider",
        "cursor": "Cursor",
        "cline": "Cline",
        "opencode": "Open Code",
        "codex": "Codex",
    }

    def __init__(
        self,
        config: dict | None = None,
        which_func: Callable[[str], str | None] | None = None,
    ):
        """Initialize agent detector.

        Args:
            config: Configuration dict with optional 'builtin' and 'names' keys.
            which_func: Injection point for shutil.which (for testing).
        """
        self._config = config or {}
        self._agents: dict[str, AgentInfo] = {}
        self._which = which_func or shutil.which

    async def initialize(self) -> None:
        """Scan for installed agents."""
        await self.refresh()

    async def refresh(self) -> None:
        """Re-scan for installed agents."""
        # Run in thread pool since shutil.which can be slow
        await asyncio.to_thread(self._scan_agents)

    def _scan_agents(self) -> None:
        """Synchronous agent scanning."""
        builtin = self._config.get("builtin", list(self.BUILTIN_AGENTS.keys()))
        names = self._config.get("names", self.BUILTIN_AGENTS)

        self._agents = {}
        for binary in builtin:
            display_name = names.get(binary, binary.title())
            path = self._which(binary)

            self._agents[binary] = AgentInfo(
                name=display_name,
                binary=binary,
                path=path or "",
                available=path is not None,
            )

            if path:
                logger.debug(f"Found agent: {binary} at {path}")
            else:
                logger.debug(f"Agent not found: {binary}")

    async def get_all(self) -> AgentsListEvent:
        """Get all known agents (available and unavailable).

        Returns:
            AgentsListEvent with all agents.
        """
        agents = [
            Agent(
                name=info.name,
                binary=info.binary,
                path=info.path,
                available=info.available,
            )
            for info in self._agents.values()
        ]
        return AgentsListEvent(agents=agents)

    async def get_available(self) -> dict[str, AgentInfo]:
        """Get only available agents.

        Returns:
            Dict mapping binary name to AgentInfo for available agents.
        """
        return {
            binary: info for binary, info in self._agents.items() if info.available
        }

    def get_agent(self, binary: str) -> AgentInfo | None:
        """Get info for a specific agent.

        Args:
            binary: Agent binary name.

        Returns:
            AgentInfo or None if not found.
        """
        return self._agents.get(binary)
