"""Cross-platform test vectors for notification pattern matching.

These vectors ensure consistent behavior across daemon (Python) and
any future implementations.
"""

import pytest

from ras.notifications.types import NotificationConfig, NotificationType
from ras.notifications.matcher import PatternMatcher


# ============================================================================
# Pattern Test Vectors
# ============================================================================

PATTERN_TEST_VECTORS = [
    # Approval patterns
    {
        "name": "yn_lowercase",
        "input": b"Do you want to continue? (y/n)",
        "expected_type": NotificationType.APPROVAL,
        "should_match": True,
    },
    {
        "name": "yn_uppercase",
        "input": b"Proceed? [Y/N]",
        "expected_type": NotificationType.APPROVAL,
        "should_match": True,
    },
    {
        "name": "yes_no_full",
        "input": b"Accept changes? [yes/no]",
        "expected_type": NotificationType.APPROVAL,
        "should_match": True,
    },
    {
        "name": "press_enter",
        "input": b"Press Enter to continue...",
        "expected_type": NotificationType.APPROVAL,
        "should_match": True,
    },
    {
        "name": "press_any_key",
        "input": b"Press any key to exit",
        "expected_type": NotificationType.APPROVAL,
        "should_match": True,
    },
    # Error patterns
    {
        "name": "error_colon",
        "input": b"Error: file not found",
        "expected_type": NotificationType.ERROR,
        "should_match": True,
    },
    {
        "name": "python_traceback",
        "input": b"Traceback (most recent call last):",
        "expected_type": NotificationType.ERROR,
        "should_match": True,
    },
    {
        "name": "panic_go",
        "input": b"panic: runtime error: index out of range",
        "expected_type": NotificationType.ERROR,
        "should_match": True,
    },
    {
        "name": "npm_error",
        "input": b"npm ERR! code ENOENT",
        "expected_type": NotificationType.ERROR,
        "should_match": True,
    },
    {
        "name": "build_failed",
        "input": b"Build failed with 3 errors",
        "expected_type": NotificationType.ERROR,
        "should_match": True,
    },
    # False positives (should NOT match specific type)
    {
        "name": "question_no_yn",
        "input": b"What is the answer?",
        "expected_type": NotificationType.APPROVAL,
        "should_match": False,
    },
    {
        "name": "normal_output",
        "input": b"Processing file 1 of 10...",
        "expected_type": None,
        "should_match": False,
    },
]


# ============================================================================
# ANSI Stripping Test Vectors
# ============================================================================

ANSI_STRIP_VECTORS = [
    {
        "name": "color_red",
        "input": b"\x1b[31mError:\x1b[0m oops",
        "expected_clean": "Error: oops",
    },
    {
        "name": "bold",
        "input": b"\x1b[1mBold text\x1b[0m",
        "expected_clean": "Bold text",
    },
    {
        "name": "multiple_codes",
        "input": b"\x1b[1;31;40mStyled\x1b[0m",
        "expected_clean": "Styled",
    },
    {
        "name": "cursor_movement",
        "input": b"\x1b[2K\x1b[1GText",
        "expected_clean": "Text",
    },
    {
        "name": "osc_title",
        "input": b"\x1b]0;Window Title\x07Content",
        "expected_clean": "Content",
    },
    {
        "name": "combined_ansi",
        "input": b"\x1b[31m\x1b[1mRed Bold\x1b[0m normal",
        "expected_clean": "Red Bold normal",
    },
]


# ============================================================================
# Snippet Extraction Test Vectors
# ============================================================================

SNIPPET_EXTRACTION_VECTORS = [
    {
        "name": "short_text",
        "input": b"Proceed? (y/n)",
        "expected_max_len": 60,
    },
    {
        "name": "long_text_middle",
        "input": b"A" * 100 + b"Error: oops" + b"B" * 100,
        "expected_max_len": 60,
    },
    {
        "name": "truncate_with_ellipsis",
        "input": b"X" * 50 + b"Proceed? (y/n)" + b"Y" * 50,
        "expected_has_ellipsis": True,
    },
]


# ============================================================================
# Cooldown Test Vectors
# ============================================================================

COOLDOWN_TEST_VECTORS = [
    {
        "name": "within_cooldown",
        "events": [
            {"time": 0.0, "type": "approval", "pattern": "p1"},
            {"time": 0.02, "type": "approval", "pattern": "p1"},  # 20ms later
        ],
        "cooldown_seconds": 0.1,
        "expected_notifications": 1,
    },
    {
        "name": "after_cooldown",
        "events": [
            {"time": 0.0, "type": "approval", "pattern": "p1"},
            {"time": 0.15, "type": "approval", "pattern": "p1"},  # 150ms later
        ],
        "cooldown_seconds": 0.1,
        "expected_notifications": 2,
    },
    {
        "name": "different_types",
        "events": [
            {"time": 0.0, "type": "approval", "pattern": "p1"},
            {"time": 0.02, "type": "error", "pattern": "p2"},
        ],
        "cooldown_seconds": 0.1,
        "expected_notifications": 2,
    },
]


# ============================================================================
# Test Vector Tests
# ============================================================================


@pytest.fixture
def matcher():
    """PatternMatcher with default config."""
    return PatternMatcher(NotificationConfig.default())


class TestPatternVectors:
    """Tests using pattern test vectors."""

    @pytest.mark.parametrize(
        "vector",
        [v for v in PATTERN_TEST_VECTORS if v["should_match"]],
        ids=[v["name"] for v in PATTERN_TEST_VECTORS if v["should_match"]],
    )
    def test_should_match(self, matcher, vector):
        """Patterns that should match do match."""
        results = matcher.process_chunk(vector["input"])
        matching_results = [
            r for r in results if r.type == vector["expected_type"]
        ]
        assert len(matching_results) >= 1, f"Expected match for {vector['name']}"

    @pytest.mark.parametrize(
        "vector",
        [
            v
            for v in PATTERN_TEST_VECTORS
            if not v["should_match"] and v["expected_type"] is not None
        ],
        ids=[
            v["name"]
            for v in PATTERN_TEST_VECTORS
            if not v["should_match"] and v["expected_type"] is not None
        ],
    )
    def test_should_not_match(self, matcher, vector):
        """Patterns that should not match don't match."""
        results = matcher.process_chunk(vector["input"])
        matching_results = [
            r for r in results if r.type == vector["expected_type"]
        ]
        assert len(matching_results) == 0, f"Unexpected match for {vector['name']}"


class TestAnsiStripVectors:
    """Tests using ANSI stripping test vectors."""

    @pytest.mark.parametrize(
        "vector",
        ANSI_STRIP_VECTORS,
        ids=[v["name"] for v in ANSI_STRIP_VECTORS],
    )
    def test_ansi_stripping(self, matcher, vector):
        """ANSI codes are properly stripped."""
        text = vector["input"].decode("utf-8", errors="replace")
        clean = matcher._strip_ansi(text)
        assert clean == vector["expected_clean"]


class TestSnippetVectors:
    """Tests using snippet extraction test vectors."""

    @pytest.mark.parametrize(
        "vector",
        SNIPPET_EXTRACTION_VECTORS,
        ids=[v["name"] for v in SNIPPET_EXTRACTION_VECTORS],
    )
    def test_snippet_length(self, matcher, vector):
        """Snippets respect max length."""
        results = matcher.process_chunk(vector["input"])
        if results:
            assert len(results[0].snippet) <= vector.get("expected_max_len", 60)

    def test_snippet_has_ellipsis_when_truncated(self, matcher):
        """Truncated snippets have ellipsis."""
        long_input = b"X" * 50 + b"Proceed? (y/n)" + b"Y" * 50
        results = matcher.process_chunk(long_input)
        approval_results = [r for r in results if r.type == NotificationType.APPROVAL]
        if approval_results:
            assert "..." in approval_results[0].snippet
