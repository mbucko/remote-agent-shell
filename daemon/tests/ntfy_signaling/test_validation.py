"""Tests for ntfy signaling validation module.

Tests cover:
- Message validation
- Nonce cache
- Timestamp validation
- Device name sanitization
"""

import os
import time
import threading

import pytest

from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType
from ras.ntfy_signaling.validation import (
    NonceCache,
    NtfySignalMessageValidator,
    ValidationError,
    ValidationResult,
    sanitize_device_name,
    NONCE_SIZE,
    TIMESTAMP_WINDOW_SECONDS,
)


def create_valid_offer(
    session_id: str = "test-session-123",
    sdp: str = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n",
    device_id: str = "device-123",
    device_name: str = "Test Phone",
    timestamp: int = None,
    nonce: bytes = None,
) -> NtfySignalMessage:
    """Create a valid OFFER message for testing."""
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


def create_valid_answer(
    session_id: str = "test-session-123",
    sdp: str = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n",
    timestamp: int = None,
    nonce: bytes = None,
) -> NtfySignalMessage:
    """Create a valid ANSWER message for testing."""
    if timestamp is None:
        timestamp = int(time.time())
    if nonce is None:
        nonce = os.urandom(NONCE_SIZE)

    return NtfySignalMessage(
        type=NtfySignalMessageMessageType.ANSWER,
        session_id=session_id,
        sdp=sdp,
        device_id="",  # Optional for ANSWER
        device_name="",  # Optional for ANSWER
        timestamp=timestamp,
        nonce=nonce,
    )


class TestNonceCache:
    """Tests for nonce cache."""

    def test_new_nonce_not_seen(self):
        """Fresh nonce is not in cache."""
        cache = NonceCache(max_size=100)
        nonce = os.urandom(16)
        assert not cache.has_seen(nonce)

    def test_added_nonce_is_seen(self):
        """Added nonce is seen."""
        cache = NonceCache(max_size=100)
        nonce = os.urandom(16)
        cache.add(nonce)
        assert cache.has_seen(nonce)

    def test_check_and_add_returns_true_for_new(self):
        """check_and_add returns True for new nonce."""
        cache = NonceCache(max_size=100)
        nonce = os.urandom(16)
        assert cache.check_and_add(nonce) is True
        assert cache.has_seen(nonce)

    def test_check_and_add_returns_false_for_replay(self):
        """check_and_add returns False for replay."""
        cache = NonceCache(max_size=100)
        nonce = os.urandom(16)
        cache.add(nonce)
        assert cache.check_and_add(nonce) is False

    def test_fifo_eviction(self):
        """Oldest nonces evicted when full."""
        cache = NonceCache(max_size=3)
        n1 = b"nonce-0000000001"
        n2 = b"nonce-0000000002"
        n3 = b"nonce-0000000003"
        n4 = b"nonce-0000000004"

        cache.add(n1)
        cache.add(n2)
        cache.add(n3)
        assert len(cache) == 3

        cache.add(n4)  # Evicts n1
        assert len(cache) == 3
        assert not cache.has_seen(n1)  # Evicted
        assert cache.has_seen(n2)
        assert cache.has_seen(n3)
        assert cache.has_seen(n4)

    def test_clear_removes_all(self):
        """Clear empties the cache."""
        cache = NonceCache(max_size=100)
        for i in range(10):
            cache.add(os.urandom(16))
        assert len(cache) == 10

        cache.clear()
        assert len(cache) == 0

    def test_thread_safety(self):
        """Cache is thread-safe."""
        cache = NonceCache(max_size=1000)
        errors = []

        def add_nonces():
            try:
                for _ in range(100):
                    nonce = os.urandom(16)
                    cache.check_and_add(nonce)
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=add_nonces) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert not errors
        assert len(cache) <= 1000


class TestNtfySignalMessageValidatorOffer:
    """Tests for validating OFFER messages (daemon side)."""

    @pytest.fixture
    def validator(self):
        """Create validator expecting OFFER."""
        return NtfySignalMessageValidator(
            pending_session_id="test-session-123",
            expected_type="OFFER",
        )

    def test_valid_offer_passes(self, validator):
        """Valid OFFER passes validation."""
        msg = create_valid_offer()
        result = validator.validate(msg)
        assert result.is_valid

    def test_wrong_type_rejected(self, validator):
        """ANSWER rejected when expecting OFFER."""
        msg = create_valid_answer()
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.WRONG_MESSAGE_TYPE

    def test_wrong_session_rejected(self, validator):
        """Wrong session ID rejected."""
        msg = create_valid_offer(session_id="wrong-session")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_SESSION

    def test_empty_session_rejected(self, validator):
        """Empty session ID rejected."""
        msg = create_valid_offer(session_id="")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_SESSION

    def test_session_too_long_rejected(self, validator):
        """Session ID exceeding 64 chars rejected."""
        msg = create_valid_offer(session_id="a" * 65)
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_SESSION_ID_FORMAT

    def test_session_invalid_chars_rejected(self, validator):
        """Session ID with invalid characters rejected."""
        msg = create_valid_offer(session_id="test!@#$%")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_SESSION_ID_FORMAT

    def test_timestamp_30s_past_valid(self, validator):
        """Timestamp 30s in past is valid (boundary)."""
        msg = create_valid_offer(timestamp=int(time.time()) - 30)
        result = validator.validate(msg)
        assert result.is_valid

    def test_timestamp_30s_future_valid(self, validator):
        """Timestamp 30s in future is valid (boundary)."""
        msg = create_valid_offer(timestamp=int(time.time()) + 30)
        result = validator.validate(msg)
        assert result.is_valid

    def test_timestamp_31s_past_rejected(self, validator):
        """Timestamp 31s in past is rejected."""
        msg = create_valid_offer(timestamp=int(time.time()) - 31)
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_TIMESTAMP

    def test_timestamp_31s_future_rejected(self, validator):
        """Timestamp 31s in future is rejected."""
        msg = create_valid_offer(timestamp=int(time.time()) + 31)
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_TIMESTAMP

    def test_timestamp_zero_rejected(self, validator):
        """Timestamp of zero rejected."""
        msg = create_valid_offer(timestamp=0)
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_TIMESTAMP

    def test_timestamp_negative_rejected(self, validator):
        """Negative timestamp rejected."""
        msg = create_valid_offer(timestamp=-1)
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_TIMESTAMP

    def test_nonce_replay_rejected(self, validator):
        """Replayed nonce rejected."""
        nonce = os.urandom(16)
        msg1 = create_valid_offer(nonce=nonce)
        msg2 = create_valid_offer(nonce=nonce)

        result1 = validator.validate(msg1)
        assert result1.is_valid

        result2 = validator.validate(msg2)
        assert not result2.is_valid
        assert result2.error == ValidationError.NONCE_REPLAY

    def test_nonce_too_short_rejected(self, validator):
        """Nonce < 16 bytes rejected."""
        msg = create_valid_offer(nonce=b"short")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_NONCE

    def test_nonce_too_long_rejected(self, validator):
        """Nonce > 16 bytes rejected."""
        msg = create_valid_offer(nonce=os.urandom(17))
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_NONCE

    def test_nonce_empty_rejected(self, validator):
        """Empty nonce rejected."""
        msg = create_valid_offer(nonce=b"")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_NONCE

    def test_sdp_empty_rejected(self, validator):
        """Empty SDP rejected."""
        msg = create_valid_offer(sdp="")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_SDP

    def test_sdp_missing_version_rejected(self, validator):
        """SDP without v=0 rejected."""
        msg = create_valid_offer(sdp="m=application 9 UDP/DTLS/SCTP")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_SDP

    def test_sdp_missing_media_rejected(self, validator):
        """SDP without m= line rejected."""
        msg = create_valid_offer(sdp="v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.INVALID_SDP

    def test_sdp_minimal_valid(self, validator):
        """Minimal valid SDP accepted."""
        msg = create_valid_offer(sdp="v=0\r\nm=application 9\r\n")
        result = validator.validate(msg)
        assert result.is_valid

    def test_device_id_missing_rejected(self, validator):
        """OFFER without device_id rejected."""
        msg = create_valid_offer(device_id="")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.MISSING_DEVICE_ID

    def test_device_name_missing_rejected(self, validator):
        """OFFER without device_name rejected."""
        msg = create_valid_offer(device_name="")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.MISSING_DEVICE_NAME

    def test_device_id_control_chars_rejected(self, validator):
        """OFFER with control characters in device_id rejected."""
        msg = create_valid_offer(device_id="device\x00id")
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.MISSING_DEVICE_ID


class TestNtfySignalMessageValidatorAnswer:
    """Tests for validating ANSWER messages (phone side)."""

    @pytest.fixture
    def validator(self):
        """Create validator expecting ANSWER."""
        return NtfySignalMessageValidator(
            pending_session_id="test-session-123",
            expected_type="ANSWER",
        )

    def test_valid_answer_passes(self, validator):
        """Valid ANSWER passes validation."""
        msg = create_valid_answer()
        result = validator.validate(msg)
        assert result.is_valid

    def test_offer_rejected(self, validator):
        """OFFER rejected when expecting ANSWER."""
        msg = create_valid_offer()
        result = validator.validate(msg)
        assert not result.is_valid
        assert result.error == ValidationError.WRONG_MESSAGE_TYPE

    def test_answer_device_info_not_required(self, validator):
        """ANSWER doesn't require device_id/device_name."""
        msg = create_valid_answer()
        msg.device_id = ""
        msg.device_name = ""
        result = validator.validate(msg)
        assert result.is_valid


class TestSanitizeDeviceName:
    """Tests for device name sanitization."""

    def test_normal_name_unchanged(self):
        """Normal name unchanged."""
        assert sanitize_device_name("My Phone") == "My Phone"

    def test_strips_whitespace(self):
        """Strips leading/trailing whitespace."""
        assert sanitize_device_name("  My Phone  ") == "My Phone"

    def test_replaces_control_chars(self):
        """Replaces control characters with space."""
        assert sanitize_device_name("Phone\x00\x01Test") == "Phone Test"

    def test_collapses_multiple_spaces(self):
        """Collapses multiple spaces into one."""
        assert sanitize_device_name("My    Phone") == "My Phone"

    def test_truncates_long_name(self):
        """Truncates to 64 characters."""
        long_name = "A" * 100
        result = sanitize_device_name(long_name)
        assert len(result) == 64

    def test_preserves_unicode(self):
        """Preserves valid Unicode."""
        assert sanitize_device_name("📱 Téléphone") == "📱 Téléphone"

    def test_preserves_japanese(self):
        """Preserves Japanese characters."""
        assert sanitize_device_name("日本語の電話") == "日本語の電話"

    def test_empty_returns_empty(self):
        """Empty string returns empty."""
        assert sanitize_device_name("") == ""

    def test_whitespace_only_returns_empty(self):
        """Whitespace-only returns empty."""
        assert sanitize_device_name("   ") == ""

    def test_control_chars_only_returns_empty(self):
        """Control chars only returns empty."""
        assert sanitize_device_name("\x00\x01\x02") == ""


class TestValidatorNonceCacheClear:
    """Tests for nonce cache clearing."""

    def test_clear_allows_reuse(self):
        """After clear, same nonce is accepted."""
        validator = NtfySignalMessageValidator(
            pending_session_id="test-session-123",
            expected_type="OFFER",
        )

        nonce = os.urandom(16)
        msg1 = create_valid_offer(nonce=nonce)
        result1 = validator.validate(msg1)
        assert result1.is_valid

        # Same nonce rejected
        msg2 = create_valid_offer(nonce=nonce)
        result2 = validator.validate(msg2)
        assert not result2.is_valid

        # Clear cache
        validator.clear_nonce_cache()

        # Now same nonce accepted
        msg3 = create_valid_offer(nonce=nonce)
        result3 = validator.validate(msg3)
        assert result3.is_valid
