"""Tests for storage module."""

import os
from datetime import datetime
from pathlib import Path

import pytest

from ras.errors import StorageError
from ras.storage import Device, DeviceStorage


class TestDevice:
    """Test Device dataclass."""

    def test_create_device(self):
        """Can create a Device."""
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        assert device.id == "abc123"
        assert device.name == "Test Phone"
        assert device.secret == b"x" * 32

    def test_default_paired_at(self):
        """paired_at defaults to now."""
        before = datetime.now()
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        after = datetime.now()
        assert before <= device.paired_at <= after

    def test_default_last_seen_is_none(self):
        """last_seen defaults to None."""
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        assert device.last_seen is None

    def test_to_dict(self):
        """Device converts to dict."""
        device = Device(
            id="abc123",
            name="Test Phone",
            secret=b"x" * 32,
            paired_at=datetime(2025, 1, 1, 12, 0, 0),
        )
        d = device.to_dict()
        assert d["id"] == "abc123"
        assert d["name"] == "Test Phone"
        assert "secret" in d  # base64 encoded
        assert d["paired_at"] == "2025-01-01T12:00:00"
        assert d["last_seen"] is None

    def test_to_dict_secret_is_base64(self):
        """Secret is base64 encoded in dict."""
        import base64

        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        d = device.to_dict()
        decoded = base64.b64decode(d["secret"])
        assert decoded == b"x" * 32

    def test_from_dict(self):
        """Device can be created from dict."""
        import base64

        d = {
            "id": "abc123",
            "name": "Test Phone",
            "secret": base64.b64encode(b"x" * 32).decode(),
            "paired_at": "2025-01-01T12:00:00",
            "last_seen": None,
        }
        device = Device.from_dict(d)
        assert device.id == "abc123"
        assert device.name == "Test Phone"
        assert device.secret == b"x" * 32
        assert device.paired_at == datetime(2025, 1, 1, 12, 0, 0)
        assert device.last_seen is None

    def test_from_dict_with_last_seen(self):
        """Device can be created from dict with last_seen."""
        import base64

        d = {
            "id": "abc123",
            "name": "Test Phone",
            "secret": base64.b64encode(b"x" * 32).decode(),
            "paired_at": "2025-01-01T12:00:00",
            "last_seen": "2025-01-15T14:30:00",
        }
        device = Device.from_dict(d)
        assert device.last_seen == datetime(2025, 1, 15, 14, 30, 0)


class TestDeviceStorageCreation:
    """Test DeviceStorage creation."""

    def test_create_storage(self, tmp_path):
        """Can create DeviceStorage."""
        storage = DeviceStorage(tmp_path)
        assert storage.directory == tmp_path

    def test_creates_directory(self, tmp_path):
        """Creates directory if it doesn't exist."""
        dir_path = tmp_path / "devices"
        storage = DeviceStorage(dir_path)
        assert dir_path.exists()

    def test_directory_permissions(self, tmp_path):
        """Directory has restricted permissions (700)."""
        dir_path = tmp_path / "secure_devices"
        storage = DeviceStorage(dir_path)
        mode = dir_path.stat().st_mode & 0o777
        assert mode == 0o700


class TestDeviceStorageSave:
    """Test saving devices."""

    def test_save_creates_file(self, tmp_path):
        """save creates device file."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        storage.save(device)
        assert (tmp_path / "device-abc123.json").exists()

    def test_save_file_permissions(self, tmp_path):
        """Device files have restricted permissions (600)."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        storage.save(device)
        file_path = tmp_path / "device-abc123.json"
        mode = file_path.stat().st_mode & 0o777
        assert mode == 0o600

    def test_save_overwrites_existing(self, tmp_path):
        """save overwrites existing device."""
        storage = DeviceStorage(tmp_path)
        device1 = Device(id="abc123", name="Old Name", secret=b"a" * 32)
        device2 = Device(id="abc123", name="New Name", secret=b"b" * 32)
        storage.save(device1)
        storage.save(device2)
        loaded = storage.load("abc123")
        assert loaded.name == "New Name"

    def test_save_valid_json(self, tmp_path):
        """Saved file contains valid JSON."""
        import json

        storage = DeviceStorage(tmp_path)
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        storage.save(device)
        file_path = tmp_path / "device-abc123.json"
        data = json.loads(file_path.read_text())
        assert data["id"] == "abc123"


class TestDeviceStorageLoad:
    """Test loading devices."""

    def test_load_existing_device(self, tmp_path):
        """Can load saved device."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        storage.save(device)
        loaded = storage.load("abc123")
        assert loaded.id == "abc123"
        assert loaded.name == "Test Phone"
        assert loaded.secret == b"x" * 32

    def test_load_nonexistent_returns_none(self, tmp_path):
        """Loading nonexistent device returns None."""
        storage = DeviceStorage(tmp_path)
        loaded = storage.load("doesnotexist")
        assert loaded is None

    def test_load_preserves_all_fields(self, tmp_path):
        """Load preserves all device fields."""
        storage = DeviceStorage(tmp_path)
        device = Device(
            id="abc123",
            name="Test Phone",
            secret=b"x" * 32,
            paired_at=datetime(2025, 1, 1, 12, 0, 0),
            last_seen=datetime(2025, 1, 15, 14, 30, 0),
        )
        storage.save(device)
        loaded = storage.load("abc123")
        assert loaded.paired_at == device.paired_at
        assert loaded.last_seen == device.last_seen


class TestDeviceStorageList:
    """Test listing devices."""

    def test_list_empty(self, tmp_path):
        """Listing empty storage returns empty list."""
        storage = DeviceStorage(tmp_path)
        devices = storage.list()
        assert devices == []

    def test_list_single_device(self, tmp_path):
        """Can list single device."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        storage.save(device)
        devices = storage.list()
        assert len(devices) == 1
        assert devices[0].id == "abc123"

    def test_list_multiple_devices(self, tmp_path):
        """Can list multiple devices."""
        storage = DeviceStorage(tmp_path)
        storage.save(Device(id="phone1", name="Phone 1", secret=b"a" * 32))
        storage.save(Device(id="phone2", name="Phone 2", secret=b"b" * 32))
        storage.save(Device(id="phone3", name="Phone 3", secret=b"c" * 32))
        devices = storage.list()
        assert len(devices) == 3
        ids = {d.id for d in devices}
        assert ids == {"phone1", "phone2", "phone3"}


class TestDeviceStorageDelete:
    """Test deleting devices."""

    def test_delete_existing_device(self, tmp_path):
        """Can delete existing device."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        storage.save(device)
        result = storage.delete("abc123")
        assert result is True
        assert not (tmp_path / "device-abc123.json").exists()

    def test_delete_nonexistent_returns_false(self, tmp_path):
        """Deleting nonexistent device returns False."""
        storage = DeviceStorage(tmp_path)
        result = storage.delete("doesnotexist")
        assert result is False

    def test_delete_removes_from_list(self, tmp_path):
        """Deleted device is removed from list."""
        storage = DeviceStorage(tmp_path)
        storage.save(Device(id="phone1", name="Phone 1", secret=b"a" * 32))
        storage.save(Device(id="phone2", name="Phone 2", secret=b"b" * 32))
        storage.delete("phone1")
        devices = storage.list()
        assert len(devices) == 1
        assert devices[0].id == "phone2"


class TestDeviceStorageUpdate:
    """Test updating devices."""

    def test_update_last_seen(self, tmp_path):
        """Can update last_seen timestamp."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="abc123", name="Test Phone", secret=b"x" * 32)
        storage.save(device)

        # Update last_seen
        loaded = storage.load("abc123")
        loaded.last_seen = datetime(2025, 1, 20, 10, 0, 0)
        storage.save(loaded)

        reloaded = storage.load("abc123")
        assert reloaded.last_seen == datetime(2025, 1, 20, 10, 0, 0)


class TestDeviceStorageIdValidation:
    """Test device ID validation."""

    def test_reject_path_traversal(self, tmp_path):
        """Rejects device IDs with path traversal."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="../evil", name="Evil Device", secret=b"x" * 32)
        with pytest.raises(StorageError, match="Invalid"):
            storage.save(device)

    def test_reject_slashes(self, tmp_path):
        """Rejects device IDs with slashes."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="foo/bar", name="Bad Device", secret=b"x" * 32)
        with pytest.raises(StorageError, match="Invalid"):
            storage.save(device)

    def test_accept_alphanumeric(self, tmp_path):
        """Accepts alphanumeric device IDs."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="phone123ABC", name="Good Device", secret=b"x" * 32)
        storage.save(device)  # Should not raise
        assert (tmp_path / "device-phone123ABC.json").exists()

    def test_accept_hyphen_underscore(self, tmp_path):
        """Accepts hyphens and underscores in device IDs."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="my-phone_1", name="Good Device", secret=b"x" * 32)
        storage.save(device)  # Should not raise
        assert (tmp_path / "device-my-phone_1.json").exists()
