"""Tests for ntfy signaling subscriber.

Tests cover:
- Subscription lifecycle (start, stop)
- Message processing
- Response publishing
- Error handling
- Cleanup
"""

import asyncio
import os
import time
from typing import Optional
from unittest.mock import AsyncMock, MagicMock, Mock, patch

import pytest

from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
from ras.ntfy_signaling.handler import NtfySignalingHandler
from ras.ntfy_signaling.subscriber import NtfySignalingSubscriber
from ras.ntfy_signaling.validation import NONCE_SIZE
from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType


def create_test_offer(
    session_id: str = "test-session-123",
    sdp: str = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n",
    device_id: str = "device-123",
    device_name: str = "Test Phone",
    timestamp: Optional[int] = None,
    nonce: Optional[bytes] = None,
) -> NtfySignalMessage:
    """Create a test OFFER message."""
    if timestamp is None:
        timestamp = int(time.time())
    if nonce is None:
        nonce = os.urandom(NONCE_SIZE)

    return NtfySignalMessage(
        type=NtfySignalMessageMessageType.OFFER,
        session_id=session_id,
        sdp=sdp,
        device_id=device_id,
        device_name=device_name,
        timestamp=timestamp,
        nonce=nonce,
    )


def create_encrypted_offer(crypto: NtfySignalingCrypto, **kwargs) -> str:
    """Create an encrypted OFFER message."""
    msg = create_test_offer(**kwargs)
    return crypto.encrypt(bytes(msg))


class TestNtfySignalingSubscriberInit:
    """Tests for subscriber initialization."""

    def test_init_with_required_params(self):
        """Subscriber can be initialized with required params."""
        master_secret = os.urandom(32)
        subscriber = NtfySignalingSubscriber(
            master_secret=master_secret,
            session_id="test-session",
            ntfy_topic="test-topic",
        )
        assert subscriber is not None
        assert subscriber._topic == "test-topic"

    def test_init_creates_handler(self):
        """Subscriber creates handler internally."""
        master_secret = os.urandom(32)
        subscriber = NtfySignalingSubscriber(
            master_secret=master_secret,
            session_id="test-session",
            ntfy_topic="test-topic",
        )
        assert subscriber._handler is not None
        assert isinstance(subscriber._handler, NtfySignalingHandler)

    def test_init_with_custom_server(self):
        """Subscriber can use custom ntfy server."""
        master_secret = os.urandom(32)
        subscriber = NtfySignalingSubscriber(
            master_secret=master_secret,
            session_id="test-session",
            ntfy_topic="test-topic",
            ntfy_server="https://custom.ntfy.server",
        )
        assert subscriber._server == "https://custom.ntfy.server"


class TestNtfySignalingSubscriberSubscription:
    """Tests for subscription lifecycle."""

    @pytest.fixture
    def master_secret(self):
        return os.urandom(32)

    @pytest.fixture
    def subscriber(self, master_secret):
        return NtfySignalingSubscriber(
            master_secret=master_secret,
            session_id="test-session-123",
            ntfy_topic="test-topic",
        )

    def test_is_subscribed_false_initially(self, subscriber):
        """is_subscribed is False before start."""
        assert not subscriber.is_subscribed()

    @pytest.mark.asyncio
    async def test_start_sets_subscribed(self, subscriber):
        """start() sets is_subscribed to True."""
        # Mock the SSE connection
        with patch.object(subscriber, "_run_subscription", new_callable=AsyncMock):
            await subscriber.start()
            assert subscriber.is_subscribed()

    @pytest.mark.asyncio
    async def test_stop_clears_subscribed(self, subscriber):
        """stop() sets is_subscribed to False."""
        with patch.object(subscriber, "_run_subscription", new_callable=AsyncMock):
            await subscriber.start()
            assert subscriber.is_subscribed()

            await subscriber.stop()
            assert not subscriber.is_subscribed()

    @pytest.mark.asyncio
    async def test_stop_is_idempotent(self, subscriber):
        """stop() can be called multiple times safely."""
        await subscriber.stop()  # Never started
        await subscriber.stop()  # Should not raise


class TestNtfySignalingSubscriberMessageProcessing:
    """Tests for message processing."""

    @pytest.fixture
    def master_secret(self):
        return os.urandom(32)

    @pytest.fixture
    def crypto(self, master_secret):
        return NtfySignalingCrypto(derive_signaling_key(master_secret))

    @pytest.fixture
    def subscriber(self, master_secret):
        sub = NtfySignalingSubscriber(
            master_secret=master_secret,
            session_id="test-session-123",
            ntfy_topic="test-topic",
        )
        # Mock peer creation in handler
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\n")
        sub._handler._create_peer = Mock(return_value=mock_peer)
        return sub

    @pytest.mark.asyncio
    async def test_process_valid_offer_publishes_answer(self, subscriber, crypto):
        """Valid OFFER triggers answer publish."""
        encrypted = create_encrypted_offer(crypto)

        # Mock publish
        subscriber._publish = AsyncMock(return_value=True)

        await subscriber._process_message(encrypted)

        # Should have published answer
        subscriber._publish.assert_called_once()
        answer_encrypted = subscriber._publish.call_args[0][0]
        assert answer_encrypted is not None

    @pytest.mark.asyncio
    async def test_process_valid_offer_triggers_callback(self, subscriber, crypto):
        """Valid OFFER triggers on_offer_received callback."""
        encrypted = create_encrypted_offer(crypto)
        subscriber._publish = AsyncMock(return_value=True)

        callback = AsyncMock()
        subscriber.on_offer_received = callback

        await subscriber._process_message(encrypted)

        callback.assert_called_once()
        args = callback.call_args[0]
        assert args[0] == "device-123"  # device_id
        assert args[1] == "Test Phone"  # device_name

    @pytest.mark.asyncio
    async def test_process_invalid_message_silent(self, subscriber):
        """Invalid message is silently ignored."""
        # Mock publish to ensure it's NOT called
        subscriber._publish = AsyncMock()

        await subscriber._process_message("invalid-message")

        subscriber._publish.assert_not_called()

    @pytest.mark.asyncio
    async def test_process_wrong_session_silent(self, subscriber, crypto):
        """Message with wrong session is silently ignored."""
        encrypted = create_encrypted_offer(crypto, session_id="wrong-session")
        subscriber._publish = AsyncMock()

        await subscriber._process_message(encrypted)

        subscriber._publish.assert_not_called()


class TestNtfySignalingSubscriberPublish:
    """Tests for response publishing."""

    @pytest.fixture
    def master_secret(self):
        return os.urandom(32)

    @pytest.fixture
    def subscriber(self, master_secret):
        return NtfySignalingSubscriber(
            master_secret=master_secret,
            session_id="test-session-123",
            ntfy_topic="test-topic",
        )

    @pytest.mark.asyncio
    async def test_publish_success(self, subscriber):
        """Successful publish returns True."""
        # Mock aiohttp response
        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__ = AsyncMock(return_value=mock_response)
        mock_response.__aexit__ = AsyncMock(return_value=None)

        mock_session = AsyncMock()
        mock_session.post = Mock(return_value=mock_response)
        subscriber._session = mock_session

        result = await subscriber._publish("encrypted-answer")
        assert result is True

    @pytest.mark.asyncio
    async def test_publish_failure_returns_false(self, subscriber):
        """Failed publish returns False."""
        mock_response = AsyncMock()
        mock_response.status = 500
        mock_response.__aenter__ = AsyncMock(return_value=mock_response)
        mock_response.__aexit__ = AsyncMock(return_value=None)

        mock_session = AsyncMock()
        mock_session.post = Mock(return_value=mock_response)
        subscriber._session = mock_session

        result = await subscriber._publish("encrypted-answer")
        assert result is False

    @pytest.mark.asyncio
    async def test_publish_retries_on_failure(self, subscriber):
        """Publish retries on transient failure."""
        # First call fails, second succeeds
        fail_response = AsyncMock()
        fail_response.status = 500
        fail_response.__aenter__ = AsyncMock(return_value=fail_response)
        fail_response.__aexit__ = AsyncMock(return_value=None)

        success_response = AsyncMock()
        success_response.status = 200
        success_response.__aenter__ = AsyncMock(return_value=success_response)
        success_response.__aexit__ = AsyncMock(return_value=None)

        mock_session = AsyncMock()
        mock_session.post = Mock(side_effect=[fail_response, success_response])
        subscriber._session = mock_session

        result = await subscriber._publish("encrypted-answer")
        assert result is True
        assert mock_session.post.call_count == 2


class TestNtfySignalingSubscriberCleanup:
    """Tests for cleanup."""

    @pytest.fixture
    def master_secret(self):
        return os.urandom(32)

    @pytest.fixture
    def subscriber(self, master_secret):
        return NtfySignalingSubscriber(
            master_secret=master_secret,
            session_id="test-session-123",
            ntfy_topic="test-topic",
        )

    @pytest.mark.asyncio
    async def test_close_zeros_key(self, subscriber):
        """close() zeros the signaling key."""
        original_key = subscriber._handler._crypto._key

        await subscriber.close()

        assert subscriber._handler._crypto._key != original_key

    @pytest.mark.asyncio
    async def test_close_clears_nonce_cache(self, subscriber):
        """close() clears the nonce cache."""
        # Add a nonce
        nonce = os.urandom(NONCE_SIZE)
        subscriber._handler._validator._nonce_cache.add(nonce)

        await subscriber.close()

        assert not subscriber._handler._validator._nonce_cache.has_seen(nonce)

    @pytest.mark.asyncio
    async def test_close_stops_subscription(self, subscriber):
        """close() stops subscription."""
        with patch.object(subscriber, "_run_subscription", new_callable=AsyncMock):
            await subscriber.start()
            assert subscriber.is_subscribed()

            await subscriber.close()
            assert not subscriber.is_subscribed()


class TestNtfySignalingSubscriberPeer:
    """Tests for peer connection access."""

    @pytest.fixture
    def master_secret(self):
        return os.urandom(32)

    @pytest.fixture
    def crypto(self, master_secret):
        return NtfySignalingCrypto(derive_signaling_key(master_secret))

    @pytest.fixture
    def subscriber(self, master_secret):
        sub = NtfySignalingSubscriber(
            master_secret=master_secret,
            session_id="test-session-123",
            ntfy_topic="test-topic",
        )
        return sub

    @pytest.mark.asyncio
    async def test_get_peer_none_initially(self, subscriber):
        """get_peer returns None before offer processed."""
        assert subscriber.get_peer() is None

    @pytest.mark.asyncio
    async def test_get_peer_returns_peer_after_offer(self, subscriber, crypto):
        """get_peer returns peer after successful offer."""
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\n")
        subscriber._handler._create_peer = Mock(return_value=mock_peer)
        subscriber._publish = AsyncMock(return_value=True)

        encrypted = create_encrypted_offer(crypto)
        await subscriber._process_message(encrypted)

        assert subscriber.get_peer() is mock_peer
