"""Integration tests for WebRTC negotiated data channels.

These tests use real aiortc peer connections (not mocks) to verify
that negotiated data channels work correctly between two peers.
"""

import asyncio

import pytest
from aiortc import RTCConfiguration, RTCPeerConnection

# Shared config - must match Android and daemon
DATA_CHANNEL_LABEL = "ras-control"
DATA_CHANNEL_ID = 0
DATA_CHANNEL_NEGOTIATED = True
DATA_CHANNEL_ORDERED = True


@pytest.fixture
def rtc_config():
    """RTC configuration without STUN servers for local testing."""
    return RTCConfiguration(iceServers=[])


async def wait_ice_gathering(pc: RTCPeerConnection, timeout: float = 5.0) -> None:
    """Wait for ICE gathering to complete."""
    if pc.iceGatheringState == "complete":
        return

    done = asyncio.Event()

    @pc.on("icegatheringstatechange")
    def on_ice_gathering_state_change():
        if pc.iceGatheringState == "complete":
            done.set()

    try:
        await asyncio.wait_for(done.wait(), timeout=timeout)
    except asyncio.TimeoutError:
        pass  # Proceed anyway


@pytest.mark.integration
class TestNegotiatedDataChannel:
    """Test negotiated data channel communication between two peers."""

    async def test_negotiated_channel_opens_on_both_sides(self, rtc_config):
        """Both peers can open negotiated channel independently."""
        offerer = RTCPeerConnection(configuration=rtc_config)
        answerer = RTCPeerConnection(configuration=rtc_config)

        try:
            # Both sides create negotiated channel with same ID
            offerer_channel = offerer.createDataChannel(
                DATA_CHANNEL_LABEL,
                negotiated=DATA_CHANNEL_NEGOTIATED,
                id=DATA_CHANNEL_ID,
                ordered=DATA_CHANNEL_ORDERED,
            )

            answerer_channel = answerer.createDataChannel(
                DATA_CHANNEL_LABEL,
                negotiated=DATA_CHANNEL_NEGOTIATED,
                id=DATA_CHANNEL_ID,
                ordered=DATA_CHANNEL_ORDERED,
            )

            # Track channel open events
            offerer_opened = asyncio.Event()
            answerer_opened = asyncio.Event()

            @offerer_channel.on("open")
            def on_offerer_open():
                offerer_opened.set()

            @answerer_channel.on("open")
            def on_answerer_open():
                answerer_opened.set()

            # Exchange SDP with ICE gathering
            offer = await offerer.createOffer()
            await offerer.setLocalDescription(offer)
            await wait_ice_gathering(offerer)

            await answerer.setRemoteDescription(offerer.localDescription)

            answer = await answerer.createAnswer()
            await answerer.setLocalDescription(answer)
            await wait_ice_gathering(answerer)

            await offerer.setRemoteDescription(answerer.localDescription)

            # Wait for channels to open
            await asyncio.wait_for(
                asyncio.gather(offerer_opened.wait(), answerer_opened.wait()),
                timeout=10.0,
            )

            assert offerer_channel.readyState == "open"
            assert answerer_channel.readyState == "open"

        finally:
            await offerer.close()
            await answerer.close()

    async def test_bidirectional_message_flow(self, rtc_config):
        """Messages flow in both directions through negotiated channel."""
        offerer = RTCPeerConnection(configuration=rtc_config)
        answerer = RTCPeerConnection(configuration=rtc_config)

        try:
            # Create channels
            offerer_channel = offerer.createDataChannel(
                DATA_CHANNEL_LABEL,
                negotiated=DATA_CHANNEL_NEGOTIATED,
                id=DATA_CHANNEL_ID,
            )

            answerer_channel = answerer.createDataChannel(
                DATA_CHANNEL_LABEL,
                negotiated=DATA_CHANNEL_NEGOTIATED,
                id=DATA_CHANNEL_ID,
            )

            # Message queues
            offerer_received = asyncio.Queue()
            answerer_received = asyncio.Queue()
            channels_open = asyncio.Event()
            open_count = 0

            @offerer_channel.on("open")
            def on_offerer_open():
                nonlocal open_count
                open_count += 1
                if open_count == 2:
                    channels_open.set()

            @answerer_channel.on("open")
            def on_answerer_open():
                nonlocal open_count
                open_count += 1
                if open_count == 2:
                    channels_open.set()

            @offerer_channel.on("message")
            def on_offerer_message(message):
                offerer_received.put_nowait(message)

            @answerer_channel.on("message")
            def on_answerer_message(message):
                answerer_received.put_nowait(message)

            # Exchange SDP with ICE gathering
            offer = await offerer.createOffer()
            await offerer.setLocalDescription(offer)
            await wait_ice_gathering(offerer)

            await answerer.setRemoteDescription(offerer.localDescription)

            answer = await answerer.createAnswer()
            await answerer.setLocalDescription(answer)
            await wait_ice_gathering(answerer)

            await offerer.setRemoteDescription(answerer.localDescription)

            # Wait for channels
            await asyncio.wait_for(channels_open.wait(), timeout=10.0)

            # Send messages both ways
            offerer_channel.send(b"hello from offerer")
            answerer_channel.send(b"hello from answerer")

            # Verify receipt
            msg_to_answerer = await asyncio.wait_for(answerer_received.get(), timeout=5.0)
            msg_to_offerer = await asyncio.wait_for(offerer_received.get(), timeout=5.0)

            assert msg_to_answerer == b"hello from offerer"
            assert msg_to_offerer == b"hello from answerer"

        finally:
            await offerer.close()
            await answerer.close()

    async def test_non_negotiated_channel_fails_with_negotiated_peer(self, rtc_config):
        """Mixing negotiated and non-negotiated channels fails.

        This test documents the failure mode that caused the original bug.
        When one side uses negotiated=True and the other waits for on("datachannel"),
        no data channel is established because on("datachannel") never fires
        for negotiated channels.
        """
        offerer = RTCPeerConnection(configuration=rtc_config)
        answerer = RTCPeerConnection(configuration=rtc_config)

        try:
            # Offerer creates negotiated channel (like Android)
            offerer.createDataChannel(
                DATA_CHANNEL_LABEL,
                negotiated=True,
                id=DATA_CHANNEL_ID,
            )

            # Answerer waits for on("datachannel") - WRONG for negotiated!
            answerer_channel_received = asyncio.Event()
            received_channel = None

            @answerer.on("datachannel")
            def on_datachannel(channel):
                nonlocal received_channel
                received_channel = channel
                answerer_channel_received.set()

            # Exchange SDP
            offer = await offerer.createOffer()
            await offerer.setLocalDescription(offer)
            await answerer.setRemoteDescription(offer)

            answer = await answerer.createAnswer()
            await answerer.setLocalDescription(answer)
            await offerer.setRemoteDescription(answer)

            # The on("datachannel") callback should NOT fire for negotiated channels
            with pytest.raises(asyncio.TimeoutError):
                await asyncio.wait_for(answerer_channel_received.wait(), timeout=0.5)

            assert received_channel is None, "on('datachannel') should not fire for negotiated channels"

        finally:
            await offerer.close()
            await answerer.close()

    async def test_peer_connection_accept_offer_uses_negotiated_channel(self):
        """Verify PeerConnection.accept_offer creates a negotiated channel.

        This is the code path used when daemon receives an offer from Android.
        Android creates a negotiated channel, so daemon must also create one
        with the same config for bidirectional communication to work.
        """
        from ras.peer import PeerConnection

        # Simulate Android: create negotiated channel
        android_pc = RTCPeerConnection(configuration=RTCConfiguration(iceServers=[]))
        android_channel = android_pc.createDataChannel(
            DATA_CHANNEL_LABEL,
            negotiated=DATA_CHANNEL_NEGOTIATED,
            id=DATA_CHANNEL_ID,
            ordered=DATA_CHANNEL_ORDERED,
        )

        # Track messages
        android_received = asyncio.Queue()
        daemon_received = []
        android_channel_opened = asyncio.Event()

        @android_channel.on("open")
        def on_android_open():
            android_channel_opened.set()

        @android_channel.on("message")
        def on_android_message(message):
            android_received.put_nowait(message)

        try:
            # Android creates offer
            offer = await android_pc.createOffer()
            await android_pc.setLocalDescription(offer)
            await wait_ice_gathering(android_pc)

            # Daemon accepts offer using PeerConnection wrapper
            async with PeerConnection(stun_servers=[]) as daemon:
                daemon.on_message(lambda m: daemon_received.append(m))

                # This is the key: accept_offer must create negotiated channel
                answer = await daemon.accept_offer(android_pc.localDescription.sdp)

                await android_pc.setRemoteDescription(
                    type(android_pc.localDescription)(sdp=answer, type="answer")
                )

                # Wait for connection
                await asyncio.wait_for(
                    asyncio.gather(
                        android_channel_opened.wait(),
                        daemon.wait_connected(timeout=10),
                    ),
                    timeout=15.0,
                )

                # Test bidirectional communication
                android_channel.send(b"from android")
                await daemon.send(b"from daemon")

                # Verify messages
                msg_from_android = await asyncio.wait_for(
                    asyncio.sleep(0.5), timeout=2.0
                )
                msg_from_daemon = await asyncio.wait_for(
                    android_received.get(), timeout=5.0
                )

                assert b"from android" in daemon_received
                assert msg_from_daemon == b"from daemon"

        finally:
            await android_pc.close()
