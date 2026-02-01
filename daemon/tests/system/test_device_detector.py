"""Tests for device type detection."""

from unittest.mock import Mock, mock_open, patch

import pytest

from ras.system.device_detector import (
    DeviceType,
    detect_device_type,
    get_hostname,
    _detect_macos_device_type,
    _detect_linux_device_type,
)


class TestDetectDeviceType:
    """Tests for detect_device_type function."""

    def test_returns_valid_enum(self):
        """detect_device_type always returns a DeviceType member."""
        result = detect_device_type()
        assert result in DeviceType

    @patch("ras.system.device_detector.sys.platform", "darwin")
    @patch("ras.system.device_detector._detect_macos_device_type")
    def test_calls_macos_detector_on_darwin(self, mock_macos):
        """On macOS, calls the macOS-specific detector."""
        mock_macos.return_value = DeviceType.LAPTOP
        result = detect_device_type()
        mock_macos.assert_called_once()
        assert result == DeviceType.LAPTOP

    @patch("ras.system.device_detector.sys.platform", "linux")
    @patch("ras.system.device_detector._detect_linux_device_type")
    def test_calls_linux_detector_on_linux(self, mock_linux):
        """On Linux, calls the Linux-specific detector."""
        mock_linux.return_value = DeviceType.SERVER
        result = detect_device_type()
        mock_linux.assert_called_once()
        assert result == DeviceType.SERVER

    @patch("ras.system.device_detector.sys.platform", "win32")
    def test_unknown_platform_returns_unknown(self):
        """Unknown platforms return UNKNOWN."""
        result = detect_device_type()
        assert result == DeviceType.UNKNOWN


class TestGetHostname:
    """Tests for get_hostname function."""

    def test_returns_non_empty_string(self):
        """get_hostname returns the system hostname."""
        result = get_hostname()
        assert isinstance(result, str)
        assert len(result) > 0

    @patch("ras.system.device_detector.socket.gethostname")
    def test_uses_socket_gethostname(self, mock_gethostname):
        """get_hostname uses socket.gethostname."""
        mock_gethostname.return_value = "test-hostname"
        result = get_hostname()
        assert result == "test-hostname"


class TestMacOSDetection:
    """Tests for macOS device type detection."""

    @patch("ras.system.device_detector.subprocess.run")
    def test_macbook_detected_as_laptop(self, mock_run):
        """MacBook models are detected as LAPTOP."""
        mock_run.return_value = Mock(stdout="MacBookPro18,1")
        result = _detect_macos_device_type()
        assert result == DeviceType.LAPTOP

    @patch("ras.system.device_detector.subprocess.run")
    def test_macbook_air_detected_as_laptop(self, mock_run):
        """MacBook Air models are detected as LAPTOP."""
        mock_run.return_value = Mock(stdout="MacBookAir10,1")
        result = _detect_macos_device_type()
        assert result == DeviceType.LAPTOP

    @patch("ras.system.device_detector.subprocess.run")
    def test_imac_detected_as_desktop(self, mock_run):
        """iMac models are detected as DESKTOP."""
        mock_run.return_value = Mock(stdout="iMac21,1")
        result = _detect_macos_device_type()
        assert result == DeviceType.DESKTOP

    @patch("ras.system.device_detector.subprocess.run")
    def test_mac_mini_detected_as_desktop(self, mock_run):
        """Mac mini models are detected as DESKTOP."""
        mock_run.return_value = Mock(stdout="Macmini9,1")
        result = _detect_macos_device_type()
        assert result == DeviceType.DESKTOP

    @patch("ras.system.device_detector.subprocess.run")
    def test_mac_pro_detected_as_desktop(self, mock_run):
        """Mac Pro models are detected as DESKTOP."""
        mock_run.return_value = Mock(stdout="MacPro7,1")
        result = _detect_macos_device_type()
        assert result == DeviceType.DESKTOP

    @patch("ras.system.device_detector.subprocess.run")
    def test_mac_studio_detected_as_desktop(self, mock_run):
        """Mac Studio models are detected as DESKTOP."""
        mock_run.return_value = Mock(stdout="Mac13,1")
        result = _detect_macos_device_type()
        assert result == DeviceType.DESKTOP

    @patch("ras.system.device_detector.subprocess.run")
    def test_subprocess_failure_returns_unknown(self, mock_run):
        """If sysctl fails, returns UNKNOWN instead of crashing."""
        mock_run.side_effect = Exception("Command failed")
        result = _detect_macos_device_type()
        assert result == DeviceType.UNKNOWN

    @patch("ras.system.device_detector.subprocess.run")
    def test_subprocess_timeout_returns_unknown(self, mock_run):
        """If sysctl times out, returns UNKNOWN."""
        import subprocess
        mock_run.side_effect = subprocess.TimeoutExpired("sysctl", 5)
        result = _detect_macos_device_type()
        assert result == DeviceType.UNKNOWN


class TestLinuxDetection:
    """Tests for Linux device type detection."""

    def test_laptop_chassis_type_9(self):
        """Linux chassis type 9 (Laptop) is detected as LAPTOP."""
        with patch("builtins.open", mock_open(read_data="9\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.LAPTOP

    def test_notebook_chassis_type_10(self):
        """Linux chassis type 10 (Notebook) is detected as LAPTOP."""
        with patch("builtins.open", mock_open(read_data="10\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.LAPTOP

    def test_subnotebook_chassis_type_14(self):
        """Linux chassis type 14 (Sub Notebook) is detected as LAPTOP."""
        with patch("builtins.open", mock_open(read_data="14\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.LAPTOP

    def test_desktop_chassis_type_3(self):
        """Linux chassis type 3 (Desktop) is detected as DESKTOP."""
        with patch("builtins.open", mock_open(read_data="3\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.DESKTOP

    def test_tower_chassis_type_7(self):
        """Linux chassis type 7 (Tower) is detected as DESKTOP."""
        with patch("builtins.open", mock_open(read_data="7\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.DESKTOP

    def test_rackmount_chassis_type_17(self):
        """Linux chassis type 17 (Rack Mount) is detected as SERVER."""
        with patch("builtins.open", mock_open(read_data="17\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.SERVER

    def test_rackmount_chassis_type_23(self):
        """Linux chassis type 23 (Rack Mount Chassis) is detected as SERVER."""
        with patch("builtins.open", mock_open(read_data="23\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.SERVER

    def test_file_not_found_returns_unknown(self):
        """If chassis_type file doesn't exist, returns UNKNOWN."""
        with patch("builtins.open", side_effect=FileNotFoundError()):
            result = _detect_linux_device_type()
            assert result == DeviceType.UNKNOWN

    def test_invalid_content_returns_unknown(self):
        """If chassis_type contains invalid data, returns UNKNOWN."""
        with patch("builtins.open", mock_open(read_data="not a number\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.UNKNOWN

    def test_unknown_chassis_type_returns_unknown(self):
        """Unknown chassis types return UNKNOWN."""
        with patch("builtins.open", mock_open(read_data="99\n")):
            result = _detect_linux_device_type()
            assert result == DeviceType.UNKNOWN
