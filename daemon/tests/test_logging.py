"""Tests for logging module."""

import logging
import re

import pytest

from ras.config import Config
from ras.logging import setup_logging


class TestSetupLogging:
    """Test logging setup."""

    def test_setup_logging_returns_logger(self):
        """Setup returns a logger instance."""
        config = Config()
        logger = setup_logging(config)

        assert isinstance(logger, logging.Logger)
        assert logger.name == "ras"

    def test_setup_logging_creates_log_file(self, tmp_path):
        """Logging setup creates log file."""
        log_file = tmp_path / "test.log"
        config = Config(log_file=str(log_file))

        logger = setup_logging(config)
        logger.info("test message")

        assert log_file.exists()
        content = log_file.read_text()
        assert "test message" in content

    def test_setup_logging_creates_log_directory(self, tmp_path):
        """Logging setup creates log directory if needed."""
        log_file = tmp_path / "subdir" / "test.log"
        config = Config(log_file=str(log_file))

        logger = setup_logging(config)
        logger.info("test message")

        assert log_file.exists()

    def test_log_levels_respected(self, tmp_path):
        """Only logs at configured level and above."""
        log_file = tmp_path / "test.log"
        config = Config(log_file=str(log_file), log_level="WARNING")

        logger = setup_logging(config)
        logger.debug("debug message")
        logger.info("info message")
        logger.warning("warning message")
        logger.error("error message")

        content = log_file.read_text()
        assert "debug message" not in content
        assert "info message" not in content
        assert "warning message" in content
        assert "error message" in content

    def test_log_format_includes_timestamp(self, tmp_path):
        """Log entries have timestamp, level, message."""
        log_file = tmp_path / "test.log"
        config = Config(log_file=str(log_file))

        logger = setup_logging(config)
        logger.info("test message")

        content = log_file.read_text()
        # Format: 2025-01-27 10:30:45 [INFO] message
        pattern = r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} \[INFO\] test message"
        assert re.search(pattern, content)

    def test_setup_logging_without_file(self):
        """Logging works without a file (console only)."""
        config = Config(log_file=None)

        logger = setup_logging(config)

        # Should not raise
        logger.info("console only message")

    def test_setup_logging_idempotent(self, tmp_path):
        """Multiple setup calls don't duplicate handlers."""
        log_file = tmp_path / "test.log"
        config = Config(log_file=str(log_file))

        logger1 = setup_logging(config)
        initial_handlers = len(logger1.handlers)

        logger2 = setup_logging(config)

        assert logger1 is logger2
        assert len(logger2.handlers) == initial_handlers
