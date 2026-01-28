package com.ras.data.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * WebRTC client for establishing peer-to-peer connections.
 *
 * Thread Safety:
 * - This class is thread-safe. WebRTC callbacks occur on different threads.
 * - State fields are @Volatile for visibility across threads.
 * - Critical sections are synchronized.
 */
class WebRTCClient(
    private val context: Context,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    companion object {
        private const val TAG = "WebRTCClient"
        private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024 // 16 MB max message size
        private const val RECEIVE_TIMEOUT_MS = 30_000L // 30 second receive timeout
        private const val HEARTBEAT_INTERVAL_MS = 15_000L // 15 second heartbeat interval
    }

    // Lock for thread-safe access to connection state
    private val lock = Any()

    @Volatile
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var dataChannel: DataChannel? = null

    @Volatile
    private var isClosed = false

    private val dataChannelOpened = CompletableDeferred<Boolean>()
    private val messageChannel = Channel<ByteArray>(Channel.UNLIMITED)

    // Heartbeat tracking
    @Volatile
    private var lastSendTime: Long = 0

    @Volatile
    private var lastReceiveTime: Long = 0

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    /**
     * Create an SDP offer for initiating a connection.
     */
    suspend fun createOffer(): String {
        checkNotClosed()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        synchronized(lock) {
            peerConnection = peerConnectionFactory.createPeerConnection(
                rtcConfig,
                createPeerConnectionObserver()
            )

            // Create data channel for control messages
            val dataChannelInit = DataChannel.Init().apply {
                ordered = true
                negotiated = true
                id = 0
            }
            dataChannel = peerConnection?.createDataChannel("ras-control", dataChannelInit)
            dataChannel?.registerObserver(createDataChannelObserver())
        }

        val offerDeferred = CompletableDeferred<SessionDescription>()

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onSetSuccess() {
                        offerDeferred.complete(sdp)
                    }
                    override fun onCreateFailure(error: String?) {
                        offerDeferred.completeExceptionally(Exception(error))
                    }
                    override fun onSetFailure(error: String?) {
                        offerDeferred.completeExceptionally(Exception(error))
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                offerDeferred.completeExceptionally(Exception(error))
            }
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())

        return offerDeferred.await().description
    }

    /**
     * Set the remote SDP answer from the daemon.
     */
    suspend fun setRemoteDescription(sdpAnswer: String) {
        checkNotClosed()

        val answerDeferred = CompletableDeferred<Unit>()

        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                answerDeferred.complete(Unit)
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                answerDeferred.completeExceptionally(Exception(error))
            }
        }, sdp)

        answerDeferred.await()
    }

    /**
     * Wait for the data channel to open.
     */
    suspend fun waitForDataChannel(timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            dataChannelOpened.await()
        } ?: false
    }

    /**
     * Send data over the data channel.
     *
     * @param data The data to send
     * @throws IllegalStateException if connection is closed
     * @throws IllegalArgumentException if data exceeds max size
     */
    suspend fun send(data: ByteArray) {
        checkNotClosed()
        require(data.size <= MAX_MESSAGE_SIZE) {
            "Message too large: ${data.size} bytes exceeds maximum $MAX_MESSAGE_SIZE"
        }

        val channel = synchronized(lock) { dataChannel }
        if (channel == null) {
            throw IllegalStateException("Data channel not available")
        }

        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
        val sent = channel.send(buffer)
        if (!sent) {
            throw IllegalStateException("Failed to send data: buffer full or channel closed")
        }
        lastSendTime = System.currentTimeMillis()
    }

    /**
     * Receive data from the data channel with timeout.
     *
     * @param timeoutMs Optional timeout in milliseconds (default: 30 seconds)
     * @return The received data
     * @throws IllegalStateException if connection is closed or timeout
     */
    suspend fun receive(timeoutMs: Long = RECEIVE_TIMEOUT_MS): ByteArray {
        checkNotClosed()

        val result = withTimeoutOrNull(timeoutMs) {
            messageChannel.receive()
        }

        if (result == null) {
            if (isClosed) {
                throw IllegalStateException("Connection closed")
            }
            throw IllegalStateException("Receive timeout after ${timeoutMs}ms")
        }

        lastReceiveTime = System.currentTimeMillis()
        return result
    }

    /**
     * Receive data without timeout (for backward compatibility).
     * Use receive(timeoutMs) for production code.
     */
    suspend fun receiveBlocking(): ByteArray {
        checkNotClosed()
        val data = messageChannel.receive()
        lastReceiveTime = System.currentTimeMillis()
        return data
    }

    /**
     * Check if the connection is healthy based on heartbeat timing.
     *
     * @param maxIdleMs Maximum allowed idle time
     * @return true if connection appears healthy
     */
    fun isHealthy(maxIdleMs: Long = HEARTBEAT_INTERVAL_MS * 3): Boolean {
        if (isClosed) return false
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false

        // If we've never received, check if we've been connected long enough
        if (lastReceiveTime == 0L) {
            return System.currentTimeMillis() - lastSendTime < maxIdleMs
        }

        return System.currentTimeMillis() - lastReceiveTime < maxIdleMs
    }

    /**
     * Get time since last received message.
     */
    fun getIdleTimeMs(): Long {
        return if (lastReceiveTime == 0L) 0 else System.currentTimeMillis() - lastReceiveTime
    }

    /**
     * Check if the data channel is open and ready.
     */
    fun isReady(): Boolean {
        if (isClosed) return false
        val channel = synchronized(lock) { dataChannel } ?: return false
        return channel.state() == DataChannel.State.OPEN
    }

    /**
     * Close the connection and release resources.
     * Safe to call multiple times.
     */
    fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true

            try {
                dataChannel?.unregisterObserver()
                dataChannel?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing data channel", e)
            }
            dataChannel = null

            try {
                peerConnection?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing peer connection", e)
            }
            peerConnection = null

            messageChannel.close()
        }

        // Complete the deferred if still waiting
        dataChannelOpened.complete(false)
        Log.d(TAG, "WebRTC client closed")
    }

    private fun checkNotClosed() {
        if (isClosed) {
            throw IllegalStateException("WebRTC client is closed")
        }
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.w(TAG, "ICE connection lost: $state")
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
        }
    }

    private fun createDataChannelObserver(): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val channel = synchronized(lock) { dataChannel }
                val state = channel?.state()
                Log.d(TAG, "Data channel state: $state")

                when (state) {
                    DataChannel.State.OPEN -> {
                        dataChannelOpened.complete(true)
                    }
                    DataChannel.State.CLOSED -> {
                        Log.w(TAG, "Data channel closed")
                    }
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val size = buffer.data.remaining()

                // Validate message size
                if (size > MAX_MESSAGE_SIZE) {
                    Log.e(TAG, "Received oversized message: $size bytes, dropping")
                    return
                }

                if (size == 0) {
                    Log.w(TAG, "Received empty message, ignoring")
                    return
                }

                val data = ByteArray(size)
                buffer.data.get(data)

                val sent = messageChannel.trySend(data)
                if (!sent.isSuccess) {
                    Log.w(TAG, "Failed to enqueue message: channel full or closed")
                }
            }
        }
    }

    /**
     * Factory for creating WebRTCClient instances.
     */
    class Factory @Inject constructor(
        private val context: Context,
        private val peerConnectionFactory: PeerConnectionFactory
    ) {
        fun create(): WebRTCClient {
            return WebRTCClient(context, peerConnectionFactory)
        }
    }
}
