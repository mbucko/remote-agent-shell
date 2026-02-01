"""Tests for QR code generation."""

import base64
import tempfile
from pathlib import Path

import pytest

from ras.pairing.qr_generator import QrGenerator
from ras.proto.ras import QrPayload


class TestQrGeneratorPayload:
    """Tests for QR payload creation."""

    @pytest.fixture
    def qr_generator(self):
        """Create a QR generator with test data."""
        return QrGenerator(master_secret=b"\x00" * 32)

    def test_payload_contains_only_master_secret(self, qr_generator):
        """Payload contains only master_secret - everything else derived."""
        payload_bytes = qr_generator._create_payload()
        parsed = QrPayload().parse(payload_bytes)

        assert parsed.version == 1
        assert parsed.ip == ""  # Not in QR
        assert parsed.port == 0  # Not in QR
        assert parsed.master_secret == b"\x00" * 32
        assert parsed.session_id == ""  # Derived from master_secret
        assert parsed.ntfy_topic == ""  # Derived from master_secret

    def test_payload_version_is_1(self, qr_generator):
        """Payload version is always 1."""
        payload_bytes = qr_generator._create_payload()
        parsed = QrPayload().parse(payload_bytes)
        assert parsed.version == 1

    def test_payload_is_valid_protobuf(self, qr_generator):
        """Payload is valid protobuf."""
        payload_bytes = qr_generator._create_payload()
        # Should not raise
        QrPayload().parse(payload_bytes)


class TestQrGeneratorTerminal:
    """Tests for terminal output."""

    @pytest.fixture
    def qr_generator(self):
        """Create a QR generator with test data."""
        return QrGenerator(master_secret=b"\x00" * 32)

    def test_terminal_output_not_empty(self, qr_generator):
        """Terminal output is not empty."""
        output = qr_generator.to_terminal()
        assert len(output) > 0

    def test_terminal_output_contains_blocks(self, qr_generator):
        """Terminal output contains block characters."""
        output = qr_generator.to_terminal()
        # Should contain some kind of block characters
        assert any(c in output for c in ["█", "▄", "▀", " "])

    def test_terminal_output_is_string(self, qr_generator):
        """Terminal output is a string."""
        output = qr_generator.to_terminal()
        assert isinstance(output, str)


class TestQrGeneratorPng:
    """Tests for PNG file output."""

    @pytest.fixture
    def qr_generator(self):
        """Create a QR generator with test data."""
        return QrGenerator(master_secret=b"\x00" * 32)

    def test_png_creates_file(self, qr_generator):
        """PNG output creates a file."""
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            path = f.name

        try:
            qr_generator.to_png(path)
            assert Path(path).exists()
            assert Path(path).stat().st_size > 0
        finally:
            Path(path).unlink(missing_ok=True)

    def test_png_is_valid_image(self, qr_generator):
        """PNG file is a valid image."""
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            path = f.name

        try:
            qr_generator.to_png(path)
            # Check PNG magic bytes
            with open(path, "rb") as f:
                magic = f.read(8)
            assert magic == b"\x89PNG\r\n\x1a\n"
        finally:
            Path(path).unlink(missing_ok=True)


class TestQrGeneratorHtml:
    """Tests for HTML output."""

    @pytest.fixture
    def qr_generator(self):
        """Create a QR generator with test data."""
        return QrGenerator(master_secret=b"\x00" * 32)

    def test_html_contains_doctype(self, qr_generator):
        """HTML output contains DOCTYPE."""
        html = qr_generator.to_html()
        assert "<!DOCTYPE html>" in html

    def test_html_contains_title(self, qr_generator):
        """HTML output contains title."""
        html = qr_generator.to_html()
        assert "<title>" in html

    def test_html_contains_embedded_image(self, qr_generator):
        """HTML output contains embedded base64 image."""
        html = qr_generator.to_html()
        assert "data:image/png;base64," in html


class TestQrGeneratorRoundTrip:
    """Tests for encoding/decoding round trip."""

    def test_payload_base64_roundtrip(self):
        """Payload can be base64 encoded and decoded."""
        qr = QrGenerator(master_secret=b"\x01\x02\x03" + b"\x00" * 29)

        # Get the base64 payload
        payload_bytes = qr._create_payload()
        payload_b64 = base64.b64encode(payload_bytes).decode("ascii")

        # Decode and parse
        decoded = base64.b64decode(payload_b64)
        parsed = QrPayload().parse(decoded)

        assert parsed.master_secret == b"\x01\x02\x03" + b"\x00" * 29
