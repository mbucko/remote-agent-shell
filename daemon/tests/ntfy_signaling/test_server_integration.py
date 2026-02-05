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
from ras.crypto import (
    PAIR_NONCE_LENGTH,
    compute_pair_request_hmac,
    compute_pair_response_hmac,
    derive_key,
    derive_ntfy_topic,
)
from ras.device_store import JsonDeviceStore
from ras.ntfy_signaling import NtfySignalingSubscriber
from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
from ras.ntfy_signaling.validation import NONCE_SIZE
from ras.proto.ras.ras import (
    NtfySignalMessage,
    NtfySignalMessageMessageType,
    PairRequest,
    PairResponse,
)
from ras.server import UnifiedServer

# Helper to let async tasks process
async def yield_to_pending_tasks():
    """Yield to pending async tasks briefly."""
    await asyncio.sleep(0)
    await asyncio.sleep(0)


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


def create_encrypted_pair_request(
    master_secret: bytes,
    session_id: str,
    device_id: str = "device-123",
    device_name: str = "Test Phone",
) -> str:
    """Create an encrypted PAIR_REQUEST message."""
    auth_key = derive_key(master_secret, "auth")
    nonce = os.urandom(PAIR_NONCE_LENGTH)

    auth_proof = compute_pair_request_hmac(auth_key, session_id, device_id, nonce)

    pair_req = PairRequest(
        device_id=device_id,
        device_name=device_name,
        auth_proof=auth_proof,
        nonce=nonce,
        session_id=session_id,
    )

    msg = NtfySignalMessage(
        type=NtfySignalMessageMessageType.PAIR_REQUEST,
        session_id=session_id,
        timestamp=int(time.time()),
        nonce=os.urandom(NONCE_SIZE),
        pair_request=pair_req,
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
    async def test_qr_data_contains_only_master_secret(self, server):
        """QR data contains only master_secret - ntfy topic is derived."""
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        qr_data = data["qr_data"]
        # QR only contains master_secret - everything else is derived
        assert "master_secret" in qr_data
        assert "ntfy_topic" not in qr_data  # Derived, not in QR

        # Verify ntfy topic can be derived from master_secret
        master_secret = bytes.fromhex(qr_data["master_secret"])
        derived_topic = derive_ntfy_topic(master_secret)
        assert derived_topic.startswith("ras-")


class TestNtfyPairRequestProcessing:
    """Tests for processing PAIR_REQUEST messages via ntfy."""

    @pytest.mark.asyncio
    async def test_valid_pair_request_completes_pairing(self, server):
        """Valid PAIR_REQUEST via ntfy completes pairing."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create encrypted PAIR_REQUEST
        encrypted = create_encrypted_pair_request(
            master_secret=master_secret,
            session_id=session_id,
            device_id="test-phone-123",
            device_name="Test Phone",
        )

        # Mock publish to capture response
        published = []
        pairing_session._ntfy_subscriber._publish = AsyncMock(
            side_effect=lambda data: published.append(data) or True
        )

        # Process pair request through subscriber
        await pairing_session._ntfy_subscriber._process_message(encrypted)

        # Wait for processing
        await yield_to_pending_tasks()

        # Verify PAIR_RESPONSE was published
        assert len(published) == 1

        # Decrypt and verify response
        signaling_key = derive_signaling_key(master_secret)
        crypto = NtfySignalingCrypto(signaling_key)
        decrypted = crypto.decrypt(published[0])
        response_msg = NtfySignalMessage().parse(decrypted)
        assert response_msg.type == NtfySignalMessageMessageType.PAIR_RESPONSE
        assert response_msg.pair_response.daemon_device_id != ""
        assert response_msg.pair_response.hostname != ""
        assert len(response_msg.pair_response.auth_proof) == 32

        # Verify session state is completed
        assert pairing_session.state == "completed"
        assert pairing_session.device_id == "test-phone-123"
        assert pairing_session.device_name == "Test Phone"

        # Verify device was stored
        device = server.device_store.get("test-phone-123")
        assert device is not None
        assert device.name == "Test Phone"

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
        await yield_to_pending_tasks()

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
        await yield_to_pending_tasks()
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


class TestNtfyClockSkewBoundary:
    """Tests for E2E-NTFY-09: Clock skew boundary cases."""

    @pytest.mark.asyncio
    async def test_timestamp_exactly_30s_past_valid(self, server):
        """E2E-NTFY-09: Timestamp exactly 30s in past is VALID (boundary)."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with timestamp exactly 30 seconds in past
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
            timestamp=int(time.time()) - 30,  # Exactly 30s - boundary
        )

        # Mock peer
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\n")
        mock_peer.on_message = Mock()
        mock_peer.close = AsyncMock()
        pairing_session._ntfy_subscriber._handler._create_peer = Mock(return_value=mock_peer)

        # Track published
        published = []
        pairing_session._ntfy_subscriber._publish = AsyncMock(
            side_effect=lambda data: published.append(data) or True
        )

        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await yield_to_pending_tasks()

        # Should be accepted (exactly at boundary)
        assert len(published) == 1

    @pytest.mark.asyncio
    async def test_timestamp_31s_past_rejected(self, server):
        """E2E-NTFY-09: Timestamp 31s in past is INVALID (outside boundary)."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with timestamp 31 seconds in past
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
            timestamp=int(time.time()) - 31,  # 31s - outside boundary
        )

        # Mock publish
        pairing_session._ntfy_subscriber._publish = AsyncMock()

        await pairing_session._ntfy_subscriber._process_message(encrypted)

        # Should be rejected
        pairing_session._ntfy_subscriber._publish.assert_not_called()

    @pytest.mark.asyncio
    async def test_timestamp_exactly_30s_future_valid(self, server):
        """E2E-NTFY-09: Timestamp exactly 30s in future is VALID (boundary)."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with timestamp exactly 30 seconds in future
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
            timestamp=int(time.time()) + 30,  # Exactly 30s in future
        )

        # Mock peer
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\n")
        mock_peer.on_message = Mock()
        mock_peer.close = AsyncMock()
        pairing_session._ntfy_subscriber._handler._create_peer = Mock(return_value=mock_peer)

        # Track published
        published = []
        pairing_session._ntfy_subscriber._publish = AsyncMock(
            side_effect=lambda data: published.append(data) or True
        )

        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await yield_to_pending_tasks()

        # Should be accepted (exactly at boundary)
        assert len(published) == 1

    @pytest.mark.asyncio
    async def test_timestamp_31s_future_rejected(self, server):
        """E2E-NTFY-09: Timestamp 31s in future is INVALID (outside boundary)."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with timestamp 31 seconds in future
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
            timestamp=int(time.time()) + 31,  # 31s in future - outside boundary
        )

        # Mock publish
        pairing_session._ntfy_subscriber._publish = AsyncMock()

        await pairing_session._ntfy_subscriber._process_message(encrypted)

        # Should be rejected
        pairing_session._ntfy_subscriber._publish.assert_not_called()


class TestNtfyLargeSdp:
    """Tests for E2E-NTFY-10: Large SDP handling."""

    @pytest.mark.asyncio
    async def test_large_sdp_with_many_ice_candidates(self, server):
        """E2E-NTFY-10: SDP with many ICE candidates (~50KB)."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create a large SDP with 500 ICE candidates (~50KB)
        base_sdp = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
        base_sdp += "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        base_sdp += "c=IN IP4 0.0.0.0\r\n"

        # Add 500 ICE candidates (~100 bytes each = ~50KB)
        for i in range(500):
            base_sdp += f"a=candidate:foundation{i} 1 udp 2130706431 192.168.{(i // 256) % 256}.{i % 256} {50000 + i} typ host generation 0 ufrag abcd network-id {i}\r\n"

        assert len(base_sdp) > 40000  # Verify it's actually large

        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
            sdp=base_sdp,
        )

        # Mock peer
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value="v=0\r\nm=application 9\r\na=answer\r\n")
        mock_peer.on_message = Mock()
        mock_peer.close = AsyncMock()
        pairing_session._ntfy_subscriber._handler._create_peer = Mock(return_value=mock_peer)

        # Track published
        published = []
        pairing_session._ntfy_subscriber._publish = AsyncMock(
            side_effect=lambda data: published.append(data) or True
        )

        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await yield_to_pending_tasks()

        # Should handle large SDP successfully
        assert len(published) == 1


class TestNtfyUnicodeDeviceName:
    """Tests for E2E-NTFY-11: Unicode device name handling."""

    @pytest.mark.asyncio
    async def test_unicode_device_name_preserved_e2e(self, server):
        """E2E-NTFY-11: Device name with Unicode characters is preserved via PAIR_REQUEST."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Unicode device name with emojis and international characters
        unicode_name = "📱 Téléphone de José 日本語"

        encrypted = create_encrypted_pair_request(
            master_secret=master_secret,
            session_id=session_id,
            device_id="unicode-phone-123",
            device_name=unicode_name,
        )

        # Track published
        published = []
        pairing_session._ntfy_subscriber._publish = AsyncMock(
            side_effect=lambda data: published.append(data) or True
        )

        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await yield_to_pending_tasks()

        # Should succeed
        assert len(published) == 1

        # Verify device name was stored correctly (sanitized but Unicode preserved)
        assert pairing_session.device_name is not None
        assert "Téléphone" in pairing_session.device_name
        assert "José" in pairing_session.device_name
        assert "日本語" in pairing_session.device_name


class TestNtfyWrongMessageType:
    """Tests for E2E-NTFY-12: Wrong message type handling."""

    @pytest.mark.asyncio
    async def test_daemon_ignores_answer_message(self, server):
        """E2E-NTFY-12: Daemon ignores ANSWER message (expects OFFER)."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create ANSWER message instead of OFFER
        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.ANSWER,  # Wrong type!
            session_id=session_id,
            sdp="v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n",
            device_id="",
            device_name="",
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
        )
        signaling_key = derive_signaling_key(master_secret)
        crypto = NtfySignalingCrypto(signaling_key)
        encrypted = crypto.encrypt(bytes(msg))

        # Mock publish
        pairing_session._ntfy_subscriber._publish = AsyncMock()

        await pairing_session._ntfy_subscriber._process_message(encrypted)

        # Should be silently rejected (wrong message type)
        pairing_session._ntfy_subscriber._publish.assert_not_called()
        assert pairing_session.state == "pending"


class TestNtfyDuplicateMessage:
    """Tests for E2E-NTFY-21: Duplicate message handling."""

    @pytest.mark.asyncio
    async def test_duplicate_offer_only_one_response(self, server):
        """E2E-NTFY-21: Same message delivered multiple times -> only one response."""
        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create offer with fixed nonce
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

        # Deliver same message 3 times (simulating ntfy duplication)
        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await yield_to_pending_tasks()

        # Should only respond once (nonce replay protection)
        assert publish_count == 1


class TestNtfyTamperedCiphertext:
    """Tests for tampered ciphertext rejection."""

    @pytest.mark.asyncio
    async def test_tampered_ciphertext_rejected(self, server):
        """Tampered ciphertext is silently rejected (GCM auth failure)."""
        import base64

        # Start pairing
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"http://127.0.0.1:{server.get_port()}/api/pair"
            ) as resp:
                data = await resp.json()

        session_id = data["session_id"]
        master_secret = bytes.fromhex(data["qr_data"]["master_secret"])
        pairing_session = server._pairing_sessions.get(session_id)

        # Create valid offer
        encrypted = create_encrypted_offer(
            master_secret=master_secret,
            session_id=session_id,
        )

        # Tamper with the ciphertext
        raw = bytearray(base64.b64decode(encrypted))
        raw[-5] ^= 0xFF  # Flip some bits
        tampered = base64.b64encode(bytes(raw)).decode()

        # Mock publish
        pairing_session._ntfy_subscriber._publish = AsyncMock()

        await pairing_session._ntfy_subscriber._process_message(tampered)

        # Should be silently rejected (authentication failure)
        pairing_session._ntfy_subscriber._publish.assert_not_called()
        assert pairing_session.state == "pending"


class TestNtfySessionTimeoutCleanup:
    """Tests for session timeout cleanup."""

    @pytest.mark.asyncio
    @pytest.mark.integration  # Uses real time for timeout
    async def test_session_timeout_cleans_up_subscriber(self, device_store):
        """Session expiration cleans up ntfy subscriber."""
        # Create server with very short timeout for testing
        server = UnifiedServer(
            device_store=device_store,
                stun_servers=[],
            pairing_timeout=0.1,  # 100ms timeout for faster test
        )
        await server.start(host="127.0.0.1", port=0)

        try:
            # Start pairing
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"http://127.0.0.1:{server.get_port()}/api/pair"
                ) as resp:
                    data = await resp.json()

            session_id = data["session_id"]
            pairing_session = server._pairing_sessions.get(session_id)
            subscriber = pairing_session._ntfy_subscriber

            assert subscriber.is_subscribed()

            # Wait for timeout (slightly longer than pairing_timeout)
            await asyncio.sleep(0.2)

            # Verify cleanup
            assert not subscriber.is_subscribed()
            assert pairing_session.state == "expired"
        finally:
            await server.close()


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
        await yield_to_pending_tasks()
        assert publish_count == 1

        # Replay should be rejected
        await pairing_session._ntfy_subscriber._process_message(encrypted)
        await yield_to_pending_tasks()
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
