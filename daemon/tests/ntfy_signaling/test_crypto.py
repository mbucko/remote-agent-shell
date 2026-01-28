"""Tests for ntfy signaling crypto module.

Tests cover:
- Key derivation (HKDF)
- Encryption/decryption (AES-256-GCM)
- Error handling
- Test vector verification
"""

import base64
import os

import pytest

from ras.ntfy_signaling.crypto import (
    DecryptionError,
    NtfySignalingCrypto,
    derive_signaling_key,
    IV_LENGTH,
    KEY_LENGTH,
    MAX_MESSAGE_SIZE,
    MIN_ENCRYPTED_SIZE,
    TAG_LENGTH,
)


class TestDeriveSignalingKey:
    """Tests for signaling key derivation."""

    def test_derives_32_byte_key(self):
        """Signaling key is 32 bytes."""
        master = bytes(32)
        key = derive_signaling_key(master)
        assert len(key) == 32

    def test_deterministic(self):
        """Same master produces same key."""
        master = bytes.fromhex("0123456789abcdef" * 4)
        key1 = derive_signaling_key(master)
        key2 = derive_signaling_key(master)
        assert key1 == key2

    def test_different_master_different_key(self):
        """Different masters produce different keys."""
        master1 = bytes(32)
        master2 = bytes.fromhex("ff" * 32)
        key1 = derive_signaling_key(master1)
        key2 = derive_signaling_key(master2)
        assert key1 != key2

    def test_different_from_other_derived_keys(self):
        """Signaling key differs from auth/ntfy keys."""
        from ras.crypto import derive_key

        master = bytes.fromhex("0123456789abcdef" * 4)
        signaling_key = derive_signaling_key(master)
        auth_key = derive_key(master, "auth")
        ntfy_key = derive_key(master, "ntfy")

        assert signaling_key != auth_key
        assert signaling_key != ntfy_key

    def test_rejects_short_master(self):
        """Rejects master secret < 32 bytes."""
        with pytest.raises(ValueError, match="must be 32 bytes"):
            derive_signaling_key(bytes(16))

    def test_rejects_long_master(self):
        """Rejects master secret > 32 bytes."""
        with pytest.raises(ValueError, match="must be 32 bytes"):
            derive_signaling_key(bytes(64))

    def test_rejects_empty_master(self):
        """Rejects empty master secret."""
        with pytest.raises(ValueError, match="must be 32 bytes"):
            derive_signaling_key(b"")


class TestNtfySignalingCryptoInit:
    """Tests for NtfySignalingCrypto initialization."""

    def test_accepts_32_byte_key(self):
        """Accepts valid 32-byte key."""
        crypto = NtfySignalingCrypto(bytes(32))
        assert crypto is not None

    def test_rejects_short_key(self):
        """Rejects key < 32 bytes."""
        with pytest.raises(ValueError, match="must be 32 bytes"):
            NtfySignalingCrypto(bytes(16))

    def test_rejects_long_key(self):
        """Rejects key > 32 bytes."""
        with pytest.raises(ValueError, match="must be 32 bytes"):
            NtfySignalingCrypto(bytes(64))


class TestNtfySignalingCryptoEncrypt:
    """Tests for encryption."""

    @pytest.fixture
    def crypto(self):
        """Create crypto instance with test key."""
        return NtfySignalingCrypto(bytes(32))

    def test_encrypt_returns_base64(self, crypto):
        """Encrypted output is valid base64."""
        encrypted = crypto.encrypt(b"test")
        # Should not raise
        decoded = base64.b64decode(encrypted)
        assert len(decoded) >= MIN_ENCRYPTED_SIZE

    def test_encrypt_produces_correct_structure(self, crypto):
        """Encrypted message has IV + ciphertext + tag."""
        plaintext = b"hello world"
        encrypted = crypto.encrypt(plaintext)
        decoded = base64.b64decode(encrypted)

        # Should be at least IV + TAG + plaintext
        expected_min = IV_LENGTH + TAG_LENGTH + len(plaintext)
        assert len(decoded) >= expected_min

    def test_encrypt_unique_per_call(self, crypto):
        """Each encryption produces different output (random IV)."""
        plaintext = b"same message"
        enc1 = crypto.encrypt(plaintext)
        enc2 = crypto.encrypt(plaintext)
        assert enc1 != enc2

    def test_encrypt_empty_plaintext(self, crypto):
        """Can encrypt empty plaintext."""
        encrypted = crypto.encrypt(b"")
        decoded = base64.b64decode(encrypted)
        assert len(decoded) == MIN_ENCRYPTED_SIZE  # IV + tag

    def test_encrypt_large_plaintext(self, crypto):
        """Can encrypt large plaintext up to limit."""
        plaintext = b"x" * (MAX_MESSAGE_SIZE - 1000)
        encrypted = crypto.encrypt(plaintext)
        assert len(encrypted) > 0

    def test_encrypt_rejects_too_large(self, crypto):
        """Rejects plaintext exceeding limit."""
        plaintext = b"x" * (MAX_MESSAGE_SIZE + 1)
        with pytest.raises(ValueError, match="exceeds"):
            crypto.encrypt(plaintext)


class TestNtfySignalingCryptoDecrypt:
    """Tests for decryption."""

    @pytest.fixture
    def crypto(self):
        """Create crypto instance with test key."""
        return NtfySignalingCrypto(bytes(32))

    def test_decrypt_reverses_encrypt(self, crypto):
        """Decryption reverses encryption."""
        plaintext = b"hello world"
        encrypted = crypto.encrypt(plaintext)
        decrypted = crypto.decrypt(encrypted)
        assert decrypted == plaintext

    def test_decrypt_roundtrip_empty(self, crypto):
        """Roundtrip works for empty plaintext."""
        plaintext = b""
        encrypted = crypto.encrypt(plaintext)
        decrypted = crypto.decrypt(encrypted)
        assert decrypted == plaintext

    def test_decrypt_roundtrip_unicode(self, crypto):
        """Roundtrip works for UTF-8 content."""
        plaintext = "📱 Téléphone 日本語".encode("utf-8")
        encrypted = crypto.encrypt(plaintext)
        decrypted = crypto.decrypt(encrypted)
        assert decrypted == plaintext

    def test_decrypt_wrong_key_fails(self):
        """Decryption with wrong key fails."""
        crypto1 = NtfySignalingCrypto(bytes(32))
        crypto2 = NtfySignalingCrypto(bytes.fromhex("ff" * 32))

        encrypted = crypto1.encrypt(b"secret")
        with pytest.raises(DecryptionError, match="Decryption failed"):
            crypto2.decrypt(encrypted)

    def test_decrypt_tampered_ciphertext_fails(self, crypto):
        """Decryption of tampered ciphertext fails."""
        encrypted = crypto.encrypt(b"test")
        decoded = bytearray(base64.b64decode(encrypted))
        # Tamper with ciphertext (not IV)
        decoded[-1] ^= 0xFF
        tampered = base64.b64encode(bytes(decoded)).decode("ascii")

        with pytest.raises(DecryptionError, match="Decryption failed"):
            crypto.decrypt(tampered)

    def test_decrypt_tampered_iv_fails(self, crypto):
        """Decryption with tampered IV fails."""
        encrypted = crypto.encrypt(b"test")
        decoded = bytearray(base64.b64decode(encrypted))
        # Tamper with IV
        decoded[0] ^= 0xFF
        tampered = base64.b64encode(bytes(decoded)).decode("ascii")

        with pytest.raises(DecryptionError, match="Decryption failed"):
            crypto.decrypt(tampered)

    def test_decrypt_invalid_base64(self, crypto):
        """Invalid base64 raises DecryptionError."""
        with pytest.raises(DecryptionError, match="Invalid base64"):
            crypto.decrypt("not-valid-base64!!!")

    def test_decrypt_too_short(self, crypto):
        """Message too short raises DecryptionError."""
        short = base64.b64encode(b"x" * 10).decode("ascii")
        with pytest.raises(DecryptionError, match="too short"):
            crypto.decrypt(short)

    def test_decrypt_empty_base64(self, crypto):
        """Empty base64 raises DecryptionError."""
        with pytest.raises(DecryptionError, match="too short"):
            crypto.decrypt("")

    def test_decrypt_too_large(self, crypto):
        """Message too large raises DecryptionError."""
        # Create a very large base64 string
        large = "A" * (MAX_MESSAGE_SIZE * 3)
        with pytest.raises(DecryptionError, match="too large"):
            crypto.decrypt(large)


class TestNtfySignalingCryptoRaw:
    """Tests for raw bytes encryption/decryption."""

    @pytest.fixture
    def crypto(self):
        """Create crypto instance with test key."""
        return NtfySignalingCrypto(bytes(32))

    def test_encrypt_raw_returns_both_formats(self, crypto):
        """encrypt_raw returns both base64 and raw bytes."""
        result = crypto.encrypt_raw(b"test")
        assert result.base64_encoded
        assert result.raw_bytes
        assert base64.b64decode(result.base64_encoded) == result.raw_bytes

    def test_decrypt_raw_reverses_encrypt_raw(self, crypto):
        """decrypt_raw reverses encrypt_raw."""
        plaintext = b"hello"
        result = crypto.encrypt_raw(plaintext)
        decrypted = crypto.decrypt_raw(result.raw_bytes)
        assert decrypted == plaintext

    def test_decrypt_raw_rejects_too_short(self, crypto):
        """decrypt_raw rejects too-short message."""
        with pytest.raises(DecryptionError, match="too short"):
            crypto.decrypt_raw(b"x" * 10)


class TestNtfySignalingCryptoZeroKey:
    """Tests for key zeroing."""

    def test_zero_key_makes_crypto_unusable(self):
        """After zero_key, decryption fails."""
        # Use a non-zero key so zeroing actually changes it
        key = os.urandom(32)
        crypto = NtfySignalingCrypto(key)
        encrypted = crypto.encrypt(b"secret")

        crypto.zero_key()

        # Should fail to decrypt (key is now zeros, different from original)
        with pytest.raises(DecryptionError):
            crypto.decrypt(encrypted)


class TestCryptoUsesCSPRNG:
    """Tests to verify CSPRNG usage."""

    def test_iv_is_random(self):
        """IV should be different each time."""
        crypto = NtfySignalingCrypto(bytes(32))

        ivs = set()
        for _ in range(100):
            encrypted = crypto.encrypt(b"test")
            decoded = base64.b64decode(encrypted)
            iv = decoded[:IV_LENGTH]
            ivs.add(iv)

        # All 100 IVs should be unique
        assert len(ivs) == 100


class TestInteroperability:
    """Tests for cross-platform interoperability."""

    def test_encrypt_decrypt_with_derived_key(self):
        """Full flow: derive key, encrypt, decrypt."""
        master = bytes.fromhex("0123456789abcdef" * 4)
        key = derive_signaling_key(master)

        crypto = NtfySignalingCrypto(key)

        plaintext = b"SDP offer content v=0..."
        encrypted = crypto.encrypt(plaintext)
        decrypted = crypto.decrypt(encrypted)

        assert decrypted == plaintext

    def test_encryption_format_for_android(self):
        """Verify encryption format matches Android expectations."""
        key = bytes(32)
        crypto = NtfySignalingCrypto(key)

        encrypted = crypto.encrypt(b"test")
        decoded = base64.b64decode(encrypted)

        # Format: IV (12) || ciphertext || tag (16)
        assert len(decoded) >= IV_LENGTH + TAG_LENGTH
        # First 12 bytes are IV
        iv = decoded[:IV_LENGTH]
        assert len(iv) == 12
        # Remaining is ciphertext with appended tag
        ciphertext_with_tag = decoded[IV_LENGTH:]
        assert len(ciphertext_with_tag) >= TAG_LENGTH
