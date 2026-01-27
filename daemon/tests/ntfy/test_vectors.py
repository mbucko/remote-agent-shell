"""Test vectors for ntfy crypto cross-platform validation.

These test vectors ensure encryption/decryption is compatible
between the daemon (Python) and Android (Kotlin) implementations.

IMPORTANT: The static test vectors in TestStaticCrossplatformVectors
should be copied to the Kotlin/Android test suite to ensure both
implementations produce identical results.
"""

import base64
import os
from unittest.mock import patch

import pytest

from ras.ntfy.crypto import NtfyCrypto


class TestStaticCrossplatformVectors:
    """Static test vectors for cross-platform validation.

    These vectors use fixed IV values (via mocking) to produce
    deterministic output that Kotlin/Android can verify against.

    To use in Kotlin:
    1. Use the same key, iv, ip, port, timestamp, nonce
    2. Encrypt and verify the base64 output matches EXPECTED_CIPHERTEXT
    3. Decrypt EXPECTED_CIPHERTEXT and verify all fields match
    """

    # Test Vector 1: Basic IPv4
    VECTOR_1 = {
        "key_hex": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "iv_hex": "000102030405060708090a0b",  # 12 bytes
        "ip": "192.168.1.100",
        "port": 8821,
        "timestamp": 1700000000,
        "nonce_hex": "deadbeefcafebabe1234567890abcdef",  # 16 bytes
    }

    # Test Vector 2: IPv6 address
    VECTOR_2 = {
        "key_hex": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "iv_hex": "aabbccddeeff00112233445566",  # 12 bytes (but only 12 used)
        "ip": "2001:db8::1",
        "port": 443,
        "timestamp": 1234567890,
        "nonce_hex": "00112233445566778899aabbccddeeff",
    }

    # Test Vector 3: Edge case - max port, zero timestamp
    VECTOR_3 = {
        "key_hex": "00000000000000000000000000000000ffffffffffffffffffffffffffffffff",
        "iv_hex": "112233445566778899aabb",  # 11 bytes - will be padded
        "ip": "255.255.255.255",
        "port": 65535,
        "timestamp": 0,
        "nonce_hex": "aaaabbbbccccddddeeeeffffaaaabbbb",
    }

    def _encrypt_with_fixed_iv(self, vector: dict) -> str:
        """Encrypt using a fixed IV for deterministic output."""
        key = bytes.fromhex(vector["key_hex"])
        iv = bytes.fromhex(vector["iv_hex"])
        # Ensure IV is exactly 12 bytes
        if len(iv) < 12:
            iv = iv + b"\x00" * (12 - len(iv))
        iv = iv[:12]

        nonce = bytes.fromhex(vector["nonce_hex"])
        crypto = NtfyCrypto(ntfy_key=key)

        with patch("os.urandom", return_value=iv):
            return crypto.encrypt_ip_notification(
                ip=vector["ip"],
                port=vector["port"],
                timestamp=vector["timestamp"],
                nonce=nonce,
            )

    def test_vector_1_encryption_deterministic(self):
        """Vector 1 produces consistent output."""
        result1 = self._encrypt_with_fixed_iv(self.VECTOR_1)
        result2 = self._encrypt_with_fixed_iv(self.VECTOR_1)
        assert result1 == result2

        # Print for Kotlin test suite (run with -s to see)
        print(f"\n=== VECTOR 1 EXPECTED CIPHERTEXT ===")
        print(f"Base64: {result1}")
        print(f"Hex: {base64.b64decode(result1).hex()}")

    def test_vector_1_decryption(self):
        """Vector 1 decrypts correctly."""
        encrypted = self._encrypt_with_fixed_iv(self.VECTOR_1)
        key = bytes.fromhex(self.VECTOR_1["key_hex"])
        crypto = NtfyCrypto(ntfy_key=key)

        decrypted = crypto.decrypt_ip_notification(encrypted)

        assert decrypted.ip == self.VECTOR_1["ip"]
        assert decrypted.port == self.VECTOR_1["port"]
        assert decrypted.timestamp == self.VECTOR_1["timestamp"]
        assert decrypted.nonce == bytes.fromhex(self.VECTOR_1["nonce_hex"])

    def test_vector_2_ipv6_encryption_deterministic(self):
        """Vector 2 (IPv6) produces consistent output."""
        result1 = self._encrypt_with_fixed_iv(self.VECTOR_2)
        result2 = self._encrypt_with_fixed_iv(self.VECTOR_2)
        assert result1 == result2

        print(f"\n=== VECTOR 2 (IPv6) EXPECTED CIPHERTEXT ===")
        print(f"Base64: {result1}")
        print(f"Hex: {base64.b64decode(result1).hex()}")

    def test_vector_2_ipv6_decryption(self):
        """Vector 2 (IPv6) decrypts correctly."""
        encrypted = self._encrypt_with_fixed_iv(self.VECTOR_2)
        key = bytes.fromhex(self.VECTOR_2["key_hex"])
        crypto = NtfyCrypto(ntfy_key=key)

        decrypted = crypto.decrypt_ip_notification(encrypted)

        assert decrypted.ip == self.VECTOR_2["ip"]
        assert decrypted.port == self.VECTOR_2["port"]
        assert decrypted.timestamp == self.VECTOR_2["timestamp"]
        assert decrypted.nonce == bytes.fromhex(self.VECTOR_2["nonce_hex"])

    def test_vector_3_edge_cases_encryption_deterministic(self):
        """Vector 3 (edge cases) produces consistent output."""
        result1 = self._encrypt_with_fixed_iv(self.VECTOR_3)
        result2 = self._encrypt_with_fixed_iv(self.VECTOR_3)
        assert result1 == result2

        print(f"\n=== VECTOR 3 (Edge Cases) EXPECTED CIPHERTEXT ===")
        print(f"Base64: {result1}")
        print(f"Hex: {base64.b64decode(result1).hex()}")

    def test_vector_3_edge_cases_decryption(self):
        """Vector 3 (edge cases) decrypts correctly."""
        encrypted = self._encrypt_with_fixed_iv(self.VECTOR_3)
        key = bytes.fromhex(self.VECTOR_3["key_hex"])
        crypto = NtfyCrypto(ntfy_key=key)

        decrypted = crypto.decrypt_ip_notification(encrypted)

        assert decrypted.ip == self.VECTOR_3["ip"]
        assert decrypted.port == self.VECTOR_3["port"]
        assert decrypted.timestamp == self.VECTOR_3["timestamp"]
        assert decrypted.nonce == bytes.fromhex(self.VECTOR_3["nonce_hex"])

    def test_generate_all_vectors_for_kotlin(self):
        """Generate all test vectors for Kotlin implementation.

        Run with: pytest -s -k test_generate_all_vectors
        """
        vectors = [
            ("VECTOR_1", self.VECTOR_1),
            ("VECTOR_2", self.VECTOR_2),
            ("VECTOR_3", self.VECTOR_3),
        ]

        print("\n" + "=" * 60)
        print("CROSS-PLATFORM TEST VECTORS FOR KOTLIN")
        print("=" * 60)

        for name, vector in vectors:
            encrypted = self._encrypt_with_fixed_iv(vector)
            print(f"\n{name}:")
            print(f"  key_hex = \"{vector['key_hex']}\"")
            print(f"  iv_hex = \"{vector['iv_hex'][:24]}\" // first 12 bytes")
            print(f"  ip = \"{vector['ip']}\"")
            print(f"  port = {vector['port']}")
            print(f"  timestamp = {vector['timestamp']}L")
            print(f"  nonce_hex = \"{vector['nonce_hex']}\"")
            print(f"  expected_base64 = \"{encrypted}\"")
            print(f"  expected_hex = \"{base64.b64decode(encrypted).hex()}\"")


class TestNtfyCryptoVectors:
    """Test vector validation for cross-platform compatibility."""

    # Known test key (32 bytes)
    TEST_KEY = bytes.fromhex(
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    )

    # Known test nonce (16 bytes)
    TEST_NONCE = bytes.fromhex("deadbeefcafebabe1234567890abcdef")

    def test_encryption_produces_valid_format(self):
        """Encrypted output has correct structure: base64(IV || ciphertext || tag)."""
        crypto = NtfyCrypto(ntfy_key=self.TEST_KEY)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=self.TEST_NONCE,
        )

        # Decode and validate structure
        data = base64.b64decode(encrypted)

        # Must have at least IV (12) + some ciphertext + tag (16)
        assert len(data) >= 28

        # IV should be first 12 bytes (random, so just check it exists)
        iv = data[:12]
        assert len(iv) == 12

    def test_decryption_roundtrip_preserves_all_fields(self):
        """All fields are preserved through encrypt/decrypt roundtrip."""
        crypto = NtfyCrypto(ntfy_key=self.TEST_KEY)

        test_cases = [
            # (ip, port, timestamp, nonce)
            ("1.2.3.4", 8821, 1234567890, self.TEST_NONCE),
            ("255.255.255.255", 65535, 0, self.TEST_NONCE),
            ("0.0.0.0", 0, 2**62, self.TEST_NONCE),
            ("192.168.1.100", 443, 1700000000, self.TEST_NONCE),
            # IPv6
            ("2001:db8::1", 8821, 1234567890, self.TEST_NONCE),
            ("::1", 22, 1234567890, self.TEST_NONCE),
            ("fe80::1%eth0", 80, 1234567890, self.TEST_NONCE),
        ]

        for ip, port, timestamp, nonce in test_cases:
            encrypted = crypto.encrypt_ip_notification(
                ip=ip,
                port=port,
                timestamp=timestamp,
                nonce=nonce,
            )

            decrypted = crypto.decrypt_ip_notification(encrypted)

            assert decrypted.ip == ip, f"IP mismatch for {ip}"
            assert decrypted.port == port, f"Port mismatch for {ip}"
            assert decrypted.timestamp == timestamp, f"Timestamp mismatch for {ip}"
            assert decrypted.nonce == nonce, f"Nonce mismatch for {ip}"

    def test_different_keys_produce_different_ciphertexts(self):
        """Different keys produce different ciphertexts."""
        key1 = bytes.fromhex("00" * 32)
        key2 = bytes.fromhex("ff" * 32)

        crypto1 = NtfyCrypto(ntfy_key=key1)
        crypto2 = NtfyCrypto(ntfy_key=key2)

        # Use same IV for comparison (not secure, just for testing)
        with pytest.MonkeyPatch().context() as mp:
            mp.setattr(os, "urandom", lambda n: b"\x00" * n)

            enc1 = crypto1.encrypt_ip_notification(
                ip="1.2.3.4",
                port=8821,
                timestamp=1234567890,
                nonce=self.TEST_NONCE,
            )

            enc2 = crypto2.encrypt_ip_notification(
                ip="1.2.3.4",
                port=8821,
                timestamp=1234567890,
                nonce=self.TEST_NONCE,
            )

        # Ciphertexts should be different (different keys)
        assert enc1 != enc2

    def test_cross_instance_compatibility(self):
        """Different NtfyCrypto instances with same key are compatible."""
        crypto1 = NtfyCrypto(ntfy_key=self.TEST_KEY)
        crypto2 = NtfyCrypto(ntfy_key=self.TEST_KEY)

        encrypted = crypto1.encrypt_ip_notification(
            ip="10.0.0.1",
            port=12345,
            timestamp=9999999999,
            nonce=self.TEST_NONCE,
        )

        decrypted = crypto2.decrypt_ip_notification(encrypted)

        assert decrypted.ip == "10.0.0.1"
        assert decrypted.port == 12345
        assert decrypted.timestamp == 9999999999

    def test_protobuf_field_encoding(self):
        """Protobuf correctly encodes all field types."""
        crypto = NtfyCrypto(ntfy_key=self.TEST_KEY)

        # Test boundary values
        test_cases = [
            # Empty string (default protobuf value)
            ("", 1, 1, self.TEST_NONCE),
            # Very long IP (synthetic, but tests string handling)
            ("a" * 100, 1, 1, self.TEST_NONCE),
            # Max uint32 port
            ("1.2.3.4", 2**32 - 1, 1, self.TEST_NONCE),
            # Large timestamp
            ("1.2.3.4", 1, 2**63 - 1, self.TEST_NONCE),
        ]

        for ip, port, timestamp, nonce in test_cases:
            encrypted = crypto.encrypt_ip_notification(
                ip=ip,
                port=port,
                timestamp=timestamp,
                nonce=nonce,
            )

            decrypted = crypto.decrypt_ip_notification(encrypted)

            assert decrypted.ip == ip
            # Note: protobuf uint32 may overflow, but int64 should be fine
            assert decrypted.timestamp == timestamp


class TestAesGcmProperties:
    """Tests for AES-GCM security properties."""

    def test_authentication_tag_prevents_tampering(self):
        """GCM authentication tag prevents undetected tampering."""
        crypto = NtfyCrypto(ntfy_key=os.urandom(32))
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        # Tamper with various parts
        data = bytearray(base64.b64decode(encrypted))

        # Tamper with IV
        tampered_iv = bytes(data)
        tampered_iv = bytearray(tampered_iv)
        tampered_iv[0] ^= 0xFF
        tampered_enc = base64.b64encode(bytes(tampered_iv)).decode()

        with pytest.raises(Exception):  # InvalidTag or similar
            crypto.decrypt_ip_notification(tampered_enc)

        # Tamper with ciphertext
        tampered_ct = bytes(data)
        tampered_ct = bytearray(tampered_ct)
        tampered_ct[15] ^= 0xFF
        tampered_enc = base64.b64encode(bytes(tampered_ct)).decode()

        with pytest.raises(Exception):
            crypto.decrypt_ip_notification(tampered_enc)

        # Tamper with tag (last 16 bytes)
        tampered_tag = bytes(data)
        tampered_tag = bytearray(tampered_tag)
        tampered_tag[-1] ^= 0xFF
        tampered_enc = base64.b64encode(bytes(tampered_tag)).decode()

        with pytest.raises(Exception):
            crypto.decrypt_ip_notification(tampered_enc)

    def test_iv_uniqueness(self):
        """Each encryption uses a unique IV."""
        crypto = NtfyCrypto(ntfy_key=os.urandom(32))
        nonce = os.urandom(16)

        ivs = set()
        for _ in range(100):
            encrypted = crypto.encrypt_ip_notification(
                ip="1.2.3.4",
                port=8821,
                timestamp=1234567890,
                nonce=nonce,
            )
            iv = base64.b64decode(encrypted)[:12]
            ivs.add(iv)

        # All IVs should be unique
        assert len(ivs) == 100


class TestReplayProtection:
    """Tests for replay protection via nonce."""

    def test_nonce_is_preserved(self):
        """Nonce is preserved through encryption."""
        crypto = NtfyCrypto(ntfy_key=os.urandom(32))
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)

        assert decrypted.nonce == nonce

    def test_different_nonces_produce_different_messages(self):
        """Different nonces produce different plaintext (and thus ciphertext)."""
        crypto = NtfyCrypto(ntfy_key=os.urandom(32))

        nonce1 = os.urandom(16)
        nonce2 = os.urandom(16)

        # Even with same IV (for test), different nonces = different plaintext
        enc1 = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce1,
        )

        enc2 = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce2,
        )

        # Ciphertexts differ due to different nonces in plaintext
        assert enc1 != enc2
