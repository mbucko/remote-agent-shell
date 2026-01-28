"""Tests for device store module."""

import base64
import json
from pathlib import Path

import pytest

from ras.device_store import JsonDeviceStore, PairedDevice


# Test master_secret for consistent testing
TEST_SECRET = b"\x00" * 32
TEST_SECRET_B64 = base64.b64encode(TEST_SECRET).decode("ascii")


class TestPairedDevice:
    """Tests for PairedDevice dataclass."""

    def test_update_last_seen(self):
        """Updates last_seen timestamp."""
        device = PairedDevice(
            device_id="device123",
            name="Test Phone",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )
        assert device.last_seen is None

        device.update_last_seen()

        assert device.last_seen is not None
        assert device.last_seen.endswith("Z")

    def test_to_dict(self):
        """Converts to dictionary."""
        device = PairedDevice(
            device_id="device123",
            name="Test Phone",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
            last_seen="2024-01-02T00:00:00Z",
        )

        d = device.to_dict()

        assert d["device_id"] == "device123"
        assert d["name"] == "Test Phone"
        assert d["master_secret"] == TEST_SECRET_B64
        assert d["paired_at"] == "2024-01-01T00:00:00Z"
        assert d["last_seen"] == "2024-01-02T00:00:00Z"

    def test_from_dict(self):
        """Creates from dictionary."""
        d = {
            "device_id": "device123",
            "name": "Test Phone",
            "master_secret": TEST_SECRET_B64,
            "paired_at": "2024-01-01T00:00:00Z",
            "last_seen": "2024-01-02T00:00:00Z",
        }

        device = PairedDevice.from_dict(d)

        assert device.device_id == "device123"
        assert device.name == "Test Phone"
        assert device.master_secret == TEST_SECRET
        assert device.last_seen == "2024-01-02T00:00:00Z"

    def test_from_dict_without_last_seen(self):
        """Creates from dictionary without last_seen."""
        d = {
            "device_id": "device123",
            "name": "Test Phone",
            "master_secret": TEST_SECRET_B64,
            "paired_at": "2024-01-01T00:00:00Z",
        }

        device = PairedDevice.from_dict(d)

        assert device.last_seen is None


class TestJsonDeviceStore:
    """Tests for JsonDeviceStore."""

    @pytest.fixture
    def store_path(self, tmp_path: Path) -> Path:
        """Create a temporary store path."""
        return tmp_path / "devices.json"

    @pytest.fixture
    def store(self, store_path: Path) -> JsonDeviceStore:
        """Create a device store."""
        return JsonDeviceStore(store_path)

    # DS01: Load non-existent file
    @pytest.mark.asyncio
    async def test_load_nonexistent_file(self, store: JsonDeviceStore):
        """Load from missing file returns empty store."""
        await store.load()

        assert len(store) == 0

    # DS02: Load valid file
    @pytest.mark.asyncio
    async def test_load_valid_file(self, store: JsonDeviceStore, store_path: Path):
        """Load file with devices."""
        data = {
            "devices": [
                {
                    "device_id": "device1",
                    "name": "Phone 1",
                    "master_secret": TEST_SECRET_B64,
                    "paired_at": "2024-01-01T00:00:00Z",
                },
                {
                    "device_id": "device2",
                    "name": "Phone 2",
                    "master_secret": TEST_SECRET_B64,
                    "paired_at": "2024-01-02T00:00:00Z",
                },
            ]
        }
        store_path.write_text(json.dumps(data))

        await store.load()

        assert len(store) == 2
        assert store.get("device1") is not None
        assert store.get("device2") is not None

    # DS03: Load corrupted JSON
    @pytest.mark.asyncio
    async def test_load_corrupted_json(self, store: JsonDeviceStore, store_path: Path):
        """Load malformed JSON returns empty store."""
        store_path.write_text("not valid json {{{")

        await store.load()

        assert len(store) == 0

    # DS04: Load partial corruption - skip malformed devices
    @pytest.mark.asyncio
    async def test_load_partial_corruption(
        self, store: JsonDeviceStore, store_path: Path
    ):
        """Skip malformed devices, load valid ones."""
        data = {
            "devices": [
                {
                    "device_id": "valid",
                    "name": "Valid Phone",
                    "master_secret": TEST_SECRET_B64,
                    "paired_at": "2024-01-01T00:00:00Z",
                },
                {"invalid": "missing required fields"},
                {
                    "device_id": "also_valid",
                    "name": "Also Valid",
                    "master_secret": TEST_SECRET_B64,
                    "paired_at": "2024-01-02T00:00:00Z",
                },
            ]
        }
        store_path.write_text(json.dumps(data))

        await store.load()

        assert len(store) == 2
        assert store.get("valid") is not None
        assert store.get("also_valid") is not None

    # DS05: Save new device
    @pytest.mark.asyncio
    async def test_save_new_device(self, store: JsonDeviceStore, store_path: Path):
        """Add device and save."""
        device = PairedDevice(
            device_id="new_device",
            name="New Phone",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )

        await store.add(device)

        # Verify file was written
        assert store_path.exists()
        data = json.loads(store_path.read_text())
        assert len(data["devices"]) == 1
        assert data["devices"][0]["device_id"] == "new_device"

    # DS06: Save update
    @pytest.mark.asyncio
    async def test_save_update(self, store: JsonDeviceStore, store_path: Path):
        """Update device and save."""
        device = PairedDevice(
            device_id="device1",
            name="Phone",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )
        await store.add(device)

        # Update last_seen
        device.update_last_seen()
        await store.save()

        # Verify file has updated timestamp
        data = json.loads(store_path.read_text())
        assert data["devices"][0]["last_seen"] is not None

    # DS07: Remove device
    @pytest.mark.asyncio
    async def test_remove_device(self, store: JsonDeviceStore, store_path: Path):
        """Remove device."""
        device = PairedDevice(
            device_id="to_remove",
            name="Phone",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )
        await store.add(device)
        assert len(store) == 1

        result = await store.remove("to_remove")

        assert result is True
        assert len(store) == 0
        data = json.loads(store_path.read_text())
        assert len(data["devices"]) == 0

    # DS08: Remove non-existent
    @pytest.mark.asyncio
    async def test_remove_nonexistent(self, store: JsonDeviceStore):
        """Remove unknown ID returns False."""
        result = await store.remove("unknown")

        assert result is False

    # DS09: is_paired true
    @pytest.mark.asyncio
    async def test_is_paired_true(self, store: JsonDeviceStore):
        """Check paired device returns True."""
        device = PairedDevice(
            device_id="paired",
            name="Phone",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )
        await store.add(device)

        assert store.is_paired("paired") is True

    # DS10: is_paired false
    @pytest.mark.asyncio
    async def test_is_paired_false(self, store: JsonDeviceStore):
        """Check unknown device returns False."""
        assert store.is_paired("unknown") is False

    # DS11: Create parent dirs
    @pytest.mark.asyncio
    async def test_create_parent_dirs(self, tmp_path: Path):
        """Create parent directories on save."""
        deep_path = tmp_path / "deep" / "nested" / "path" / "devices.json"
        store = JsonDeviceStore(deep_path)
        device = PairedDevice(
            device_id="device1",
            name="Phone",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )

        await store.add(device)

        assert deep_path.exists()

    # DS12: Get all devices
    @pytest.mark.asyncio
    async def test_all_devices(self, store: JsonDeviceStore):
        """Get all devices."""
        device1 = PairedDevice(
            device_id="device1",
            name="Phone 1",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )
        device2 = PairedDevice(
            device_id="device2",
            name="Phone 2",
            master_secret=TEST_SECRET,
            paired_at="2024-01-02T00:00:00Z",
        )
        await store.add(device1)
        await store.add(device2)

        all_devices = store.all()

        assert len(all_devices) == 2
        device_ids = {d.device_id for d in all_devices}
        assert device_ids == {"device1", "device2"}

    # DS13: Contains check
    @pytest.mark.asyncio
    async def test_contains(self, store: JsonDeviceStore):
        """Check contains operator."""
        device = PairedDevice(
            device_id="device1",
            name="Phone",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )
        await store.add(device)

        assert "device1" in store
        assert "unknown" not in store

    # Test get returns None for unknown
    @pytest.mark.asyncio
    async def test_get_unknown(self, store: JsonDeviceStore):
        """Get unknown device returns None."""
        assert store.get("unknown") is None

    # Test replace existing device
    @pytest.mark.asyncio
    async def test_replace_existing(self, store: JsonDeviceStore):
        """Adding device with same ID replaces it."""
        device1 = PairedDevice(
            device_id="device1",
            name="Old Name",
            master_secret=TEST_SECRET,
            paired_at="2024-01-01T00:00:00Z",
        )
        await store.add(device1)

        device2 = PairedDevice(
            device_id="device1",
            name="New Name",
            master_secret=TEST_SECRET,
            paired_at="2024-01-02T00:00:00Z",
        )
        await store.add(device2)

        assert len(store) == 1
        assert store.get("device1").name == "New Name"

    # Test add_device method (matches PairingManager protocol)
    @pytest.mark.asyncio
    async def test_add_device_method(self, store: JsonDeviceStore, store_path: Path):
        """Add device using add_device method (PairingManager protocol)."""
        await store.add_device(
            device_id="new_device",
            device_name="New Phone",
            master_secret=TEST_SECRET,
        )

        # Verify device was added
        assert len(store) == 1
        device = store.get("new_device")
        assert device is not None
        assert device.name == "New Phone"
        assert device.master_secret == TEST_SECRET
        assert device.paired_at is not None
        assert device.last_seen is not None

        # Verify file was written
        assert store_path.exists()
        data = json.loads(store_path.read_text())
        assert len(data["devices"]) == 1
