"""Secure device storage for RAS daemon.

This module provides:
- Device: Paired device with secret
- DeviceStorage: Secure file-based storage

Security features:
- File permissions (600 for files, 700 for directory)
- Device ID validation (prevent path traversal)
- Base64 encoding for binary secrets
"""

import base64
import json
import os
import re
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from ras.errors import StorageError

__all__ = [
    "Device",
    "DeviceStorage",
    "StorageError",
]

# Valid device ID pattern: alphanumeric, hyphens, underscores
DEVICE_ID_PATTERN = re.compile(r"^[a-zA-Z0-9_-]+$")


@dataclass
class Device:
    """Paired device information.

    Attributes:
        id: Unique device identifier.
        name: Human-readable device name.
        secret: 32-byte shared secret.
        paired_at: When device was paired.
        last_seen: When device was last connected.
    """

    id: str
    name: str
    secret: bytes
    paired_at: datetime = field(default_factory=datetime.now)
    last_seen: datetime | None = None

    def to_dict(self) -> dict:
        """Convert to dict for JSON serialization."""
        return {
            "id": self.id,
            "name": self.name,
            "secret": base64.b64encode(self.secret).decode(),
            "paired_at": self.paired_at.isoformat(),
            "last_seen": self.last_seen.isoformat() if self.last_seen else None,
        }

    @classmethod
    def from_dict(cls, d: dict) -> "Device":
        """Create from dict."""
        return cls(
            id=d["id"],
            name=d["name"],
            secret=base64.b64decode(d["secret"]),
            paired_at=datetime.fromisoformat(d["paired_at"]),
            last_seen=datetime.fromisoformat(d["last_seen"]) if d.get("last_seen") else None,
        )


class DeviceStorage:
    """Secure file-based device storage.

    Stores device information in JSON files with restricted permissions.

    Attributes:
        directory: Storage directory path.
    """

    def __init__(self, directory: Path) -> None:
        """Initialize storage.

        Creates directory if it doesn't exist, with secure permissions.

        Args:
            directory: Path to storage directory.
        """
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        os.chmod(self.directory, 0o700)

    def _validate_device_id(self, device_id: str) -> None:
        """Validate device ID to prevent path traversal.

        Args:
            device_id: Device identifier to validate.

        Raises:
            StorageError: If device ID is invalid.
        """
        if not DEVICE_ID_PATTERN.match(device_id):
            raise StorageError(f"Invalid device ID: {device_id}")

    def _path(self, device_id: str) -> Path:
        """Get file path for device.

        Args:
            device_id: Device identifier.

        Returns:
            Path to device file.
        """
        return self.directory / f"device-{device_id}.json"

    def save(self, device: Device) -> None:
        """Save device to disk with secure permissions.

        Args:
            device: Device to save.

        Raises:
            StorageError: If device ID is invalid.
        """
        self._validate_device_id(device.id)

        path = self._path(device.id)
        data = json.dumps(device.to_dict(), indent=2)

        # Write with restricted permissions (owner read/write only)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            os.write(fd, data.encode())
        finally:
            os.close(fd)

    def load(self, device_id: str) -> Device | None:
        """Load device from disk.

        Args:
            device_id: Device identifier.

        Returns:
            Device if found, None otherwise.
        """
        path = self._path(device_id)
        if not path.exists():
            return None
        data = json.loads(path.read_text())
        return Device.from_dict(data)

    def list(self) -> list[Device]:
        """List all devices.

        Returns:
            List of all stored devices.
        """
        devices = []
        for path in self.directory.glob("device-*.json"):
            data = json.loads(path.read_text())
            devices.append(Device.from_dict(data))
        return devices

    def delete(self, device_id: str) -> bool:
        """Delete device.

        Args:
            device_id: Device identifier.

        Returns:
            True if deleted, False if not found.
        """
        path = self._path(device_id)
        if path.exists():
            path.unlink()
            return True
        return False
