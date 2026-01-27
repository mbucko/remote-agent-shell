"""Session naming utilities."""

import os
import re


def sanitize_name(name: str) -> str:
    """Sanitize a name for use in tmux session names.

    Converts to lowercase, replaces non-alphanumeric with hyphens,
    collapses multiple hyphens, and strips leading/trailing hyphens.

    Args:
        name: Name to sanitize.

    Returns:
        Sanitized name, or "unnamed" if result would be empty.
    """
    # Lowercase
    name = name.lower()

    # Replace non-alphanumeric with hyphen
    name = re.sub(r"[^a-z0-9-]", "-", name)

    # Collapse multiple hyphens
    name = re.sub(r"-+", "-", name)

    # Strip leading/trailing hyphens
    name = name.strip("-")

    # Handle empty result
    if not name:
        name = "unnamed"

    return name


def generate_session_name(
    directory: str,
    agent: str,
    existing: list[str],
    max_length: int = 50,
    prefix: str = "ras",
) -> str:
    """Generate a unique session name.

    Format: ras-<agent>-<directory>
    Appends -2, -3, etc. for duplicates.

    Args:
        directory: Working directory path.
        agent: Agent identifier.
        existing: List of existing session names.
        max_length: Maximum name length.
        prefix: Session name prefix.

    Returns:
        Unique session name.
    """
    dir_name = os.path.basename(directory.rstrip(os.sep))
    sanitized_dir = sanitize_name(dir_name)
    sanitized_agent = sanitize_name(agent)

    base_name = f"{prefix}-{sanitized_agent}-{sanitized_dir}"

    # Truncate if needed (leave room for suffix like "-99")
    suffix_reserve = 4
    if len(base_name) > max_length - suffix_reserve:
        base_name = base_name[: max_length - suffix_reserve]
        # Don't end with a hyphen
        base_name = base_name.rstrip("-")

    # Check for collisions
    name = base_name
    counter = 2
    existing_set = set(existing)
    while name in existing_set:
        name = f"{base_name}-{counter}"
        counter += 1
        # Safety: don't loop forever
        if counter > 1000:
            # Use random suffix instead
            import secrets

            name = f"{base_name}-{secrets.token_hex(4)}"
            break

    return name
