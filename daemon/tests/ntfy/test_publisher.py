"""Tests for ntfy publisher module."""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import aiohttp
import pytest

from ras.ntfy.crypto import NtfyCrypto
from ras.ntfy.publisher import NtfyPublisher


class TestNtfyPublisherInit:
    """Tests for NtfyPublisher initialization."""

    def test_initializes_with_required_args(self):
        """Publisher initializes with required arguments."""
        crypto = MagicMock(spec=NtfyCrypto)
        publisher = NtfyPublisher(crypto=crypto, topic="test-topic")

        assert publisher.topic == "test-topic"
        assert publisher.server == "https://ntfy.sh"

    def test_accepts_custom_server(self):
        """Publisher accepts custom server URL."""
        crypto = MagicMock(spec=NtfyCrypto)
        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            server="https://custom.ntfy.server",
        )

        assert publisher.server == "https://custom.ntfy.server"

    def test_strips_trailing_slash_from_server(self):
        """Server URL trailing slash is stripped."""
        crypto = MagicMock(spec=NtfyCrypto)
        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            server="https://ntfy.sh/",
        )

        assert publisher.server == "https://ntfy.sh"

    def test_accepts_http_session(self):
        """Publisher accepts external HTTP session."""
        crypto = MagicMock(spec=NtfyCrypto)
        session = MagicMock(spec=aiohttp.ClientSession)
        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        assert publisher._session is session
        assert publisher._owns_session is False


class TestNtfyPublisherContextManager:
    """Tests for async context manager."""

    @pytest.mark.asyncio
    async def test_creates_session_on_enter(self):
        """Context manager creates session if not provided."""
        crypto = MagicMock(spec=NtfyCrypto)
        publisher = NtfyPublisher(crypto=crypto, topic="test-topic")

        async with publisher as p:
            assert p._session is not None
            assert isinstance(p._session, aiohttp.ClientSession)

    @pytest.mark.asyncio
    async def test_closes_owned_session_on_exit(self):
        """Context manager closes owned session on exit."""
        crypto = MagicMock(spec=NtfyCrypto)
        publisher = NtfyPublisher(crypto=crypto, topic="test-topic")

        async with publisher:
            session = publisher._session

        # Session should be closed and cleared
        assert publisher._session is None

    @pytest.mark.asyncio
    async def test_does_not_close_external_session(self):
        """Context manager doesn't close external session."""
        crypto = MagicMock(spec=NtfyCrypto)
        session = AsyncMock(spec=aiohttp.ClientSession)
        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        async with publisher:
            pass

        session.close.assert_not_called()


class TestNtfyPublisherPublish:
    """Tests for publish_ip_change."""

    @pytest.mark.asyncio
    async def test_publishes_encrypted_message(self):
        """Successfully publishes encrypted IP change."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        async with publisher:
            result = await publisher.publish_ip_change("1.2.3.4", 8821)

        assert result is True
        crypto.encrypt_ip_notification.assert_called_once()
        session.post.assert_called_once()

    @pytest.mark.asyncio
    async def test_uses_correct_url(self):
        """Uses correct ntfy URL."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="my-topic",
            server="https://ntfy.example.com",
            http_session=session,
        )

        async with publisher:
            await publisher.publish_ip_change("1.2.3.4", 8821)

        call_args = session.post.call_args
        assert call_args[0][0] == "https://ntfy.example.com/my-topic"

    @pytest.mark.asyncio
    async def test_sends_correct_headers(self):
        """Sends correct Content-Type header."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        async with publisher:
            await publisher.publish_ip_change("1.2.3.4", 8821)

        call_args = session.post.call_args
        assert call_args[1]["headers"] == {"Content-Type": "text/plain"}

    @pytest.mark.asyncio
    async def test_sends_encrypted_data(self):
        """Sends encrypted data as body."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "my_encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        async with publisher:
            await publisher.publish_ip_change("1.2.3.4", 8821)

        call_args = session.post.call_args
        assert call_args[1]["data"] == "my_encrypted_data"


class TestNtfyPublisherRetry:
    """Tests for retry behavior."""

    @pytest.mark.asyncio
    async def test_retries_on_failure(self):
        """Retries on server failure."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        # First call fails, second succeeds
        mock_response_fail = AsyncMock()
        mock_response_fail.status = 500
        mock_response_fail.text = AsyncMock(return_value="Server error")
        mock_response_fail.__aenter__.return_value = mock_response_fail
        mock_response_fail.__aexit__.return_value = None

        mock_response_success = AsyncMock()
        mock_response_success.status = 200
        mock_response_success.__aenter__.return_value = mock_response_success
        mock_response_success.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.side_effect = [mock_response_fail, mock_response_success]

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )
        # Override retry delays for faster test
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        async with publisher:
            result = await publisher.publish_ip_change("1.2.3.4", 8821)

        assert result is True
        assert session.post.call_count == 2

    @pytest.mark.asyncio
    async def test_retries_on_exception(self):
        """Retries on network exception."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.side_effect = [
            aiohttp.ClientError("Connection failed"),
            mock_response,
        ]

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        async with publisher:
            result = await publisher.publish_ip_change("1.2.3.4", 8821)

        assert result is True
        assert session.post.call_count == 2

    @pytest.mark.asyncio
    async def test_fails_after_max_retries(self):
        """Returns False after exhausting retries."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 500
        mock_response.text = AsyncMock(return_value="Server error")
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        async with publisher:
            result = await publisher.publish_ip_change("1.2.3.4", 8821)

        assert result is False
        assert session.post.call_count == 3  # MAX_RETRIES

    @pytest.mark.asyncio
    async def test_exponential_backoff_delays(self):
        """Uses exponential backoff between retries."""
        # Verify default delays are exponential
        assert NtfyPublisher.RETRY_DELAYS == [1.0, 2.0, 4.0]


class TestNtfyPublisherErrors:
    """Tests for error handling."""

    @pytest.mark.asyncio
    async def test_returns_false_without_context_manager(self):
        """Returns False if used without context manager (error caught in retry)."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        publisher = NtfyPublisher(crypto=crypto, topic="test-topic")
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        result = await publisher.publish_ip_change("1.2.3.4", 8821)

        assert result is False

    @pytest.mark.asyncio
    async def test_handles_http_4xx_errors(self):
        """Handles HTTP 4xx errors without crash."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 400
        mock_response.text = AsyncMock(return_value="Bad request")
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        async with publisher:
            result = await publisher.publish_ip_change("1.2.3.4", 8821)

        assert result is False


class TestNtfyPublisherClose:
    """Tests for close method."""

    @pytest.mark.asyncio
    async def test_close_owned_session(self):
        """Close closes owned session."""
        crypto = MagicMock(spec=NtfyCrypto)
        publisher = NtfyPublisher(crypto=crypto, topic="test-topic")

        # Manually enter to create session
        await publisher.__aenter__()
        session = publisher._session

        await publisher.close()

        assert publisher._session is None

    @pytest.mark.asyncio
    async def test_close_does_not_close_external_session(self):
        """Close doesn't close external session."""
        crypto = MagicMock(spec=NtfyCrypto)
        session = AsyncMock(spec=aiohttp.ClientSession)

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        await publisher.close()

        session.close.assert_not_called()


class TestNtfyPublisherConstants:
    """Tests for publisher constants."""

    def test_max_retries(self):
        """Max retries is 3."""
        assert NtfyPublisher.MAX_RETRIES == 3

    def test_request_timeout(self):
        """Request timeout is 10 seconds."""
        assert NtfyPublisher.REQUEST_TIMEOUT == 10.0


class TestNtfyPublisherEncryption:
    """Tests for encryption integration."""

    @pytest.mark.asyncio
    async def test_passes_ip_and_port_to_crypto(self):
        """Passes correct IP and port to crypto."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        async with publisher:
            await publisher.publish_ip_change("192.168.1.100", 9999)

        call_args = crypto.encrypt_ip_notification.call_args
        assert call_args[1]["ip"] == "192.168.1.100"
        assert call_args[1]["port"] == 9999

    @pytest.mark.asyncio
    async def test_generates_random_nonce(self):
        """Generates random nonce for each publish."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        async with publisher:
            await publisher.publish_ip_change("1.2.3.4", 8821)
            await publisher.publish_ip_change("1.2.3.4", 8821)

        # Get nonces from both calls
        call1_nonce = crypto.encrypt_ip_notification.call_args_list[0][1]["nonce"]
        call2_nonce = crypto.encrypt_ip_notification.call_args_list[1][1]["nonce"]

        # Nonces should be 16 bytes each
        assert len(call1_nonce) == 16
        assert len(call2_nonce) == 16

        # Nonces should be different (with overwhelming probability)
        assert call1_nonce != call2_nonce

    @pytest.mark.asyncio
    async def test_includes_timestamp(self):
        """Includes timestamp in encryption."""
        crypto = MagicMock(spec=NtfyCrypto)
        crypto.encrypt_ip_notification.return_value = "encrypted_data"

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            http_session=session,
        )

        async with publisher:
            await publisher.publish_ip_change("1.2.3.4", 8821)

        call_args = crypto.encrypt_ip_notification.call_args
        timestamp = call_args[1]["timestamp"]

        # Should be a reasonable timestamp (after 2020)
        assert timestamp > 1577836800  # Jan 1, 2020


class TestNtfyPublisherProperties:
    """Tests for property accessors."""

    def test_topic_property(self):
        """Topic property returns topic."""
        crypto = MagicMock(spec=NtfyCrypto)
        publisher = NtfyPublisher(
            crypto=crypto,
            topic="my-special-topic",
        )

        assert publisher.topic == "my-special-topic"

    def test_server_property(self):
        """Server property returns server."""
        crypto = MagicMock(spec=NtfyCrypto)
        publisher = NtfyPublisher(
            crypto=crypto,
            topic="test-topic",
            server="https://my.server.com",
        )

        assert publisher.server == "https://my.server.com"
