"""QR code generation for pairing.

Generates QR codes containing the pairing payload (protobuf encoded,
then base64 encoded) for display in terminal, browser, or as PNG file.
"""

import base64
import io

import qrcode
from qrcode.main import QRCode

from ras.proto.ras import QrPayload


class QrGenerator:
    """Generate QR codes for pairing.

    The QR code contains a base64-encoded protobuf payload with:
    - Protocol version (1)
    - Master secret (32 bytes)

    Everything else is derived from master_secret:
    - session_id: derived via HKDF
    - ntfy_topic: derived via SHA256

    Daemon IP/port are discovered dynamically via mDNS or ntfy DISCOVER.
    """

    def __init__(
        self,
        master_secret: bytes,
    ):
        """Initialize QR generator.

        Args:
            master_secret: 32-byte master secret.
        """
        self.master_secret = master_secret

    def _create_payload(self) -> bytes:
        """Create protobuf payload.

        Returns:
            Serialized protobuf bytes.
        """
        # Only master_secret in QR code - everything else derived
        payload = QrPayload(
            version=1,
            ip="",
            port=0,
            master_secret=self.master_secret,
            session_id="",  # Derived from master_secret
            ntfy_topic="",  # Derived from master_secret
            tailscale_ip="",
            tailscale_port=0,
        )
        return bytes(payload)

    def _create_qr(self) -> QRCode:
        """Create QR code object.

        Returns:
            QRCode instance with payload data.
        """
        payload = self._create_payload()
        payload_b64 = base64.b64encode(payload).decode("ascii")

        qr = qrcode.QRCode(
            version=None,  # Auto-size
            error_correction=qrcode.constants.ERROR_CORRECT_M,
            box_size=10,
            border=4,
        )
        qr.add_data(payload_b64)
        qr.make(fit=True)
        return qr

    def to_terminal(self) -> str:
        """Generate ASCII art for terminal display.

        Returns:
            String with QR code using Unicode block characters.
        """
        qr = self._create_qr()

        # Use Unicode blocks for better quality
        output = io.StringIO()
        qr.print_ascii(out=output, invert=True)
        return output.getvalue()

    def to_png(self, path: str) -> None:
        """Save QR code as PNG file.

        Args:
            path: Path to save PNG file.
        """
        qr = self._create_qr()
        img = qr.make_image(fill_color="black", back_color="white")
        img.save(path)

    def to_html(self) -> str:
        """Generate HTML with embedded QR code.

        Returns:
            Complete HTML document with embedded QR code image.
        """
        qr = self._create_qr()
        img = qr.make_image(fill_color="black", back_color="white")

        buffer = io.BytesIO()
        img.save(buffer, format="PNG")
        img_b64 = base64.b64encode(buffer.getvalue()).decode("ascii")

        return f"""<!DOCTYPE html>
<html>
<head>
    <title>RemoteAgentShell Pairing</title>
    <style>
        body {{
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            height: 100vh;
            margin: 0;
            background: #1a1a1a;
            color: #fff;
            font-family: system-ui, sans-serif;
        }}
        h1 {{ margin-bottom: 20px; }}
        img {{ border: 10px solid white; border-radius: 10px; }}
        p {{ margin-top: 20px; color: #888; }}
    </style>
</head>
<body>
    <h1>Scan to Pair</h1>
    <img src="data:image/png;base64,{img_b64}" alt="QR Code">
    <p>Scan with RemoteAgentShell app</p>
</body>
</html>
"""
