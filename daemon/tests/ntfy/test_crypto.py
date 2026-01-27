"""Tests for ntfy crypto module."""

import os

import pytest
from cryptography.exceptions import InvalidTag

from ras.ntfy.crypto import IpChangeData, NtfyCrypto


class TestNtfyCryptoInit:
    """Tests for NtfyCrypto initialization."""

    def test_accepts_32_byte_key(self):
        """Accepts valid 32-byte key."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)

        assert crypto is not None

    def test_rejects_short_key(self):
        """Rejects key shorter than 32 bytes."""
        key = os.urandom(16)

        with pytest.raises(ValueError, match="must be 32 bytes"):
            NtfyCrypto(ntfy_key=key)

    def test_rejects_long_key(self):
        """Rejects key longer than 32 bytes."""
        key = os.urandom(64)

        with pytest.raises(ValueError, match="must be 32 bytes"):
            NtfyCrypto(ntfy_key=key)

    def test_rejects_empty_key(self):
        """Rejects empty key."""
        with pytest.raises(ValueError, match="must be 32 bytes"):
            NtfyCrypto(ntfy_key=b"")


class TestNtfyCryptoEncrypt:
    """Tests for encryption."""

    def test_encrypt_returns_base64(self):
        """Encryption returns base64-encoded string."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        result = crypto.encrypt_ip_notification(
            ip="192.168.1.1",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        # Should be valid base64
        import base64

        decoded = base64.b64decode(result)
        # Should have at least IV (12) + tag (16) = 28 bytes
        assert len(decoded) >= 28

    def test_encrypt_ipv4(self):
        """Encrypts IPv4 address."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        result = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        assert isinstance(result, str)
        assert len(result) > 0

    def test_encrypt_ipv6(self):
        """Encrypts IPv6 address."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        result = crypto.encrypt_ip_notification(
            ip="2001:0db8:85a3:0000:0000:8a2e:0370:7334",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        assert isinstance(result, str)
        assert len(result) > 0

    def test_encrypt_rejects_short_nonce(self):
        """Rejects nonce shorter than 16 bytes."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(8)

        with pytest.raises(ValueError, match="nonce must be 16 bytes"):
            crypto.encrypt_ip_notification(
                ip="1.2.3.4",
                port=8821,
                timestamp=1234567890,
                nonce=nonce,
            )

    def test_encrypt_rejects_long_nonce(self):
        """Rejects nonce longer than 16 bytes."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(32)

        with pytest.raises(ValueError, match="nonce must be 16 bytes"):
            crypto.encrypt_ip_notification(
                ip="1.2.3.4",
                port=8821,
                timestamp=1234567890,
                nonce=nonce,
            )

    def test_encrypt_different_ivs(self):
        """Different encryptions produce different ciphertexts."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        result1 = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        result2 = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        # IVs should be different, so ciphertexts should differ
        assert result1 != result2


class TestNtfyCryptoDecrypt:
    """Tests for decryption."""

    def test_decrypt_roundtrip(self):
        """Decrypt reverses encrypt."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="192.168.1.100",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)

        assert decrypted.ip == "192.168.1.100"
        assert decrypted.port == 8821
        assert decrypted.timestamp == 1234567890
        assert decrypted.nonce == nonce

    def test_decrypt_ipv6_roundtrip(self):
        """Decrypt roundtrip with IPv6."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        ipv6 = "2001:db8:85a3::8a2e:370:7334"
        encrypted = crypto.encrypt_ip_notification(
            ip=ipv6,
            port=9999,
            timestamp=9876543210,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)

        assert decrypted.ip == ipv6
        assert decrypted.port == 9999
        assert decrypted.timestamp == 9876543210

    def test_decrypt_returns_ipchangedata(self):
        """Decrypt returns IpChangeData dataclass."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)

        assert isinstance(decrypted, IpChangeData)

    def test_decrypt_invalid_base64(self):
        """Rejects invalid base64."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)

        with pytest.raises(ValueError, match="Invalid base64"):
            crypto.decrypt_ip_notification("not valid base64!!!")

    def test_decrypt_too_short(self):
        """Rejects message that's too short."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)

        import base64

        # Only 20 bytes - less than IV + tag
        short_data = base64.b64encode(b"x" * 20).decode()

        with pytest.raises(ValueError, match="Message too short"):
            crypto.decrypt_ip_notification(short_data)

    def test_decrypt_wrong_key(self):
        """Fails with wrong key."""
        key1 = os.urandom(32)
        key2 = os.urandom(32)
        crypto1 = NtfyCrypto(ntfy_key=key1)
        crypto2 = NtfyCrypto(ntfy_key=key2)
        nonce = os.urandom(16)

        encrypted = crypto1.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        with pytest.raises(InvalidTag):
            crypto2.decrypt_ip_notification(encrypted)

    def test_decrypt_tampered_ciphertext(self):
        """Fails with tampered ciphertext."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        # Tamper with the ciphertext
        import base64

        data = bytearray(base64.b64decode(encrypted))
        data[15] ^= 0xFF  # Flip bits in ciphertext
        tampered = base64.b64encode(bytes(data)).decode()

        with pytest.raises(InvalidTag):
            crypto.decrypt_ip_notification(tampered)


class TestNtfyCryptoConstants:
    """Tests for crypto constants."""

    def test_iv_size(self):
        """IV size is 12 bytes."""
        assert NtfyCrypto.IV_SIZE == 12

    def test_tag_size(self):
        """Tag size is 16 bytes."""
        assert NtfyCrypto.TAG_SIZE == 16

    def test_nonce_size(self):
        """Nonce size is 16 bytes."""
        assert NtfyCrypto.NONCE_SIZE == 16

    def test_min_encrypted_size(self):
        """Min encrypted size is IV + tag."""
        assert NtfyCrypto.MIN_ENCRYPTED_SIZE == 28


class TestNtfyCryptoEdgeCases:
    """Edge case tests."""

    def test_empty_ip(self):
        """Handles empty IP (protobuf allows it)."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)
        assert decrypted.ip == ""

    def test_zero_port(self):
        """Handles zero port."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=0,
            timestamp=1234567890,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)
        assert decrypted.port == 0

    def test_large_port(self):
        """Handles max port number."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=65535,
            timestamp=1234567890,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)
        assert decrypted.port == 65535

    def test_zero_timestamp(self):
        """Handles zero timestamp."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=0,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)
        assert decrypted.timestamp == 0

    def test_large_timestamp(self):
        """Handles large timestamp."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = os.urandom(16)

        large_ts = 2**62  # Very far in the future

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=large_ts,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)
        assert decrypted.timestamp == large_ts

    def test_all_zero_nonce(self):
        """Handles all-zero nonce."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = b"\x00" * 16

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)
        assert decrypted.nonce == nonce

    def test_all_ff_nonce(self):
        """Handles all-0xFF nonce."""
        key = os.urandom(32)
        crypto = NtfyCrypto(ntfy_key=key)
        nonce = b"\xff" * 16

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=1234567890,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)
        assert decrypted.nonce == nonce
