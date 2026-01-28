"""SDP validation utilities.

Validates SDP content to catch configuration errors early.
"""

from dataclasses import dataclass


@dataclass
class SdpValidationResult:
    """Result of SDP validation."""

    is_valid: bool
    candidate_count: int
    has_host: bool
    has_srflx: bool
    has_relay: bool
    errors: list[str]


def validate_sdp(sdp: str, description: str = "SDP") -> SdpValidationResult:
    """Validate SDP contains required fields.

    Args:
        sdp: The SDP string to validate
        description: Human-readable description for error messages

    Returns:
        SdpValidationResult with validation details
    """
    errors = []

    # Count candidates by type
    candidates = [line for line in sdp.split("\n") if line.startswith("a=candidate:")]
    candidate_count = len(candidates)

    # Check for candidate types - they appear as "typ host", "typ srflx", etc.
    # Need to handle end-of-line (no trailing space)
    has_host = any(" host" in c for c in candidates)
    has_srflx = any(" srflx" in c for c in candidates)
    has_relay = any(" relay" in c for c in candidates)

    if candidate_count == 0:
        errors.append(f"{description} contains no ICE candidates")

    is_valid = len(errors) == 0

    return SdpValidationResult(
        is_valid=is_valid,
        candidate_count=candidate_count,
        has_host=has_host,
        has_srflx=has_srflx,
        has_relay=has_relay,
        errors=errors,
    )


def require_candidates(sdp: str, description: str = "SDP") -> None:
    """Validate SDP contains ICE candidates, raise if not.

    Args:
        sdp: The SDP string to validate
        description: Human-readable description for error messages

    Raises:
        ValueError: If no candidates found
    """
    result = validate_sdp(sdp, description)
    if not result.is_valid:
        raise ValueError(
            f"{description} validation failed: {', '.join(result.errors)}. "
            "This usually means ICE gathering didn't complete before SDP was sent."
        )


def extract_candidates(sdp: str) -> list[str]:
    """Extract all ICE candidates from SDP.

    Args:
        sdp: The SDP string

    Returns:
        List of candidate lines
    """
    return [line for line in sdp.split("\n") if line.startswith("a=candidate:")]
