"""Directory browsing for session creation."""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Callable

from ras.proto.ras.ras import DirectoriesListEvent, DirectoryEntry

logger = logging.getLogger(__name__)


class DirectoryBrowser:
    """Browse directories for session creation.

    Respects root, whitelist, blacklist, and hidden file settings.
    """

    def __init__(
        self,
        config: dict | None = None,
        listdir_func: Callable[[str], list[str]] | None = None,
    ):
        """Initialize directory browser.

        Args:
            config: Configuration with 'root', 'whitelist', 'blacklist', 'show_hidden'.
            listdir_func: Injection point for os.listdir (for testing).
        """
        self._config = config or {}
        self._listdir = listdir_func or os.listdir

    async def list(
        self,
        parent: str = "",
        recent: list[str] | None = None,
    ) -> DirectoriesListEvent:
        """List directories under parent.

        Args:
            parent: Parent directory path. Empty string means root.
            recent: List of recent directories to include at top level.

        Returns:
            DirectoriesListEvent with directory entries.
        """
        return await asyncio.to_thread(self._list_sync, parent, recent)

    def _list_sync(
        self,
        parent: str,
        recent: list[str] | None,
    ) -> DirectoriesListEvent:
        """Synchronous directory listing."""
        recent = recent or []
        root = os.path.expanduser(self._config.get("root", "~"))
        show_hidden = self._config.get("show_hidden", False)
        blacklist = self._config.get("blacklist", [])

        # Determine actual parent path
        if not parent:
            parent_path = os.path.realpath(root)
        else:
            parent_path = os.path.realpath(os.path.expanduser(parent))

        # Validate parent is under root
        root_resolved = os.path.realpath(root)
        if not self._is_under(parent_path, root_resolved):
            logger.warning(f"Parent '{parent}' is outside root")
            return DirectoriesListEvent(
                parent=parent,
                entries=[],
                recent=recent if not parent else [],
            )

        entries: list[DirectoryEntry] = []

        try:
            items = self._listdir(parent_path)
        except OSError as e:
            logger.warning(f"Failed to list directory: {e}")
            return DirectoriesListEvent(
                parent=parent,
                entries=[],
                recent=recent if not parent else [],
            )

        # Sort alphabetically, directories first
        items.sort(key=lambda x: (not self._is_dir(parent_path, x), x.lower()))

        for name in items:
            # Skip hidden files unless configured to show
            if not show_hidden and name.startswith("."):
                continue

            full_path = os.path.join(parent_path, name)

            # Skip non-directories
            if not os.path.isdir(full_path):
                continue

            # Skip unreadable directories
            if not os.access(full_path, os.R_OK):
                continue

            # Skip blacklisted
            if self._is_blacklisted(full_path, root_resolved, blacklist):
                continue

            entries.append(
                DirectoryEntry(
                    name=name,
                    path=full_path,
                    is_directory=True,
                )
            )

        return DirectoriesListEvent(
            parent=parent_path,
            entries=entries,
            # Only include recent dirs at root level
            recent=recent if not parent else [],
        )

    def _is_under(self, path: str, parent: str) -> bool:
        """Check if path is under parent."""
        path = os.path.normpath(path)
        parent = os.path.normpath(parent)
        return path == parent or path.startswith(parent + os.sep)

    def _is_dir(self, parent: str, name: str) -> bool:
        """Check if name is a directory under parent."""
        try:
            return os.path.isdir(os.path.join(parent, name))
        except OSError:
            return False

    def _is_blacklisted(
        self,
        path: str,
        root: str,
        blacklist: list[str],
    ) -> bool:
        """Check if path is in blacklist."""
        import fnmatch

        for pattern in blacklist:
            # Expand pattern
            expanded = os.path.expanduser(pattern)

            if "*" in expanded:
                # Glob pattern
                base_dir = os.path.dirname(expanded)
                glob_pattern = os.path.basename(expanded)
                base_resolved = os.path.realpath(base_dir)

                if self._is_under(path, base_resolved):
                    rel = os.path.relpath(path, base_resolved)
                    first_component = rel.split(os.sep)[0]
                    if fnmatch.fnmatch(first_component, glob_pattern):
                        return True
            else:
                # Exact path
                pattern_resolved = os.path.realpath(expanded)
                if self._is_under(path, pattern_resolved):
                    return True

        return False
