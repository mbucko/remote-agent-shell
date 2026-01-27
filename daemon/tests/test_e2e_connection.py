"""End-to-end connection tests."""

import asyncio

import pytest

from ras.peer import PeerConnection
from ras.protocols import PeerState


@pytest.mark.integration
class TestE2EConnection:
    """End-to-end tests for WebRTC connections."""

    async def test_two_peers_connect_locally(self):
        """Two peers can connect and exchange messages locally."""
        received_peer1 = []
        received_peer2 = []

        async def on_message_1(msg):
            received_peer1.append(msg)

        async def on_message_2(msg):
            received_peer2.append(msg)

        async with PeerConnection(stun_servers=[]) as peer1, PeerConnection(stun_servers=[]) as peer2:
            peer1.on_message(on_message_1)
            peer2.on_message(on_message_2)

            # Exchange SDP
            offer = await peer1.create_offer()
            answer = await peer2.accept_offer(offer)
            await peer1.set_remote_description(answer)

            # Wait for connection
            await asyncio.gather(
                peer1.wait_connected(timeout=30),
                peer2.wait_connected(timeout=30),
            )

            assert peer1.state == PeerState.CONNECTED
            assert peer2.state == PeerState.CONNECTED

            # Exchange messages
            await peer1.send(b"hello from peer1")
            await peer2.send(b"hello from peer2")

            # Wait for messages to arrive
            await asyncio.sleep(0.5)

            assert b"hello from peer2" in received_peer1
            assert b"hello from peer1" in received_peer2

    async def test_signaling_server_flow(self):
        """Full flow: signaling server, client peer connects."""
        import aiohttp

        from ras.signaling import SignalingServer

        connected_peers = []

        async def on_connected(sid, peer):
            connected_peers.append((sid, peer))

        # Create server with random available port
        server = SignalingServer(host="127.0.0.1", port=0)
        server.on_connected(on_connected)

        # Start server
        await server.start()

        # Get the actual port
        actual_port = server.actual_port
        assert actual_port > 0

        try:
            session_id = server.create_session()

            # Create a client peer
            client_peer = PeerConnection(stun_servers=[])
            client_received = []

            async def on_client_message(msg):
                client_received.append(msg)

            client_peer.on_message(on_client_message)

            # Client creates offer
            offer = await client_peer.create_offer()

            # Post to signaling server
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"http://127.0.0.1:{actual_port}/signal/{session_id}",
                    json={"offer": offer},
                ) as resp:
                    assert resp.status == 200
                    data = await resp.json()
                    answer = data["answer"]

            # Set remote description
            await client_peer.set_remote_description(answer)

            # Wait for connection
            await client_peer.wait_connected(timeout=30)
            assert client_peer.state == PeerState.CONNECTED

            # Give time for server callback
            await asyncio.sleep(0.5)

            # Verify server got the connection
            assert len(connected_peers) == 1
            assert connected_peers[0][0] == session_id

            # Clean up
            await client_peer.close()

        finally:
            await server.close()

    async def test_multiple_messages(self):
        """Peers can exchange multiple messages."""
        received = {"peer1": [], "peer2": []}

        async with PeerConnection(stun_servers=[]) as peer1, PeerConnection(stun_servers=[]) as peer2:
            peer1.on_message(lambda m: received["peer1"].append(m))
            peer2.on_message(lambda m: received["peer2"].append(m))

            # Connect
            offer = await peer1.create_offer()
            answer = await peer2.accept_offer(offer)
            await peer1.set_remote_description(answer)

            await asyncio.gather(
                peer1.wait_connected(timeout=30),
                peer2.wait_connected(timeout=30),
            )

            # Send multiple messages
            for i in range(5):
                await peer1.send(f"msg{i}".encode())
                await peer2.send(f"reply{i}".encode())

            await asyncio.sleep(0.5)

            assert len(received["peer1"]) == 5
            assert len(received["peer2"]) == 5
