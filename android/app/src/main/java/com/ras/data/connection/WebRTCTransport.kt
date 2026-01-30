package com.ras.data.connection

import com.ras.data.webrtc.WebRTCClient

/**
 * Transport implementation wrapping WebRTCClient.
 *
 * Adapts the existing WebRTCClient to the Transport interface.
 */
class WebRTCTransport(
    private val webRTCClient: WebRTCClient
) : Transport {

    override val type: TransportType = TransportType.WEBRTC

    override val isConnected: Boolean
        get() = webRTCClient.isReady()

    private var bytesSent: Long = 0
    private var bytesReceived: Long = 0
    private var messagesSent: Long = 0
    private var messagesReceived: Long = 0
    private val connectedAt: Long = System.currentTimeMillis()
    private var lastActivity: Long = connectedAt

    override suspend fun send(data: ByteArray) {
        webRTCClient.send(data)
        bytesSent += data.size
        messagesSent++
        lastActivity = System.currentTimeMillis()
    }

    override suspend fun receive(timeoutMs: Long): ByteArray {
        val data = webRTCClient.receive(timeoutMs)
        bytesReceived += data.size
        messagesReceived++
        lastActivity = System.currentTimeMillis()
        return data
    }

    override fun close() {
        webRTCClient.close()
    }

    override fun getStats(): TransportStats {
        return TransportStats(
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            messagesSent = messagesSent,
            messagesReceived = messagesReceived,
            connectedAtMs = connectedAt,
            lastActivityMs = lastActivity,
            estimatedLatencyMs = null  // WebRTC doesn't easily expose this
        )
    }
}
