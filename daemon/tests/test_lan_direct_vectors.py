"""Tests validating daemon against shared LAN Direct auth test vectors.

These tests ensure the Python daemon produces identical cryptographic outputs
to the test vectors (and thus to the Android implementation). They catch
bugs like the double-derivation issue where Android called deriveKey() on
an already-derived authToken.
"""

import json
import time
from pathlib import Path
from unittest.mock import MagicMock

from ras.crypto import compute_signaling_hmac, derive_key
from ras.proto.ras import LanDirectAuthRequest, LanDirectAuthResponse
from ras.server import UnifiedServer

# Load test vectors
VECTORS_PATH = Path(__file__).parent.parent.parent / "test-vectors" / "lan_direct_auth.json"
with open(VECTORS_PATH) as f:
    TEST_VECTORS = json.load(f)


class MockDeviceStore:
    """Minimal mock for device store."""

    def __init__(self):
        self.devices = {}

    def get(self, device_id: str):
        return self.devices.get(device_id)


class MockIpProvider:
    async def get_ip(self) -> str:
        return "127.0.0.1"


def _get_vector(vector_id: str) -> dict:
    """Get a specific test vector by ID."""
    for v in TEST_VECTORS["vectors"]:
        if v["id"] == vector_id:
            return v
    raise ValueError(f"Vector not found: {vector_id}")


def _get_error_case(case_id: str) -> dict:
    """Get a specific error case by ID."""
    for c in TEST_VECTORS["error_cases"]:
        if c["id"] == case_id:
            return c
    raise ValueError(f"Error case not found: {case_id}")


class TestKeyDerivation:
    """Test key derivation matches vectors."""

    def test_derive_auth_key_matches_vector(self):
        """Derive auth key from master secret and verify against vector."""
        v = _get_vector("key_derivation")
        master_secret = bytes.fromhex(v["master_secret_hex"])

        auth_key = derive_key(master_secret, v["purpose"])

        assert auth_key.hex() == v["expected_auth_key_hex"]


class TestHmacComputation:
    """Test HMAC computation matches vectors."""

    def test_signaling_hmac_matches_vector(self):
        """Compute signaling HMAC and verify against vector."""
        v = _get_vector("hmac_computation")
        auth_key = bytes.fromhex(v["auth_key_hex"])
        body = bytes.fromhex(v["body_hex"]) if v["body_hex"] else b""

        signature = compute_signaling_hmac(
            auth_key, v["device_id"], v["timestamp"], body
        )

        assert signature.hex() == v["expected_signature_hex"]


class TestProtobufSerialization:
    """Test protobuf serialization matches vectors."""

    def test_parse_protobuf_from_vector(self):
        """Parse known protobuf hex and verify fields match."""
        v = _get_vector("protobuf_serialization")
        proto_bytes = bytes.fromhex(v["expected_protobuf_hex"])

        request = LanDirectAuthRequest().parse(proto_bytes)

        assert request.device_id == v["device_id"]
        assert request.timestamp == v["timestamp"]
        assert request.signature == v["signature_hex"]

    def test_build_protobuf_matches_vector(self):
        """Build auth request from known inputs and verify bytes match."""
        v = _get_vector("protobuf_serialization")

        request = LanDirectAuthRequest(
            device_id=v["device_id"],
            timestamp=v["timestamp"],
            signature=v["signature_hex"],
        )

        assert bytes(request).hex() == v["expected_protobuf_hex"]


class TestFullFlow:
    """Test the full auth flow from master secret to protobuf."""

    def test_full_flow_matches_vector(self):
        """Full flow: derive key -> compute HMAC -> build protobuf."""
        v = _get_vector("full_auth_request")
        master_secret = bytes.fromhex(v["master_secret_hex"])

        # Step 1: Derive auth key
        auth_key = derive_key(master_secret, "auth")
        assert auth_key.hex() == v["expected_auth_key_hex"]

        # Step 2: Compute HMAC
        signature = compute_signaling_hmac(
            auth_key, v["device_id"], v["timestamp"], b""
        )
        assert signature.hex() == v["expected_signature_hex"]

        # Step 3: Build protobuf
        request = LanDirectAuthRequest(
            device_id=v["device_id"],
            timestamp=v["timestamp"],
            signature=signature.hex(),
        )
        assert bytes(request).hex() == v["expected_protobuf_hex"]

    def test_full_flow_validates_with_server(self):
        """Full flow protobuf validates successfully with _validate_ws_auth."""
        v = _get_vector("full_auth_request")
        master_secret = bytes.fromhex(v["master_secret_hex"])

        # Derive key and build auth request (like Android does)
        auth_key = derive_key(master_secret, "auth")
        signature = compute_signaling_hmac(
            auth_key, v["device_id"], v["timestamp"], b""
        )
        request = LanDirectAuthRequest(
            device_id=v["device_id"],
            timestamp=v["timestamp"],
            signature=signature.hex(),
        )
        proto_bytes = bytes(request)

        # Validate with server (like daemon does)
        # We need to mock time since the vector uses a fixed timestamp
        server = UnifiedServer(
            device_store=MockDeviceStore(),
        )

        # Patch time.time to return the vector's timestamp
        import unittest.mock
        with unittest.mock.patch("ras.server.time") as mock_time:
            mock_time.time.return_value = v["timestamp"]
            result = server._validate_ws_auth(proto_bytes, v["device_id"], auth_key)

        assert result is True


class TestDoubleDerivationBug:
    """Test that using master_secret directly (the bug) fails validation."""

    def test_double_derivation_produces_different_hmac(self):
        """Using master_secret directly produces a different HMAC than derived key."""
        ec = _get_error_case("double_derivation_bug")
        master_secret = bytes.fromhex(ec["master_secret_hex"])

        # The correct path: derive auth_key first
        auth_key = derive_key(master_secret, "auth")
        correct_sig = compute_signaling_hmac(
            auth_key, ec["device_id"], ec["timestamp"], b""
        )

        # The buggy path: use master_secret directly
        wrong_sig = compute_signaling_hmac(
            master_secret, ec["device_id"], ec["timestamp"], b""
        )

        # They must differ
        assert correct_sig.hex() != wrong_sig.hex()
        assert correct_sig.hex() == ec["correct_signature_hex"]
        assert wrong_sig.hex() == ec["wrong_signature_hex"]

    def test_double_derivation_fails_server_validation(self):
        """Auth request built with wrong key fails _validate_ws_auth."""
        ec = _get_error_case("double_derivation_bug")
        master_secret = bytes.fromhex(ec["master_secret_hex"])

        # Build auth request using master_secret (the bug)
        wrong_sig = compute_signaling_hmac(
            master_secret, ec["device_id"], ec["timestamp"], b""
        )
        request = LanDirectAuthRequest(
            device_id=ec["device_id"],
            timestamp=ec["timestamp"],
            signature=wrong_sig.hex(),
        )

        # Server validates using derived auth_key
        auth_key = derive_key(master_secret, "auth")
        server = UnifiedServer(
            device_store=MockDeviceStore(),
        )

        import unittest.mock
        with unittest.mock.patch("ras.server.time") as mock_time:
            mock_time.time.return_value = ec["timestamp"]
            result = server._validate_ws_auth(
                bytes(request), ec["device_id"], auth_key
            )

        assert result is False

    def test_wrong_protobuf_bytes_match_vector(self):
        """The 'wrong' protobuf bytes match the error case vector."""
        ec = _get_error_case("double_derivation_bug")
        master_secret = bytes.fromhex(ec["master_secret_hex"])

        wrong_sig = compute_signaling_hmac(
            master_secret, ec["device_id"], ec["timestamp"], b""
        )
        request = LanDirectAuthRequest(
            device_id=ec["device_id"],
            timestamp=ec["timestamp"],
            signature=wrong_sig.hex(),
        )

        assert bytes(request).hex() == ec["wrong_protobuf_hex"]
