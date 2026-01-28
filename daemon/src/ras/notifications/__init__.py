"""Notification system for detecting agent events in terminal output."""

from .types import (
    NotificationConfig,
    MatchResult,
    NotificationType,
    APPROVAL_PATTERNS,
    ERROR_PATTERNS,
    DEFAULT_SHELL_PROMPTS,
)
from .matcher import PatternMatcher
from .dispatcher import NotificationDispatcher

__all__ = [
    "NotificationConfig",
    "MatchResult",
    "NotificationType",
    "PatternMatcher",
    "NotificationDispatcher",
    "APPROVAL_PATTERNS",
    "ERROR_PATTERNS",
    "DEFAULT_SHELL_PROMPTS",
]
