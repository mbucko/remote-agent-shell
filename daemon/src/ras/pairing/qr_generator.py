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
    - Daemon IP address
    - Daemon port
    - Master secret (32 bytes)
    - Session ID
    - ntfy topic
    - Tailscale IP/port (optional)
    """

    def __init__(
        self,
        ip: str,
        port: int,
        master_secret: bytes,
        session_id: str,
        ntfy_topic: str,
        tailscale_ip: str | None = None,
        tailscale_port: int | None = None,
    ):
        """Initialize QR generator.

        Args:
            ip: Daemon's public IP address (IPv4 or IPv6).
            port: Daemon's HTTP signaling port.
            master_secret: 32-byte master secret.
            session_id: Pairing session ID.
            ntfy_topic: ntfy topic for IP change notifications.
            tailscale_ip: Optional Tailscale IP for direct VPN connection.
            tailscale_port: Optional Tailscale port (defaults to 9876).
        """
        self.ip = ip
        self.port = port
        self.master_secret = master_secret
        self.session_id = session_id
        self.ntfy_topic = ntfy_topic
        self.tailscale_ip = tailscale_ip
        self.tailscale_port = tailscale_port

    def _create_payload(self) -> bytes:
        """Create protobuf payload.

        Returns:
            Serialized protobuf bytes.
        """
        payload = QrPayload(
            version=1,
            ip=self.ip,
            port=self.port,
            master_secret=self.master_secret,
            session_id=self.session_id,
            ntfy_topic=self.ntfy_topic,
            tailscale_ip=self.tailscale_ip or "",
            tailscale_port=self.tailscale_port or 0,
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
    <p>Session: {self.session_id[:8]}...</p>
</body>
</html>
"""
