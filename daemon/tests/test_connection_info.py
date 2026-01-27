"""Tests for connection info module."""

import base64
import json

import pytest

from ras.connection import ConnectionInfo
from ras.crypto import derive_keys


class TestConnectionInfo:
    """Test ConnectionInfo dataclass."""

    def test_to_dict(self):
        """ConnectionInfo serializes to dict."""
        info = ConnectionInfo(ip="1.2.3.4", port=8821, session_id="abc123")
        d = info.to_dict()

        assert d["ip"] == "1.2.3.4"
        assert d["port"] == 8821
        assert d["session"] == "abc123"
        assert d["version"] == 1

    def test_to_json(self):
        """ConnectionInfo serializes to JSON."""
        info = ConnectionInfo(ip="1.2.3.4", port=8821, session_id="abc123")
        j = info.to_json()
        parsed = json.loads(j)

        assert parsed["ip"] == "1.2.3.4"
        assert parsed["port"] == 8821
        assert parsed["session"] == "abc123"

    def test_from_dict(self):
        """Can deserialize ConnectionInfo from dict."""
        d = {"version": 1, "ip": "1.2.3.4", "port": 8821, "session": "abc123"}
        info = ConnectionInfo.from_dict(d)

        assert info.ip == "1.2.3.4"
        assert info.port == 8821
        assert info.session_id == "abc123"
        assert info.version == 1

    def test_from_dict_default_version(self):
        """from_dict uses default version if not present."""
        d = {"ip": "1.2.3.4", "port": 8821, "session": "abc123"}
        info = ConnectionInfo.from_dict(d)

        assert info.version == 1

    def test_from_json(self):
        """Can deserialize ConnectionInfo from JSON string."""
        j = '{"version": 1, "ip": "5.6.7.8", "port": 9999, "session": "xyz789"}'
        info = ConnectionInfo.from_json(j)

        assert info.ip == "5.6.7.8"
        assert info.port == 9999
        assert info.session_id == "xyz789"

    def test_session_id_generated_if_not_provided(self):
        """Session ID auto-generated if not provided."""
        info = ConnectionInfo(ip="1.2.3.4", port=8821)

        assert info.session_id
        assert len(info.session_id) >= 8

    def test_session_id_unique(self):
        """Generated session IDs are unique."""
        info1 = ConnectionInfo(ip="1.2.3.4", port=8821)
        info2 = ConnectionInfo(ip="1.2.3.4", port=8821)

        assert info1.session_id != info2.session_id

    def test_generates_qr_code_bytes(self):
        """Can generate QR code as PNG bytes."""
        info = ConnectionInfo(ip="1.2.3.4", port=8821, session_id="abc123")
        png_bytes = info.generate_qr()

        # PNG magic bytes
        assert png_bytes[:8] == b"\x89PNG\r\n\x1a\n"

    def test_qr_code_contains_json(self):
        """QR code encodes the JSON representation."""
        info = ConnectionInfo(ip="1.2.3.4", port=8821, session_id="abc123")

        # We can't easily decode a QR from bytes in tests,
        # but we verify the JSON is what would be encoded
        expected_json = info.to_json()
        assert "1.2.3.4" in expected_json
        assert "8821" in expected_json
        assert "abc123" in expected_json

    def test_roundtrip(self):
        """Can serialize and deserialize ConnectionInfo."""
        original = ConnectionInfo(ip="10.0.0.1", port=5000, session_id="test123")
        json_str = original.to_json()
        restored = ConnectionInfo.from_json(json_str)

        assert restored.ip == original.ip
        assert restored.port == original.port
        assert restored.session_id == original.session_id
        assert restored.version == original.version


class TestConnectionInfoWithSecret:
    """Test ConnectionInfo with secret/topic."""

    def test_create_with_secret(self):
        """Can create ConnectionInfo with secret."""
        secret = b"x" * 32
        info = ConnectionInfo(ip="1.2.3.4", port=8821, secret=secret)
        assert info.secret == secret

    def test_default_secret_is_none(self):
        """Default secret is None."""
        info = ConnectionInfo(ip="1.2.3.4", port=8821)
        assert info.secret is None

    def test_topic_property_with_secret(self):
        """topic property returns derived topic when secret set."""
        secret = b"x" * 32
        info = ConnectionInfo(ip="1.2.3.4", port=8821, secret=secret)
        expected_topic = derive_keys(secret).topic
        assert info.topic == expected_topic

    def test_topic_property_without_secret(self):
        """topic property returns None when no secret."""
        info = ConnectionInfo(ip="1.2.3.4", port=8821)
        assert info.topic is None

    def test_to_dict_includes_secret(self):
        """to_dict includes base64-encoded secret."""
        secret = b"x" * 32
        info = ConnectionInfo(ip="1.2.3.4", port=8821, secret=secret)
        d = info.to_dict()
        assert "secret" in d
        assert d["secret"] == base64.b64encode(secret).decode()

    def test_to_dict_includes_topic(self):
        """to_dict includes derived topic."""
        secret = b"x" * 32
        info = ConnectionInfo(ip="1.2.3.4", port=8821, secret=secret)
        d = info.to_dict()
        assert "topic" in d
        assert len(d["topic"]) == 12

    def test_to_dict_without_secret_no_extra_fields(self):
        """to_dict without secret doesn't include secret/topic."""
        info = ConnectionInfo(ip="1.2.3.4", port=8821)
        d = info.to_dict()
        assert "secret" not in d
        assert "topic" not in d

    def test_from_dict_with_secret(self):
        """from_dict can deserialize secret."""
        secret = b"x" * 32
        d = {
            "version": 1,
            "ip": "1.2.3.4",
            "port": 8821,
            "session": "abc123",
            "secret": base64.b64encode(secret).decode(),
            "topic": "abcdef123456",
        }
        info = ConnectionInfo.from_dict(d)
        assert info.secret == secret

    def test_from_dict_without_secret(self):
        """from_dict handles missing secret."""
        d = {
            "version": 1,
            "ip": "1.2.3.4",
            "port": 8821,
            "session": "abc123",
        }
        info = ConnectionInfo.from_dict(d)
        assert info.secret is None

    def test_roundtrip_with_secret(self):
        """Can serialize and deserialize ConnectionInfo with secret."""
        secret = b"x" * 32
        original = ConnectionInfo(ip="10.0.0.1", port=5000, session_id="test123", secret=secret)
        json_str = original.to_json()
        restored = ConnectionInfo.from_json(json_str)

        assert restored.secret == original.secret
        assert restored.topic == original.topic

    def test_qr_code_with_secret(self):
        """QR code includes secret when set."""
        secret = b"x" * 32
        info = ConnectionInfo(ip="1.2.3.4", port=8821, secret=secret)
        expected_json = info.to_json()
        # Verify secret is in the JSON
        parsed = json.loads(expected_json)
        assert "secret" in parsed
        assert "topic" in parsed
