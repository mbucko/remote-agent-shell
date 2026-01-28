"""Pattern matcher for detecting agent events in terminal output."""

import logging
import re
import signal
from typing import Optional

from .types import (
    MatchResult,
    NotificationConfig,
    NotificationType,
    ALT_SCREEN_ENTER,
    ALT_SCREEN_EXIT,
)

logger = logging.getLogger(__name__)


class RegexTimeoutError(Exception):
    """Regex execution timed out (ReDoS protection)."""

    pass


class PatternMatcher:
    """Matches notification patterns in terminal output.

    Features:
    - ANSI escape code stripping before matching
    - Sliding window buffer for chunk boundary handling
    - Alternate screen detection (suppresses matching in vim/less/htop)
    - ReDoS protection with timeout

    Usage:
        matcher = PatternMatcher(config)
        for chunk in output_stream:
            results = matcher.process_chunk(chunk)
            for result in results:
                # Handle notification
    """

    # Regex to strip ANSI escape codes (includes private mode sequences like \x1b[?1049h)
    ANSI_ESCAPE = re.compile(
        r"\x1b\[\??[0-9;]*[a-zA-Z]"  # CSI sequences including private mode (\x1b[?...)
        r"|\x1b\].*?\x07"  # OSC sequences (window title, etc.)
        r"|\x1b\([A-Za-z]"  # Character set selection
    )

    def __init__(self, config: NotificationConfig):
        """Initialize PatternMatcher.

        Args:
            config: Notification configuration with patterns.
        """
        self._config = config

        # Compile patterns
        self._approval_patterns = [
            (p, re.compile(p, re.IGNORECASE))
            for p in config.approval_patterns
        ]
        self._error_patterns = [
            (p, re.compile(p, re.MULTILINE))
            for p in config.error_patterns
        ]
        self._shell_prompt_patterns = [
            (p, re.compile(p, re.MULTILINE))
            for p in config.shell_prompt_patterns
        ]

        # State
        self._buffer = b""
        self._in_alt_screen = False
        self._last_was_prompt = False  # Track if last output was just a prompt

        logger.debug(
            "PatternMatcher initialized: %d approval, %d error, %d shell patterns",
            len(self._approval_patterns),
            len(self._error_patterns),
            len(self._shell_prompt_patterns),
        )

    def process_chunk(self, data: bytes) -> list[MatchResult]:
        """Process an output chunk and return any matches.

        Args:
            data: Raw terminal output bytes.

        Returns:
            List of MatchResult for any patterns found.
        """
        if not data:
            return []

        # Update alternate screen state
        self._update_alt_screen_state(data)

        # Skip matching if in alternate screen (vim, less, htop, etc.)
        if self._in_alt_screen:
            logger.debug("Skipping match - in alternate screen")
            return []

        # Combine with sliding window buffer
        combined = self._buffer + data

        # Decode and strip ANSI codes
        text = self._strip_ansi(combined.decode("utf-8", errors="replace"))

        results = []

        # Check approval patterns
        for pattern_str, regex in self._approval_patterns:
            match = self._safe_search(regex, text)
            if match:
                results.append(
                    MatchResult(
                        type=NotificationType.APPROVAL,
                        pattern=pattern_str,
                        snippet=self._extract_snippet(text, match),
                        position=match.start(),
                    )
                )

        # Check error patterns
        for pattern_str, regex in self._error_patterns:
            match = self._safe_search(regex, text)
            if match:
                results.append(
                    MatchResult(
                        type=NotificationType.ERROR,
                        pattern=pattern_str,
                        snippet=self._extract_snippet(text, match),
                        position=match.start(),
                    )
                )

        # Check shell prompt (task completion)
        # Only notify if there was non-prompt content before this prompt
        prompt_found = False
        for pattern_str, regex in self._shell_prompt_patterns:
            match = self._safe_search(regex, text)
            if match:
                prompt_found = True
                # Only trigger completion if last chunk wasn't also just a prompt
                if not self._last_was_prompt:
                    results.append(
                        MatchResult(
                            type=NotificationType.COMPLETION,
                            pattern=pattern_str,
                            snippet="Task completed",
                            position=match.start(),
                        )
                    )
                break

        # Track prompt state for next iteration
        self._last_was_prompt = prompt_found and len(text.strip()) < 20

        # Update sliding window buffer (keep last N bytes)
        window_size = self._config.sliding_window_size
        if len(combined) > window_size:
            self._buffer = combined[-window_size:]
        else:
            self._buffer = combined

        if results:
            logger.debug("Found %d matches in chunk", len(results))

        return results

    def reset(self) -> None:
        """Reset matcher state."""
        self._buffer = b""
        self._in_alt_screen = False
        self._last_was_prompt = False
        logger.debug("PatternMatcher reset")

    @property
    def in_alternate_screen(self) -> bool:
        """True if currently in alternate screen mode."""
        return self._in_alt_screen

    def _strip_ansi(self, text: str) -> str:
        """Remove ANSI escape codes from text."""
        return self.ANSI_ESCAPE.sub("", text)

    def _safe_search(
        self, regex: re.Pattern, text: str
    ) -> Optional[re.Match]:
        """Search with timeout protection against ReDoS.

        Args:
            regex: Compiled regex pattern.
            text: Text to search.

        Returns:
            Match object if found, None otherwise.
        """
        timeout_sec = self._config.regex_timeout_ms / 1000.0

        # Try to use signal-based timeout (Unix only)
        try:
            return self._search_with_signal_timeout(regex, text, timeout_sec)
        except AttributeError:
            # signal.SIGALRM not available (Windows)
            # Run without timeout protection
            return regex.search(text)

    def _search_with_signal_timeout(
        self, regex: re.Pattern, text: str, timeout_sec: float
    ) -> Optional[re.Match]:
        """Search with signal-based timeout (Unix only)."""

        def timeout_handler(signum, frame):
            raise RegexTimeoutError(f"Regex timed out after {timeout_sec}s")

        old_handler = signal.signal(signal.SIGALRM, timeout_handler)
        signal.setitimer(signal.ITIMER_REAL, timeout_sec)

        try:
            return regex.search(text)
        except RegexTimeoutError:
            logger.warning(
                "Regex timed out: pattern=%s, text_len=%d",
                regex.pattern[:50],
                len(text),
            )
            return None
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, old_handler)

    def _extract_snippet(self, text: str, match: re.Match) -> str:
        """Extract context around a match.

        Args:
            text: Full text that was matched.
            match: Match object.

        Returns:
            Snippet of ~50 chars around the match.
        """
        context = self._config.snippet_context_chars
        max_len = self._config.max_snippet_length

        start = max(0, match.start() - context)
        end = min(len(text), match.end() + context)
        snippet = text[start:end].strip()

        # Replace newlines with spaces for single-line snippet
        snippet = " ".join(snippet.split())

        # Add ellipsis if truncated
        if start > 0:
            snippet = "..." + snippet
        if end < len(text):
            snippet = snippet + "..."

        # Hard cap at max length
        if len(snippet) > max_len:
            snippet = snippet[: max_len - 3] + "..."

        return snippet

    def _update_alt_screen_state(self, data: bytes) -> None:
        """Track alternate screen buffer state.

        Args:
            data: Raw terminal output bytes.
        """
        text = data.decode("utf-8", errors="replace")

        # Check for enter alternate screen
        for seq in ALT_SCREEN_ENTER:
            if seq in text:
                if not self._in_alt_screen:
                    logger.debug("Entering alternate screen")
                self._in_alt_screen = True
                return

        # Check for exit alternate screen
        for seq in ALT_SCREEN_EXIT:
            if seq in text:
                if self._in_alt_screen:
                    logger.debug("Exiting alternate screen")
                self._in_alt_screen = False
                return
