"""Session persistence via JSON file."""

import asyncio
import json
import logging
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable

logger = logging.getLogger(__name__)


@dataclass
class PersistedSession:
    """Session data for persistence."""

    id: str
    tmux_name: str
    display_name: str
    directory: str
    agent: str
    created_at: int
    last_activity_at: int
    status: int  # SessionStatus enum value


class SessionPersistence:
    """Handles loading and saving session state to JSON.

    Uses atomic writes to prevent corruption.
    """

    def __init__(
        self,
        path: Path | str | None = None,
        max_recent_dirs: int = 10,
        file_ops: dict[str, Callable[..., Any]] | None = None,
    ):
        """Initialize persistence.

        Args:
            path: Path to JSON file. Defaults to ~/.config/ras/sessions.json.
            max_recent_dirs: Maximum recent directories to track.
            file_ops: Injectable file operations for testing.
        """
        if path is None:
            path = Path.home() / ".config" / "ras" / "sessions.json"
        self._path = Path(path)
        self._max_recent_dirs = max_recent_dirs
        self._recent_dirs: list[str] = []

        # Allow injection for testing
        self._file_ops = file_ops or {
            "exists": lambda p: p.exists(),
            "read": lambda p: p.read_text(),
            "write": self._atomic_write,
            "mkdir": lambda p: p.mkdir(parents=True, exist_ok=True),
        }

    def _atomic_write(self, path: Path, content: str) -> None:
        """Write atomically via temp file and rename."""
        temp_path = path.with_suffix(".tmp")
        temp_path.write_text(content)
        temp_path.rename(path)

    async def load(self) -> list[PersistedSession]:
        """Load sessions from JSON file.

        Returns:
            List of persisted sessions, or empty list if file doesn't exist.
        """
        # Run blocking I/O in thread pool
        return await asyncio.to_thread(self._load_sync)

    def _load_sync(self) -> list[PersistedSession]:
        """Synchronous load implementation."""
        if not self._file_ops["exists"](self._path):
            return []

        try:
            content = self._file_ops["read"](self._path)
            data = json.loads(content)

            sessions = []
            for item in data.get("sessions", []):
                try:
                    sessions.append(
                        PersistedSession(
                            id=item["id"],
                            tmux_name=item["tmux_name"],
                            display_name=item["display_name"],
                            directory=item["directory"],
                            agent=item["agent"],
                            created_at=item["created_at"],
                            last_activity_at=item["last_activity_at"],
                            status=item.get("status", 1),  # Default to ACTIVE
                        )
                    )
                except (KeyError, TypeError) as e:
                    logger.warning(f"Skipping invalid session entry: {e}")

            # Load recent directories
            self._recent_dirs = data.get("recent_directories", [])

            logger.debug(f"Loaded {len(sessions)} sessions from {self._path}")
            return sessions

        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON in sessions file: {e}")
            return []
        except OSError as e:
            logger.error(f"Failed to read sessions file: {e}")
            return []

    async def save(self, sessions: list[PersistedSession]) -> None:
        """Save sessions to JSON file.

        Args:
            sessions: List of sessions to save.
        """
        await asyncio.to_thread(self._save_sync, sessions)

    def _save_sync(self, sessions: list[PersistedSession]) -> None:
        """Synchronous save implementation."""
        # Ensure parent directory exists
        self._file_ops["mkdir"](self._path.parent)

        data = {
            "sessions": [asdict(s) for s in sessions],
            "recent_directories": self._recent_dirs[: self._max_recent_dirs],
        }

        content = json.dumps(data, indent=2)
        self._file_ops["write"](self._path, content)

        logger.debug(f"Saved {len(sessions)} sessions to {self._path}")

    def add_recent_directory(self, directory: str) -> None:
        """Add a directory to the recent list.

        Args:
            directory: Directory path to add.
        """
        # Remove if already exists (will be re-added at front)
        if directory in self._recent_dirs:
            self._recent_dirs.remove(directory)

        # Add to front
        self._recent_dirs.insert(0, directory)

        # Trim to max
        self._recent_dirs = self._recent_dirs[: self._max_recent_dirs]

    def get_recent_directories(self) -> list[str]:
        """Get list of recently used directories.

        Returns:
            List of directory paths, most recent first.
        """
        return self._recent_dirs.copy()
