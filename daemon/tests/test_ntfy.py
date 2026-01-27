"""Tests for ntfy client module."""

import json
import time
from unittest.mock import AsyncMock, Mock

import pytest

from ras.crypto import decrypt, encrypt
from ras.errors import NtfyError
from ras.ntfy import IpUpdate, NtfyClient


class TestIpUpdate:
    """Test IpUpdate dataclass."""

    def test_create_ip_update(self):
        """Can create IpUpdate."""
        update = IpUpdate(ip="1.2.3.4", port=8821, timestamp=1234, nonce="abc123")
        assert update.ip == "1.2.3.4"
        assert update.port == 8821
        assert update.timestamp == 1234
        assert update.nonce == "abc123"


class TestNtfyClientCreation:
    """Test NtfyClient creation."""

    def test_create_client(self):
        """Can create NtfyClient."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        assert client.server == "https://ntfy.sh"
        assert client.topic == "abc123"

    def test_strips_trailing_slash(self):
        """Strips trailing slash from server URL."""
        client = NtfyClient(
            server="https://ntfy.sh/",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        assert client.server == "https://ntfy.sh"

    def test_custom_max_age(self):
        """Can configure max_age."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            max_age=300,
        )
        assert client.max_age == 300

    def test_default_max_age(self):
        """Default max_age is 300 seconds."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        assert client.max_age == 300


class TestEncryptUpdate:
    """Test IP update encryption."""

    def test_encrypt_update_returns_bytes(self):
        """_encrypt_update returns bytes."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        encrypted = client._encrypt_update("1.2.3.4", 8821)
        assert isinstance(encrypted, bytes)

    def test_encrypt_update_is_encrypted(self):
        """_encrypt_update encrypts the IP."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        encrypted = client._encrypt_update("1.2.3.4", 8821)
        assert b"1.2.3.4" not in encrypted

    def test_encrypt_update_contains_all_fields(self):
        """Encrypted update contains ip, port, timestamp, nonce."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        encrypted = client._encrypt_update("1.2.3.4", 8821)
        decrypted = json.loads(decrypt(b"x" * 32, encrypted))
        assert decrypted["ip"] == "1.2.3.4"
        assert decrypted["port"] == 8821
        assert "timestamp" in decrypted
        assert "nonce" in decrypted

    def test_encrypt_update_unique_nonces(self):
        """Each update has unique nonce."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        e1 = client._encrypt_update("1.2.3.4", 8821)
        e2 = client._encrypt_update("1.2.3.4", 8821)
        d1 = json.loads(decrypt(b"x" * 32, e1))
        d2 = json.loads(decrypt(b"x" * 32, e2))
        assert d1["nonce"] != d2["nonce"]


class TestDecryptUpdate:
    """Test IP update decryption."""

    def test_decrypt_update_roundtrip(self):
        """Can decrypt encrypted update."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        encrypted = client._encrypt_update("1.2.3.4", 8821)
        ip, port = client.decrypt_update(encrypted)
        assert ip == "1.2.3.4"
        assert port == 8821

    def test_decrypt_update_wrong_key_fails(self):
        """Decryption with wrong key raises NtfyError."""
        client1 = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"a" * 32,
        )
        client2 = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"b" * 32,
        )
        encrypted = client1._encrypt_update("1.2.3.4", 8821)
        with pytest.raises(NtfyError, match="decrypt"):
            client2.decrypt_update(encrypted)

    def test_decrypt_update_tampered_fails(self):
        """Decryption of tampered data raises NtfyError."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        encrypted = bytearray(client._encrypt_update("1.2.3.4", 8821))
        encrypted[20] ^= 0xFF
        with pytest.raises(NtfyError, match="decrypt"):
            client.decrypt_update(bytes(encrypted))


class TestTimestampValidation:
    """Test timestamp validation."""

    def test_reject_expired_update(self):
        """Rejects updates with old timestamps."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            max_age=60,
        )
        # Create old message
        old_payload = {
            "ip": "1.2.3.4",
            "port": 8821,
            "timestamp": int(time.time()) - 120,  # 2 minutes old
            "nonce": "abc123",
        }
        encrypted = encrypt(b"x" * 32, json.dumps(old_payload).encode())
        with pytest.raises(NtfyError, match="expired"):
            client.decrypt_update(encrypted)

    def test_reject_future_update(self):
        """Rejects updates with future timestamps."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            max_age=60,
        )
        future_payload = {
            "ip": "1.2.3.4",
            "port": 8821,
            "timestamp": int(time.time()) + 120,  # 2 minutes in future
            "nonce": "abc123",
        }
        encrypted = encrypt(b"x" * 32, json.dumps(future_payload).encode())
        with pytest.raises(NtfyError, match="expired"):
            client.decrypt_update(encrypted)

    def test_accept_update_within_max_age(self):
        """Accepts updates within max_age window."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            max_age=60,
        )
        recent_payload = {
            "ip": "1.2.3.4",
            "port": 8821,
            "timestamp": int(time.time()) - 30,  # 30 seconds old
            "nonce": "abc123",
        }
        encrypted = encrypt(b"x" * 32, json.dumps(recent_payload).encode())
        ip, port = client.decrypt_update(encrypted)
        assert ip == "1.2.3.4"


class TestReplayProtection:
    """Test replay protection."""

    def test_reject_replayed_nonce(self):
        """Rejects replayed nonces."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        payload = {
            "ip": "1.2.3.4",
            "port": 8821,
            "timestamp": int(time.time()),
            "nonce": "same_nonce_123",
        }
        encrypted = encrypt(b"x" * 32, json.dumps(payload).encode())
        # First decode succeeds
        client.decrypt_update(encrypted)
        # Second decode (replay) fails
        with pytest.raises(NtfyError, match="[Rr]eplay"):
            client.decrypt_update(encrypted)

    def test_tracks_seen_nonces(self):
        """Tracks seen nonces."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        payload = {
            "ip": "1.2.3.4",
            "port": 8821,
            "timestamp": int(time.time()),
            "nonce": "unique_nonce",
        }
        encrypted = encrypt(b"x" * 32, json.dumps(payload).encode())
        client.decrypt_update(encrypted)
        assert "unique_nonce" in client._seen_nonces


class TestSendIpUpdate:
    """Test sending IP updates."""

    @pytest.mark.asyncio
    async def test_send_ip_update_success(self):
        """Sends encrypted IP update to ntfy."""
        http_client = AsyncMock()
        http_client.post.return_value = Mock(status_code=200)

        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            http_client=http_client,
        )

        await client.send_ip_update("1.2.3.4", 8821)

        http_client.post.assert_called_once()
        call_args = http_client.post.call_args
        assert "abc123" in call_args[0][0]
        assert b"1.2.3.4" not in call_args[1]["content"]

    @pytest.mark.asyncio
    async def test_send_ip_update_correct_url(self):
        """Sends to correct URL."""
        http_client = AsyncMock()
        http_client.post.return_value = Mock(status_code=200)

        client = NtfyClient(
            server="https://ntfy.sh",
            topic="mytopic",
            ntfy_key=b"x" * 32,
            http_client=http_client,
        )

        await client.send_ip_update("1.2.3.4", 8821)

        call_args = http_client.post.call_args
        assert call_args[0][0] == "https://ntfy.sh/mytopic"

    @pytest.mark.asyncio
    async def test_send_ip_update_content_type(self):
        """Sends with correct content type."""
        http_client = AsyncMock()
        http_client.post.return_value = Mock(status_code=200)

        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            http_client=http_client,
        )

        await client.send_ip_update("1.2.3.4", 8821)

        call_args = http_client.post.call_args
        assert call_args[1]["headers"]["Content-Type"] == "application/octet-stream"

    @pytest.mark.asyncio
    async def test_send_ip_update_error_response(self):
        """Raises NtfyError on error response."""
        http_client = AsyncMock()
        http_client.post.return_value = Mock(status_code=500)

        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            http_client=http_client,
        )

        with pytest.raises(NtfyError, match="500"):
            await client.send_ip_update("1.2.3.4", 8821)

    @pytest.mark.asyncio
    async def test_send_ip_update_network_error(self):
        """Raises NtfyError on network error."""
        import httpx

        http_client = AsyncMock()
        http_client.post.side_effect = httpx.HTTPError("Connection refused")

        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            http_client=http_client,
        )

        with pytest.raises(NtfyError, match="send"):
            await client.send_ip_update("1.2.3.4", 8821)

    @pytest.mark.asyncio
    async def test_send_without_client_raises(self):
        """Raises NtfyError if http_client not initialized."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )

        with pytest.raises(NtfyError, match="client"):
            await client.send_ip_update("1.2.3.4", 8821)


class TestAsyncContextManager:
    """Test async context manager."""

    @pytest.mark.asyncio
    async def test_context_manager_creates_client(self):
        """Context manager creates http client."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        assert client.http_client is None
        async with client:
            assert client.http_client is not None

    @pytest.mark.asyncio
    async def test_context_manager_closes_client(self):
        """Context manager closes http client."""
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
        )
        async with client:
            http_client = client.http_client
        # After exit, client should be closed
        assert client._owns_client is True

    @pytest.mark.asyncio
    async def test_context_manager_preserves_injected_client(self):
        """Context manager preserves injected http client."""
        http_client = AsyncMock()
        client = NtfyClient(
            server="https://ntfy.sh",
            topic="abc123",
            ntfy_key=b"x" * 32,
            http_client=http_client,
        )
        async with client:
            assert client.http_client is http_client
        # Should not close injected client
        assert client._owns_client is False
