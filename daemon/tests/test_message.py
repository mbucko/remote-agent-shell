"""Tests for message module."""

import time

import pytest

from ras.errors import MessageError
from ras.message import Message, MessageCodec


class TestMessage:
    """Test Message dataclass."""

    def test_create_message(self):
        """Can create a message with type and payload."""
        msg = Message(type="output", payload={"data": "hello"})
        assert msg.type == "output"
        assert msg.payload == {"data": "hello"}

    def test_default_seq_is_zero(self):
        """Default sequence number is 0."""
        msg = Message(type="ping", payload={})
        assert msg.seq == 0

    def test_default_timestamp_is_zero(self):
        """Default timestamp is 0."""
        msg = Message(type="ping", payload={})
        assert msg.timestamp == 0

    def test_to_dict(self):
        """Message converts to dict."""
        msg = Message(type="output", payload={"data": "hello"}, seq=5, timestamp=1234)
        d = msg.to_dict()
        assert d["type"] == "output"
        assert d["payload"] == {"data": "hello"}
        assert d["seq"] == 5
        assert d["timestamp"] == 1234

    def test_from_dict(self):
        """Message can be created from dict."""
        d = {"type": "output", "payload": {"data": "hello"}, "seq": 5, "timestamp": 1234}
        msg = Message.from_dict(d)
        assert msg.type == "output"
        assert msg.payload == {"data": "hello"}
        assert msg.seq == 5
        assert msg.timestamp == 1234


class TestMessageCodecEncode:
    """Test message encoding."""

    def test_encode_returns_bytes(self):
        """encode returns bytes."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={})
        encoded = codec.encode(msg)
        assert isinstance(encoded, bytes)

    def test_encode_is_encrypted(self):
        """Encoded data is encrypted (not plaintext)."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="output", payload={"data": "secret_data"})
        encoded = codec.encode(msg)
        assert b"secret_data" not in encoded

    def test_encode_assigns_seq(self):
        """encode assigns sequence number if not set."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={})
        codec.encode(msg)
        assert msg.seq > 0

    def test_encode_increments_seq(self):
        """encode increments sequence number."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        m1 = Message(type="ping", payload={})
        m2 = Message(type="ping", payload={})
        codec.encode(m1)
        codec.encode(m2)
        assert m2.seq == m1.seq + 1

    def test_encode_assigns_timestamp(self):
        """encode assigns timestamp if not set."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={})
        before = int(time.time())
        codec.encode(msg)
        after = int(time.time())
        assert before <= msg.timestamp <= after

    def test_encode_preserves_explicit_seq(self):
        """encode preserves explicitly set seq."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={}, seq=100)
        codec.encode(msg)
        assert msg.seq == 100

    def test_encode_preserves_explicit_timestamp(self):
        """encode preserves explicitly set timestamp."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={}, timestamp=1234567890)
        codec.encode(msg)
        assert msg.timestamp == 1234567890


class TestMessageCodecDecode:
    """Test message decoding."""

    def test_decode_roundtrip(self):
        """Encoded message can be decoded."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="output", payload={"data": "hello"})
        encoded = codec.encode(msg)
        decoded = codec.decode(encoded)
        assert decoded.type == "output"
        assert decoded.payload == {"data": "hello"}

    def test_decode_preserves_seq(self):
        """Decoded message has correct seq."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={})
        encoded = codec.encode(msg)
        decoded = codec.decode(encoded)
        assert decoded.seq == msg.seq

    def test_decode_preserves_timestamp(self):
        """Decoded message has correct timestamp."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={})
        encoded = codec.encode(msg)
        decoded = codec.decode(encoded)
        assert decoded.timestamp == msg.timestamp

    def test_decode_wrong_key_fails(self):
        """Decoding with wrong key raises MessageError."""
        codec1 = MessageCodec(encrypt_key=b"a" * 32)
        codec2 = MessageCodec(encrypt_key=b"b" * 32)
        msg = Message(type="ping", payload={})
        encoded = codec1.encode(msg)
        with pytest.raises(MessageError, match="Decryption failed"):
            codec2.decode(encoded)

    def test_decode_tampered_data_fails(self):
        """Decoding tampered data raises MessageError."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={})
        encoded = bytearray(codec.encode(msg))
        encoded[20] ^= 0xFF  # Flip a bit
        with pytest.raises(MessageError, match="Decryption failed"):
            codec.decode(bytes(encoded))

    def test_decode_invalid_json_fails(self):
        """Decoding invalid JSON raises MessageError."""
        from ras.crypto import encrypt

        key = b"x" * 32
        codec = MessageCodec(encrypt_key=key)
        # Encrypt invalid JSON
        encrypted = encrypt(key, b"not valid json")
        with pytest.raises(MessageError, match="Invalid message format"):
            codec.decode(encrypted)


class TestTimestampValidation:
    """Test timestamp validation."""

    def test_reject_expired_message(self):
        """Rejects messages with old timestamps."""
        codec = MessageCodec(encrypt_key=b"x" * 32, max_age=60)
        msg = Message(type="ping", payload={}, timestamp=int(time.time()) - 120, seq=1)
        encoded = codec.encode(msg)
        with pytest.raises(MessageError, match="expired"):
            codec.decode(encoded)

    def test_reject_future_message(self):
        """Rejects messages with future timestamps."""
        codec = MessageCodec(encrypt_key=b"x" * 32, max_age=60)
        msg = Message(type="ping", payload={}, timestamp=int(time.time()) + 120, seq=1)
        encoded = codec.encode(msg)
        with pytest.raises(MessageError, match="expired"):
            codec.decode(encoded)

    def test_accept_message_within_max_age(self):
        """Accepts messages within max_age window."""
        codec = MessageCodec(encrypt_key=b"x" * 32, max_age=60)
        msg = Message(type="ping", payload={}, timestamp=int(time.time()) - 30, seq=1)
        encoded = codec.encode(msg)
        decoded = codec.decode(encoded)
        assert decoded.type == "ping"


class TestReplayProtection:
    """Test replay protection."""

    def test_reject_duplicate_seq(self):
        """Rejects duplicate sequence numbers."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={})
        encoded = codec.encode(msg)
        codec.decode(encoded)  # First decode succeeds
        with pytest.raises(MessageError, match="Replay"):
            codec.decode(encoded)  # Second decode fails

    def test_reject_seq_below_window(self):
        """Rejects sequence numbers below window floor."""
        codec = MessageCodec(encrypt_key=b"x" * 32, window_size=100)
        # Simulate having seen seq 200
        codec._highest_seen = 200
        codec._seen_seqs = set(range(100, 201))
        # Seq 50 is below floor (100)
        msg = Message(type="ping", payload={}, seq=50, timestamp=int(time.time()))
        encoded = codec.encode(msg)
        with pytest.raises(MessageError, match="too old"):
            codec.decode(encoded)

    def test_accept_seq_within_window(self):
        """Accepts sequence numbers within window."""
        codec = MessageCodec(encrypt_key=b"x" * 32, window_size=100)
        codec._highest_seen = 200
        # seq 150 is within window [100, 200]
        msg = Message(type="ping", payload={}, seq=150, timestamp=int(time.time()))
        encoded = codec.encode(msg)
        decoded = codec.decode(encoded)
        assert decoded.seq == 150

    def test_window_slides_forward(self):
        """Window slides forward as new seqs arrive."""
        codec = MessageCodec(encrypt_key=b"x" * 32, window_size=10)
        # Send messages 1-20
        for i in range(1, 21):
            msg = Message(type="ping", payload={}, seq=i, timestamp=int(time.time()))
            codec.encode(msg)
            codec.decode(codec.encode(Message(type="ping", payload={}, timestamp=int(time.time()))))
        # Highest seen should be 20, floor should be 10
        # Old seq (5) should be rejected
        msg = Message(type="ping", payload={}, seq=5, timestamp=int(time.time()))
        encoded = codec.encode(msg)
        with pytest.raises(MessageError, match="too old"):
            codec.decode(encoded)

    def test_out_of_order_within_window(self):
        """Accepts out-of-order messages within window."""
        codec = MessageCodec(encrypt_key=b"x" * 32, window_size=100)
        # Send seq 100 first
        m1 = Message(type="ping", payload={}, seq=100, timestamp=int(time.time()))
        codec.decode(codec.encode(m1))
        # Then send seq 50 (within window [0, 100])
        m2 = Message(type="ping", payload={}, seq=50, timestamp=int(time.time()))
        decoded = codec.decode(codec.encode(m2))
        assert decoded.seq == 50


class TestCodecConfiguration:
    """Test codec configuration."""

    def test_custom_max_age(self):
        """Can configure max_age."""
        codec = MessageCodec(encrypt_key=b"x" * 32, max_age=300)
        assert codec.max_age == 300

    def test_custom_window_size(self):
        """Can configure window_size."""
        codec = MessageCodec(encrypt_key=b"x" * 32, window_size=500)
        assert codec.window_size == 500

    def test_default_max_age(self):
        """Default max_age is 60 seconds."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        assert codec.max_age == 60

    def test_default_window_size(self):
        """Default window_size is 1000."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        assert codec.window_size == 1000


class TestComplexPayloads:
    """Test complex message payloads."""

    def test_nested_dict_payload(self):
        """Can encode/decode nested dict payloads."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(
            type="output",
            payload={"nested": {"level1": {"level2": {"value": 123}}}},
        )
        decoded = codec.decode(codec.encode(msg))
        assert decoded.payload["nested"]["level1"]["level2"]["value"] == 123

    def test_list_payload(self):
        """Can encode/decode list in payload."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="output", payload={"items": [1, 2, 3, "four", {"five": 5}]})
        decoded = codec.decode(codec.encode(msg))
        assert decoded.payload["items"] == [1, 2, 3, "four", {"five": 5}]

    def test_unicode_payload(self):
        """Can encode/decode unicode in payload."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="output", payload={"text": "Hello, 世界! 🌍"})
        decoded = codec.decode(codec.encode(msg))
        assert decoded.payload["text"] == "Hello, 世界! 🌍"

    def test_empty_payload(self):
        """Can encode/decode empty payload."""
        codec = MessageCodec(encrypt_key=b"x" * 32)
        msg = Message(type="ping", payload={})
        decoded = codec.decode(codec.encode(msg))
        assert decoded.payload == {}
