"""Contract tests for QR code format between daemon and mobile clients.

These tests verify the EXACT format expected by Android/iOS apps.
The mobile apps expect: base64(protobuf(QrPayload))

QR codes contain ONLY master_secret. Everything else is derived:
- session_id: derived via HKDF with "session" info
- ntfy_topic: derived via SHA256
- daemon IP/port: discovered via mDNS or ntfy DISCOVER
"""

import base64

import pytest

from ras.crypto import derive_ntfy_topic, derive_session_id
from ras.proto.ras import QrPayload
from ras.pairing.qr_generator import QrGenerator


class TestQrCodeContract:
    """Contract tests ensuring QR code format matches mobile app expectations.

    The Android app (QrPayloadParser.kt) expects:
    1. QR code content is base64 encoded
    2. Decoded bytes are a valid protobuf QrPayload
    3. QrPayload has version=1
    4. QrPayload has master_secret (32 bytes)

    Everything else is derived from master_secret on the client side.
    """

    @pytest.fixture
    def sample_master_secret(self):
        """Sample master secret for testing."""
        return bytes.fromhex("00" * 32)

    def test_qr_content_is_base64_decodable(self, sample_master_secret):
        """QR-CONTRACT-01: QR content must be valid base64."""
        qr = QrGenerator(master_secret=sample_master_secret)
        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")

        decoded = base64.b64decode(qr_content)
        assert len(decoded) > 0

    def test_qr_content_is_valid_protobuf(self, sample_master_secret):
        """QR-CONTRACT-02: Decoded QR content must be valid QrPayload protobuf."""
        qr = QrGenerator(master_secret=sample_master_secret)
        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")

        decoded = base64.b64decode(qr_content)
        payload = QrPayload().parse(decoded)
        assert payload is not None

    def test_qr_payload_version_is_1(self, sample_master_secret):
        """QR-CONTRACT-03: QrPayload.version must be 1."""
        qr = QrGenerator(master_secret=sample_master_secret)
        payload_bytes = qr._create_payload()

        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)
        assert payload.version == 1

    def test_qr_payload_has_no_ip(self, sample_master_secret):
        """QR-CONTRACT-04: QrPayload.ip should be empty."""
        qr = QrGenerator(master_secret=sample_master_secret)
        payload_bytes = qr._create_payload()

        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)
        assert payload.ip == ""

    def test_qr_payload_has_no_session_id(self, sample_master_secret):
        """QR-CONTRACT-05: QrPayload.session_id should be empty (derived)."""
        qr = QrGenerator(master_secret=sample_master_secret)
        payload_bytes = qr._create_payload()

        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)
        assert payload.session_id == ""

    def test_qr_payload_has_no_ntfy_topic(self, sample_master_secret):
        """QR-CONTRACT-06: QrPayload.ntfy_topic should be empty (derived)."""
        qr = QrGenerator(master_secret=sample_master_secret)
        payload_bytes = qr._create_payload()

        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)
        assert payload.ntfy_topic == ""

    def test_qr_payload_master_secret_is_32_bytes(self, sample_master_secret):
        """QR-CONTRACT-07: QrPayload.master_secret must be exactly 32 bytes."""
        qr = QrGenerator(master_secret=sample_master_secret)
        payload_bytes = qr._create_payload()

        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)
        assert len(payload.master_secret) == 32


class TestDerivationContract:
    """Tests that derivation functions work correctly."""

    def test_session_id_derivation_is_deterministic(self):
        """SESSION-01: session_id derived from master_secret is deterministic."""
        master_secret = b"\x42" * 32

        id1 = derive_session_id(master_secret)
        id2 = derive_session_id(master_secret)

        assert id1 == id2
        assert len(id1) == 24  # 12 bytes = 24 hex chars

    def test_ntfy_topic_derivation_is_deterministic(self):
        """NTFY-01: ntfy_topic derived from master_secret is deterministic."""
        master_secret = b"\x42" * 32

        topic1 = derive_ntfy_topic(master_secret)
        topic2 = derive_ntfy_topic(master_secret)

        assert topic1 == topic2
        assert topic1.startswith("ras-")
        assert len(topic1) == 16

    def test_different_secrets_produce_different_ids(self):
        """SESSION-02: Different secrets produce different session IDs."""
        id1 = derive_session_id(b"\x00" * 32)
        id2 = derive_session_id(b"\xff" * 32)
        assert id1 != id2


class TestQrCodeNotJson:
    """Tests that explicitly verify QR code is NOT JSON."""

    def test_qr_content_is_not_json(self):
        """QR-CONTRACT-08: QR content must NOT be JSON."""
        import json

        qr = QrGenerator(master_secret=b"\x00" * 32)
        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")
        decoded = base64.b64decode(qr_content)

        try:
            json.loads(decoded)
            pytest.fail("QR content decoded to JSON - should be protobuf!")
        except (json.JSONDecodeError, UnicodeDecodeError):
            pass


class TestApiToQrIntegration:
    """Integration tests verifying API response → QR generation → Phone parsing."""

    @pytest.mark.integration
    @pytest.mark.asyncio
    async def test_api_response_produces_valid_qr(self):
        """QR-INT-01: API response data produces valid QR for mobile apps."""
        from ras.config import Config, DaemonConfig
        from ras.daemon import Daemon
        from pathlib import Path
        import tempfile
        import aiohttp

        with tempfile.TemporaryDirectory() as tmp:
            config = Config()
            config.daemon = DaemonConfig(
                devices_file=str(Path(tmp) / "devices.json"),
                sessions_file=str(Path(tmp) / "sessions.json"),
            )
            config.port = 0
            config.bind_address = "127.0.0.1"

            daemon = Daemon(config=config)
            try:
                await daemon.start()
                port = daemon._get_server_port()

                async with aiohttp.ClientSession() as http:
                    async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                        assert resp.status == 200
                        data = await resp.json()
                        qr_data = data["qr_data"]

                    # Only master_secret in QR data
                    assert "master_secret" in qr_data
                    assert "session_id" not in qr_data
                    assert "ntfy_topic" not in qr_data

                    # Create QR generator
                    qr = QrGenerator(
                        master_secret=bytes.fromhex(qr_data["master_secret"]),
                    )

                    payload_bytes = qr._create_payload()
                    qr_content = base64.b64encode(payload_bytes).decode("ascii")

                    decoded = base64.b64decode(qr_content)
                    payload = QrPayload().parse(decoded)

                    assert payload.version == 1
                    assert payload.master_secret == bytes.fromhex(
                        qr_data["master_secret"]
                    )
                    assert payload.session_id == ""
                    assert payload.ntfy_topic == ""

            finally:
                await daemon.stop()


class TestMobileClientSimulation:
    """Tests that simulate exactly what mobile clients do."""

    def _simulate_android_parse(self, qr_content: str) -> dict:
        """Simulate Android's QrPayloadParser.parse() method."""
        try:
            decoded = base64.b64decode(qr_content.strip())
        except Exception:
            raise ValueError("INVALID_BASE64")

        try:
            payload = QrPayload().parse(decoded)
        except Exception:
            raise ValueError("PARSE_ERROR")

        if payload.version != 1:
            raise ValueError("UNSUPPORTED_VERSION")

        if len(payload.master_secret) != 32:
            raise ValueError("INVALID_SECRET_LENGTH")

        # Derive session_id and ntfy_topic from master_secret
        session_id = derive_session_id(payload.master_secret)
        ntfy_topic = derive_ntfy_topic(payload.master_secret)

        return {
            "version": payload.version,
            "master_secret": payload.master_secret,
            "session_id": session_id,
            "ntfy_topic": ntfy_topic,
        }

    def test_android_parser_accepts_valid_qr(self):
        """QR-MOBILE-01: Android parser accepts correctly formatted QR."""
        qr = QrGenerator(master_secret=b"\x42" * 32)
        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")

        result = self._simulate_android_parse(qr_content)

        assert result["master_secret"] == b"\x42" * 32
        assert len(result["session_id"]) == 24
        assert result["ntfy_topic"].startswith("ras-")

    def test_android_parser_rejects_json(self):
        """QR-MOBILE-02: Android parser rejects JSON format."""
        import json

        json_qr = json.dumps({"master_secret": "00" * 32})

        with pytest.raises(ValueError):
            self._simulate_android_parse(json_qr)
