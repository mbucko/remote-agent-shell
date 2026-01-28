"""Configuration management for RAS daemon."""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import yaml


DEFAULT_STUN_SERVERS = [
    "stun:stun.l.google.com:19302",
    "stun:stun.cloudflare.com:3478",
]


@dataclass
class NtfyConfig:
    """ntfy notification configuration."""

    server: str = "https://ntfy.sh"
    enabled: bool = True


@dataclass
class IpMonitorConfig:
    """IP monitoring configuration."""

    check_interval: float = 30.0  # seconds
    enabled: bool = True


@dataclass
class NotificationPatternsConfig:
    """Custom notification patterns configuration."""

    shell_prompt: str | None = None  # Override default shell prompt pattern
    custom_approval: list[str] = field(default_factory=list)  # Additional approval patterns
    custom_error: list[str] = field(default_factory=list)  # Additional error patterns


@dataclass
class NotificationsConfig:
    """Notification detection configuration."""

    enabled: bool = True  # Master toggle
    cooldown_seconds: float = 5.0  # Dedup cooldown
    regex_timeout_ms: int = 100  # ReDoS protection timeout
    patterns: NotificationPatternsConfig = field(default_factory=NotificationPatternsConfig)


@dataclass
class Config:
    """Daemon configuration."""

    port: int = 8765
    bind_address: str = "0.0.0.0"
    default_directory: str | None = None
    default_agent: str | None = None
    log_level: str = "INFO"
    log_file: str | None = None
    stun_servers: list[str] = field(default_factory=lambda: DEFAULT_STUN_SERVERS.copy())
    ntfy: NtfyConfig = field(default_factory=NtfyConfig)
    ip_monitor: IpMonitorConfig = field(default_factory=IpMonitorConfig)
    notifications: NotificationsConfig = field(default_factory=NotificationsConfig)


def get_config_path(custom_path: Path | None = None) -> Path:
    """Get the configuration file path.

    Args:
        custom_path: Override path. If None, returns default.

    Returns:
        Path to config file.
    """
    if custom_path is not None:
        return custom_path
    return Path.home() / ".config" / "ras" / "config.yaml"


def _default_file_reader(path: Path) -> dict[str, Any] | None:
    """Default file reader that loads YAML from disk."""
    if not path.exists():
        return None
    try:
        content = path.read_text()
        if not content.strip():
            return None
        return yaml.safe_load(content)
    except yaml.YAMLError:
        return None


def load_config(
    path: Path | None = None,
    file_reader: Callable[[Path], dict[str, Any] | None] | None = None,
) -> Config:
    """Load configuration from file.

    Args:
        path: Path to config file. If None, uses default path.
        file_reader: Injectable file reader for testing.

    Returns:
        Config object with values from file or defaults.
    """
    config_path = get_config_path(path)
    reader = file_reader or _default_file_reader

    data = reader(config_path)

    if data is None:
        return Config()

    # Parse ntfy config section
    ntfy_data = data.get("ntfy", {})
    ntfy_config = NtfyConfig(
        server=ntfy_data.get("server", NtfyConfig.server),
        enabled=ntfy_data.get("enabled", NtfyConfig.enabled),
    )

    # Parse ip_monitor config section
    ip_monitor_data = data.get("ip_monitor", {})
    ip_monitor_config = IpMonitorConfig(
        check_interval=ip_monitor_data.get(
            "check_interval", IpMonitorConfig.check_interval
        ),
        enabled=ip_monitor_data.get("enabled", IpMonitorConfig.enabled),
    )

    # Parse notifications config section
    notifications_data = data.get("notifications", {})
    patterns_data = notifications_data.get("patterns", {})
    patterns_config = NotificationPatternsConfig(
        shell_prompt=patterns_data.get("shell_prompt"),
        custom_approval=patterns_data.get("custom_approval", []),
        custom_error=patterns_data.get("custom_error", []),
    )
    notifications_config = NotificationsConfig(
        enabled=notifications_data.get("enabled", NotificationsConfig.enabled),
        cooldown_seconds=notifications_data.get(
            "cooldown_seconds", NotificationsConfig.cooldown_seconds
        ),
        regex_timeout_ms=notifications_data.get(
            "regex_timeout_ms", NotificationsConfig.regex_timeout_ms
        ),
        patterns=patterns_config,
    )

    return Config(
        port=data.get("port", Config.port),
        bind_address=data.get("bind_address", Config.bind_address),
        default_directory=data.get("default_directory", Config.default_directory),
        default_agent=data.get("default_agent", Config.default_agent),
        log_level=data.get("log_level", Config.log_level),
        log_file=data.get("log_file", Config.log_file),
        stun_servers=data.get("stun_servers", DEFAULT_STUN_SERVERS.copy()),
        ntfy=ntfy_config,
        ip_monitor=ip_monitor_config,
        notifications=notifications_config,
    )
