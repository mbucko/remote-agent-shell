"""Tests for TailscaleListener connection routing.

These tests prevent regression of Bug 1: Connection Tracking Bug
where _handle_packet() created a new TailscaleTransport for every
data packet instead of routing to existing connections.
"""

import asyncio
import struct
from typing import Any, Tuple
from unittest.mock import AsyncMock, Mock, patch

import pytest

from ras.tailscale.listener import TailscaleListener
from ras.tailscale.transport import HANDSHAKE_MAGIC, TailscaleProtocol, TailscaleTransport


@pytest.fixture
def mock_tailscale_info():
    """Mock detect_tailscale to return test Tailscale info."""
    info = Mock()
    info.ip = "100.64.0.1"
    info.interface = "utun0"
    return info


@pytest.fixture
def mock_protocol():
    """Create a mock TailscaleProtocol."""
    protocol = Mock(spec=TailscaleProtocol)
    protocol._queue = asyncio.Queue()
    protocol.send_handshake_response = Mock()
    return protocol


@pytest.fixture
def mock_transport():
    """Create a mock asyncio DatagramTransport."""
    transport = Mock()
    transport.sendto = Mock()
    transport.close = Mock()
    return transport


class TestConnectionReuse:
    """Tests that packets reuse existing connections after handshake."""

    @pytest.mark.asyncio
    async def test_handle_packet_routes_to_existing_connection(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Bug 1 regression: packets must route to existing transport, not create new ones."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # Simulate handshake
        await listener._handle_handshake(addr)

        # Verify connection was created and tracked
        assert addr in listener._connections
        transport = listener._connections[addr]
        assert isinstance(transport, TailscaleTransport)

        # Simulate data packet
        data = struct.pack(">I", 5) + b"hello"
        await listener._handle_packet(data, addr)

        # Should route to existing transport (packet put in queue)
        # Should NOT create new connection
        assert len(listener._connections) == 1
        assert listener._connections[addr] is transport

    @pytest.mark.asyncio
    async def test_handle_packet_does_not_create_transport_for_data(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Bug 1 regression: data packets should not trigger transport creation."""
        transport_creation_count = [0]
        original_transport_init = TailscaleTransport.__init__

        def counting_init(self, *args, **kwargs):
            transport_creation_count[0] += 1
            return original_transport_init(self, *args, **kwargs)

        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # Handshake should create one transport
        with patch.object(TailscaleTransport, '__init__', counting_init):
            await listener._handle_handshake(addr)
            assert transport_creation_count[0] == 1

            # Data packets should NOT create more transports
            for i in range(10):
                data = struct.pack(">I", 5) + f"msg{i:02d}".encode()[:5]
                await listener._handle_packet(data, addr)

            # Still only one transport created
            assert transport_creation_count[0] == 1

    @pytest.mark.asyncio
    async def test_handle_packet_retrieves_from_connections_dict(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Bug 1 regression: packets should be routed via _connections dict."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # Simulate handshake
        await listener._handle_handshake(addr)

        # Verify connection exists
        assert addr in listener._connections
        transport = listener._connections[addr]

        # Send data packet
        data = struct.pack(">I", 5) + b"hello"
        await listener._handle_packet(data, addr)

        # Packet should be in the transport's own queue (not protocol queue)
        queue_item = await asyncio.wait_for(
            transport._queue.get(),
            timeout=1.0
        )
        assert queue_item == (data, addr)


class TestMultiPacketRouting:
    """Tests that multiple packets all route to the same transport."""

    @pytest.mark.parametrize("packet_count", [10, 50, 100])
    @pytest.mark.asyncio
    async def test_multiple_packets_route_to_same_transport(
        self, packet_count, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Bug 1 regression: many packets should not create many transports."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)
        task = await listener._handle_handshake(addr)
        if task:
            await task

        # Send many packets
        for i in range(packet_count):
            data = struct.pack(">I", 5) + f"m{i:04d}"[:5].encode()
            await listener._handle_packet(data, addr)

        # Still only one connection
        assert len(listener._connections) == 1

    @pytest.mark.asyncio
    async def test_all_packets_queued_for_transport(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """All sent packets should be queued for the transport to receive."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)
        await listener._handle_handshake(addr)
        transport = listener._connections[addr]

        packet_count = 10
        packets_sent = []

        for i in range(packet_count):
            data = struct.pack(">I", 5) + f"m{i:04d}"[:5].encode()
            packets_sent.append(data)
            await listener._handle_packet(data, addr)

        # All packets should be in the transport's queue
        assert transport._queue.qsize() == packet_count

        # Verify packet contents
        for expected_data in packets_sent:
            queued_data, queued_addr = await transport._queue.get()
            assert queued_data == expected_data
            assert queued_addr == addr


class TestConnectionManagement:
    """Tests for connection tracking and management."""

    @pytest.mark.asyncio
    async def test_multiple_clients_get_separate_transports(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Each client address gets its own transport."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr1 = ("100.64.0.2", 12345)
        addr2 = ("100.64.0.3", 54321)

        await listener._handle_handshake(addr1)
        await listener._handle_handshake(addr2)

        assert len(listener._connections) == 2
        assert listener._connections[addr1] is not listener._connections[addr2]

    @pytest.mark.asyncio
    async def test_connections_dict_maps_address_to_transport(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """_connections dict correctly maps (host, port) tuple to transport."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addresses = [
            ("100.64.0.2", 12345),
            ("100.64.0.3", 12346),
            ("100.64.0.4", 12347),
        ]

        for addr in addresses:
            await listener._handle_handshake(addr)

        # Verify all connections tracked
        assert len(listener._connections) == 3
        for addr in addresses:
            assert addr in listener._connections
            assert isinstance(listener._connections[addr], TailscaleTransport)
            assert listener._connections[addr].remote_address == addr

    @pytest.mark.asyncio
    async def test_rehandshake_from_same_address_does_not_duplicate(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Re-handshake from same address should not create duplicate connection."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # First handshake
        await listener._handle_handshake(addr)
        first_transport = listener._connections[addr]
        assert connection_callback.call_count == 1

        # Second handshake from same address
        await listener._handle_handshake(addr)

        # Should still be the same connection (or replaced, but not duplicated)
        assert len(listener._connections) == 1
        # Re-handshake should use existing transport
        assert listener._connections[addr] is first_transport
        # Callback should not be called again for re-handshake
        assert connection_callback.call_count == 1


class TestUnknownAddressHandling:
    """Tests for handling packets from unknown addresses."""

    @pytest.mark.asyncio
    async def test_packet_from_unknown_address_is_ignored(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Bug 1 regression: unknown address should not create transport."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        # No handshake, just send data
        unknown_addr = ("100.64.0.99", 9999)
        data = struct.pack(">I", 5) + b"hello"

        await listener._handle_packet(data, unknown_addr)

        # Should not have created a connection
        assert unknown_addr not in listener._connections
        assert len(listener._connections) == 0

    @pytest.mark.asyncio
    async def test_no_transport_created_for_unknown_address(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Unknown addresses should not trigger transport creation."""
        transport_creation_count = [0]
        original_transport_init = TailscaleTransport.__init__

        def counting_init(self, *args, **kwargs):
            transport_creation_count[0] += 1
            return original_transport_init(self, *args, **kwargs)

        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        unknown_addr = ("100.64.0.99", 9999)

        with patch.object(TailscaleTransport, '__init__', counting_init):
            for i in range(10):
                data = struct.pack(">I", 5) + f"m{i:04d}"[:5].encode()
                await listener._handle_packet(data, unknown_addr)

            # No transports should have been created
            assert transport_creation_count[0] == 0

    @pytest.mark.asyncio
    async def test_no_exception_for_unknown_address(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Packets from unknown addresses should not raise exceptions."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        unknown_addr = ("100.64.0.99", 9999)
        data = struct.pack(">I", 5) + b"hello"

        # Should not raise
        await listener._handle_packet(data, unknown_addr)

        # Connection callback should never be called
        connection_callback.assert_not_called()


class TestIntegrationStyle:
    """Integration-style tests for complete flows."""

    @pytest.mark.asyncio
    async def test_full_flow_handshake_then_multiple_packets(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Full flow: handshake → multiple data packets → single transport used."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # Step 1: Handshake
        handshake_data = struct.pack(">II", HANDSHAKE_MAGIC, 0)
        await listener._handle_packet(handshake_data, addr)

        # Verify connection created
        assert addr in listener._connections
        connection_callback.assert_called_once()

        # Step 2: Multiple data packets
        for i in range(20):
            data = struct.pack(">I", 5) + f"m{i:04d}"[:5].encode()
            await listener._handle_packet(data, addr)

        # Step 3: Verify single transport used
        assert len(listener._connections) == 1

    @pytest.mark.asyncio
    async def test_two_clients_connect_packets_route_correctly(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Two clients connect, packets route to correct transports."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr1 = ("100.64.0.2", 12345)
        addr2 = ("100.64.0.3", 54321)

        # Both clients handshake
        await listener._handle_handshake(addr1)
        await listener._handle_handshake(addr2)

        assert len(listener._connections) == 2

        # Send interleaved packets from both clients
        for i in range(10):
            data1 = struct.pack(">I", 6) + f"A{i:04d}\n"[:6].encode()
            data2 = struct.pack(">I", 6) + f"B{i:04d}\n"[:6].encode()
            await listener._handle_packet(data1, addr1)
            await listener._handle_packet(data2, addr2)

        # Still only two connections
        assert len(listener._connections) == 2
        assert listener._connections[addr1] is not listener._connections[addr2]

    @pytest.mark.asyncio
    async def test_rapid_packet_burst(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Stress test: rapid packet bursts should not create multiple connections."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)
        await listener._handle_handshake(addr)

        # Simulate rapid burst of 1000 packets
        tasks = []
        for i in range(1000):
            data = struct.pack(">I", 10) + f"burst{i:05d}"[:10].encode()
            tasks.append(listener._handle_packet(data, addr))

        await asyncio.gather(*tasks)

        # Still only one connection
        assert len(listener._connections) == 1


class TestCallbackNotBlocking:
    """Tests that on_connection callback doesn't block the receive loop.

    Critical bug discovered: The listener was awaiting the on_connection
    callback inline, which blocked the receive loop. This prevented auth
    messages from being received while the callback was waiting for them.

    Flow that caused deadlock:
    1. Phone sends handshake
    2. Daemon receives, creates transport, awaits on_connection callback
    3. Callback (daemon._on_tailscale_connection) waits for auth via transport.receive()
    4. Phone sends auth packet
    5. BUG: Listener's receive loop is blocked in callback, can't receive auth
    6. After 10s timeout, callback returns
    7. Only NOW does listener receive the auth packet that was waiting in kernel buffer
    """

    @pytest.mark.asyncio
    async def test_callback_should_not_block_receive_loop(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Verify receive loop continues while callback is processing.

        This is the critical test for the deadlock bug.
        """
        # Track callback execution
        callback_started = asyncio.Event()
        callback_received_signal = asyncio.Event()
        callback_data_received = []

        async def slow_callback(transport):
            """Callback that waits for data from transport."""
            callback_started.set()
            try:
                # Try to receive data from transport (like daemon does for auth)
                # This should work if receive loop isn't blocked
                data = await asyncio.wait_for(
                    transport._queue.get(),
                    timeout=1.0
                )
                callback_data_received.append(data)
                callback_received_signal.set()
            except asyncio.TimeoutError:
                pass  # Expected if bug exists

        listener = TailscaleListener(on_connection=slow_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # Start handshake (this will create transport and call callback)
        handshake_task = asyncio.create_task(listener._handle_handshake(addr))

        # Wait for callback to start
        await asyncio.wait_for(callback_started.wait(), timeout=2.0)

        # Now send a data packet while callback is still running
        # If bug exists: receive loop is blocked, this won't be processed
        # If fixed: receive loop processes this and enqueues to transport
        auth_data = struct.pack(">I", 4) + b"test"
        await listener._handle_packet(auth_data, addr)

        # Wait a bit to see if callback gets the data
        try:
            await asyncio.wait_for(callback_received_signal.wait(), timeout=0.5)
            # Success! Callback received data while it was running
            assert len(callback_data_received) == 1
        except asyncio.TimeoutError:
            # Bug exists - callback didn't receive data
            # Let handshake task finish to avoid hanging
            handshake_task.cancel()
            try:
                await handshake_task
            except asyncio.CancelledError:
                pass

            pytest.fail(
                "Callback timed out waiting for data. "
                "This indicates the receive loop is blocked by the callback. "
                "Fix: Use asyncio.create_task() instead of await for the callback."
            )

        await handshake_task

    @pytest.mark.asyncio
    async def test_handle_handshake_spawns_callback_as_task(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Verify _handle_handshake returns quickly (doesn't await callback)."""
        callback_started = asyncio.Event()
        callback_can_finish = asyncio.Event()

        async def slow_callback(transport):
            callback_started.set()
            await callback_can_finish.wait()

        listener = TailscaleListener(on_connection=slow_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # _handle_handshake should return quickly, not wait for slow callback
        start = asyncio.get_event_loop().time()
        task = await listener._handle_handshake(addr)
        elapsed = asyncio.get_event_loop().time() - start

        # If _handle_handshake awaited the callback, it would block forever
        # If it spawns a task, it should return almost immediately
        assert elapsed < 0.1, (
            f"_handle_handshake took {elapsed:.2f}s but should return immediately. "
            "It's awaiting the callback instead of spawning it as a task."
        )

        # Wait for callback to confirm it started
        await asyncio.wait_for(callback_started.wait(), timeout=1.0)
        assert callback_started.is_set(), "Callback should have started"

        # Let callback finish and await the returned task to properly clean up
        callback_can_finish.set()
        if task:
            await task

    @pytest.mark.asyncio
    async def test_multiple_handshakes_can_process_concurrently(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Verify multiple handshakes don't serialize on slow callbacks."""
        callbacks_running = []
        all_callbacks_started = asyncio.Event()
        release_callbacks = asyncio.Event()

        async def blocking_callback(transport):
            my_id = len(callbacks_running) + 1
            callbacks_running.append(my_id)
            if len(callbacks_running) == 3:
                all_callbacks_started.set()
            await release_callbacks.wait()  # Block until released
            callbacks_running.remove(my_id)

        listener = TailscaleListener(on_connection=blocking_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        # Handle multiple handshakes rapidly
        addrs = [
            ("100.64.0.2", 12345),
            ("100.64.0.3", 12346),
            ("100.64.0.4", 12347),
        ]

        start = asyncio.get_event_loop().time()

        # All handshakes should complete quickly, collect the tasks
        tasks = []
        for addr in addrs:
            task = await listener._handle_handshake(addr)
            if task:
                tasks.append(task)

        elapsed = asyncio.get_event_loop().time() - start

        # If callbacks were awaited inline, they would block forever
        # With task spawning, it should return almost immediately
        assert elapsed < 0.1, (
            f"Processing 3 handshakes took {elapsed:.2f}s. "
            "Callbacks should be spawned as tasks, not awaited inline."
        )

        # Wait for all 3 callbacks to start
        await asyncio.wait_for(all_callbacks_started.wait(), timeout=1.0)

        # All 3 callbacks should be running concurrently (blocked on event)
        assert len(callbacks_running) == 3, (
            f"Expected 3 concurrent callbacks, got {len(callbacks_running)}. "
            "Callbacks are serializing instead of running concurrently."
        )

        # Release callbacks and await tasks
        release_callbacks.set()
        await asyncio.gather(*tasks)


class TestTransportCloseDoesNotCloseListenerSocket:
    """Tests that closing a TailscaleTransport does NOT close the shared listener socket.

    Critical bug fixed: When a phone disconnects, TailscaleTransport.close() was
    calling self._transport.close() which closed the shared UDP listener socket.
    This caused subsequent reconnection attempts to fail with "ICMP Port Unreachable".

    Root cause: In UDP, there's no concept of individual connections - the listener
    has ONE socket for ALL clients. Each TailscaleTransport is just a logical
    connection that shares the same underlying socket.
    """

    @pytest.mark.asyncio
    async def test_transport_close_does_not_close_underlying_socket(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Closing TailscaleTransport should NOT close the shared UDP socket."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # Client connects
        await listener._handle_handshake(addr)
        transport = listener._connections[addr]
        assert transport.is_connected

        # Client disconnects - close the transport
        transport.close()
        assert not transport.is_connected

        # CRITICAL: The listener's underlying socket should NOT be closed
        # If it was closed, mock_transport.close() would have been called
        mock_transport.close.assert_not_called()

    @pytest.mark.asyncio
    async def test_new_client_can_connect_after_previous_client_disconnects(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """After closing a transport, new clients should still be able to connect."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        # First client connects and disconnects
        addr1 = ("100.64.0.2", 12345)
        await listener._handle_handshake(addr1)
        transport1 = listener._connections[addr1]
        transport1.close()

        # Second client connects (simulating phone reconnect with different port)
        addr2 = ("100.64.0.2", 54321)  # Same IP, different port
        await listener._handle_handshake(addr2)

        # Second client should get a working transport
        assert addr2 in listener._connections
        transport2 = listener._connections[addr2]
        assert transport2.is_connected

        # Callback should have been called for second connection
        assert connection_callback.call_count == 2

    @pytest.mark.asyncio
    async def test_closed_connection_cleaned_up_on_next_packet(
        self, mock_tailscale_info, mock_protocol, mock_transport
    ):
        """Closed connections should be cleaned up when next packet arrives."""
        connection_callback = AsyncMock()
        listener = TailscaleListener(on_connection=connection_callback)
        listener._protocol = mock_protocol
        listener._transport = mock_transport
        listener._tailscale_info = mock_tailscale_info
        listener._running = True

        addr = ("100.64.0.2", 12345)

        # Client connects
        await listener._handle_handshake(addr)
        transport = listener._connections[addr]

        # Client disconnects (close transport)
        transport.close()
        assert not transport.is_connected

        # Connection still in dict (lazy cleanup)
        assert addr in listener._connections

        # Packet arrives from same address (late packet after disconnect)
        data = struct.pack(">I", 5) + b"hello"
        await listener._handle_packet(data, addr)

        # Connection should be cleaned up now
        assert addr not in listener._connections
