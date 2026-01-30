"""Tailscale direct connection support."""

from ras.tailscale.detector import TailscaleInfo, detect_tailscale, get_all_tailscale_ips
from ras.tailscale.listener import TailscaleListener
from ras.tailscale.transport import TailscaleTransport

__all__ = [
    "TailscaleInfo",
    "detect_tailscale",
    "get_all_tailscale_ips",
    "TailscaleListener",
    "TailscaleTransport",
]
