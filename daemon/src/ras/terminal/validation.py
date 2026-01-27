"""Input validation for terminal I/O."""

import re
from dataclasses import dataclass

# Session ID: 12 alphanumeric characters
SESSION_ID_PATTERN = re.compile(r"^[a-zA-Z0-9]{12}$")

# Max input size (64KB)
MAX_INPUT_SIZE = 65536

# Rate limit: 100 inputs per second
RATE_LIMIT_PER_SECOND = 100


@dataclass
class ValidationError:
    """Validation error with code and message."""

    code: str
    message: str


def validate_session_id(session_id: str) -> ValidationError | None:
    """Validate session ID format.

    Args:
        session_id: The session ID to validate.

    Returns:
        ValidationError if invalid, None if valid.
    """
    if not session_id:
        return ValidationError("INVALID_SESSION_ID", "Session ID is required")

    if ".." in session_id or "/" in session_id or "\x00" in session_id:
        return ValidationError(
            "INVALID_SESSION_ID", "Session ID contains invalid characters"
        )

    if not SESSION_ID_PATTERN.match(session_id):
        return ValidationError(
            "INVALID_SESSION_ID", "Session ID must be 12 alphanumeric characters"
        )

    return None


def validate_input_data(data: bytes) -> ValidationError | None:
    """Validate input data.

    Args:
        data: The input data to validate.

    Returns:
        ValidationError if invalid, None if valid.
    """
    if len(data) > MAX_INPUT_SIZE:
        return ValidationError(
            "INPUT_TOO_LARGE", f"Input exceeds {MAX_INPUT_SIZE} bytes"
        )

    return None
