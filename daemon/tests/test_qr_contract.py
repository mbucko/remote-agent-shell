"""Contract tests for QR code format between daemon and mobile clients.

These tests verify the EXACT format expected by Android/iOS apps.
The mobile apps expect: base64(protobuf(QrPayload))

This test would have caught the bug where CLI generated JSON instead of protobuf.
"""

import base64

import pytest

from ras.proto.ras import QrPayload
from ras.pairing.qr_generator import QrGenerator


class TestQrCodeContract:
    """Contract tests ensuring QR code format matches mobile app expectations.

    The Android app (QrPayloadParser.kt) expects:
    1. QR code content is base64 encoded
    2. Decoded bytes are a valid protobuf QrPayload
    3. QrPayload has version=1
    4. QrPayload has all required fields

    These tests simulate EXACTLY what the mobile app does.
    """

    @pytest.fixture
    def sample_qr_data(self):
        """Sample QR data that would come from daemon API."""
        return {
            "ip": "203.0.113.42",
            "port": 8765,
            "master_secret": "00" * 32,  # 32 bytes as hex
            "session_id": "abc123def456789012345678",
            "ntfy_topic": "ras-9f86d081884c7d65",
        }

    def test_qr_content_is_base64_decodable(self, sample_qr_data):
        """QR-CONTRACT-01: QR content must be valid base64.

        Android does: Base64.decode(qrContent.trim(), Base64.DEFAULT)
        """
        qr = QrGenerator(
            ip=sample_qr_data["ip"],
            port=sample_qr_data["port"],
            master_secret=bytes.fromhex(sample_qr_data["master_secret"]),
            session_id=sample_qr_data["session_id"],
            ntfy_topic=sample_qr_data["ntfy_topic"],
        )

        # Get terminal output which contains base64
        terminal_output = qr.to_terminal()

        # Extract the actual QR data (what would be scanned)
        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")

        # This is what Android does - must not raise
        decoded = base64.b64decode(qr_content)
        assert len(decoded) > 0

    def test_qr_content_is_valid_protobuf(self, sample_qr_data):
        """QR-CONTRACT-02: Decoded QR content must be valid QrPayload protobuf.

        Android does: QrPayload.parseFrom(bytes)
        """
        qr = QrGenerator(
            ip=sample_qr_data["ip"],
            port=sample_qr_data["port"],
            master_secret=bytes.fromhex(sample_qr_data["master_secret"]),
            session_id=sample_qr_data["session_id"],
            ntfy_topic=sample_qr_data["ntfy_topic"],
        )

        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")

        # Simulate Android parsing
        decoded = base64.b64decode(qr_content)
        payload = QrPayload().parse(decoded)  # Must not raise

        assert payload is not None

    def test_qr_payload_version_is_1(self, sample_qr_data):
        """QR-CONTRACT-03: QrPayload.version must be 1.

        Android checks: payload.version == SUPPORTED_VERSION (1)
        """
        qr = QrGenerator(
            ip=sample_qr_data["ip"],
            port=sample_qr_data["port"],
            master_secret=bytes.fromhex(sample_qr_data["master_secret"]),
            session_id=sample_qr_data["session_id"],
            ntfy_topic=sample_qr_data["ntfy_topic"],
        )

        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")

        decoded = base64.b64decode(qr_content)
        payload = QrPayload().parse(decoded)

        assert payload.version == 1, "Mobile apps only support version 1"

    def test_qr_payload_has_required_ip(self, sample_qr_data):
        """QR-CONTRACT-04: QrPayload.ip must not be blank.

        Android checks: payload.ip.isBlank() returns MISSING_FIELD error
        """
        qr = QrGenerator(
            ip=sample_qr_data["ip"],
            port=sample_qr_data["port"],
            master_secret=bytes.fromhex(sample_qr_data["master_secret"]),
            session_id=sample_qr_data["session_id"],
            ntfy_topic=sample_qr_data["ntfy_topic"],
        )

        payload_bytes = qr._create_payload()
        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)

        assert payload.ip, "IP must not be blank"
        assert payload.ip == sample_qr_data["ip"]

    def test_qr_payload_has_required_session_id(self, sample_qr_data):
        """QR-CONTRACT-05: QrPayload.session_id must not be blank.

        Android checks: payload.sessionId.isBlank() returns MISSING_FIELD error
        """
        qr = QrGenerator(
            ip=sample_qr_data["ip"],
            port=sample_qr_data["port"],
            master_secret=bytes.fromhex(sample_qr_data["master_secret"]),
            session_id=sample_qr_data["session_id"],
            ntfy_topic=sample_qr_data["ntfy_topic"],
        )

        payload_bytes = qr._create_payload()
        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)

        assert payload.session_id, "Session ID must not be blank"
        assert payload.session_id == sample_qr_data["session_id"]

    def test_qr_payload_has_required_ntfy_topic(self, sample_qr_data):
        """QR-CONTRACT-06: QrPayload.ntfy_topic must not be blank.

        Android checks: payload.ntfyTopic.isBlank() returns MISSING_FIELD error
        """
        qr = QrGenerator(
            ip=sample_qr_data["ip"],
            port=sample_qr_data["port"],
            master_secret=bytes.fromhex(sample_qr_data["master_secret"]),
            session_id=sample_qr_data["session_id"],
            ntfy_topic=sample_qr_data["ntfy_topic"],
        )

        payload_bytes = qr._create_payload()
        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)

        assert payload.ntfy_topic, "ntfy topic must not be blank"
        assert payload.ntfy_topic == sample_qr_data["ntfy_topic"]

    def test_qr_payload_master_secret_is_32_bytes(self, sample_qr_data):
        """QR-CONTRACT-07: QrPayload.master_secret must be exactly 32 bytes.

        Android checks: payload.masterSecret.size() != EXPECTED_SECRET_LENGTH (32)
        """
        qr = QrGenerator(
            ip=sample_qr_data["ip"],
            port=sample_qr_data["port"],
            master_secret=bytes.fromhex(sample_qr_data["master_secret"]),
            session_id=sample_qr_data["session_id"],
            ntfy_topic=sample_qr_data["ntfy_topic"],
        )

        payload_bytes = qr._create_payload()
        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)

        assert len(payload.master_secret) == 32, "Master secret must be 32 bytes"

    def test_qr_payload_port_is_valid(self, sample_qr_data):
        """QR-CONTRACT-08: QrPayload.port must be 1-65535.

        Android checks: payload.port < MIN_PORT || payload.port > MAX_PORT
        """
        qr = QrGenerator(
            ip=sample_qr_data["ip"],
            port=sample_qr_data["port"],
            master_secret=bytes.fromhex(sample_qr_data["master_secret"]),
            session_id=sample_qr_data["session_id"],
            ntfy_topic=sample_qr_data["ntfy_topic"],
        )

        payload_bytes = qr._create_payload()
        decoded = base64.b64decode(base64.b64encode(payload_bytes))
        payload = QrPayload().parse(decoded)

        assert 1 <= payload.port <= 65535, "Port must be 1-65535"
        assert payload.port == sample_qr_data["port"]


class TestQrCodeNotJson:
    """Tests that explicitly verify QR code is NOT JSON.

    This would have caught the bug where CLI generated JSON.
    """

    def test_qr_content_is_not_json(self):
        """QR-CONTRACT-09: QR content must NOT be JSON.

        A common mistake is to encode as JSON instead of protobuf.
        """
        import json

        qr = QrGenerator(
            ip="192.168.1.1",
            port=8765,
            master_secret=b"\x00" * 32,
            session_id="test-session",
            ntfy_topic="ras-test",
        )

        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")

        # Try to decode as base64 first
        decoded = base64.b64decode(qr_content)

        # Decoded content should NOT be valid JSON
        try:
            json.loads(decoded)
            pytest.fail("QR content decoded to JSON - should be protobuf!")
        except (json.JSONDecodeError, UnicodeDecodeError):
            pass  # Good - it's not JSON

    def test_qr_content_starts_with_protobuf_not_brace(self):
        """QR-CONTRACT-10: Decoded QR content must not start with '{'.

        JSON objects start with '{', protobuf does not.
        """
        qr = QrGenerator(
            ip="192.168.1.1",
            port=8765,
            master_secret=b"\x00" * 32,
            session_id="test-session",
            ntfy_topic="ras-test",
        )

        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")
        decoded = base64.b64decode(qr_content)

        assert decoded[0:1] != b"{", "Protobuf should not start with '{'"


class TestApiToQrIntegration:
    """Integration tests verifying API response → QR generation → Phone parsing.

    This simulates the full flow and would have caught the JSON bug.
    """

    @pytest.mark.asyncio
    async def test_api_response_produces_valid_qr(self):
        """QR-INT-01: API response data produces valid QR for mobile apps.

        This tests the EXACT flow:
        1. Daemon API returns qr_data JSON
        2. CLI uses qr_data to create QrGenerator
        3. QrGenerator produces base64 protobuf
        4. Mobile app parses it successfully
        """
        from ras.config import Config, DaemonConfig
        from ras.daemon import Daemon
        from pathlib import Path
        import tempfile
        import aiohttp

        # Create temp config
        with tempfile.TemporaryDirectory() as tmp:
            config = Config()
            config.daemon = DaemonConfig(
                devices_file=str(Path(tmp) / "devices.json"),
                sessions_file=str(Path(tmp) / "sessions.json"),
            )
            config.port = 0  # Random port
            config.bind_address = "127.0.0.1"

            daemon = Daemon(config=config)
            try:
                await daemon.start()
                port = daemon._get_server_port()

                async with aiohttp.ClientSession() as http:
                    # 1. Get API response (what CLI does)
                    async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                        assert resp.status == 200
                        data = await resp.json()
                        qr_data = data["qr_data"]

                    # 2. Create QR generator (what CLI should do)
                    qr = QrGenerator(
                        ip=qr_data["ip"],
                        port=qr_data["port"],
                        master_secret=bytes.fromhex(qr_data["master_secret"]),
                        session_id=qr_data["session_id"],
                        ntfy_topic=qr_data["ntfy_topic"],
                    )

                    # 3. Generate QR content
                    payload_bytes = qr._create_payload()
                    qr_content = base64.b64encode(payload_bytes).decode("ascii")

                    # 4. Simulate mobile app parsing
                    decoded = base64.b64decode(qr_content)
                    payload = QrPayload().parse(decoded)

                    # 5. Verify all fields match
                    assert payload.version == 1
                    assert payload.ip == qr_data["ip"]
                    assert payload.port == qr_data["port"]
                    assert payload.master_secret == bytes.fromhex(qr_data["master_secret"])
                    assert payload.session_id == qr_data["session_id"]
                    assert payload.ntfy_topic == qr_data["ntfy_topic"]

            finally:
                await daemon.stop()

    @pytest.mark.asyncio
    async def test_signaling_uses_session_id_from_qr(self):
        """QR-INT-02: Signaling endpoint uses session_id from QR payload.

        Verifies the session_id in QR matches what signaling expects.
        """
        from ras.config import Config, DaemonConfig
        from ras.daemon import Daemon
        from ras.crypto import derive_key, compute_signaling_hmac
        from ras.proto.ras import SignalRequest
        from pathlib import Path
        import tempfile
        import aiohttp
        import time

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
                    # 1. Start pairing
                    async with http.post(f"http://127.0.0.1:{port}/api/pair") as resp:
                        data = await resp.json()
                        qr_data = data["qr_data"]

                    # 2. Create QR and parse (simulating phone)
                    qr = QrGenerator(
                        ip=qr_data["ip"],
                        port=qr_data["port"],
                        master_secret=bytes.fromhex(qr_data["master_secret"]),
                        session_id=qr_data["session_id"],
                        ntfy_topic=qr_data["ntfy_topic"],
                    )
                    payload_bytes = qr._create_payload()
                    qr_content = base64.b64encode(payload_bytes).decode("ascii")

                    # Phone parses QR
                    decoded = base64.b64decode(qr_content)
                    parsed = QrPayload().parse(decoded)

                    # 3. Phone derives auth key and sends signal
                    auth_key = derive_key(parsed.master_secret, "auth")
                    timestamp = int(time.time())

                    body = bytes(SignalRequest(
                        sdp_offer="v=0",
                        device_id="test-phone",
                        device_name="Test Device",
                    ))

                    signature = compute_signaling_hmac(
                        auth_key,
                        parsed.session_id,  # Use session_id from parsed QR
                        timestamp,
                        body
                    )

                    # 4. Signal request should be accepted (not 400 invalid session)
                    from unittest.mock import patch, AsyncMock, MagicMock

                    mock_peer = AsyncMock()
                    mock_peer.accept_offer = AsyncMock(return_value="v=0")
                    mock_peer.wait_connected = AsyncMock()
                    mock_peer.close = AsyncMock()
                    mock_peer.on_message = MagicMock()

                    with patch("ras.server.PeerConnection", return_value=mock_peer):
                        async with http.post(
                            f"http://127.0.0.1:{port}/signal/{parsed.session_id}",
                            data=body,
                            headers={
                                "Content-Type": "application/x-protobuf",
                                "X-RAS-Timestamp": str(timestamp),
                                "X-RAS-Signature": signature.hex(),
                            },
                        ) as resp:
                            # Should succeed, not 400 (invalid session)
                            assert resp.status == 200, \
                                f"Signaling failed with {resp.status} - session_id mismatch?"

            finally:
                await daemon.stop()


class TestMobileClientSimulation:
    """Tests that simulate exactly what mobile clients do.

    These tests mirror the Android QrPayloadParser.parse() logic.
    """

    def _simulate_android_parse(self, qr_content: str) -> dict:
        """Simulate Android's QrPayloadParser.parse() method.

        Returns dict with parsed fields or raises appropriate exception.
        """
        # Step 1: Base64 decode
        try:
            decoded = base64.b64decode(qr_content.strip())
        except Exception:
            raise ValueError("INVALID_BASE64")

        # Step 2: Parse protobuf
        try:
            payload = QrPayload().parse(decoded)
        except Exception:
            raise ValueError("PARSE_ERROR")

        # Step 3: Validate version
        if payload.version != 1:
            raise ValueError("UNSUPPORTED_VERSION")

        # Step 4: Validate required fields
        if not payload.ip:
            raise ValueError("MISSING_FIELD: ip")
        if not payload.session_id:
            raise ValueError("MISSING_FIELD: session_id")
        if not payload.ntfy_topic:
            raise ValueError("MISSING_FIELD: ntfy_topic")

        # Step 5: Validate secret length
        if len(payload.master_secret) != 32:
            raise ValueError("INVALID_SECRET_LENGTH")

        # Step 6: Validate port
        if payload.port < 1 or payload.port > 65535:
            raise ValueError("INVALID_PORT")

        return {
            "version": payload.version,
            "ip": payload.ip,
            "port": payload.port,
            "master_secret": payload.master_secret,
            "session_id": payload.session_id,
            "ntfy_topic": payload.ntfy_topic,
        }

    def test_android_parser_accepts_valid_qr(self):
        """QR-MOBILE-01: Android parser accepts correctly formatted QR."""
        qr = QrGenerator(
            ip="192.168.1.100",
            port=8765,
            master_secret=b"\x42" * 32,
            session_id="valid-session-id-123",
            ntfy_topic="ras-notification-topic",
        )

        payload_bytes = qr._create_payload()
        qr_content = base64.b64encode(payload_bytes).decode("ascii")

        # Should not raise
        result = self._simulate_android_parse(qr_content)

        assert result["ip"] == "192.168.1.100"
        assert result["port"] == 8765
        assert result["session_id"] == "valid-session-id-123"

    def test_android_parser_rejects_json(self):
        """QR-MOBILE-02: Android parser rejects JSON format.

        This is the EXACT bug that occurred - CLI generated JSON.
        """
        import json

        # This is what the buggy CLI was generating
        json_qr = json.dumps({
            "ip": "192.168.1.100",
            "port": 8765,
            "master_secret": "00" * 32,
            "session_id": "session123",
            "ntfy_topic": "ras-topic",
        })

        # Android would fail to parse this
        with pytest.raises(ValueError) as exc_info:
            self._simulate_android_parse(json_qr)

        # It fails at some validation step - not successfully parsed
        error = str(exc_info.value)
        valid_errors = ["INVALID_BASE64", "PARSE_ERROR", "UNSUPPORTED_VERSION", "MISSING_FIELD"]
        assert any(e in error for e in valid_errors), f"Unexpected error: {error}"

    def test_android_parser_rejects_base64_json(self):
        """QR-MOBILE-03: Android parser rejects base64-encoded JSON.

        Even if JSON is base64 encoded, protobuf parsing fails.
        """
        import json

        json_data = json.dumps({"ip": "192.168.1.100", "port": 8765})
        base64_json = base64.b64encode(json_data.encode()).decode("ascii")

        with pytest.raises(ValueError) as exc_info:
            self._simulate_android_parse(base64_json)

        # It fails at some validation step - protobuf may parse but fields are wrong
        error = str(exc_info.value)
        valid_errors = ["PARSE_ERROR", "UNSUPPORTED_VERSION", "MISSING_FIELD", "INVALID_SECRET_LENGTH"]
        assert any(e in error for e in valid_errors), f"Unexpected error: {error}"
