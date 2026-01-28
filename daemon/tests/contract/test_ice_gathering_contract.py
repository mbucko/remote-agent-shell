"""Contract tests for ICE gathering behavior.

Verifies that both Android and daemon follow the same ICE gathering contract:
- SDPs must contain ICE candidates before being sent
- ICE gathering must complete before SDP is returned
"""

import re
from pathlib import Path

import pytest


# Expected contract: SDP must contain at least one candidate
MIN_EXPECTED_CANDIDATES = 1


def extract_android_ice_gathering_behavior() -> dict:
    """Extract ICE gathering behavior from Android WebRTCClient.kt."""
    # Navigate from daemon/tests/contract/ to repo root, then to android
    android_file = (
        Path(__file__).parents[3]
        / "android/app/src/main/java/com/ras/data/webrtc/WebRTCClient.kt"
    )
    content = android_file.read_text()

    behavior = {}

    # Check if code waits for ICE gathering
    behavior["waits_for_gathering"] = "iceGatheringComplete.await()" in content

    # Check if there's a timeout (handle Kotlin number literals with underscores like 10_000L)
    timeout_match = re.search(r"withTimeoutOrNull\(([\d_]+)", content)
    if timeout_match:
        # Remove underscores from Kotlin number literal
        timeout_str = timeout_match.group(1).replace("_", "")
        behavior["gathering_timeout_ms"] = int(timeout_str)
    else:
        behavior["gathering_timeout_ms"] = None

    # Check if SDP is validated
    behavior["validates_candidates"] = (
        "requireCandidates" in content or "countCandidates" in content
    )

    return behavior


def extract_daemon_ice_gathering_behavior() -> dict:
    """Extract ICE gathering behavior from daemon peer.py."""
    daemon_file = Path(__file__).parents[2] / "src/ras/peer.py"
    content = daemon_file.read_text()

    behavior = {}

    # Check if code waits for ICE gathering
    behavior["waits_for_gathering"] = "_wait_ice_gathering" in content

    # Check timeout in _wait_ice_gathering
    timeout_match = re.search(
        r"_wait_ice_gathering\(.*?timeout.*?=\s*([\d.]+)", content
    )
    if not timeout_match:
        timeout_match = re.search(
            r"def _wait_ice_gathering\(self, timeout: float = ([\d.]+)", content
        )
    behavior["gathering_timeout_s"] = (
        float(timeout_match.group(1)) if timeout_match else None
    )

    return behavior


class TestIceGatheringContract:
    """Verify Android and daemon follow ICE gathering contract."""

    def test_android_waits_for_ice_gathering(self):
        """Android must wait for ICE gathering before returning offer."""
        behavior = extract_android_ice_gathering_behavior()

        assert behavior[
            "waits_for_gathering"
        ], "Android must wait for ICE gathering to complete before returning SDP"

    def test_android_validates_candidates(self):
        """Android must validate SDP contains candidates."""
        behavior = extract_android_ice_gathering_behavior()

        assert behavior[
            "validates_candidates"
        ], "Android must validate SDP contains ICE candidates"

    def test_daemon_waits_for_ice_gathering(self):
        """Daemon must wait for ICE gathering before returning answer."""
        behavior = extract_daemon_ice_gathering_behavior()

        assert behavior[
            "waits_for_gathering"
        ], "Daemon must wait for ICE gathering to complete before returning SDP"

    def test_gathering_timeouts_are_reasonable(self):
        """Both sides should have similar, reasonable gathering timeouts."""
        android = extract_android_ice_gathering_behavior()
        daemon = extract_daemon_ice_gathering_behavior()

        # Android timeout in ms, daemon in seconds
        android_timeout_s = (android["gathering_timeout_ms"] or 10000) / 1000
        daemon_timeout_s = daemon["gathering_timeout_s"] or 10.0

        # Both should be between 5-30 seconds
        assert 5 <= android_timeout_s <= 30, (
            f"Android gathering timeout ({android_timeout_s}s) should be 5-30s"
        )
        assert 5 <= daemon_timeout_s <= 30, (
            f"Daemon gathering timeout ({daemon_timeout_s}s) should be 5-30s"
        )

        # Timeouts should be within 2x of each other
        ratio = max(android_timeout_s, daemon_timeout_s) / min(
            android_timeout_s, daemon_timeout_s
        )
        assert ratio <= 2.0, (
            f"Gathering timeouts too different: "
            f"Android={android_timeout_s}s, Daemon={daemon_timeout_s}s"
        )
