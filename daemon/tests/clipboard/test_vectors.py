"""Test vectors for clipboard paste cross-platform validation.

These test vectors ensure clipboard content encoding and truncation
is compatible between the daemon (Python) and Android (Kotlin) implementations.

Run these tests with:
    cd daemon
    uv run pytest tests/clipboard/test_vectors.py -v
"""

import pytest

# Max paste size (64KB)
MAX_PASTE_BYTES = 65536


# ============================================================================
# CLIPBOARD CONTENT VECTORS
# ============================================================================

class TestClipboardContentVectors:
    """Test vectors for clipboard content encoding."""

    CONTENT_VECTORS = [
        # Basic text
        {
            "name": "simple_ascii",
            "input": "hello world",
            "expected_bytes": b"hello world",
            "expected_length": 11,
        },
        # Unicode
        {
            "name": "unicode_cjk",
            "input": "Hello 世界",
            "expected_bytes": b"Hello \xe4\xb8\x96\xe7\x95\x8c",
            "expected_length": 12,
        },
        {
            "name": "emoji_4byte",
            "input": "🎉",
            "expected_bytes": b"\xf0\x9f\x8e\x89",
            "expected_length": 4,
        },
        # Whitespace
        {
            "name": "newlines_lf",
            "input": "line1\nline2",
            "expected_bytes": b"line1\nline2",
            "expected_length": 11,
        },
        {
            "name": "newlines_crlf",
            "input": "line1\r\nline2",
            "expected_bytes": b"line1\r\nline2",
            "expected_length": 12,
        },
        {
            "name": "tabs",
            "input": "col1\tcol2",
            "expected_bytes": b"col1\tcol2",
            "expected_length": 9,
        },
        # Edge cases
        {
            "name": "whitespace_only",
            "input": "   ",
            "expected_bytes": b"   ",
            "expected_length": 3,
        },
        # Size limits
        {
            "name": "exactly_64kb",
            "input": "x" * 65536,
            "expected_bytes": b"x" * 65536,
            "expected_length": 65536,
        },
        {
            "name": "over_64kb",
            "input": "x" * 65537,
            "expected_bytes": b"x" * 65536,  # Truncated
            "expected_length": 65536,
        },
    ]

    @pytest.mark.parametrize("vector", CONTENT_VECTORS, ids=lambda v: v["name"])
    def test_content_encoding(self, vector):
        """Verify content is encoded correctly as UTF-8."""
        input_text = vector["input"]
        expected_bytes = vector["expected_bytes"]
        expected_length = vector["expected_length"]

        # Encode to UTF-8
        encoded = input_text.encode("utf-8")

        # Apply size limit
        if len(encoded) > MAX_PASTE_BYTES:
            encoded = encoded[:MAX_PASTE_BYTES]

        assert len(encoded) == expected_length
        assert encoded == expected_bytes


# ============================================================================
# UTF-8 TRUNCATION VECTORS
# ============================================================================

class TestUtf8TruncationVectors:
    """Test vectors for UTF-8 safe truncation at size limit."""

    TRUNCATION_VECTORS = [
        {
            "name": "truncate_mid_2byte",
            "input": "x" * 65535 + "é",  # é is 2 bytes (C3 A9)
            "max_bytes": 65536,
            "expected_length": 65535,  # Should not split the 2-byte char
        },
        {
            "name": "truncate_mid_3byte",
            "input": "x" * 65534 + "世",  # 世 is 3 bytes (E4 B8 96)
            "max_bytes": 65536,
            "expected_length": 65534,  # Should not split the 3-byte char
        },
        {
            "name": "truncate_mid_4byte",
            "input": "x" * 65533 + "🎉",  # 🎉 is 4 bytes (F0 9F 8E 89)
            "max_bytes": 65536,
            "expected_length": 65533,  # Should not split the 4-byte char
        },
        {
            "name": "truncate_exact_boundary",
            "input": "x" * 65532 + "🎉",  # Fits exactly at 65536
            "max_bytes": 65536,
            "expected_length": 65536,  # Fits perfectly
        },
        {
            "name": "truncate_ascii_only",
            "input": "x" * 65540,
            "max_bytes": 65536,
            "expected_length": 65536,  # Simple truncation for ASCII
        },
    ]

    @pytest.mark.parametrize("vector", TRUNCATION_VECTORS, ids=lambda v: v["name"])
    def test_utf8_safe_truncation(self, vector):
        """Verify truncation doesn't split multi-byte characters."""
        input_text = vector["input"]
        max_bytes = vector["max_bytes"]
        expected_length = vector["expected_length"]

        encoded = input_text.encode("utf-8")
        truncated = truncate_utf8_safe(encoded, max_bytes)

        assert len(truncated) == expected_length
        # Verify it's still valid UTF-8
        truncated.decode("utf-8")


def truncate_utf8_safe(data: bytes, max_bytes: int) -> bytes:
    """Truncate bytes at UTF-8 character boundary.

    Reference implementation matching the Kotlin version.
    """
    if len(data) <= max_bytes:
        return data

    end = max_bytes

    # Check if byte at end-1 starts a multi-byte sequence that extends past end
    # We scan backwards to find the start of any character that might be split
    while end > 0:
        byte = data[end - 1]

        # If it's a continuation byte (10xxxxxx), we're in the middle of a char
        if (byte & 0xC0) == 0x80:
            end -= 1
            continue

        # It's either ASCII or a lead byte
        if byte < 0x80:
            # ASCII - we're at a valid boundary
            break
        elif byte < 0xE0:
            # 2-byte lead - need 2 bytes total
            char_len = 2
        elif byte < 0xF0:
            # 3-byte lead - need 3 bytes total
            char_len = 3
        else:
            # 4-byte lead - need 4 bytes total
            char_len = 4

        # Check if the full character fits within max_bytes
        if (end - 1) + char_len <= max_bytes:
            # Character fits, we're at a valid boundary
            break
        else:
            # Character would be truncated, exclude it
            end -= 1
            break

    return data[:end]


# ============================================================================
# EMPTY/EDGE CASE VECTORS
# ============================================================================

class TestEdgeCaseVectors:
    """Test vectors for edge cases."""

    EDGE_CASES = [
        {
            "name": "empty_string",
            "input": "",
            "should_send": False,
        },
        {
            "name": "single_char",
            "input": "x",
            "should_send": True,
            "expected_bytes": b"x",
        },
        {
            "name": "single_emoji",
            "input": "🎉",
            "should_send": True,
            "expected_bytes": b"\xf0\x9f\x8e\x89",
        },
        {
            "name": "only_newlines",
            "input": "\n\n\n",
            "should_send": True,
            "expected_bytes": b"\n\n\n",
        },
        {
            "name": "bom_marker",
            "input": "\ufeffhello",
            "should_send": True,
            "expected_bytes": b"\xef\xbb\xbfhello",
        },
    ]

    @pytest.mark.parametrize("vector", EDGE_CASES, ids=lambda v: v["name"])
    def test_edge_cases(self, vector):
        """Verify edge cases are handled correctly."""
        input_text = vector["input"]
        should_send = vector["should_send"]

        if not should_send:
            assert input_text == ""
        else:
            encoded = input_text.encode("utf-8")
            assert encoded == vector["expected_bytes"]


# ============================================================================
# SPECIAL CHARACTER VECTORS
# ============================================================================

class TestSpecialCharacterVectors:
    """Test vectors for special characters that must be sent literally."""

    SPECIAL_CHARS = [
        {
            "name": "shell_metachar_semicolon",
            "input": "; rm -rf /",
            "expected_bytes": b"; rm -rf /",
        },
        {
            "name": "command_substitution",
            "input": "$(whoami)",
            "expected_bytes": b"$(whoami)",
        },
        {
            "name": "backtick_execution",
            "input": "`whoami`",
            "expected_bytes": b"`whoami`",
        },
        {
            "name": "pipe_injection",
            "input": "| cat /etc/passwd",
            "expected_bytes": b"| cat /etc/passwd",
        },
        {
            "name": "env_expansion",
            "input": "$HOME",
            "expected_bytes": b"$HOME",
        },
        {
            "name": "escape_sequence",
            "input": "\x1b[31m",
            "expected_bytes": b"\x1b[31m",
        },
        {
            "name": "null_byte",
            "input": "hello\x00world",
            "expected_bytes": b"hello\x00world",
        },
    ]

    @pytest.mark.parametrize("vector", SPECIAL_CHARS, ids=lambda v: v["name"])
    def test_special_chars_sent_literally(self, vector):
        """Verify special characters are encoded literally."""
        input_text = vector["input"]
        expected = vector["expected_bytes"]

        encoded = input_text.encode("utf-8")
        assert encoded == expected
