"""Notification types and pattern definitions."""

from dataclasses import dataclass
from enum import Enum
from typing import Optional


class NotificationType(Enum):
    """Type of notification event."""

    APPROVAL = "approval"
    COMPLETION = "completion"
    ERROR = "error"


@dataclass
class MatchResult:
    """Result of pattern matching."""

    type: NotificationType
    pattern: str  # Pattern that matched
    snippet: str  # Context around match (~50 chars)
    position: int  # Position in buffer


@dataclass
class NotificationConfig:
    """Configuration for notification detection."""

    # Pattern lists
    approval_patterns: list[str]
    error_patterns: list[str]
    shell_prompt_patterns: list[str]

    # Timing
    cooldown_seconds: float = 5.0
    regex_timeout_ms: int = 100

    # Buffer
    sliding_window_size: int = 500
    snippet_context_chars: int = 25
    max_snippet_length: int = 60

    @classmethod
    def default(cls) -> "NotificationConfig":
        """Create config with default patterns."""
        return cls(
            approval_patterns=list(APPROVAL_PATTERNS),
            error_patterns=list(ERROR_PATTERNS),
            shell_prompt_patterns=list(DEFAULT_SHELL_PROMPTS),
        )


# ============================================================================
# Built-in Approval Patterns
# ============================================================================

APPROVAL_PATTERNS = [
    # Yes/No variations
    r"\?\s*\(y/n\)",  # ? (y/n)
    r"\?\s*\[y/N\]",  # ? [y/N]
    r"\?\s*\[Y/n\]",  # ? [Y/n]
    r"\?\s*\[yes/no\]",  # ? [yes/no]
    r"\(yes/no\)",  # (yes/no)

    # Common prompts
    r"(?i)proceed\?",  # proceed?
    r"(?i)continue\?",  # continue?
    r"(?i)approve\?",  # approve?
    r"(?i)confirm\?",  # confirm?
    r"(?i)accept\?",  # accept?

    # Interactive prompts
    r"(?i)press enter",  # Press Enter
    r"(?i)press any key",  # Press any key
    r"(?i)hit enter",  # Hit Enter

    # Agent-specific (common)
    r"(?i)do you want to proceed",  # Claude Code
    r"(?i)should I continue",  # Various agents
    r"(?i)would you like to",  # Various agents
]

# ============================================================================
# Built-in Error Patterns
# ============================================================================

ERROR_PATTERNS = [
    # Common error prefixes (start of line)
    r"^error:",  # error:
    r"^Error:",  # Error:
    r"^ERROR:",  # ERROR:
    r"^error\[",  # error[E0001]
    r"(?i)^failed:",  # failed: / Failed:
    r"(?i)^failure:",  # failure: / Failure:

    # Exception patterns
    r"(?i)exception:",  # exception:
    r"(?i)exception in",  # Exception in thread
    r"Traceback \(most recent call last\):",  # Python traceback
    r"(?i)stack trace:",  # Stack trace:

    # Language-specific
    r"^panic:",  # Go/Rust panic
    r"^fatal:",  # Fatal error
    r"^FATAL:",  # FATAL error
    r"(?i)segmentation fault",  # C/C++ segfault
    r"(?i)unhandled.*exception",  # Unhandled exception

    # Build errors
    r"(?i)build failed",  # Build failed
    r"(?i)compilation failed",  # Compilation failed
    r"npm ERR!",  # npm error
    r"(?i)cargo error",  # Cargo error
]

# ============================================================================
# Built-in Shell Prompt Patterns
# ============================================================================

DEFAULT_SHELL_PROMPTS = [
    r"^\$ ",  # bash default
    r"^% ",  # zsh default
    r"^> ",  # generic
    r"^❯ ",  # starship/powerline
    r"^➜ ",  # oh-my-zsh
    r"^λ ",  # haskell-style
    r"^\[\w+@\w+.*\]\$ ",  # [user@host dir]$
]

# ============================================================================
# Alternate Screen Detection Sequences
# ============================================================================

# ANSI sequences that enter alternate screen buffer (vim, less, htop, etc.)
ALT_SCREEN_ENTER = [
    "\x1b[?1049h",  # Enter alternate screen (modern)
    "\x1b[?47h",  # Legacy alternate screen
]

# ANSI sequences that exit alternate screen buffer
ALT_SCREEN_EXIT = [
    "\x1b[?1049l",  # Exit alternate screen (modern)
    "\x1b[?47l",  # Legacy exit
]
