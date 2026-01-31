package com.ras.data.connection

import android.util.Log
import com.ras.signaling.CapabilityExchangeResult
import com.ras.signaling.ReconnectionSignaler
import com.ras.signaling.ReconnectionSignalerResult

/**
 * SignalingChannel implementation that uses ntfy for signaling.
 *
 * Wraps ReconnectionSignaler to implement the SignalingChannel interface
 * for use with ConnectionOrchestrator and strategies.
 */
class NtfySignalingChannel(
    private val signaler: ReconnectionSignaler,
    private val host: String,
    private val port: Int,
    private val masterSecret: ByteArray,
    private val deviceId: String,
    private val deviceName: String
) : SignalingChannel {

    companion object {
        private const val TAG = "NtfySignalingChannel"
    }

    private var lastReceivedCapabilities: ConnectionCapabilities? = null

    override suspend fun exchangeCapabilities(
        capabilities: ConnectionCapabilities,
        onProgress: SignalingProgressCallback?
    ): ConnectionCapabilities? {
        Log.d(TAG, "Exchanging capabilities with daemon")

        val result = signaler.exchangeCapabilities(
            host = host,
            port = port,
            masterSecret = masterSecret,
            deviceId = deviceId,
            ourCapabilities = com.ras.proto.ConnectionCapabilities.newBuilder()
                .setTailscaleIp(capabilities.tailscaleIp ?: "")
                .setTailscalePort(capabilities.tailscalePort ?: 0)
                .setSupportsWebrtc(capabilities.supportsWebRTC)
                .setSupportsTurn(capabilities.supportsTurn)
                .setProtocolVersion(capabilities.protocolVersion)
                .build(),
            onProgress = onProgress
        )

        return when (result) {
            is CapabilityExchangeResult.Success -> {
                val caps = result.capabilities
                val converted = ConnectionCapabilities(
                    tailscaleIp = caps.tailscaleIp.takeIf { it.isNotEmpty() },
                    tailscalePort = caps.tailscalePort.takeIf { it > 0 },
                    supportsWebRTC = caps.supportsWebrtc,
                    supportsTurn = caps.supportsTurn,
                    protocolVersion = caps.protocolVersion
                )
                lastReceivedCapabilities = converted
                Log.i(TAG, "Got daemon capabilities: tailscale=${converted.tailscaleIp}:${converted.tailscalePort}")
                converted
            }
            is CapabilityExchangeResult.NetworkError -> {
                Log.w(TAG, "Capability exchange failed: network error")
                null
            }
            is CapabilityExchangeResult.DeviceNotFound -> {
                Log.w(TAG, "Capability exchange failed: device not found")
                null
            }
            is CapabilityExchangeResult.AuthenticationFailed -> {
                Log.w(TAG, "Capability exchange failed: auth failed")
                null
            }
        }
    }

    override suspend fun sendOffer(
        sdp: String,
        onProgress: SignalingProgressCallback?
    ): String? {
        Log.d(TAG, "Sending SDP offer via signaler")

        val result = signaler.exchangeSdp(
            host = host,
            port = port,
            masterSecret = masterSecret,
            sdpOffer = sdp,
            deviceId = deviceId,
            deviceName = deviceName,
            onProgress = onProgress
        )

        return when (result) {
            is ReconnectionSignalerResult.Success -> {
                Log.i(TAG, "Got SDP answer" + if (result.usedNtfyPath) " via ntfy" else " direct")

                // Extract capabilities from answer if present
                result.capabilities?.let { caps ->
                    lastReceivedCapabilities = ConnectionCapabilities(
                        tailscaleIp = caps.tailscaleIp.takeIf { it.isNotEmpty() },
                        tailscalePort = caps.tailscalePort.takeIf { it > 0 },
                        supportsWebRTC = caps.supportsWebrtc,
                        supportsTurn = caps.supportsTurn,
                        protocolVersion = caps.protocolVersion
                    )
                    Log.i(TAG, "Received capabilities: tailscale=${lastReceivedCapabilities?.tailscaleIp}:${lastReceivedCapabilities?.tailscalePort}")
                }

                result.sdpAnswer
            }
            is ReconnectionSignalerResult.DeviceNotFound -> {
                Log.e(TAG, "Device not found on daemon")
                null
            }
            is ReconnectionSignalerResult.AuthenticationFailed -> {
                Log.e(TAG, "Authentication failed during signaling")
                null
            }
            is ReconnectionSignalerResult.NtfyTimeout -> {
                Log.e(TAG, "Signaling timed out")
                null
            }
            is ReconnectionSignalerResult.Error -> {
                Log.e(TAG, "Signaling error: ${result.message}")
                null
            }
        }
    }

    override suspend fun close() {
        Log.d(TAG, "Closing signaling channel")
        // No cleanup needed - signaler manages its own resources
    }

    /**
     * Get the last received capabilities from daemon.
     */
    fun getReceivedCapabilities(): ConnectionCapabilities? = lastReceivedCapabilities
}
