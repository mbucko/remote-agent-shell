"""Persist paired devices to JSON file."""

import json
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)


@dataclass
class PairedDevice:
    """A paired phone device."""

    device_id: str
    name: str
    public_key: str  # Base64 encoded
    paired_at: str  # ISO format
    last_seen: Optional[str] = None

    def update_last_seen(self) -> None:
        """Update last_seen to current UTC time."""
        self.last_seen = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "device_id": self.device_id,
            "name": self.name,
            "public_key": self.public_key,
            "paired_at": self.paired_at,
            "last_seen": self.last_seen,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "PairedDevice":
        """Create from dictionary."""
        return cls(
            device_id=d["device_id"],
            name=d["name"],
            public_key=d["public_key"],
            paired_at=d["paired_at"],
            last_seen=d.get("last_seen"),
        )


class JsonDeviceStore:
    """JSON file-based device storage."""

    def __init__(self, path: Path):
        """Initialize device store.

        Args:
            path: Path to JSON file for persistence.
        """
        self.path = path
        self._devices: dict[str, PairedDevice] = {}

    async def load(self) -> None:
        """Load devices from file."""
        if not self.path.exists():
            logger.debug(f"No devices file at {self.path}")
            return

        try:
            with open(self.path) as f:
                data = json.load(f)

            for item in data.get("devices", []):
                try:
                    device = PairedDevice.from_dict(item)
                    self._devices[device.device_id] = device
                except (KeyError, TypeError) as e:
                    logger.warning(f"Skipping malformed device entry: {e}")

            logger.debug(f"Loaded {len(self._devices)} devices")

        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse devices file: {e}")
        except Exception as e:
            logger.error(f"Failed to load devices: {e}")

    async def save(self) -> None:
        """Save devices to file."""
        try:
            # Ensure parent directory exists
            self.path.parent.mkdir(parents=True, exist_ok=True)

            data = {"devices": [d.to_dict() for d in self._devices.values()]}

            with open(self.path, "w") as f:
                json.dump(data, f, indent=2)

            logger.debug(f"Saved {len(self._devices)} devices")

        except Exception as e:
            logger.error(f"Failed to save devices: {e}")

    async def add(self, device: PairedDevice) -> None:
        """Add or update a device."""
        self._devices[device.device_id] = device
        await self.save()

    async def remove(self, device_id: str) -> bool:
        """Remove a device.

        Returns:
            True if device was removed, False if not found.
        """
        if device_id in self._devices:
            del self._devices[device_id]
            await self.save()
            return True
        return False

    def get(self, device_id: str) -> Optional[PairedDevice]:
        """Get device by ID."""
        return self._devices.get(device_id)

    def is_paired(self, device_id: str) -> bool:
        """Check if device is paired."""
        return device_id in self._devices

    def all(self) -> list[PairedDevice]:
        """Get all devices."""
        return list(self._devices.values())

    def __len__(self) -> int:
        return len(self._devices)

    def __contains__(self, device_id: str) -> bool:
        return device_id in self._devices
