"""Tests that validate implementation against test vectors.

These tests ensure the daemon implementation matches the contracts
defined in test-vectors/*.json for cross-platform compatibility.
"""

import json
from pathlib import Path

import pytest

from ras.crypto import (
    compute_hmac,
    compute_signaling_hmac,
    derive_key,
    derive_ntfy_topic,
)

# Path to test vectors directory
VECTORS_DIR = Path(__file__).parent.parent.parent.parent / "test-vectors"


class TestKeyDerivationVectors:
    """Tests for key derivation against test vectors."""

    @pytest.fixture
    def vectors(self):
        """Load key derivation test vectors."""
        vectors_file = VECTORS_DIR / "key_derivation.json"
        if not vectors_file.exists():
            pytest.skip("Test vectors file not found")
        with open(vectors_file) as f:
            return json.load(f)

    def test_all_key_derivation_vectors(self, vectors):
        """All key derivation test vectors pass."""
        for case in vectors["vectors"]:
            if "expected_key_hex" in case:
                master_secret = bytes.fromhex(case["master_secret_hex"])
                result = derive_key(master_secret, case["purpose"])
                assert result.hex() == case["expected_key_hex"], f"Failed: {case['id']}"

    def test_all_topic_derivation_vectors(self, vectors):
        """All topic derivation test vectors pass."""
        for case in vectors["vectors"]:
            if "expected_topic" in case:
                master_secret = bytes.fromhex(case["master_secret_hex"])
                result = derive_ntfy_topic(master_secret)
                assert result == case["expected_topic"], f"Failed: {case['id']}"

    def test_vector_derive_auth_key_1(self, vectors):
        """Explicit test for derive_auth_key_1 vector."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        expected = bytes.fromhex(
            "bec0c3289e346d890ea330014e23e6e7cf95f82c8bd7f5f133850c89ac165a43"
        )
        result = derive_key(master_secret, "auth")
        assert result == expected

    def test_vector_derive_encrypt_key_1(self, vectors):
        """Explicit test for derive_encrypt_key_1 vector."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        expected = bytes.fromhex(
            "fdb096356d535edd24a3eee6f2126b77018c51dff15c86ccf6bc3c76f086c2a0"
        )
        result = derive_key(master_secret, "encrypt")
        assert result == expected

    def test_vector_derive_ntfy_key_1(self, vectors):
        """Explicit test for derive_ntfy_key_1 vector."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        expected = bytes.fromhex(
            "e3d801b5755b78c380d59c1285c1a65290db0334cc2994dfd048ebff2df8781f"
        )
        result = derive_key(master_secret, "ntfy")
        assert result == expected

    def test_vector_derive_topic_1(self, vectors):
        """Explicit test for derive_topic_1 vector."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        expected = "ras-4884fdaafea4"
        result = derive_ntfy_topic(master_secret)
        assert result == expected


class TestHmacVectors:
    """Tests for HMAC against test vectors."""

    @pytest.fixture
    def vectors(self):
        """Load HMAC test vectors."""
        vectors_file = VECTORS_DIR / "hmac.json"
        if not vectors_file.exists():
            pytest.skip("Test vectors file not found")
        with open(vectors_file) as f:
            return json.load(f)

    def test_all_hmac_vectors(self, vectors):
        """All HMAC test vectors pass."""
        for case in vectors["vectors"]:
            key = bytes.fromhex(case["key_hex"])
            message = bytes.fromhex(case["message_hex"])
            expected = bytes.fromhex(case["expected_hmac_hex"])
            result = compute_hmac(key, message)
            assert result == expected, f"Failed: {case['id']}"

    def test_vector_hmac_auth_challenge(self, vectors):
        """Explicit test for hmac_auth_challenge vector."""
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

    def test_vector_hmac_empty_message(self, vectors):
        """Explicit test for hmac_empty_message vector."""
        key = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        message = b""
        expected = bytes.fromhex(
            "c7b5e12ec029a887022abbdc648f8380db2f41e44220ec1530553c24d81d2fee"
        )
        result = compute_hmac(key, message)
        assert result == expected


class TestSignalingHmacVectors:
    """Tests for signaling HMAC format."""

    @pytest.fixture
    def vectors(self):
        """Load signaling test vectors."""
        vectors_file = VECTORS_DIR / "signaling.json"
        if not vectors_file.exists():
            pytest.skip("Test vectors file not found")
        with open(vectors_file) as f:
            return json.load(f)

    def test_signaling_hmac_format(self):
        """Signaling HMAC uses correct format: session_id || timestamp || body."""
        auth_key = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        session_id = "test-session"
        timestamp = 1706400000
        body = b"test body"

        result = compute_signaling_hmac(auth_key, session_id, timestamp, body)

        # The HMAC input should be: UTF8(session_id) || BE64(timestamp) || body
        import struct
        expected_input = (
            session_id.encode("utf-8")
            + struct.pack(">Q", timestamp)
            + body
        )
        expected_hmac = compute_hmac(auth_key, expected_input)

        assert result == expected_hmac
        assert len(result) == 32


class TestQrPayloadVectors:
    """Tests for QR payload encoding."""

    @pytest.fixture
    def vectors(self):
        """Load QR payload test vectors."""
        vectors_file = VECTORS_DIR / "qr_payload.json"
        if not vectors_file.exists():
            pytest.skip("Test vectors file not found")
        with open(vectors_file) as f:
            return json.load(f)

    def test_qr_payload_structure(self, vectors):
        """QR payload has correct protobuf structure."""
        from ras.pairing.qr_generator import QrGenerator
        from ras.proto.ras import QrPayload

        for case in vectors.get("test_cases", []):
            if "input" not in case:
                continue

            inp = case["input"]
            qr = QrGenerator(
                ip=inp["ip"],
                port=inp["port"],
                master_secret=bytes.fromhex(inp["master_secret_hex"]),
                session_id=inp["session_id"],
                ntfy_topic=inp["ntfy_topic"],
            )

            payload_bytes = qr._create_payload()
            parsed = QrPayload().parse(payload_bytes)

            assert parsed.version == 1
            assert parsed.ip == inp["ip"]
            assert parsed.port == inp["port"]
            assert parsed.session_id == inp["session_id"]
            assert parsed.ntfy_topic == inp["ntfy_topic"]


class TestAuthHandshakeVectors:
    """Tests for authentication handshake protocol."""

    @pytest.fixture
    def vectors(self):
        """Load auth handshake test vectors."""
        vectors_file = VECTORS_DIR / "auth_handshake.json"
        if not vectors_file.exists():
            pytest.skip("Test vectors file not found")
        with open(vectors_file) as f:
            return json.load(f)

    def test_auth_protocol_hmac_computation(self, vectors):
        """Auth protocol HMAC computation matches spec."""
        for case in vectors.get("test_cases", []):
            if "hmac_computation" not in case:
                continue

            hc = case["hmac_computation"]
            auth_key = bytes.fromhex(hc["auth_key_hex"])
            nonce = bytes.fromhex(hc["nonce_hex"])
            expected = bytes.fromhex(hc["expected_hmac_hex"])

            result = compute_hmac(auth_key, nonce)
            assert result == expected, f"Failed: {case.get('id', 'unknown')}"


class TestCrossImplementationCompatibility:
    """Tests to ensure daemon is compatible with mobile implementations."""

    def test_key_derivation_is_deterministic(self):
        """Same inputs always produce same outputs."""
        master_secret = b"\x12\x34\x56\x78" * 8

        results = []
        for _ in range(10):
            auth_key = derive_key(master_secret, "auth")
            results.append(auth_key)

        # All results should be identical
        assert all(r == results[0] for r in results)

    def test_hmac_is_deterministic(self):
        """HMAC computation is deterministic."""
        key = b"\x00" * 32
        message = b"test message"

        results = []
        for _ in range(10):
            hmac = compute_hmac(key, message)
            results.append(hmac)

        assert all(r == results[0] for r in results)

    def test_signaling_hmac_is_deterministic(self):
        """Signaling HMAC is deterministic."""
        auth_key = b"\x00" * 32
        session_id = "test"
        timestamp = 1706400000
        body = b"body"

        results = []
        for _ in range(10):
            hmac = compute_signaling_hmac(auth_key, session_id, timestamp, body)
            results.append(hmac)

        assert all(r == results[0] for r in results)

    def test_topic_format_is_correct(self):
        """ntfy topic has correct format: ras-XXXXXXXXXXXX (16 chars total)."""
        master_secret = b"\x00" * 32
        topic = derive_ntfy_topic(master_secret)

        assert topic.startswith("ras-")
        assert len(topic) == 16  # "ras-" + 12 hex chars

        # Hex part should be valid
        hex_part = topic[4:]
        bytes.fromhex(hex_part)  # Should not raise

    def test_derived_keys_are_32_bytes(self):
        """All derived keys are exactly 32 bytes."""
        master_secret = b"\x00" * 32

        for purpose in ["auth", "encrypt", "ntfy"]:
            key = derive_key(master_secret, purpose)
            assert len(key) == 32

    def test_hmac_output_is_32_bytes(self):
        """HMAC-SHA256 output is 32 bytes."""
        key = b"\x00" * 32
        message = b"test"
        hmac = compute_hmac(key, message)
        assert len(hmac) == 32
