"""Tests for HKDF key derivation."""

import pytest

from ras.crypto import derive_key, derive_ntfy_topic, generate_master_secret


class TestGenerateMasterSecret:
    """Tests for master secret generation."""

    def test_generates_32_bytes(self):
        """Master secret is 32 bytes."""
        secret = generate_master_secret()
        assert len(secret) == 32

    def test_generates_different_each_time(self):
        """Each call generates a unique secret."""
        secrets = [generate_master_secret() for _ in range(10)]
        assert len(set(secrets)) == 10

    def test_returns_bytes(self):
        """Returns bytes type."""
        secret = generate_master_secret()
        assert isinstance(secret, bytes)


class TestDeriveKey:
    """Tests for HKDF key derivation."""

    def test_derive_auth_key_vector_1(self):
        """Test vector: derive_auth_key_1."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        expected = bytes.fromhex(
            "bec0c3289e346d890ea330014e23e6e7cf95f82c8bd7f5f133850c89ac165a43"
        )
        result = derive_key(master_secret, "auth")
        assert result == expected

    def test_derive_encrypt_key_vector_1(self):
        """Test vector: derive_encrypt_key_1."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        expected = bytes.fromhex(
            "fdb096356d535edd24a3eee6f2126b77018c51dff15c86ccf6bc3c76f086c2a0"
        )
        result = derive_key(master_secret, "encrypt")
        assert result == expected

    def test_derive_ntfy_key_vector_1(self):
        """Test vector: derive_ntfy_key_1."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        expected = bytes.fromhex(
            "e3d801b5755b78c380d59c1285c1a65290db0334cc2994dfd048ebff2df8781f"
        )
        result = derive_key(master_secret, "ntfy")
        assert result == expected

    def test_derive_auth_key_zeros(self):
        """Test vector: derive_auth_key_zeros."""
        master_secret = bytes.fromhex(
            "0000000000000000000000000000000000000000000000000000000000000000"
        )
        expected = bytes.fromhex(
            "31df6cff2f7200af61bee50e3b01fad553d8e430c2b0c376e498598956d7e809"
        )
        result = derive_key(master_secret, "auth")
        assert result == expected

    def test_derive_auth_key_ones(self):
        """Test vector: derive_auth_key_ones."""
        master_secret = bytes.fromhex(
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        )
        expected = bytes.fromhex(
            "c24fcd4ea4a6a0c8c02fb2417b5fc998cd8191e681808cc9fa5209c9e65b0790"
        )
        result = derive_key(master_secret, "auth")
        assert result == expected

    def test_different_purposes_give_different_keys(self):
        """Different purposes produce different derived keys."""
        master_secret = generate_master_secret()
        auth_key = derive_key(master_secret, "auth")
        encrypt_key = derive_key(master_secret, "encrypt")
        ntfy_key = derive_key(master_secret, "ntfy")

        assert auth_key != encrypt_key
        assert auth_key != ntfy_key
        assert encrypt_key != ntfy_key

    def test_same_input_same_output(self):
        """Same inputs produce same output (deterministic)."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        result1 = derive_key(master_secret, "auth")
        result2 = derive_key(master_secret, "auth")
        assert result1 == result2

    def test_returns_32_bytes(self):
        """Derived key is 32 bytes."""
        master_secret = generate_master_secret()
        result = derive_key(master_secret, "auth")
        assert len(result) == 32

    def test_rejects_short_master_secret(self):
        """Rejects master secret that is too short."""
        with pytest.raises(ValueError, match="32 bytes"):
            derive_key(b"\x00" * 16, "auth")

    def test_rejects_long_master_secret(self):
        """Rejects master secret that is too long."""
        with pytest.raises(ValueError, match="32 bytes"):
            derive_key(b"\x00" * 64, "auth")


class TestDeriveNtfyTopic:
    """Tests for ntfy topic derivation."""

    def test_derive_topic_vector_1(self):
        """Test vector: derive_topic_1."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        expected = "ras-4884fdaafea4"
        result = derive_ntfy_topic(master_secret)
        assert result == expected

    def test_topic_starts_with_ras(self):
        """Topic always starts with 'ras-'."""
        master_secret = generate_master_secret()
        topic = derive_ntfy_topic(master_secret)
        assert topic.startswith("ras-")

    def test_topic_has_correct_length(self):
        """Topic is 'ras-' + 12 hex chars = 16 chars total."""
        master_secret = generate_master_secret()
        topic = derive_ntfy_topic(master_secret)
        assert len(topic) == 16

    def test_topic_hex_part_is_valid(self):
        """Hex part after 'ras-' is valid hex."""
        master_secret = generate_master_secret()
        topic = derive_ntfy_topic(master_secret)
        hex_part = topic[4:]
        # Should not raise
        bytes.fromhex(hex_part)

    def test_same_input_same_topic(self):
        """Same master secret produces same topic (deterministic)."""
        master_secret = bytes.fromhex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        result1 = derive_ntfy_topic(master_secret)
        result2 = derive_ntfy_topic(master_secret)
        assert result1 == result2

    def test_rejects_short_master_secret(self):
        """Rejects master secret that is too short."""
        with pytest.raises(ValueError, match="32 bytes"):
            derive_ntfy_topic(b"\x00" * 16)
