"""Test vectors for ntfy signaling crypto.

These tests verify cross-platform compatibility by using pre-computed vectors
from test-vectors/ntfy_signaling.json.
"""

import json
from pathlib import Path

import pytest

from ras.ntfy_signaling.crypto import (
    NtfySignalingCrypto,
    derive_signaling_key,
)


# Load test vectors
VECTORS_PATH = Path(__file__).parent.parent.parent.parent / "test-vectors" / "ntfy_signaling.json"


@pytest.fixture(scope="module")
def vectors():
    """Load test vectors from JSON file."""
    with open(VECTORS_PATH) as f:
        return json.load(f)


class TestKeyDerivationVectors:
    """Test key derivation against pre-computed vectors."""

    def test_derive_signaling_key_1(self, vectors):
        """Test key derivation with standard input."""
        v = next(x for x in vectors["key_derivation_vectors"] if x["id"] == "derive_signaling_key_1")
        master_secret = bytes.fromhex(v["master_secret_hex"])
        expected = bytes.fromhex(v["expected_signaling_key_hex"])

        result = derive_signaling_key(master_secret)
        assert result == expected

    def test_derive_signaling_key_zeros(self, vectors):
        """Test key derivation with all zeros."""
        v = next(x for x in vectors["key_derivation_vectors"] if x["id"] == "derive_signaling_key_zeros")
        master_secret = bytes.fromhex(v["master_secret_hex"])
        expected = bytes.fromhex(v["expected_signaling_key_hex"])

        result = derive_signaling_key(master_secret)
        assert result == expected

    def test_derive_signaling_key_ones(self, vectors):
        """Test key derivation with all ones."""
        v = next(x for x in vectors["key_derivation_vectors"] if x["id"] == "derive_signaling_key_ones")
        master_secret = bytes.fromhex(v["master_secret_hex"])
        expected = bytes.fromhex(v["expected_signaling_key_hex"])

        result = derive_signaling_key(master_secret)
        assert result == expected


class TestEncryptionVectors:
    """Test encryption against pre-computed vectors."""

    def test_decrypt_offer_message(self, vectors):
        """Test decryption of offer message vector."""
        v = next(x for x in vectors["encryption_vectors"] if x["id"] == "encrypt_offer_message")
        key = bytes.fromhex(v["signaling_key_hex"])
        ciphertext_b64 = v["expected_ciphertext_base64"]
        expected_plaintext = v["plaintext_utf8"].encode("utf-8")

        crypto = NtfySignalingCrypto(key)
        result = crypto.decrypt(ciphertext_b64)
        assert result == expected_plaintext

    def test_decrypt_empty(self, vectors):
        """Test decryption of empty plaintext vector."""
        v = next(x for x in vectors["encryption_vectors"] if x["id"] == "encrypt_empty")
        key = bytes.fromhex(v["signaling_key_hex"])
        ciphertext_b64 = v["expected_ciphertext_base64"]

        crypto = NtfySignalingCrypto(key)
        result = crypto.decrypt(ciphertext_b64)
        assert result == b""


class TestSerializationVectors:
    """Test protobuf serialization against pre-computed vectors."""

    def test_offer_message_serialization(self, vectors):
        """Test OFFER message serialization matches expected bytes."""
        from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType

        v = next(x for x in vectors["serialization_vectors"] if x["id"] == "offer_message_1")
        fields = v["fields"]

        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.OFFER,
            session_id=fields["session_id"],
            sdp=fields["sdp"],
            device_id=fields["device_id"],
            device_name=fields["device_name"],
            timestamp=fields["timestamp"],
            nonce=bytes.fromhex(fields["nonce_hex"]),
        )

        serialized = bytes(msg)
        expected = bytes.fromhex(v["expected_protobuf_hex"])

        assert serialized == expected

    def test_answer_message_serialization(self, vectors):
        """Test ANSWER message serialization matches expected bytes."""
        from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType

        v = next(x for x in vectors["serialization_vectors"] if x["id"] == "answer_message_1")
        fields = v["fields"]

        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.ANSWER,
            session_id=fields["session_id"],
            sdp=fields["sdp"],
            device_id=fields["device_id"],
            device_name=fields["device_name"],
            timestamp=fields["timestamp"],
            nonce=bytes.fromhex(fields["nonce_hex"]),
        )

        serialized = bytes(msg)
        expected = bytes.fromhex(v["expected_protobuf_hex"])

        assert serialized == expected

    def test_offer_deserialization_roundtrip(self, vectors):
        """Test OFFER can be serialized and deserialized."""
        from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType

        v = next(x for x in vectors["serialization_vectors"] if x["id"] == "offer_message_1")
        expected_bytes = bytes.fromhex(v["expected_protobuf_hex"])

        # Deserialize
        msg = NtfySignalMessage().parse(expected_bytes)

        # Verify fields
        assert msg.type == NtfySignalMessageMessageType.OFFER
        assert msg.session_id == v["fields"]["session_id"]
        assert msg.sdp == v["fields"]["sdp"]
        assert msg.device_id == v["fields"]["device_id"]
        assert msg.device_name == v["fields"]["device_name"]
        assert msg.timestamp == v["fields"]["timestamp"]
        assert msg.nonce == bytes.fromhex(v["fields"]["nonce_hex"])


class TestCrossLanguageCompatibility:
    """Tests to verify compatibility with Android/iOS implementations."""

    def test_encryption_format_matches_spec(self, vectors):
        """Verify encrypted format: base64(IV || ciphertext || tag)."""
        import base64

        v = next(x for x in vectors["encryption_vectors"] if x["id"] == "encrypt_offer_message")
        ciphertext_b64 = v["expected_ciphertext_base64"]
        iv_hex = v["iv_hex"]

        # Decode and verify structure
        encrypted = base64.b64decode(ciphertext_b64)

        # IV should be first 12 bytes
        assert len(encrypted) >= 12 + 16  # IV + minimum tag
        assert encrypted[:12].hex() == iv_hex

        # Last 16 bytes should be tag
        plaintext_len = len(v["plaintext_utf8"].encode("utf-8"))
        expected_total = 12 + plaintext_len + 16  # IV + ciphertext + tag
        assert len(encrypted) == expected_total

    def test_base64_uses_standard_alphabet(self, vectors):
        """Verify base64 uses standard alphabet with padding (RFC 4648 Section 4)."""
        v = next(x for x in vectors["encryption_vectors"] if x["id"] == "encrypt_offer_message")
        ciphertext_b64 = v["expected_ciphertext_base64"]

        # Standard base64 alphabet: A-Z, a-z, 0-9, +, /, =
        import re
        pattern = r'^[A-Za-z0-9+/]+=*$'
        assert re.match(pattern, ciphertext_b64), "Must use standard base64 alphabet"

        # Should have padding to multiple of 4
        assert len(ciphertext_b64) % 4 == 0
