"""Input validation for session management."""

import fnmatch
import os
import re
import secrets
import string
from typing import Any

# Session ID configuration
SESSION_ID_LENGTH = 12
SESSION_ID_ALPHABET = string.ascii_letters + string.digits  # a-zA-Z0-9


def generate_session_id() -> str:
    """Generate a cryptographically secure session ID.

    Returns:
        12-character alphanumeric string.
    """
    return "".join(secrets.choice(SESSION_ID_ALPHABET) for _ in range(SESSION_ID_LENGTH))


def validate_directory(
    path: str,
    root: str,
    whitelist: list[str] | None = None,
    blacklist: list[str] | None = None,
) -> str | None:
    """Validate directory path.

    Args:
        path: Path to validate.
        root: Root directory (all paths must be under this).
        whitelist: If non-empty, path must be under one of these.
        blacklist: Path must not be under any of these.

    Returns:
        Error message or None if valid.
    """
    whitelist = whitelist or []
    blacklist = blacklist or []

    if not path:
        return "Directory path is required"

    # Expand and resolve to catch path traversal
    try:
        resolved = os.path.realpath(os.path.expanduser(path))
        root_resolved = os.path.realpath(os.path.expanduser(root))
    except (ValueError, OSError) as e:
        return f"Invalid path: {e}"

    # Must exist
    if not os.path.exists(resolved):
        return "Directory does not exist"

    # Must be a directory
    if not os.path.isdir(resolved):
        return "Path is not a directory"

    # Must be readable
    if not os.access(resolved, os.R_OK):
        return "Directory is not readable"

    # Must be under root (prevent path traversal)
    if not _is_under(resolved, root_resolved):
        return "Directory is not allowed (outside root)"

    # Check whitelist (if specified, must be under at least one)
    if whitelist:
        allowed = False
        for w in whitelist:
            w_resolved = os.path.realpath(os.path.expanduser(w))
            if _is_under(resolved, w_resolved):
                allowed = True
                break
        if not allowed:
            return "Directory is not allowed (not in whitelist)"

    # Check blacklist
    for b in blacklist:
        # Handle glob patterns like ~/.*
        if "*" in b:
            b_expanded = os.path.expanduser(b)
            base_dir = os.path.dirname(b_expanded)
            pattern = os.path.basename(b_expanded)
            base_resolved = os.path.realpath(base_dir)

            # Check if resolved is under base_dir and matches pattern
            if _is_under(resolved, base_resolved):
                rel = os.path.relpath(resolved, base_resolved)
                first_component = rel.split(os.sep)[0]
                if fnmatch.fnmatch(first_component, pattern):
                    return "Directory is not allowed (blacklisted)"
        else:
            b_resolved = os.path.realpath(os.path.expanduser(b))
            if _is_under(resolved, b_resolved):
                return "Directory is not allowed (blacklisted)"

    return None


def _is_under(path: str, parent: str) -> bool:
    """Check if path is under (or equal to) parent."""
    # Normalize paths
    path = os.path.normpath(path)
    parent = os.path.normpath(parent)

    # Same path is allowed
    if path == parent:
        return True

    # Must start with parent + separator
    return path.startswith(parent + os.sep)


def validate_agent(agent: str, available: dict[str, Any]) -> str | None:
    """Validate agent.

    Args:
        agent: Agent identifier (e.g., "claude").
        available: Dict of available agents (from AgentDetector.get_available()).

    Returns:
        Error message or None if valid.
    """
    if not agent:
        return "Agent is required"

    if agent not in available:
        return f"Agent '{agent}' is not installed"

    agent_info = available[agent]
    if hasattr(agent_info, "available") and not agent_info.available:
        return f"Agent '{agent}' binary not found"

    return None


def validate_session_id(session_id: str) -> str | None:
    """Validate session ID format.

    Session IDs must be exactly 12 alphanumeric characters (a-zA-Z0-9).

    Args:
        session_id: Session ID to validate.

    Returns:
        Error message or None if valid.
    """
    if not session_id:
        return "Session ID is required"

    # Check for path traversal attempts
    if ".." in session_id or "/" in session_id or "\x00" in session_id:
        return "Invalid session ID format"

    # Session IDs are 12 alphanumeric characters
    if not re.match(r"^[a-zA-Z0-9]{12}$", session_id):
        return "Invalid session ID format"

    return None


def validate_name(name: str, max_length: int = 100) -> str | None:
    """Validate session display name.

    Args:
        name: Name to validate.
        max_length: Maximum allowed length.

    Returns:
        Error message or None if valid.
    """
    if not name:
        return "Name is required"

    if len(name) > max_length:
        return f"Name is too long (max {max_length} characters)"

    return None
