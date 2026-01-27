"""Connection info and QR code generation."""

import base64
import json
import secrets
from dataclasses import dataclass, field
from io import BytesIO
from typing import Self

import qrcode

from ras.crypto import derive_keys


def _generate_session_id() -> str:
    """Generate a random session ID."""
    return secrets.token_hex(8)


@dataclass
class ConnectionInfo:
    """Connection information for pairing.

    Attributes:
        ip: Server IP address.
        port: Server port.
        session_id: Unique session identifier.
        secret: Optional 32-byte shared secret for encryption.
        version: Protocol version.
    """

    ip: str
    port: int
    session_id: str = field(default_factory=_generate_session_id)
    secret: bytes | None = None
    version: int = 1

    @property
    def topic(self) -> str | None:
        """Derive ntfy topic from secret.

        Returns:
            12-char hex topic if secret is set, None otherwise.
        """
        if self.secret is None:
            return None
        return derive_keys(self.secret).topic

    def to_dict(self) -> dict:
        """Serialize to dictionary."""
        d = {
            "version": self.version,
            "ip": self.ip,
            "port": self.port,
            "session": self.session_id,
        }
        if self.secret is not None:
            d["secret"] = base64.b64encode(self.secret).decode()
            d["topic"] = self.topic
        return d

    def to_json(self) -> str:
        """Serialize to JSON string."""
        return json.dumps(self.to_dict())

    @classmethod
    def from_dict(cls, d: dict) -> Self:
        """Deserialize from dictionary."""
        secret = None
        if "secret" in d:
            secret = base64.b64decode(d["secret"])
        return cls(
            ip=d["ip"],
            port=d["port"],
            session_id=d["session"],
            secret=secret,
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
