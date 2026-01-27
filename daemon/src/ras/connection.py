"""Connection info and QR code generation."""

import json
import secrets
from dataclasses import dataclass, field
from io import BytesIO
from typing import Self

import qrcode


def _generate_session_id() -> str:
    """Generate a random session ID."""
    return secrets.token_hex(8)


@dataclass
class ConnectionInfo:
    """Connection information for pairing."""

    ip: str
    port: int
    session_id: str = field(default_factory=_generate_session_id)
    version: int = 1

    def to_dict(self) -> dict:
        """Serialize to dictionary."""
        return {
            "version": self.version,
            "ip": self.ip,
            "port": self.port,
            "session": self.session_id,
        }

    def to_json(self) -> str:
        """Serialize to JSON string."""
        return json.dumps(self.to_dict())

    @classmethod
    def from_dict(cls, d: dict) -> Self:
        """Deserialize from dictionary."""
        return cls(
            ip=d["ip"],
            port=d["port"],
            session_id=d["session"],
            version=d.get("version", 1),
        )

    @classmethod
    def from_json(cls, j: str) -> Self:
        """Deserialize from JSON string."""
        return cls.from_dict(json.loads(j))

    def generate_qr(self) -> bytes:
        """Generate QR code as PNG bytes."""
        qr = qrcode.make(self.to_json())
        buffer = BytesIO()
        qr.save(buffer, format="PNG")
        return buffer.getvalue()

    def print_qr_terminal(self) -> None:
        """Print QR code to terminal using ASCII."""
        qr = qrcode.QRCode()
        qr.add_data(self.to_json())
        qr.print_ascii()
