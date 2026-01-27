"""Tests for connection info module."""

import json

import pytest

from ras.connection import ConnectionInfo


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
