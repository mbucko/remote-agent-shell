"""Tests for ntfy signaling retry behavior.

These tests verify:
- Subscriber reconnects on SSE disconnect
- Publish retries on transient failures
- Publish gives up after max retries
- SSE reconnection with proper delay
"""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import aiohttp
import pytest

from ras.ntfy_signaling.subscriber import NtfySignalingSubscriber


class TestPublishRetry:
    """Tests for publish retry behavior."""

    @pytest.fixture
    def mock_session(self):
        """Create a mock aiohttp session."""
        session = MagicMock(spec=aiohttp.ClientSession)
        return session

    @pytest.fixture
    def subscriber(self, mock_session):
        """Create a subscriber with mock session."""
        return NtfySignalingSubscriber(
            master_secret=b"x" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
            http_session=mock_session,
        )

    @pytest.mark.asyncio
    async def test_publish_succeeds_first_attempt(self, subscriber, mock_session):
        """Verify publish succeeds on first attempt when server returns 200."""
        # Set up mock response
        mock_response = MagicMock()
        mock_response.status = 200
        mock_response.__aenter__ = AsyncMock(return_value=mock_response)
        mock_response.__aexit__ = AsyncMock(return_value=None)

        mock_session.post = MagicMock(return_value=mock_response)

        result = await subscriber._publish("test-data")

        assert result is True
        mock_session.post.assert_called_once()

    @pytest.mark.asyncio
    async def test_publish_retries_on_transient_failure(self, subscriber, mock_session):
        """Verify publish retries on connection errors."""
        call_count = 0

        async def mock_post(*args, **kwargs):
            nonlocal call_count
            call_count += 1

            if call_count < 3:
                raise aiohttp.ClientError("Connection reset")

            # Success on 3rd attempt
            mock_response = MagicMock()
            mock_response.status = 200
            return mock_response

        # Create async context manager mock
        mock_cm = MagicMock()
        mock_cm.__aenter__ = AsyncMock(side_effect=mock_post)
        mock_cm.__aexit__ = AsyncMock(return_value=None)
        mock_session.post = MagicMock(return_value=mock_cm)

        # Actually we need to properly mock the async context manager
        call_count = 0

        async def side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                raise aiohttp.ClientError("Connection reset")
            mock_response = MagicMock()
            mock_response.status = 200
            return mock_response

        mock_cm.__aenter__ = AsyncMock(side_effect=side_effect)
        mock_session.post = MagicMock(return_value=mock_cm)

        # Override retry delays for faster test
        subscriber.PUBLISH_RETRY_DELAYS = [0.01, 0.01, 0.01]

        result = await subscriber._publish("test-data")

        assert result is True
        assert call_count == 3

    @pytest.mark.asyncio
    async def test_publish_gives_up_after_max_retries(self, subscriber, mock_session):
        """Verify publish gives up after MAX_PUBLISH_RETRIES attempts."""
        call_count = 0

        async def side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            raise aiohttp.ClientError("Connection refused")

        mock_cm = MagicMock()
        mock_cm.__aenter__ = AsyncMock(side_effect=side_effect)
        mock_cm.__aexit__ = AsyncMock(return_value=None)
        mock_session.post = MagicMock(return_value=mock_cm)

        # Override retry delays for faster test
        subscriber.PUBLISH_RETRY_DELAYS = [0.01, 0.01, 0.01]

        result = await subscriber._publish("test-data")

        assert result is False
        assert call_count == subscriber.MAX_PUBLISH_RETRIES

    @pytest.mark.asyncio
    async def test_publish_retries_on_non_200_status(self, subscriber, mock_session):
        """Verify publish retries when server returns non-200 status."""
        call_count = 0

        async def side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            mock_response = MagicMock()
            if call_count < 3:
                mock_response.status = 503  # Service unavailable
            else:
                mock_response.status = 200
            return mock_response

        mock_cm = MagicMock()
        mock_cm.__aenter__ = AsyncMock(side_effect=side_effect)
        mock_cm.__aexit__ = AsyncMock(return_value=None)
        mock_session.post = MagicMock(return_value=mock_cm)

        # Override retry delays for faster test
        subscriber.PUBLISH_RETRY_DELAYS = [0.01, 0.01, 0.01]

        result = await subscriber._publish("test-data")

        assert result is True
        assert call_count == 3


class TestSSEReconnection:
    """Tests for SSE subscription reconnection."""

    @pytest.mark.asyncio
    async def test_subscriber_reconnects_on_disconnect(self):
        """Verify subscriber reconnects automatically when SSE disconnects."""
        mock_session = MagicMock(spec=aiohttp.ClientSession)

        subscriber = NtfySignalingSubscriber(
            master_secret=b"x" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
            http_session=mock_session,
        )

        # Disable reconnect delay for fast test
        subscriber.SSE_RECONNECT_DELAY = 0

        connect_count = 0
        reconnect_happened = asyncio.Event()

        def mock_get(*args, **kwargs):
            nonlocal connect_count
            connect_count += 1

            if connect_count == 1:
                # First connection fails - return an async context manager that raises
                cm = MagicMock()
                cm.__aenter__ = AsyncMock(side_effect=aiohttp.ClientError("Connection reset"))
                cm.__aexit__ = AsyncMock(return_value=None)
                return cm

            if connect_count == 2:
                # Second connection attempt - we've proven reconnection works
                reconnect_happened.set()
                subscriber._stop_event.set()
                cm = MagicMock()
                cm.__aenter__ = AsyncMock(side_effect=asyncio.CancelledError())
                cm.__aexit__ = AsyncMock(return_value=None)
                return cm

            # Shouldn't reach here
            cm = MagicMock()
            cm.__aenter__ = AsyncMock(side_effect=asyncio.CancelledError())
            cm.__aexit__ = AsyncMock(return_value=None)
            return cm

        mock_session.get = mock_get

        # Start subscription
        await subscriber.start()

        # Wait for reconnection to happen
        await asyncio.wait_for(reconnect_happened.wait(), timeout=1.0)

        # Stop subscription
        await subscriber.stop()

        # Should have tried to connect at least twice
        assert connect_count >= 2

    @pytest.mark.asyncio
    async def test_subscriber_stops_cleanly_on_stop(self):
        """Verify subscriber stops without error when stop() called."""
        mock_session = MagicMock(spec=aiohttp.ClientSession)

        subscriber = NtfySignalingSubscriber(
            master_secret=b"x" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
            http_session=mock_session,
        )

        # Event that never gets set - simulates a hanging connection
        never_complete = asyncio.Event()
        connection_started = asyncio.Event()

        # Make get return a context manager that hangs
        def mock_get(*args, **kwargs):
            async def hang():
                connection_started.set()
                await never_complete.wait()

            cm = MagicMock()
            cm.__aenter__ = AsyncMock(side_effect=hang)
            cm.__aexit__ = AsyncMock(return_value=None)
            return cm

        mock_session.get = mock_get

        await subscriber.start()
        # Wait for connection attempt to start
        await asyncio.wait_for(connection_started.wait(), timeout=1.0)
        assert subscriber.is_subscribed()

        # Stop should complete quickly even though connection is "hanging"
        await asyncio.wait_for(subscriber.stop(), timeout=1.0)
        assert not subscriber.is_subscribed()

    @pytest.mark.asyncio
    async def test_subscriber_respects_reconnect_delay(self):
        """Verify subscriber waits before reconnecting (using mocked sleep)."""
        mock_session = MagicMock(spec=aiohttp.ClientSession)

        subscriber = NtfySignalingSubscriber(
            master_secret=b"x" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
            http_session=mock_session,
        )

        # Set a specific reconnect delay
        subscriber.SSE_RECONNECT_DELAY = 5.0  # Large value to prove we're not waiting

        sleep_delays = []
        test_complete = asyncio.Event()

        def mock_get(*args, **kwargs):
            cm = MagicMock()
            if len(sleep_delays) >= 1:
                # After first reconnect delay, stop the test
                subscriber._stop_event.set()
                test_complete.set()
                cm.__aenter__ = AsyncMock(side_effect=asyncio.CancelledError())
            else:
                cm.__aenter__ = AsyncMock(side_effect=aiohttp.ClientError("Connection failed"))
            cm.__aexit__ = AsyncMock(return_value=None)
            return cm

        mock_session.get = mock_get

        # Patch asyncio.sleep to capture delay values without actually sleeping
        original_sleep = asyncio.sleep

        async def mock_sleep(delay):
            if delay == subscriber.SSE_RECONNECT_DELAY:
                sleep_delays.append(delay)
            # Don't actually sleep - return immediately

        with patch("asyncio.sleep", mock_sleep):
            await subscriber.start()
            # Wait for test to complete
            await asyncio.wait_for(test_complete.wait(), timeout=1.0)
            await subscriber.stop()

        # Verify the reconnect delay was used
        assert len(sleep_delays) >= 1
        assert sleep_delays[0] == 5.0  # Should be our configured delay


class TestMessageProcessing:
    """Tests for message processing with retries."""

    @pytest.mark.asyncio
    async def test_process_message_publishes_answer(self):
        """Verify process_message publishes answer on success."""
        mock_session = MagicMock(spec=aiohttp.ClientSession)

        subscriber = NtfySignalingSubscriber(
            master_secret=b"x" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
            http_session=mock_session,
        )

        # Mock the handler to return a result
        from ras.ntfy_signaling.handler import HandlerResult

        mock_result = HandlerResult(
            should_respond=True,
            answer_encrypted="encrypted-answer",
            device_id="device-123",
            device_name="Test Device",
            peer=MagicMock(),
        )

        subscriber._handler.handle_message = AsyncMock(return_value=mock_result)

        # Mock publish to succeed
        publish_called = False

        async def mock_publish(data):
            nonlocal publish_called
            publish_called = True
            return True

        subscriber._publish = mock_publish

        # Process a message
        await subscriber._process_message("encrypted-offer")

        assert publish_called

    @pytest.mark.asyncio
    async def test_process_message_calls_callback_on_success(self):
        """Verify callback is called after successful publish."""
        mock_session = MagicMock(spec=aiohttp.ClientSession)

        subscriber = NtfySignalingSubscriber(
            master_secret=b"x" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
            http_session=mock_session,
        )

        # Mock the handler to return a result
        from ras.ntfy_signaling.handler import HandlerResult

        mock_peer = MagicMock()
        mock_result = HandlerResult(
            should_respond=True,
            answer_encrypted="encrypted-answer",
            device_id="device-123",
            device_name="Test Device",
            peer=mock_peer,
        )

        subscriber._handler.handle_message = AsyncMock(return_value=mock_result)
        subscriber._publish = AsyncMock(return_value=True)

        # Set up callback
        callback_args = []

        async def on_offer(device_id, device_name, peer, is_reconnection):
            callback_args.append((device_id, device_name, peer, is_reconnection))

        subscriber.on_offer_received = on_offer

        # Process a message
        await subscriber._process_message("encrypted-offer")

        assert len(callback_args) == 1
        assert callback_args[0][0] == "device-123"
        assert callback_args[0][1] == "Test Device"
        assert callback_args[0][2] == mock_peer

    @pytest.mark.asyncio
    async def test_process_message_no_callback_if_publish_fails(self):
        """Verify callback is NOT called if publish fails."""
        mock_session = MagicMock(spec=aiohttp.ClientSession)

        subscriber = NtfySignalingSubscriber(
            master_secret=b"x" * 32,
            session_id="test-session",
            ntfy_topic="test-topic",
            http_session=mock_session,
        )

        # Mock the handler to return a result
        from ras.ntfy_signaling.handler import HandlerResult

        mock_result = HandlerResult(
            should_respond=True,
            answer_encrypted="encrypted-answer",
            device_id="device-123",
            device_name="Test Device",
            peer=MagicMock(),
        )

        subscriber._handler.handle_message = AsyncMock(return_value=mock_result)
        subscriber._publish = AsyncMock(return_value=False)  # Publish fails

        # Set up callback
        callback_called = False

        async def on_offer(device_id, device_name, peer, is_reconnection):
            nonlocal callback_called
            callback_called = True

        subscriber.on_offer_received = on_offer

        # Process a message
        await subscriber._process_message("encrypted-offer")

        assert not callback_called


class TestHandlerRetry:
    """Tests for handler-level retry scenarios."""

    @pytest.mark.asyncio
    async def test_handler_silent_on_decryption_failure(self):
        """Verify handler returns None silently on decryption failure."""
        from ras.ntfy_signaling.handler import NtfySignalingHandler

        handler = NtfySignalingHandler(
            master_secret=b"x" * 32,
            pending_session_id="test-session",
        )

        # Send garbage that can't be decrypted
        result = await handler.handle_message("not-valid-base64-encrypted-data!!!")

        assert result is None  # Silent failure

    @pytest.mark.asyncio
    async def test_handler_silent_on_validation_failure(self):
        """Verify handler returns None silently on validation failure."""
        from ras.ntfy_signaling.handler import NtfySignalingHandler
        from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
        from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType
        import time
        import os

        master_secret = b"x" * 32
        handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id="test-session",
        )

        # Create a message with wrong session_id
        crypto = NtfySignalingCrypto(derive_signaling_key(master_secret))
        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.OFFER,
            session_id="wrong-session",  # Wrong session
            sdp="dummy-sdp",
            device_id="device-123",
            device_name="Test",
            timestamp=int(time.time()),
            nonce=os.urandom(16),
        )
        encrypted = crypto.encrypt(bytes(msg))

        result = await handler.handle_message(encrypted)

        assert result is None  # Silent failure

    @pytest.mark.asyncio
    async def test_handler_no_retry_on_auth_failure(self):
        """Verify non-retriable errors don't cause retries at handler level.

        This tests that decryption failures, validation failures, etc.
        are not retriable - they return None immediately.
        """
        from ras.ntfy_signaling.handler import NtfySignalingHandler

        handler = NtfySignalingHandler(
            master_secret=b"x" * 32,
            pending_session_id="test-session",
        )

        # Invalid encrypted data - this should not retry
        results = []
        for _ in range(3):
            result = await handler.handle_message("invalid-data")
            results.append(result)

        # All should be None (no retry logic in handler - each call is independent)
        assert all(r is None for r in results)
