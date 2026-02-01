package com.ras.data.webrtc

import android.content.Context
import android.util.Log
import com.ras.util.GlobalConnectionTimer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * Ownership:
 * - Tracks who currently owns the connection (PairingManager or ConnectionManager)
 * - Only the current owner can close the connection
 * - Ownership must be explicitly transferred via transferOwnership()
 */
class WebRTCClient(
    private val context: Context,
    private val peerConnectionFactory: PeerConnectionFactory,
    initialOwner: ConnectionOwnership = ConnectionOwnership.PairingManager
) {
    companion object {
        private const val TAG = "WebRTCClient"
        private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024 // 16 MB max message size
        private const val RECEIVE_TIMEOUT_MS = 30_000L // 30 second receive timeout
        private const val HEARTBEAT_INTERVAL_MS = 15_000L // 15 second heartbeat interval
        // Buffer threshold for backpressure - wait if buffer exceeds this
        private const val BUFFER_LOW_THRESHOLD = 64 * 1024L // 64 KB
        // ICE gathering timeout - reduced from 10s because COMPLETE state may not fire
        // during offer creation on Android. Host candidates are gathered immediately,
        // STUN candidates within 1-2 seconds.
        private const val ICE_GATHERING_TIMEOUT_MS = 2_000L
    }

    // Lock for thread-safe access to connection state
    private val lock = Any()

    @Volatile
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var dataChannel: DataChannel? = null

    @Volatile
    private var isClosed = false

    // Ownership tracking to prevent race conditions during handoff
    @Volatile
    private var owner: ConnectionOwnership = initialOwner

    private val dataChannelOpened = CompletableDeferred<Boolean>()
    private val messageChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var iceGatheringComplete = CompletableDeferred<Unit>()

    // Backpressure: signal when buffer drains below threshold
    @Volatile
    private var bufferDrainedDeferred: CompletableDeferred<Unit>? = null
    private val sendMutex = Mutex()

    // ICE candidate tracking
    @Volatile
    private var gatheredCandidateCount = 0

    // Heartbeat tracking
    @Volatile
    private var lastSendTime: Long = 0

    @Volatile
    private var lastReceiveTime: Long = 0

    // Callback for when connection is lost (data channel closed or ICE failed)
    private var onDisconnect: (() -> Unit)? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
    )

    /**
     * Create an SDP offer for initiating a connection.
     *
     * This function waits for ICE gathering to complete before returning,
     * ensuring all ICE candidates are included in the SDP offer.
     */
    suspend fun createOffer(): String {
        checkNotClosed()

        // Reset ICE gathering state for this offer
        iceGatheringComplete = CompletableDeferred()
        gatheredCandidateCount = 0

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            // Pre-gather ICE candidates to speed up subsequent connections
            iceCandidatePoolSize = 2
            // Use aggressive nomination for faster ICE selection on reconnects
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
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

        val localDescSet = CompletableDeferred<Unit>()

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onSetSuccess() {
                        localDescSet.complete(Unit)
                    }
                    override fun onCreateFailure(error: String?) {
                        localDescSet.completeExceptionally(Exception(error))
                    }
                    override fun onSetFailure(error: String?) {
                        localDescSet.completeExceptionally(Exception(error))
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                localDescSet.completeExceptionally(Exception(error))
            }
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())

        // Wait for local description to be set
        localDescSet.await()

        // Wait for ICE gathering to complete
        // Note: On Android, IceGatheringState.COMPLETE may not fire during offer creation,
        // only after setting remote description. Use short timeout since host candidates
        // are gathered immediately and STUN candidates within 1-2 seconds.
        Log.d(TAG, "Waiting for ICE gathering (${ICE_GATHERING_TIMEOUT_MS}ms timeout)...")
        val gatheringCompleted = withTimeoutOrNull(ICE_GATHERING_TIMEOUT_MS) {
            iceGatheringComplete.await()
            true
        } ?: false

        if (gatheringCompleted) {
            Log.i(TAG, "ICE gathering completed with $gatheredCandidateCount candidates")
        } else {
            Log.w(TAG, "ICE gathering timeout after ${ICE_GATHERING_TIMEOUT_MS}ms, proceeding with $gatheredCandidateCount candidates")
        }

        // Return the local description WITH gathered ICE candidates
        val localDesc = peerConnection?.localDescription
            ?: throw IllegalStateException("Local description not available")

        // Inject VPN candidates (Tailscale, etc.) that WebRTC might have missed
        val sdpWithVpn = VpnCandidateInjector.injectVpnCandidates(localDesc.description)

        // Validate SDP contains candidates
        SdpValidator.requireCandidates(sdpWithVpn, "Offer SDP")
        Log.d(TAG, "Offer contains ${SdpValidator.countCandidates(sdpWithVpn)} ICE candidates (after VPN injection)")

        return sdpWithVpn
    }

    /**
     * Set the remote SDP answer from the daemon.
     */
    suspend fun setRemoteDescription(sdpAnswer: String) {
        checkNotClosed()

        // Log remote candidates from answer
        val candidateCount = sdpAnswer.lines().count { it.startsWith("a=candidate:") }
        Log.d(TAG, "Setting remote description with $candidateCount ICE candidates")
        sdpAnswer.lines()
            .filter { it.startsWith("a=candidate:") }
            .forEach { Log.d(TAG, "Remote candidate: $it") }

        val answerDeferred = CompletableDeferred<Unit>()

        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully, ICE should start")
                answerDeferred.complete(Unit)
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
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
     * Send data over the data channel with backpressure support.
     *
     * If the send buffer is full, waits for it to drain before sending.
     * This prevents buffer overflow when sending large amounts of data (e.g., images).
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

        // Serialize sends to ensure proper ordering with backpressure
        sendMutex.withLock {
            val channel = synchronized(lock) { dataChannel }
                ?: throw IllegalStateException("Data channel not available")

            // Wait for buffer to drain if above threshold
            while (channel.bufferedAmount() > BUFFER_LOW_THRESHOLD) {
                checkNotClosed()
                if (channel.state() != DataChannel.State.OPEN) {
                    throw IllegalStateException("Data channel closed while waiting for buffer")
                }

                // Create deferred to wait for buffer drain callback
                val deferred = CompletableDeferred<Unit>()
                bufferDrainedDeferred = deferred

                // Wait for onBufferedAmountChange to signal drain (with timeout)
                val drained = withTimeoutOrNull(5000L) {
                    deferred.await()
                    true
                } ?: false

                if (!drained) {
                    Log.w(TAG, "Buffer drain timeout, retrying send")
                }
            }

            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
            val sent = channel.send(buffer)
            if (!sent) {
                throw IllegalStateException("Failed to send data: channel closed")
            }
            lastSendTime = System.currentTimeMillis()
        }
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
     * Get the current owner of this connection.
     */
    fun getOwner(): ConnectionOwnership = owner

    /**
     * Set callback for when connection is lost.
     * Called when data channel closes or ICE connection fails.
     */
    fun onDisconnect(callback: () -> Unit) {
        onDisconnect = callback
    }

    /**
     * Transfer ownership of this connection to a new owner.
     *
     * @param newOwner The new owner to transfer to
     * @return true if transfer succeeded, false if connection is disposed
     */
    fun transferOwnership(newOwner: ConnectionOwnership): Boolean {
        synchronized(lock) {
            if (owner == ConnectionOwnership.Disposed) {
                Log.w(TAG, "transferOwnership() called but connection is disposed")
                return false
            }
            if (isClosed) {
                Log.w(TAG, "transferOwnership() called but connection is closed")
                return false
            }
            val previousOwner = owner
            owner = newOwner
            Log.d(TAG, "Ownership transferred from $previousOwner to $newOwner")
            return true
        }
    }

    /**
     * Close the connection, but only if the caller is the current owner.
     * This prevents race conditions where cleanup code tries to close a connection
     * that has already been handed off to another owner.
     *
     * @param caller The owner attempting to close
     * @return true if the connection was closed, false if caller is not owner
     */
    fun closeByOwner(caller: ConnectionOwnership): Boolean {
        synchronized(lock) {
            if (owner != caller) {
                Log.w(TAG, "closeByOwner() called by $caller but owner is $owner - ignoring")
                return false
            }
            owner = ConnectionOwnership.Disposed
        }
        // Do actual close outside synchronized to avoid holding lock during cleanup
        doClose()
        return true
    }

    /**
     * Close the connection and release resources.
     * Safe to call multiple times.
     *
     * Note: Prefer closeByOwner() when ownership tracking is in use.
     * This method is kept for backward compatibility.
     */
    fun close() {
        synchronized(lock) {
            owner = ConnectionOwnership.Disposed
        }
        doClose()
    }

    /**
     * Internal close implementation.
     */
    private fun doClose() {
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
                    PeerConnection.IceConnectionState.CHECKING -> {
                        GlobalConnectionTimer.logMark("ice_checking")
                        Log.d(TAG, "ICE checking - testing candidate pairs")
                    }
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        GlobalConnectionTimer.logMark("ice_connected")
                        // Log connection stats to see which candidate pair succeeded
                        peerConnection?.getStats { report ->
                            report.statsMap.values
                                .filter { it.type == "candidate-pair" && it.members["state"] == "succeeded" }
                                .forEach { pair ->
                                    val localId = pair.members["localCandidateId"] as? String
                                    val remoteId = pair.members["remoteCandidateId"] as? String
                                    Log.i(TAG, "ICE succeeded with pair: local=$localId, remote=$remoteId")
                                }
                            report.statsMap.values
                                .filter { it.type == "local-candidate" || it.type == "remote-candidate" }
                                .forEach { candidate ->
                                    val id = candidate.id
                                    val candidateType = candidate.members["candidateType"] as? String
                                    val ip = candidate.members["ip"] as? String ?: candidate.members["address"] as? String
                                    Log.d(TAG, "Candidate $id: type=$candidateType, ip=$ip")
                                }
                        }
                        Log.i(TAG, "ICE connected! Peer-to-peer connection established")
                    }
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        GlobalConnectionTimer.logMark("ice_completed")
                        Log.i(TAG, "ICE completed - all checks done")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        GlobalConnectionTimer.logMark("ice_${state.toString().lowercase()}")
                        Log.w(TAG, "ICE connection lost: $state")
                        // Notify listener that connection is lost
                        onDisconnect?.invoke()
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
                when (state) {
                    PeerConnection.IceGatheringState.GATHERING -> {
                        GlobalConnectionTimer.logMark("ice_gathering_start")
                    }
                    PeerConnection.IceGatheringState.COMPLETE -> {
                        GlobalConnectionTimer.logMark("ice_gathering_complete")
                        iceGatheringComplete.complete(Unit)
                    }
                    else -> {}
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    gatheredCandidateCount++
                    // Log candidate type for debugging (host, srflx, relay)
                    val candidateType = when {
                        candidate.sdp.contains("typ host") -> "host"
                        candidate.sdp.contains("typ srflx") -> "srflx"
                        candidate.sdp.contains("typ relay") -> "relay"
                        candidate.sdp.contains("typ prflx") -> "prflx"
                        else -> "unknown"
                    }
                    Log.d(TAG, "ICE candidate #$gatheredCandidateCount ($candidateType): ${candidate.sdp}")
                }
            }
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
            override fun onBufferedAmountChange(previousAmount: Long) {
                val channel = synchronized(lock) { dataChannel } ?: return
                val currentAmount = channel.bufferedAmount()

                // Signal waiting coroutines if buffer dropped below threshold
                if (currentAmount <= BUFFER_LOW_THRESHOLD && previousAmount > BUFFER_LOW_THRESHOLD) {
                    bufferDrainedDeferred?.complete(Unit)
                    bufferDrainedDeferred = null
                }
            }

            override fun onStateChange() {
                val channel = synchronized(lock) { dataChannel }
                val state = channel?.state()
                Log.d(TAG, "Data channel state: $state")

                when (state) {
                    DataChannel.State.OPEN -> {
                        GlobalConnectionTimer.logMark("channel_open")
                        dataChannelOpened.complete(true)
                    }
                    DataChannel.State.CLOSED -> {
                        GlobalConnectionTimer.logMark("channel_closed")
                        Log.w(TAG, "Data channel closed")
                        // Close message channel so receive() doesn't hang
                        messageChannel.close()
                        // Notify listener that connection is lost
                        onDisconnect?.invoke()
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
        fun create(
            initialOwner: ConnectionOwnership = ConnectionOwnership.PairingManager
        ): WebRTCClient {
            return WebRTCClient(context, peerConnectionFactory, initialOwner)
        }
    }
}
