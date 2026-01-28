"""Contract tests for data channel configuration.

Verifies that Android and daemon agree on data channel parameters.
These tests parse the actual source files to detect mismatches.
"""

import re
from pathlib import Path

import pytest

# Expected shared configuration
EXPECTED_CONFIG = {
    "label": "ras-control",
    "negotiated": True,
    "id": 0,
    "ordered": True,
}


def extract_android_data_channel_config() -> dict:
    """Extract data channel config from Android WebRTCClient.kt."""
    android_file = (
        Path(__file__).parents[3]
        / "android/app/src/main/java/com/ras/data/webrtc/WebRTCClient.kt"
    )
    content = android_file.read_text()

    config = {}

    # Extract negotiated value
    match = re.search(r"negotiated\s*=\s*(true|false)", content)
    if match:
        config["negotiated"] = match.group(1) == "true"

    # Extract id value
    match = re.search(r"\bid\s*=\s*(\d+)", content)
    if match:
        config["id"] = int(match.group(1))

    # Extract ordered value
    match = re.search(r"ordered\s*=\s*(true|false)", content)
    if match:
        config["ordered"] = match.group(1) == "true"

    # Extract label from createDataChannel call
    match = re.search(r'createDataChannel\s*\(\s*"([^"]+)"', content)
    if match:
        config["label"] = match.group(1)

    return config


def extract_daemon_data_channel_config() -> dict:
    """Extract data channel config from daemon peer.py.

    Specifically looks for the negotiated channel in accept_offer(),
    which is what the daemon uses when receiving an offer from Android.
    """
    daemon_file = Path(__file__).parents[2] / "src/ras/peer.py"
    content = daemon_file.read_text()

    config = {}

    # Find the accept_offer function and extract config from it
    # Look for createDataChannel with negotiated=True (the one used with Android)
    accept_offer_match = re.search(
        r"async def accept_offer.*?(?=\n    async def|\Z)",
        content,
        re.DOTALL,
    )

    if accept_offer_match:
        accept_offer_body = accept_offer_match.group(0)

        # Extract label
        label_match = re.search(r'createDataChannel\s*\(\s*"([^"]+)"', accept_offer_body)
        if label_match:
            config["label"] = label_match.group(1)

        # Extract negotiated
        neg_match = re.search(r"negotiated\s*=\s*(True|False)", accept_offer_body)
        if neg_match:
            config["negotiated"] = neg_match.group(1) == "True"

        # Extract id
        id_match = re.search(r"\bid\s*=\s*(\d+)", accept_offer_body)
        if id_match:
            config["id"] = int(id_match.group(1))

        # Extract ordered
        ord_match = re.search(r"ordered\s*=\s*(True|False)", accept_offer_body)
        if ord_match:
            config["ordered"] = ord_match.group(1) == "True"

    return config


class TestDataChannelContract:
    """Verify Android and daemon agree on data channel configuration."""

    def test_android_config_matches_expected(self):
        """Android data channel config matches expected values."""
        config = extract_android_data_channel_config()

        assert config.get("label") == EXPECTED_CONFIG["label"], (
            f"Android label mismatch: {config.get('label')} != {EXPECTED_CONFIG['label']}"
        )
        assert config.get("negotiated") == EXPECTED_CONFIG["negotiated"], (
            f"Android negotiated mismatch: {config.get('negotiated')} != {EXPECTED_CONFIG['negotiated']}"
        )
        assert config.get("id") == EXPECTED_CONFIG["id"], (
            f"Android id mismatch: {config.get('id')} != {EXPECTED_CONFIG['id']}"
        )
        assert config.get("ordered") == EXPECTED_CONFIG["ordered"], (
            f"Android ordered mismatch: {config.get('ordered')} != {EXPECTED_CONFIG['ordered']}"
        )

    def test_daemon_config_matches_expected(self):
        """Daemon data channel config matches expected values."""
        config = extract_daemon_data_channel_config()

        assert config.get("label") == EXPECTED_CONFIG["label"], (
            f"Daemon label mismatch: {config.get('label')} != {EXPECTED_CONFIG['label']}"
        )
        assert config.get("negotiated") == EXPECTED_CONFIG["negotiated"], (
            f"Daemon negotiated mismatch: {config.get('negotiated')} != {EXPECTED_CONFIG['negotiated']}"
        )
        assert config.get("id") == EXPECTED_CONFIG["id"], (
            f"Daemon id mismatch: {config.get('id')} != {EXPECTED_CONFIG['id']}"
        )
        assert config.get("ordered") == EXPECTED_CONFIG["ordered"], (
            f"Daemon ordered mismatch: {config.get('ordered')} != {EXPECTED_CONFIG['ordered']}"
        )

    def test_android_and_daemon_configs_match(self):
        """Android and daemon data channel configs are identical."""
        android_config = extract_android_data_channel_config()
        daemon_config = extract_daemon_data_channel_config()

        assert android_config == daemon_config, (
            f"Config mismatch!\nAndroid: {android_config}\nDaemon: {daemon_config}"
        )
