"""Tests for config module."""

from pathlib import Path
from unittest.mock import Mock

import pytest
import yaml

from ras.config import Config, get_config_path, load_config


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
