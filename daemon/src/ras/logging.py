"""Logging configuration for RAS daemon."""

import logging
from pathlib import Path

from ras.config import Config

# Module-level logger cache
_logger: logging.Logger | None = None


def setup_logging(config: Config) -> logging.Logger:
    """Set up logging based on configuration.

    Args:
        config: Configuration object with log settings.

    Returns:
        Configured logger instance.
    """
    global _logger

    # Return existing logger if already set up (idempotent)
    if _logger is not None:
        return _logger

    logger = logging.getLogger("ras")
    logger.setLevel(getattr(logging, config.log_level.upper(), logging.INFO))

    # Clear any existing handlers
    logger.handlers.clear()

    # Log format: 2025-01-27 10:30:45 [INFO] message
    formatter = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
    formatter.datefmt = "%Y-%m-%d %H:%M:%S"

    # File handler if log_file is configured
    if config.log_file:
        log_path = Path(config.log_file)
        log_path.parent.mkdir(parents=True, exist_ok=True)

        file_handler = logging.FileHandler(log_path)
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)

    # Console handler
    console_handler = logging.StreamHandler()
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)

    # Prevent propagation to root logger
    logger.propagate = False

    _logger = logger
    return logger


def reset_logging() -> None:
    """Reset logging state. Used for testing."""
    global _logger
    if _logger is not None:
        _logger.handlers.clear()
        _logger = None
