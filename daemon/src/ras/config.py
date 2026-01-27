"""Configuration management for RAS daemon."""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import yaml


@dataclass
class Config:
    """Daemon configuration."""

    port: int = 8765
    bind_address: str = "0.0.0.0"
    default_directory: str | None = None
    default_agent: str | None = None
    log_level: str = "INFO"
    log_file: str | None = None


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

    return Config(
        port=data.get("port", Config.port),
        bind_address=data.get("bind_address", Config.bind_address),
        default_directory=data.get("default_directory", Config.default_directory),
        default_agent=data.get("default_agent", Config.default_agent),
        log_level=data.get("log_level", Config.log_level),
        log_file=data.get("log_file", Config.log_file),
    )
