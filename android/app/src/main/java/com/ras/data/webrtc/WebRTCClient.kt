package com.ras.data.webrtc

import android.content.Context
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
 */
class WebRTCClient(
    private val context: Context,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private val dataChannelOpened = CompletableDeferred<Boolean>()
    private val messageChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    /**
     * Create an SDP offer for initiating a connection.
     */
    suspend fun createOffer(): String {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

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
     */
    suspend fun send(data: ByteArray) {
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
        dataChannel?.send(buffer)
    }

    /**
     * Receive data from the data channel.
     */
    suspend fun receive(): ByteArray {
        return messageChannel.receive()
    }

    /**
     * Close the connection and release resources.
     */
    fun close() {
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
        messageChannel.close()
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
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
                if (dataChannel?.state() == DataChannel.State.OPEN) {
                    dataChannelOpened.complete(true)
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                messageChannel.trySend(data)
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
