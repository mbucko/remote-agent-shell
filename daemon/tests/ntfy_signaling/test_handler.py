"""Tests for ntfy signaling handler.

Tests cover:
- Message handling flow
- WebRTC offer/answer creation
- Error handling (silent failures)
- Cleanup on completion/timeout
"""

import asyncio
import json
import os
import time
from typing import Optional
from unittest.mock import AsyncMock, MagicMock, Mock, patch

import pytest

from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
from ras.ntfy_signaling.handler import (
    NtfySignalingHandler,
    HandlerResult,
)
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


def create_encrypted_offer(
    crypto: NtfySignalingCrypto,
    **kwargs,
) -> str:
    """Create an encrypted OFFER message."""
    msg = create_test_offer(**kwargs)
    return crypto.encrypt(bytes(msg))


def json_wrap_sdp(sdp: str, sdp_type: str = "answer") -> str:
    """Wrap raw SDP in JSON format for PeerConnection compatibility."""
    return json.dumps({"type": sdp_type, "sdp": sdp})


class TestNtfySignalingHandlerInit:
    """Tests for handler initialization."""

    def test_init_with_master_secret(self):
        """Handler can be initialized with master secret."""
        master_secret = os.urandom(32)
        handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id="test-session",
        )
        assert handler is not None

    def test_init_derives_signaling_key(self):
        """Handler derives signaling key from master secret."""
        master_secret = os.urandom(32)
        handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id="test-session",
        )
        expected_key = derive_signaling_key(master_secret)
        assert handler._crypto._key == expected_key


class TestNtfySignalingHandlerHandleMessage:
    """Tests for message handling."""

    @pytest.fixture
    def master_secret(self):
        """Test master secret."""
        return os.urandom(32)

    @pytest.fixture
    def crypto(self, master_secret):
        """Crypto handler for creating test messages."""
        return NtfySignalingCrypto(derive_signaling_key(master_secret))

    @pytest.fixture
    def handler(self, master_secret):
        """Create handler for testing."""
        return NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id="test-session-123",
        )

    @pytest.mark.asyncio
    async def test_valid_offer_returns_result(self, handler, crypto):
        """Valid OFFER returns HandlerResult with answer."""
        encrypted = create_encrypted_offer(crypto)

        # Mock the peer creation
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value=json_wrap_sdp("v=0\r\nm=application 9\r\na=answer\r\n"))
        handler._create_peer = Mock(return_value=mock_peer)

        result = await handler.handle_message(encrypted)

        assert result is not None
        assert result.should_respond is True
        assert result.answer_encrypted is not None
        assert result.device_id == "device-123"
        assert result.device_name == "Test Phone"

    @pytest.mark.asyncio
    async def test_answer_is_properly_encrypted(self, handler, crypto):
        """Answer is encrypted with same key."""
        encrypted = create_encrypted_offer(crypto)

        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value=json_wrap_sdp("v=0\r\nm=application 9\r\na=answer\r\n"))
        handler._create_peer = Mock(return_value=mock_peer)

        result = await handler.handle_message(encrypted)

        # Should be able to decrypt the answer
        decrypted = crypto.decrypt(result.answer_encrypted)
        msg = NtfySignalMessage().parse(decrypted)
        assert msg.type == NtfySignalMessageMessageType.ANSWER
        assert "v=0" in msg.sdp

    @pytest.mark.asyncio
    async def test_invalid_base64_returns_none(self, handler):
        """Invalid base64 is silently ignored."""
        result = await handler.handle_message("not-valid-base64!!!")
        assert result is None

    @pytest.mark.asyncio
    async def test_wrong_key_returns_none(self, handler):
        """Message encrypted with wrong key is silently ignored."""
        wrong_crypto = NtfySignalingCrypto(os.urandom(32))
        encrypted = create_encrypted_offer(wrong_crypto)

        result = await handler.handle_message(encrypted)
        assert result is None

    @pytest.mark.asyncio
    async def test_tampered_message_returns_none(self, handler, crypto):
        """Tampered message is silently ignored."""
        encrypted = create_encrypted_offer(crypto)
        # Tamper with the message
        import base64
        data = bytearray(base64.b64decode(encrypted))
        data[-1] ^= 0xFF
        tampered = base64.b64encode(bytes(data)).decode()

        result = await handler.handle_message(tampered)
        assert result is None

    @pytest.mark.asyncio
    async def test_wrong_session_id_returns_none(self, handler, crypto):
        """Wrong session ID is silently ignored."""
        encrypted = create_encrypted_offer(crypto, session_id="wrong-session")

        result = await handler.handle_message(encrypted)
        assert result is None

    @pytest.mark.asyncio
    async def test_expired_timestamp_returns_none(self, handler, crypto):
        """Expired timestamp is silently ignored."""
        encrypted = create_encrypted_offer(
            crypto,
            timestamp=int(time.time()) - 60,  # 60 seconds ago
        )

        result = await handler.handle_message(encrypted)
        assert result is None

    @pytest.mark.asyncio
    async def test_replay_attack_returns_none(self, handler, crypto):
        """Replayed message is silently ignored."""
        nonce = os.urandom(NONCE_SIZE)
        encrypted = create_encrypted_offer(crypto, nonce=nonce)

        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value=json_wrap_sdp("v=0\r\nm=application 9\r\n"))
        handler._create_peer = Mock(return_value=mock_peer)

        # First should succeed
        result1 = await handler.handle_message(encrypted)
        assert result1 is not None

        # Replay should fail silently
        result2 = await handler.handle_message(encrypted)
        assert result2 is None

    @pytest.mark.asyncio
    async def test_answer_message_type_returns_none(self, handler, crypto):
        """ANSWER message type is silently ignored (daemon expects OFFER)."""
        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.ANSWER,
            session_id="test-session-123",
            sdp="v=0\r\nm=application 9\r\n",
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
        )
        encrypted = crypto.encrypt(bytes(msg))

        result = await handler.handle_message(encrypted)
        assert result is None

    @pytest.mark.asyncio
    async def test_missing_device_id_returns_none(self, handler, crypto):
        """OFFER without device_id is silently ignored."""
        encrypted = create_encrypted_offer(crypto, device_id="")

        result = await handler.handle_message(encrypted)
        assert result is None

    @pytest.mark.asyncio
    async def test_missing_device_name_returns_none(self, handler, crypto):
        """OFFER without device_name is silently ignored."""
        encrypted = create_encrypted_offer(crypto, device_name="")

        result = await handler.handle_message(encrypted)
        assert result is None

    @pytest.mark.asyncio
    async def test_empty_sdp_returns_none(self, handler, crypto):
        """OFFER with empty SDP is silently ignored."""
        encrypted = create_encrypted_offer(crypto, sdp="")

        result = await handler.handle_message(encrypted)
        assert result is None

    @pytest.mark.asyncio
    async def test_invalid_sdp_format_returns_none(self, handler, crypto):
        """OFFER with invalid SDP format is silently ignored."""
        encrypted = create_encrypted_offer(crypto, sdp="invalid sdp")

        result = await handler.handle_message(encrypted)
        assert result is None


class TestNtfySignalingHandlerAnswerCreation:
    """Tests for answer message creation."""

    @pytest.fixture
    def master_secret(self):
        return os.urandom(32)

    @pytest.fixture
    def crypto(self, master_secret):
        return NtfySignalingCrypto(derive_signaling_key(master_secret))

    @pytest.fixture
    def handler(self, master_secret):
        return NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id="test-session-123",
        )

    @pytest.mark.asyncio
    async def test_answer_includes_session_id(self, handler, crypto):
        """Answer includes same session_id as offer."""
        encrypted = create_encrypted_offer(crypto, session_id="test-session-123")

        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value=json_wrap_sdp("v=0\r\nm=application 9\r\n"))
        handler._create_peer = Mock(return_value=mock_peer)

        result = await handler.handle_message(encrypted)

        decrypted = crypto.decrypt(result.answer_encrypted)
        msg = NtfySignalMessage().parse(decrypted)
        assert msg.session_id == "test-session-123"

    @pytest.mark.asyncio
    async def test_answer_has_fresh_timestamp(self, handler, crypto):
        """Answer has current timestamp."""
        encrypted = create_encrypted_offer(crypto)

        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value=json_wrap_sdp("v=0\r\nm=application 9\r\n"))
        handler._create_peer = Mock(return_value=mock_peer)

        before = int(time.time())
        result = await handler.handle_message(encrypted)
        after = int(time.time())

        decrypted = crypto.decrypt(result.answer_encrypted)
        msg = NtfySignalMessage().parse(decrypted)
        assert before <= msg.timestamp <= after

    @pytest.mark.asyncio
    async def test_answer_has_unique_nonce(self, handler, crypto):
        """Each answer has unique nonce."""
        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value=json_wrap_sdp("v=0\r\nm=application 9\r\n"))
        handler._create_peer = Mock(return_value=mock_peer)

        # Get two answers
        encrypted1 = create_encrypted_offer(crypto)
        result1 = await handler.handle_message(encrypted1)

        encrypted2 = create_encrypted_offer(crypto)  # New nonce in offer
        result2 = await handler.handle_message(encrypted2)

        # Decrypt and compare nonces
        msg1 = NtfySignalMessage().parse(crypto.decrypt(result1.answer_encrypted))
        msg2 = NtfySignalMessage().parse(crypto.decrypt(result2.answer_encrypted))

        assert msg1.nonce != msg2.nonce
        assert len(msg1.nonce) == NONCE_SIZE
        assert len(msg2.nonce) == NONCE_SIZE


class TestNtfySignalingHandlerCleanup:
    """Tests for cleanup on completion/timeout."""

    @pytest.fixture
    def master_secret(self):
        return os.urandom(32)

    @pytest.fixture
    def handler(self, master_secret):
        return NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id="test-session-123",
        )

    def test_clear_nonce_cache_clears_cache(self, handler):
        """clear_nonce_cache clears the nonce cache."""
        # Add a nonce
        nonce = os.urandom(NONCE_SIZE)
        handler._validator._nonce_cache.add(nonce)
        assert handler._validator._nonce_cache.has_seen(nonce)

        # Clear
        handler.clear_nonce_cache()
        assert not handler._validator._nonce_cache.has_seen(nonce)

    def test_zero_key_clears_crypto(self, handler):
        """zero_key makes crypto unusable."""
        original_key = handler._crypto._key

        handler.zero_key()

        # Key should be zeroed
        assert handler._crypto._key != original_key
        assert handler._crypto._key == bytes(32)


class TestNtfySignalingHandlerDeviceName:
    """Tests for device name handling."""

    @pytest.fixture
    def master_secret(self):
        return os.urandom(32)

    @pytest.fixture
    def crypto(self, master_secret):
        return NtfySignalingCrypto(derive_signaling_key(master_secret))

    @pytest.fixture
    def handler(self, master_secret):
        return NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id="test-session-123",
        )

    @pytest.mark.asyncio
    async def test_unicode_device_name_preserved(self, handler, crypto):
        """Unicode device name is preserved."""
        encrypted = create_encrypted_offer(
            crypto,
            device_name="📱 Téléphone",
        )

        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value=json_wrap_sdp("v=0\r\nm=application 9\r\n"))
        handler._create_peer = Mock(return_value=mock_peer)

        result = await handler.handle_message(encrypted)

        assert "Téléphone" in result.device_name

    @pytest.mark.asyncio
    async def test_control_chars_sanitized(self, handler, crypto):
        """Control characters in device name are sanitized."""
        encrypted = create_encrypted_offer(
            crypto,
            device_name="Phone\x00\x01Test",
        )

        mock_peer = AsyncMock()
        mock_peer.accept_offer = AsyncMock(return_value=json_wrap_sdp("v=0\r\nm=application 9\r\n"))
        handler._create_peer = Mock(return_value=mock_peer)

        result = await handler.handle_message(encrypted)

        # Control chars replaced with space, collapsed
        assert "\x00" not in result.device_name
        assert "Phone" in result.device_name
        assert "Test" in result.device_name


class TestHandlerResult:
    """Tests for HandlerResult dataclass."""

    def test_should_respond_true_with_answer(self):
        """should_respond is True when answer is present."""
        result = HandlerResult(
            should_respond=True,
            answer_encrypted="encrypted-answer",
            device_id="device-123",
            device_name="Test Phone",
            peer=Mock(),
        )
        assert result.should_respond is True
        assert result.answer_encrypted == "encrypted-answer"

    def test_no_response_result(self):
        """Can create result indicating no response needed."""
        result = HandlerResult(
            should_respond=False,
            answer_encrypted=None,
            device_id=None,
            device_name=None,
            peer=None,
        )
        assert result.should_respond is False
