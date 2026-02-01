"""Device type detection for different platforms."""

import socket
import subprocess
import sys
from enum import IntEnum


class DeviceType(IntEnum):
    """Device type matching proto DeviceType enum."""

    UNKNOWN = 0
    LAPTOP = 1
    DESKTOP = 2
    SERVER = 3


def detect_device_type() -> DeviceType:
    """Detect the device type based on hardware.

    Returns:
        DeviceType enum value based on system hardware detection.
    """
    if sys.platform == "darwin":
        return _detect_macos_device_type()
    elif sys.platform == "linux":
        return _detect_linux_device_type()
    return DeviceType.UNKNOWN


def _detect_macos_device_type() -> DeviceType:
    """Detect device type on macOS using sysctl."""
    try:
        result = subprocess.run(
            ["sysctl", "-n", "hw.model"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        model = result.stdout.strip().lower()
        if "macbook" in model:
            return DeviceType.LAPTOP
        # iMac, Mac mini, Mac Pro, Mac Studio = desktop
        return DeviceType.DESKTOP
    except Exception:
        return DeviceType.UNKNOWN


def _detect_linux_device_type() -> DeviceType:
    """Detect device type on Linux using DMI chassis type."""
    try:
        with open("/sys/class/dmi/id/chassis_type") as f:
            chassis = int(f.read().strip())
            # Laptop types: 9=Laptop, 10=Notebook, 14=Sub Notebook
            if chassis in (9, 10, 14):
                return DeviceType.LAPTOP
            # Desktop types: 3=Desktop, 4=Low Profile, 5=Pizza Box,
            #                6=Mini Tower, 7=Tower
            elif chassis in (3, 4, 5, 6, 7):
                return DeviceType.DESKTOP
            # Server types: 17=Rack Mount, 23=Rack Mount Chassis
            elif chassis in (17, 23):
                return DeviceType.SERVER
    except Exception:
        pass
    return DeviceType.UNKNOWN


def get_hostname() -> str:
    """Get the system hostname.

    Returns:
        The system hostname as a string.
    """
    return socket.gethostname()
