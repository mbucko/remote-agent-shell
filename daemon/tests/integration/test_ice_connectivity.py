"""Integration tests for ICE connectivity.

These tests use real aiortc peer connections to verify ICE works correctly.
"""

import asyncio

import pytest
from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection

from ras.sdp_validator import extract_candidates, validate_sdp


@pytest.fixture
def rtc_config():
    """RTC configuration with STUN servers."""
    return RTCConfiguration(
        iceServers=[RTCIceServer(urls=["stun:stun.l.google.com:19302"])]
    )


async def wait_for_gathering(pc: RTCPeerConnection, timeout: float = 10.0) -> None:
    """Wait for ICE gathering to complete."""
    if pc.iceGatheringState == "complete":
        return

    done = asyncio.Event()

    @pc.on("icegatheringstatechange")
    def on_change():
        if pc.iceGatheringState == "complete":
            done.set()

    await asyncio.wait_for(done.wait(), timeout=timeout)


class TestIceGathering:
    """Test ICE candidate gathering."""

    @pytest.mark.asyncio
    async def test_offer_contains_candidates_after_gathering(self, rtc_config):
        """Offer SDP contains ICE candidates after gathering completes."""
        pc = RTCPeerConnection(configuration=rtc_config)

        try:
            # Create data channel to trigger ICE
            pc.createDataChannel("test")

            # Create offer
            offer = await pc.createOffer()
            await pc.setLocalDescription(offer)

            # Wait for gathering
            await wait_for_gathering(pc)

            # Validate SDP
            sdp = pc.localDescription.sdp
            validation = validate_sdp(sdp, "Test offer")

            assert validation.is_valid, f"Offer should be valid: {validation.errors}"
            assert validation.candidate_count >= 1, "Should have at least 1 candidate"
            assert validation.has_host, "Should have at least one host candidate"

        finally:
            await pc.close()

    @pytest.mark.asyncio
    async def test_answer_contains_candidates_after_gathering(self, rtc_config):
        """Answer SDP contains ICE candidates after gathering completes."""
        offerer = RTCPeerConnection(configuration=rtc_config)
        answerer = RTCPeerConnection(configuration=rtc_config)

        try:
            # Offerer creates offer
            offerer.createDataChannel("test")
            offer = await offerer.createOffer()
            await offerer.setLocalDescription(offer)

            # Wait for offerer gathering
            await wait_for_gathering(offerer)

            # Answerer processes offer and creates answer
            await answerer.setRemoteDescription(offerer.localDescription)
            answer = await answerer.createAnswer()
            await answerer.setLocalDescription(answer)

            # Wait for answerer gathering
            await wait_for_gathering(answerer)

            # Validate answer SDP
            sdp = answerer.localDescription.sdp
            validation = validate_sdp(sdp, "Test answer")

            assert validation.is_valid, f"Answer should be valid: {validation.errors}"
            assert validation.candidate_count >= 1, "Should have at least 1 candidate"

        finally:
            await offerer.close()
            await answerer.close()


class TestIceConnectivity:
    """Test ICE connectivity between peers."""

    @pytest.mark.asyncio
    async def test_peers_connect_with_complete_sdp(self, rtc_config):
        """Two peers can connect when SDPs contain all candidates."""
        offerer = RTCPeerConnection(configuration=rtc_config)
        answerer = RTCPeerConnection(configuration=rtc_config)

        try:
            # Track connection states
            offerer_connected = asyncio.Event()
            answerer_connected = asyncio.Event()

            @offerer.on("connectionstatechange")
            def on_offerer_state():
                if offerer.connectionState == "connected":
                    offerer_connected.set()

            @answerer.on("connectionstatechange")
            def on_answerer_state():
                if answerer.connectionState == "connected":
                    answerer_connected.set()

            # Create negotiated data channels
            offerer.createDataChannel("test", negotiated=True, id=0)
            answerer.createDataChannel("test", negotiated=True, id=0)

            # Offerer creates and gathers offer
            offer = await offerer.createOffer()
            await offerer.setLocalDescription(offer)
            await wait_for_gathering(offerer)

            # Answerer processes offer and creates answer
            await answerer.setRemoteDescription(offerer.localDescription)
            answer = await answerer.createAnswer()
            await answerer.setLocalDescription(answer)
            await wait_for_gathering(answerer)

            # Offerer processes answer
            await offerer.setRemoteDescription(answerer.localDescription)

            # Both should connect
            await asyncio.wait_for(
                asyncio.gather(offerer_connected.wait(), answerer_connected.wait()),
                timeout=10.0,
            )

            assert offerer.connectionState == "connected"
            assert answerer.connectionState == "connected"

        finally:
            await offerer.close()
            await answerer.close()

    @pytest.mark.asyncio
    async def test_incomplete_sdp_has_no_candidates(self, rtc_config):
        """SDP before gathering completes has no candidates (documents the bug)."""
        pc = RTCPeerConnection(configuration=rtc_config)

        try:
            pc.createDataChannel("test")

            # Create offer WITHOUT waiting for gathering (the bug!)
            offer = await pc.createOffer()
            await pc.setLocalDescription(offer)

            # Immediately check SDP before gathering completes
            incomplete_sdp = offer.sdp

            # Check candidate count - should be zero or very few
            validation = validate_sdp(incomplete_sdp, "Incomplete offer")

            # This documents the bug: SDP created before gathering has no candidates
            # The validation should fail (is_valid = False) or have 0 candidates
            assert (
                validation.candidate_count == 0 or not validation.is_valid
            ), "SDP before gathering should have no/few candidates"

        finally:
            await pc.close()
