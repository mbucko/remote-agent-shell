"""Contract tests for capability exchange behavior.

Documents the expected behavior for capability exchange optimization:
- Capability exchange is optional for WebRTC strategy
- Capability exchange is only needed for Tailscale strategy
- WebRTC strategy can proceed without daemon capabilities
"""

import re
from pathlib import Path

import pytest


class TestCapabilityExchangeContract:
    """Verify capability exchange behavior between Android and daemon."""

    def test_capability_exchange_is_optional_for_webrtc(self):
        """WebRTC strategy should work without capability exchange.

        When local Tailscale is not available:
        - Android should skip capability exchange (saves ~4 seconds)
        - WebRTC strategy should still work correctly
        - Context should have localTailscaleAvailable = false
        """
        # Read ConnectionOrchestrator to verify skip logic
        android_file = (
            Path(__file__).parents[3]
            / "android/app/src/main/java/com/ras/data/connection/ConnectionOrchestrator.kt"
        )
        content = android_file.read_text()

        # Verify capability exchange is conditional on local Tailscale
        assert "if (localTailscale != null)" in content, (
            "Capability exchange should be conditional on local Tailscale"
        )

        # Verify skip produces progress event
        assert "CapabilityExchangeSkipped" in content, (
            "Should emit CapabilityExchangeSkipped when skipping"
        )

        # Verify context is still valid when skipped
        assert "localTailscaleAvailable = false" in content, (
            "Should set localTailscaleAvailable = false when no Tailscale"
        )

    def test_webrtc_strategy_only_needs_local_tailscale_flag(self):
        """WebRTC strategy should only use localTailscaleAvailable from context.

        It should NOT require:
        - daemonTailscaleIp
        - daemonTailscalePort
        - Any other daemon capabilities
        """
        # Read WebRTCStrategy to verify it only uses localTailscaleAvailable
        android_file = (
            Path(__file__).parents[3]
            / "android/app/src/main/java/com/ras/data/connection/WebRTCStrategy.kt"
        )
        content = android_file.read_text()

        # Should use localTailscaleAvailable for filtering
        assert "context.localTailscaleAvailable" in content, (
            "WebRTCStrategy should use localTailscaleAvailable"
        )

        # Should NOT use daemon Tailscale info
        assert "daemonTailscaleIp" not in content, (
            "WebRTCStrategy should not use daemonTailscaleIp"
        )
        assert "daemonTailscalePort" not in content, (
            "WebRTCStrategy should not use daemonTailscalePort"
        )

    def test_signaling_channel_gets_capabilities_during_sdp_exchange(self):
        """Capabilities should still be received during SDP exchange.

        Even when capability exchange is skipped, the daemon sends
        capabilities in the SDP answer response via ntfy.
        """
        # Read NtfySignalingChannel to verify it extracts capabilities from answer
        android_file = (
            Path(__file__).parents[3]
            / "android/app/src/main/java/com/ras/data/connection/NtfySignalingChannel.kt"
        )
        content = android_file.read_text()

        # Should extract capabilities from answer
        assert "result.capabilities" in content, (
            "Should extract capabilities from answer"
        )
        assert "lastReceivedCapabilities" in content, (
            "Should store received capabilities"
        )

    def test_tailscale_strategy_requires_capability_exchange(self):
        """Tailscale strategy requires daemon Tailscale info.

        This is why capability exchange is NOT skipped when local
        Tailscale is available - TailscaleStrategy needs the daemon's
        Tailscale IP and port to connect directly.
        """
        # Read TailscaleStrategy to verify it uses daemon info
        android_file = (
            Path(__file__).parents[3]
            / "android/app/src/main/java/com/ras/data/connection/TailscaleStrategy.kt"
        )
        content = android_file.read_text()

        # Should use daemon Tailscale info from context
        assert "daemonTailscaleIp" in content or "tailscaleIp" in content, (
            "TailscaleStrategy should use daemon Tailscale IP"
        )

    def test_ice_gathering_timeout_is_reasonable(self):
        """ICE gathering timeout should be short enough to not block.

        On Android, IceGatheringState.COMPLETE may not fire during
        offer creation. A 2-second timeout is sufficient because:
        - Host candidates are gathered immediately
        - STUN candidates arrive within 1-2 seconds
        """
        android_file = (
            Path(__file__).parents[3]
            / "android/app/src/main/java/com/ras/data/webrtc/WebRTCClient.kt"
        )
        content = android_file.read_text()

        # Find ICE gathering timeout constant (handles Kotlin literals like 2_000L)
        match = re.search(r"ICE_GATHERING_TIMEOUT_MS\s*=\s*([\d_]+)", content)
        assert match, "Should have ICE_GATHERING_TIMEOUT_MS constant"

        # Remove underscores and L suffix from Kotlin number literal
        timeout_str = match.group(1).replace("_", "")
        timeout_ms = int(timeout_str)

        # Should be between 1-5 seconds
        assert 1000 <= timeout_ms <= 5000, (
            f"ICE gathering timeout ({timeout_ms}ms) should be 1-5 seconds"
        )

        # Verify documentation explains the reason
        assert "COMPLETE state may not fire" in content or "COMPLETE may not fire" in content, (
            "Should document why short timeout is used"
        )
