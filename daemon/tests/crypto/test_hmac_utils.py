"""Tests for HMAC utilities."""

import pytest

from ras.crypto import compute_hmac, verify_hmac, compute_signaling_hmac


class TestComputeHmac:
    """Tests for HMAC-SHA256 computation."""

    def test_hmac_auth_challenge_vector(self):
        """Test vector: hmac_auth_challenge."""
        key = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        message = bytes.fromhex(
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        )
        expected = bytes.fromhex(
            "fc620ba9fee2a44f2ea7a4cdf04348f2fa7299feb84ea028c48f80bba0bdddb0"
        )
        result = compute_hmac(key, message)
        assert result == expected

    def test_hmac_empty_message_vector(self):
        """Test vector: hmac_empty_message."""
        key = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        message = bytes.fromhex("")
        expected = bytes.fromhex(
            "c7b5e12ec029a887022abbdc648f8380db2f41e44220ec1530553c24d81d2fee"
        )
        result = compute_hmac(key, message)
        assert result == expected

    def test_hmac_long_message_vector(self):
        """Test vector: hmac_long_message."""
        key = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        message = bytes.fromhex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
        )
        expected = bytes.fromhex(
            "cecc4a1eb9d85d1e061c0d16f0e830c3a50d76cfab314fc420e149e83133e91d"
        )
        result = compute_hmac(key, message)
        assert result == expected

    def test_returns_32_bytes(self):
        """HMAC-SHA256 always returns 32 bytes."""
        key = b"\x00" * 32
        result = compute_hmac(key, b"test message")
        assert len(result) == 32

    def test_deterministic(self):
        """Same inputs produce same output."""
        key = b"\x00" * 32
        message = b"test"
        result1 = compute_hmac(key, message)
        result2 = compute_hmac(key, message)
        assert result1 == result2

    def test_different_keys_different_hmac(self):
        """Different keys produce different HMACs."""
        message = b"same message"
        hmac1 = compute_hmac(b"\x00" * 32, message)
        hmac2 = compute_hmac(b"\x01" * 32, message)
        assert hmac1 != hmac2

    def test_different_messages_different_hmac(self):
        """Different messages produce different HMACs."""
        key = b"\x00" * 32
        hmac1 = compute_hmac(key, b"message1")
        hmac2 = compute_hmac(key, b"message2")
        assert hmac1 != hmac2


class TestVerifyHmac:
    """Tests for constant-time HMAC verification."""

    def test_verify_correct_hmac(self):
        """Verification succeeds with correct HMAC."""
        key = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        message = b"test message"
        hmac_value = compute_hmac(key, message)
        assert verify_hmac(key, message, hmac_value) is True

    def test_verify_wrong_hmac(self):
        """Verification fails with wrong HMAC."""
        key = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        message = b"test message"
        wrong_hmac = b"\x00" * 32
        assert verify_hmac(key, message, wrong_hmac) is False

    def test_verify_truncated_hmac(self):
        """Verification fails with truncated HMAC."""
        key = b"\x00" * 32
        message = b"test"
        correct_hmac = compute_hmac(key, message)
        truncated = correct_hmac[:16]
        assert verify_hmac(key, message, truncated) is False

    def test_verify_empty_hmac(self):
        """Verification fails with empty HMAC."""
        key = b"\x00" * 32
        message = b"test"
        assert verify_hmac(key, message, b"") is False

    def test_verify_one_bit_difference(self):
        """Verification fails with single bit difference."""
        key = b"\x00" * 32
        message = b"test"
        correct_hmac = compute_hmac(key, message)
        # Flip one bit
        wrong_hmac = bytes([correct_hmac[0] ^ 1]) + correct_hmac[1:]
        assert verify_hmac(key, message, wrong_hmac) is False


class TestComputeSignalingHmac:
    """Tests for HTTP signaling HMAC computation."""

    def test_includes_session_id(self):
        """Different session IDs produce different HMACs."""
        auth_key = b"\x00" * 32
        timestamp = 1706400000
        body = b"test body"

        hmac1 = compute_signaling_hmac(auth_key, "session1", timestamp, body)
        hmac2 = compute_signaling_hmac(auth_key, "session2", timestamp, body)
        assert hmac1 != hmac2

    def test_includes_timestamp(self):
        """Different timestamps produce different HMACs."""
        auth_key = b"\x00" * 32
        session_id = "test-session"
        body = b"test body"

        hmac1 = compute_signaling_hmac(auth_key, session_id, 1706400000, body)
        hmac2 = compute_signaling_hmac(auth_key, session_id, 1706400001, body)
        assert hmac1 != hmac2

    def test_includes_body(self):
        """Different bodies produce different HMACs."""
        auth_key = b"\x00" * 32
        session_id = "test-session"
        timestamp = 1706400000

        hmac1 = compute_signaling_hmac(auth_key, session_id, timestamp, b"body1")
        hmac2 = compute_signaling_hmac(auth_key, session_id, timestamp, b"body2")
        assert hmac1 != hmac2

    def test_returns_32_bytes(self):
        """Returns 32-byte HMAC."""
        auth_key = b"\x00" * 32
        result = compute_signaling_hmac(auth_key, "session", 1706400000, b"body")
        assert len(result) == 32

    def test_deterministic(self):
        """Same inputs produce same output."""
        auth_key = b"\x00" * 32
        session_id = "test"
        timestamp = 1706400000
        body = b"test"

        result1 = compute_signaling_hmac(auth_key, session_id, timestamp, body)
        result2 = compute_signaling_hmac(auth_key, session_id, timestamp, body)
        assert result1 == result2

    def test_empty_body(self):
        """Works with empty body."""
        auth_key = b"\x00" * 32
        result = compute_signaling_hmac(auth_key, "session", 1706400000, b"")
        assert len(result) == 32

    def test_unicode_session_id(self):
        """Works with unicode session ID."""
        auth_key = b"\x00" * 32
        result = compute_signaling_hmac(auth_key, "session-日本語", 1706400000, b"")
        assert len(result) == 32
