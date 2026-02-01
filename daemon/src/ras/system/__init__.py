"""System detection utilities."""

from .device_detector import DeviceType, detect_device_type, get_hostname

__all__ = ["DeviceType", "detect_device_type", "get_hostname"]
