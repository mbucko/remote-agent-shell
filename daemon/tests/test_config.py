"""Tests for config module."""

from pathlib import Path
from unittest.mock import Mock

import pytest
import yaml

from ras.config import (
    Config,
    NotificationPatternsConfig,
    NotificationsConfig,
    get_config_path,
    load_config,
)


class TestConfigDefaults:
    """Test default configuration values."""

    def test_default_config_values(self):
        """Config has sensible defaults when no file exists."""
        config = Config()

        assert config.port == 8765
        assert config.bind_address == "0.0.0.0"
        assert config.default_directory is None
        assert config.default_agent is None
        assert config.log_level == "INFO"
        assert config.log_file is None


class TestGetConfigPath:
    """Test config path resolution."""

    def test_get_config_path_default(self):
        """Default config path is ~/.config/ras/config.yaml."""
        path = get_config_path()
        assert path == Path.home() / ".config" / "ras" / "config.yaml"

    def test_get_config_path_custom(self):
        """Can override config path."""
        custom = Path("/custom/config.yaml")
        path = get_config_path(custom)
        assert path == custom


class TestLoadConfig:
    """Test config loading."""

    def test_load_config_no_file_returns_defaults(self, tmp_path):
        """Config returns defaults when no file exists."""
        nonexistent = tmp_path / "nonexistent.yaml"
        config = load_config(nonexistent)

        assert config.port == 8765
        assert config.log_level == "INFO"

    def test_load_config_from_file(self, tmp_path):
        """Config loads values from YAML file."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            yaml.dump(
                {
                    "port": 9000,
                    "bind_address": "127.0.0.1",
                    "log_level": "DEBUG",
                }
            )
        )

        config = load_config(config_file)

        assert config.port == 9000
        assert config.bind_address == "127.0.0.1"
        assert config.log_level == "DEBUG"

    def test_config_file_overrides_defaults(self, tmp_path):
        """File values override defaults, missing values use defaults."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({"port": 1234}))

        config = load_config(config_file)

        assert config.port == 1234
        assert config.bind_address == "0.0.0.0"  # Default
        assert config.log_level == "INFO"  # Default

    def test_load_config_with_injectable_reader(self, tmp_path):
        """Config loading supports injectable file reader for testing."""
        mock_reader = Mock(return_value={"port": 5555, "log_level": "WARNING"})

        config = load_config(tmp_path / "config.yaml", file_reader=mock_reader)

        assert config.port == 5555
        assert config.log_level == "WARNING"
        mock_reader.assert_called_once()

    def test_load_config_handles_invalid_yaml(self, tmp_path):
        """Config handles invalid YAML gracefully."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text("invalid: yaml: content: [")

        config = load_config(config_file)

        # Should return defaults on parse error
        assert config.port == 8765

    def test_load_config_handles_empty_file(self, tmp_path):
        """Config handles empty file gracefully."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text("")

        config = load_config(config_file)

        assert config.port == 8765


class TestNotificationsConfig:
    """Test notification configuration loading."""

    def test_notifications_defaults(self):
        """NotificationsConfig has sensible defaults."""
        config = NotificationsConfig()

        assert config.enabled is True
        assert config.cooldown_seconds == 5.0
        assert config.regex_timeout_ms == 100
        assert config.patterns.shell_prompt is None
        assert config.patterns.custom_approval == []
        assert config.patterns.custom_error == []

    def test_load_notifications_enabled(self, tmp_path):
        """Can disable notifications via config."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            yaml.dump(
                {
                    "notifications": {
                        "enabled": False,
                    }
                }
            )
        )

        config = load_config(config_file)

        assert config.notifications.enabled is False

    def test_load_notifications_cooldown(self, tmp_path):
        """Can configure cooldown seconds."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            yaml.dump(
                {
                    "notifications": {
                        "cooldown_seconds": 10.0,
                    }
                }
            )
        )

        config = load_config(config_file)

        assert config.notifications.cooldown_seconds == 10.0

    def test_load_notifications_regex_timeout(self, tmp_path):
        """Can configure regex timeout."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            yaml.dump(
                {
                    "notifications": {
                        "regex_timeout_ms": 200,
                    }
                }
            )
        )

        config = load_config(config_file)

        assert config.notifications.regex_timeout_ms == 200

    def test_load_notifications_shell_prompt(self, tmp_path):
        """Can override shell prompt pattern."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            yaml.dump(
                {
                    "notifications": {
                        "patterns": {
                            "shell_prompt": r"^myhost\$ ",
                        }
                    }
                }
            )
        )

        config = load_config(config_file)

        assert config.notifications.patterns.shell_prompt == r"^myhost\$ "

    def test_load_notifications_custom_approval(self, tmp_path):
        """Can add custom approval patterns."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            yaml.dump(
                {
                    "notifications": {
                        "patterns": {
                            "custom_approval": ["my custom pattern", "another pattern"],
                        }
                    }
                }
            )
        )

        config = load_config(config_file)

        assert config.notifications.patterns.custom_approval == [
            "my custom pattern",
            "another pattern",
        ]

    def test_load_notifications_custom_error(self, tmp_path):
        """Can add custom error patterns."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            yaml.dump(
                {
                    "notifications": {
                        "patterns": {
                            "custom_error": ["CUSTOM_ERROR:", "MyAppError"],
                        }
                    }
                }
            )
        )

        config = load_config(config_file)

        assert config.notifications.patterns.custom_error == [
            "CUSTOM_ERROR:",
            "MyAppError",
        ]

    def test_load_notifications_full_config(self, tmp_path):
        """Can load full notifications config."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            yaml.dump(
                {
                    "notifications": {
                        "enabled": True,
                        "cooldown_seconds": 3.0,
                        "regex_timeout_ms": 50,
                        "patterns": {
                            "shell_prompt": r"^\$ ",
                            "custom_approval": ["custom approve"],
                            "custom_error": ["custom error"],
                        },
                    }
                }
            )
        )

        config = load_config(config_file)

        assert config.notifications.enabled is True
        assert config.notifications.cooldown_seconds == 3.0
        assert config.notifications.regex_timeout_ms == 50
        assert config.notifications.patterns.shell_prompt == r"^\$ "
        assert config.notifications.patterns.custom_approval == ["custom approve"]
        assert config.notifications.patterns.custom_error == ["custom error"]

    def test_notifications_defaults_when_missing(self, tmp_path):
        """Notifications uses defaults when not in config."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump({"port": 9000}))

        config = load_config(config_file)

        assert config.notifications.enabled is True
        assert config.notifications.cooldown_seconds == 5.0
        assert config.notifications.regex_timeout_ms == 100
