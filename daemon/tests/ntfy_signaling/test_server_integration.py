"""Integration tests for ntfy signaling with UnifiedServer.

Tests cover:
- Server starts ntfy subscriber when pairing begins
- OFFER via ntfy triggers answer
- Pairing completes successfully via ntfy relay
- Cleanup on cancel/timeout/completion
"""

import asyncio
import os
import time
from pathlib import Path
from typing import Optional
from unittest.mock import AsyncMock, Mock, patch

import aiohttp
import pytest

from ras.config import Config
from ras.crypto import derive_key, derive_ntfy_topic
from ras.device_store import JsonDeviceStore
from ras.ntfy_signaling import NtfySignalingSubscriber
from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
from ras.ntfy_signaling.validation import NONCE_SIZE
from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType
from ras.server import UnifiedServer


def create_encrypted_offer(
    master_secret: bytes,
    session_id: str,
    sdp: str = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n",
    device_id: str = "device-123",
    device_name: str = "Test Phone",
    timestamp: Optional[int] = None,
    nonce: Optional[bytes] = None,
) -> str:
    """Create an encrypted OFFER message."""
    if timestamp is None:
        timestamp = int(time.time())
    if nonce is None:
        nonce = os.urandom(NONCE_SIZE)

    msg = NtfySignalMessage(
        type=NtfySignalMessageMessageType.OFFER,
        session_id=session_id,
        sdp=sdp,
        device_id=device_id,
        device_name=device_name,
        timestamp=timestamp,
        nonce=nonce,
    )

    signaling_key = derive_signaling_key(master_secret)
    crypto = NtfySignalingCrypto(signaling_key)
    return crypto.encrypt(bytes(msg))


@pytest.fixture
def temp_devices_file(tmp_path):
    """Create temporary devices file."""
    return tmp_path / "devices.json"


@pytest.fixture
async def device_store(temp_devices_file):
    """Create device store."""
    store = JsonDeviceStore(temp_devices_file)
    await store.load()
    return store


@pytest.fixture
async def server(device_store):
    """Create and start UnifiedServer."""
    server = UnifiedServer(
        device_store=device_store,
        stun_servers=[],
        pairing_timeout=60.0,
        ntfy_server="https://ntfy.sh",
    )
    await server.start(host="127.0.0.1", port=0)
    yield server
    await server.close()


class TestNtfySignalingServerIntegration:
    """Integration tests for ntfy signaling with server."""

    @pytest.mark.asyncio
    async def test_pairing_starts_ntfy_subscriber(self, server):
        """Pairing session starts ntfy subscriber."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                assert resp.status == 200
                data = await resp.json()

        session_id = data["session_id"]
        pairing_session = server._pairing_sessions.get(session_id)

        # Verify ntfy subscriber is active
        assert pairing_session is not None
        assert pairing_session._ntfy_subscriber is not None
        assert pairing_session._ntfy_subscriber.is_subscribed()

    @pytest.mark.asyncio
    async def test_pairing_cancel_stops_ntfy_subscriber(self, server):
        """Cancelling pairing stops ntfy subscriber."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        pairing_session = server._pairing_sessions.get(session_id)
        subscriber = pairing_session._ntfy_subscriber

        # Cancel pairing
        async with aiohttp.ClientSession() as session:
            async with session.delete(
                f"http://127.0.0.1:{server.get_port()}/api/pair/{session_id}"
            ) as resp:
                assert resp.status == 200

        # Subscriber should be stopped
        assert not subscriber.is_subscribed()

    @pytest.mark.asyncio
    async def test_qr_data_includes_ntfy_topic(self, server):
        """QR data includes ntfy topic for phone."""
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        qr_data = data["qr_data"]
        assert "ntfy_topic" in qr_data
        assert qr_data["ntfy_topic"]  # Not empty

        # Verify topic matches derived value
        master_secret = bytes.fromhex(qr_data["master_secret"])
        expected_topic = derive_ntfy_topic(master_secret)
        assert qr_data["ntfy_topic"] == expected_topic


class TestNtfyOfferProcessing:
    """Tests for processing OFFER messages via ntfy."""

    @pytest.mark.asyncio
    async def test_valid_offer_triggers_signaling_state(self, server):
        """Valid OFFER via ntfy changes session to signaling state."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create encrypted offer
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
            device_id="test-phone-123",
            device_name="Test Phone",
        )

        # Mock peer creation to avoid actual WebRTC
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\n")
        mock_peer.on_message = Mock()
        mock_peer.close = AsyncMock()
        pairing_session._ntfy_subscriber._handler._create_peer = Mock(return_value=mock_peer)

        # Mock publish to capture answer
        published = []
        pairing_session._ntfy_subscriber._publish = AsyncMock(
            side_effect=lambda data: published.append(data) or True
        )

        # Process offer through subscriber
        await pairing_session._ntfy_subscriber._process_message(encrypted)

        # Wait for processing
        await asyncio.sleep(0.1)

        # Verify answer was published
        assert len(published) == 1

        # Verify session state changed (may be signaling or authenticating
        # depending on timing - auth flow starts immediately after signaling)
        assert pairing_session.state in ("signaling", "authenticating")
        assert pairing_session.device_id == "test-phone-123"
        assert pairing_session.device_name == "Test Phone"

    @pytest.mark.asyncio
    async def test_invalid_offer_silent_reject(self, server):
        """Invalid OFFER is silently rejected."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with wrong session ID
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id="wrong-session-id",
        )

        # Mock publish
        pairing_session._ntfy_subscriber._publish = AsyncMock()

        # Process invalid offer
        await pairing_session._ntfy_subscriber._process_message(encrypted)

        # Should not publish answer
        pairing_session._ntfy_subscriber._publish.assert_not_called()

        # Session state should remain pending
        assert pairing_session.state == "pending"


class TestNtfyAnswerCreation:
    """Tests for ANSWER message creation."""

    @pytest.mark.asyncio
    async def test_answer_is_encrypted_with_same_key(self, server):
        """Answer is encrypted with signaling key from master secret."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer
        encrypted_offer = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
        )

        # Mock peer
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\na=answer\r\n")
        mock_peer.on_message = Mock()
        mock_peer.close = AsyncMock()
        pairing_session._ntfy_subscriber._handler._create_peer = Mock(return_value=mock_peer)

        # Capture published answer
        published = []
        pairing_session._ntfy_subscriber._publish = AsyncMock(
            side_effect=lambda data: published.append(data) or True
        )

        # Process offer
        await pairing_session._ntfy_subscriber._process_message(encrypted_offer)
        await asyncio.sleep(0.1)

        # Decrypt answer with same key
        signaling_key = derive_signaling_key(master_secret)
        crypto = NtfySignalingCrypto(signaling_key)
        decrypted = crypto.decrypt(published[0])

        # Parse and verify
        msg = NtfySignalMessage().parse(decrypted)
        assert msg.type == NtfySignalMessageMessageType.ANSWER
        assert msg.session_id == session_id
        assert "v=0" in msg.sdp

    @pytest.mark.asyncio
    async def test_answer_has_fresh_timestamp(self, server):
        """Answer has current timestamp."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer
        encrypted_offer = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
        )

        # Mock peer
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\n")
        mock_peer.on_message = Mock()
        mock_peer.close = AsyncMock()
        pairing_session._ntfy_subscriber._handler._create_peer = Mock(return_value=mock_peer)

        # Capture answer
        published = []
        pairing_session._ntfy_subscriber._publish = AsyncMock(
            side_effect=lambda data: published.append(data) or True
        )

        before = int(time.time())
        await pairing_session._ntfy_subscriber._process_message(encrypted_offer)
        await asyncio.sleep(0.1)
        after = int(time.time())

        # Decrypt and check timestamp
        signaling_key = derive_signaling_key(master_secret)
        crypto = NtfySignalingCrypto(signaling_key)
        msg = NtfySignalMessage().parse(crypto.decrypt(published[0]))

        assert before <= msg.timestamp <= after


class TestNtfyCleanup:
    """Tests for ntfy signaling cleanup."""

    @pytest.mark.asyncio
    async def test_server_close_cleans_up_all_subscribers(self, device_store):
        """Server close cleans up all ntfy subscribers."""
        server = UnifiedServer(
            device_store=device_store,
            stun_servers=[],
            pairing_timeout=60.0,
        )
        await server.start(host="127.0.0.1", port=0)

        # Start multiple pairing sessions
        subscribers = []
        async with aiohttp.ClientSession() as session:
            for _ in range(3):
                async with session.post(
                    f"http://127.0.0.1:{server.get_port()}/api/pair"
                ) as resp:
                    data = await resp.json()
                    session_id = data["session_id"]
                    pairing_session = server._pairing_sessions.get(session_id)
                    subscribers.append(pairing_session._ntfy_subscriber)

        # All should be subscribed
        assert all(s.is_subscribed() for s in subscribers)

        # Close server
        await server.close()

        # All should be unsubscribed
        assert all(not s.is_subscribed() for s in subscribers)

    @pytest.mark.asyncio
    async def test_ntfy_subscriber_key_zeroed_on_cleanup(self, server):
        """Signaling key is zeroed when session is cleaned up."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        pairing_session = server._pairing_sessions.get(session_id)
        handler = pairing_session._ntfy_subscriber._handler
        original_key = handler._crypto._key

        # Cancel pairing (triggers cleanup)
        async with aiohttp.ClientSession() as session:
            async with session.delete(
                f"http://127.0.0.1:{server.get_port()}/api/pair/{session_id}"
            ) as resp:
                assert resp.status == 200

        # Key should be zeroed
        assert handler._crypto._key != original_key
        assert handler._crypto._key == bytes(32)


class TestNtfySecurityRequirements:
    """Tests for security requirements of ntfy signaling."""

    @pytest.mark.asyncio
    async def test_replay_attack_rejected(self, server):
        """Replayed message is rejected."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with specific nonce
        nonce = os.urandom(NONCE_SIZE)
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
            nonce=nonce,
        )

        # Mock peer
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\n")
        mock_peer.on_message = Mock()
        mock_peer.close = AsyncMock()
        pairing_session._ntfy_subscriber._handler._create_peer = Mock(return_value=mock_peer)

        # Track published
        publish_count = 0
        async def mock_publish(data):
            nonlocal publish_count
            publish_count += 1
            return True
        pairing_session._ntfy_subscriber._publish = mock_publish

        # First message should succeed
        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await asyncio.sleep(0.1)
        assert publish_count == 1

        # Replay should be rejected
        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await asyncio.sleep(0.1)
        assert publish_count == 1  # Still 1, replay rejected

    @pytest.mark.asyncio
    async def test_expired_timestamp_rejected(self, server):
        """Message with expired timestamp is rejected."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with old timestamp
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
            timestamp=int(time.time()) - 60,  # 60 seconds ago
        )

        # Mock publish
        pairing_session._ntfy_subscriber._publish = AsyncMock()

        # Process
        await pairing_session._ntfy_subscriber._process_message(encrypted)

        # Should not publish (rejected)
        pairing_session._ntfy_subscriber._publish.assert_not_called()

    @pytest.mark.asyncio
    async def test_wrong_key_rejected(self, server):
        """Message encrypted with wrong key is rejected."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with WRONG master secret
        wrong_secret = os.urandom(32)
        encrypted = create_encrypted_offer(
            master_secret=wrong_secret,
            session_id=session_id,
        )

        # Mock publish
        pairing_session._ntfy_subscriber._publish = AsyncMock()

        # Process
        await pairing_session._ntfy_subscriber._process_message(encrypted)

        # Should not publish (decryption failed)
        pairing_session._ntfy_subscriber._publish.assert_not_called()
