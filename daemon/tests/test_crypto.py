"""Tests for crypto module."""

import pytest

from ras.crypto import (
    CryptoError,
    KeyBundle,
    compute_hmac,
    decrypt,
    derive_keys,
    encrypt,
    generate_secret,
    verify_hmac,
)


class TestSecretGeneration:
    """Test secure secret generation."""

    def test_generates_32_bytes(self):
        """Secret is exactly 32 bytes."""
        secret = generate_secret()
        assert len(secret) == 32

    def test_generates_unique_secrets(self):
        """Each call generates different secret."""
        s1 = generate_secret()
        s2 = generate_secret()
        assert s1 != s2

    def test_uses_secure_random(self):
        """Uses cryptographically secure random (basic entropy check)."""
        secret = generate_secret()
        unique_bytes = len(set(secret))
        # Should have good distribution (at least 20 unique bytes in 32)
        assert unique_bytes > 20


class TestKeyDerivation:
    """Test HKDF key derivation."""

    def test_derive_returns_key_bundle(self):
        """derive_keys returns a KeyBundle."""
        secret = b"x" * 32
        keys = derive_keys(secret)
        assert isinstance(keys, KeyBundle)

    def test_derive_auth_key(self):
        """Derives 32-byte auth key."""
        secret = b"x" * 32
        keys = derive_keys(secret)
        assert len(keys.auth_key) == 32

    def test_derive_encrypt_key(self):
        """Derives 32-byte encrypt key."""
        secret = b"x" * 32
        keys = derive_keys(secret)
        assert len(keys.encrypt_key) == 32

    def test_derive_ntfy_key(self):
        """Derives 32-byte ntfy key."""
        secret = b"x" * 32
        keys = derive_keys(secret)
        assert len(keys.ntfy_key) == 32

    def test_derive_topic(self):
        """Derives 12-char hex topic."""
        secret = b"x" * 32
        keys = derive_keys(secret)
        assert len(keys.topic) == 12
        assert all(c in "0123456789abcdef" for c in keys.topic)

    def test_different_secrets_different_keys(self):
        """Different secrets produce different keys."""
        keys1 = derive_keys(b"a" * 32)
        keys2 = derive_keys(b"b" * 32)
        assert keys1.auth_key != keys2.auth_key
        assert keys1.encrypt_key != keys2.encrypt_key

    def test_same_secret_same_keys(self):
        """Same secret produces same keys (deterministic)."""
        secret = b"x" * 32
        keys1 = derive_keys(secret)
        keys2 = derive_keys(secret)
        assert keys1.auth_key == keys2.auth_key
        assert keys1.encrypt_key == keys2.encrypt_key

    def test_keys_are_independent(self):
        """Different purposes produce different keys."""
        secret = b"x" * 32
        keys = derive_keys(secret)
        assert keys.auth_key != keys.encrypt_key
        assert keys.encrypt_key != keys.ntfy_key
        assert keys.auth_key != keys.ntfy_key

    def test_reject_wrong_secret_length(self):
        """Rejects secret that isn't 32 bytes."""
        with pytest.raises(ValueError, match="32 bytes"):
            derive_keys(b"too short")


class TestEncryption:
    """Test AES-GCM encryption."""

    def test_encrypt_decrypt_roundtrip(self):
        """Encrypted data can be decrypted."""
        key = b"x" * 32
        plaintext = b"hello world"
        ciphertext = encrypt(key, plaintext)
        decrypted = decrypt(key, ciphertext)
        assert decrypted == plaintext

    def test_ciphertext_different_each_time(self):
        """Same plaintext produces different ciphertext (random nonce)."""
        key = b"x" * 32
        plaintext = b"hello"
        c1 = encrypt(key, plaintext)
        c2 = encrypt(key, plaintext)
        assert c1 != c2

    def test_ciphertext_format(self):
        """Ciphertext is nonce (12) + encrypted + tag (16)."""
        key = b"x" * 32
        plaintext = b"hello"
        ciphertext = encrypt(key, plaintext)
        # At minimum: 12 (nonce) + len(plaintext) + 16 (tag)
        assert len(ciphertext) >= 12 + len(plaintext) + 16

    def test_decrypt_wrong_key_fails(self):
        """Decryption with wrong key raises CryptoError."""
        key1 = b"a" * 32
        key2 = b"b" * 32
        ciphertext = encrypt(key1, b"hello")
        with pytest.raises(CryptoError):
            decrypt(key2, ciphertext)

    def test_decrypt_tampered_data_fails(self):
        """Decryption of tampered data raises CryptoError."""
        key = b"x" * 32
        ciphertext = bytearray(encrypt(key, b"hello"))
        ciphertext[20] ^= 0xFF  # Flip a bit
        with pytest.raises(CryptoError):
            decrypt(key, bytes(ciphertext))

    def test_decrypt_truncated_data_fails(self):
        """Decryption of truncated data raises CryptoError."""
        key = b"x" * 32
        ciphertext = encrypt(key, b"hello")
        with pytest.raises(CryptoError):
            decrypt(key, ciphertext[:10])

    def test_encrypt_empty_data(self):
        """Can encrypt empty data."""
        key = b"x" * 32
        ciphertext = encrypt(key, b"")
        decrypted = decrypt(key, ciphertext)
        assert decrypted == b""

    def test_encrypt_large_data(self):
        """Can encrypt large data."""
        key = b"x" * 32
        plaintext = b"x" * 100000
        ciphertext = encrypt(key, plaintext)
        decrypted = decrypt(key, ciphertext)
        assert decrypted == plaintext

    def test_reject_wrong_key_length(self):
        """Rejects key that isn't 32 bytes."""
        with pytest.raises(ValueError, match="32 bytes"):
            encrypt(b"short", b"hello")
        with pytest.raises(ValueError, match="32 bytes"):
            decrypt(b"short", b"x" * 30)


class TestHmac:
    """Test HMAC operations."""

    def test_compute_hmac_returns_32_bytes(self):
        """HMAC-SHA256 produces 32-byte output."""
        key = b"x" * 32
        data = b"hello"
        result = compute_hmac(key, data)
        assert len(result) == 32

    def test_verify_hmac_valid(self):
        """verify_hmac returns True for valid HMAC."""
        key = b"x" * 32
        data = b"hello"
        mac = compute_hmac(key, data)
        assert verify_hmac(key, data, mac) is True

    def test_verify_hmac_invalid(self):
        """verify_hmac returns False for invalid HMAC."""
        key = b"x" * 32
        data = b"hello"
        wrong_mac = b"z" * 32
        assert verify_hmac(key, data, wrong_mac) is False

    def test_verify_hmac_wrong_key(self):
        """verify_hmac returns False with wrong key."""
        key1 = b"a" * 32
        key2 = b"b" * 32
        data = b"hello"
        mac = compute_hmac(key1, data)
        assert verify_hmac(key2, data, mac) is False

    def test_hmac_deterministic(self):
        """Same inputs produce same HMAC."""
        key = b"x" * 32
        data = b"hello"
        mac1 = compute_hmac(key, data)
        mac2 = compute_hmac(key, data)
        assert mac1 == mac2

    def test_hmac_different_data_different_result(self):
        """Different data produces different HMAC."""
        key = b"x" * 32
        mac1 = compute_hmac(key, b"hello")
        mac2 = compute_hmac(key, b"world")
        assert mac1 != mac2
