"""Formatting utilities for CLI output."""

from datetime import datetime, timezone


def format_time_ago(timestamp_str: str | None) -> str:
    """Format ISO timestamp as relative time (e.g., '2 hours ago').

    Args:
        timestamp_str: ISO 8601 timestamp string (with or without 'Z' suffix).

    Returns:
        Human-readable relative time string like "2 hours ago" or "Never".

    Examples:
        >>> format_time_ago("2024-01-01T12:00:00Z")
        "2 days ago"
        >>> format_time_ago(None)
        "Never"
    """
    if not timestamp_str:
        return "Never"

    ts = datetime.fromisoformat(timestamp_str.replace("Z", "+00:00"))
    now = datetime.now(timezone.utc)
    delta = now - ts
    seconds = delta.total_seconds()

    if seconds < 60:
        return "Just now"
    elif seconds < 3600:
        minutes = int(seconds / 60)
        return f"{minutes} minute{'s' if minutes != 1 else ''} ago"
    elif seconds < 86400:
        hours = int(seconds / 3600)
        return f"{hours} hour{'s' if hours != 1 else ''} ago"
    else:
        days = int(seconds / 86400)
        return f"{days} day{'s' if days != 1 else ''} ago"
