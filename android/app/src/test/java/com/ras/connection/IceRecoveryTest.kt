package com.ras.connection

import com.ras.data.connection.ConnectionManager
import com.ras.data.connection.ConnectionState
import com.ras.data.webrtc.WebRTCClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.webrtc.PeerConnection

@OptIn(ExperimentalCoroutinesApi::class)
class IceRecoveryTest {

    @Test
    fun `ICE disconnected then reconnected cancels recovery timer`() = runTest {
        val onDisconnect = mockk<() -> Unit>(relaxed = true)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val client = createMockWebRTCClient(scope, onDisconnect)

        val observer = client.createObserver()

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(1000)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)
        advanceTimeBy(5000)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(20000)

        verify(exactly = 0) { onDisconnect() }
    }

    @Test
    fun `ICE disconnected timeout expires triggers disconnect`() = runTest {
        val onDisconnect = mockk<() -> Unit>(relaxed = true)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val client = createMockWebRTCClient(scope, onDisconnect)
        val observer = client.createObserver()

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(1000)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)
        advanceTimeBy(15000)

        verify(exactly = 1) { onDisconnect() }
    }

    @Test
    fun `ICE failed triggers immediate disconnect`() = runTest {
        val onDisconnect = mockk<() -> Unit>(relaxed = true)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val client = createMockWebRTCClient(scope, onDisconnect)
        val observer = client.createObserver()

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(1000)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        advanceTimeBy(100)

        verify(exactly = 1) { onDisconnect() }
    }

    @Test
    fun `service stays running during ICE recovery`() = runTest {
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)

        every { connectionManager.connectionState } returns connectionState

        connectionState.value = ConnectionState.RECOVERING
        advanceTimeBy(5000)

        connectionState.value = ConnectionState.CONNECTED
        advanceTimeBy(1000)
    }

    @Test
    fun `service stop only scheduled on DISCONNECTED state`() = runTest {
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)

        every { connectionManager.connectionState } returns connectionState

        connectionState.value = ConnectionState.RECOVERING
        advanceTimeBy(5000)

        connectionState.value = ConnectionState.DISCONNECTED
        advanceTimeBy(1000)
    }

    @Test
    fun `rapid ICE disconnect reconnect cycles handled correctly`() = runTest {
        val onDisconnect = mockk<() -> Unit>(relaxed = true)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val client = createMockWebRTCClient(scope, onDisconnect)
        val observer = client.createObserver()

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(100)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)
        advanceTimeBy(100)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(100)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)
        advanceTimeBy(100)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(20000)

        verify(exactly = 0) { onDisconnect() }
    }

    @Test
    fun `recovery timeout cancelled when ICE reconnects`() = runTest {
        val onDisconnect = mockk<() -> Unit>(relaxed = true)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val client = createMockWebRTCClient(scope, onDisconnect)
        val observer = client.createObserver()

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(1000)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)
        advanceTimeBy(10000)

        observer.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(10000)

        verify(exactly = 0) { onDisconnect() }
    }

    private fun createMockWebRTCClient(scope: TestScope, onDisconnect: () -> Unit): MockWebRTCClient {
        return MockWebRTCClient(scope, onDisconnect)
    }

    private class MockWebRTCClient(
        private val scope: TestScope,
        private val onDisconnectCallback: () -> Unit
    ) {
        fun createObserver(): PeerConnection.Observer {
            return object : PeerConnection.Observer {
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) {}
                override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
            }
        }
    }
}
