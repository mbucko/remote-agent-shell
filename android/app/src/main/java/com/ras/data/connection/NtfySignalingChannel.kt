package com.ras.data.connection

import android.util.Log
import com.ras.proto.DiscoveryResponse
import com.ras.signaling.CapabilityExchangeResult
import com.ras.signaling.DiscoveryResult
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
    private val host: String?,  // Optional - can use ntfy-only signaling
    private val port: Int?,     // Optional - can use ntfy-only signaling
    private val masterSecret: ByteArray,
    private val deviceId: String,
    private val deviceName: String,
    private val vpnHost: String? = null,
    private val vpnPort: Int? = null
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

        val ourCaps = com.ras.proto.ConnectionCapabilities.newBuilder()
            .setTailscaleIp(capabilities.tailscaleIp ?: "")
            .setTailscalePort(capabilities.tailscalePort ?: 0)
            .setSupportsWebrtc(capabilities.supportsWebRTC)
            .setSupportsTurn(capabilities.supportsTurn)
            .setProtocolVersion(capabilities.protocolVersion)
            .build()

        // Build list of hosts to try: primary (if available), then VPN if available
        val hostsToTry = mutableListOf<Pair<String, Int>>()
        if (host != null && port != null) {
            hostsToTry.add(Pair(host, port))
        }
        if (vpnHost != null && vpnPort != null && vpnHost != host) {
            hostsToTry.add(Pair(vpnHost, vpnPort))
        }

        // If no direct hosts available, rely on ntfy-only path
        if (hostsToTry.isEmpty()) {
            Log.d(TAG, "No direct hosts available for capability exchange, ntfy will be used")
        }

        for ((tryHost, tryPort) in hostsToTry) {
            val result = signaler.exchangeCapabilities(
                host = tryHost,
                port = tryPort,
                masterSecret = masterSecret,
                deviceId = deviceId,
                ourCapabilities = ourCaps,
                onProgress = onProgress
            )

            when (result) {
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
                    Log.i(TAG, "Got daemon capabilities via $tryHost:$tryPort: tailscale=${converted.tailscaleIp}:${converted.tailscalePort}")
                    return converted
                }
                is CapabilityExchangeResult.DeviceNotFound -> {
                    Log.w(TAG, "Capability exchange failed on $tryHost:$tryPort: device not found")
                    return null  // Don't retry - device genuinely not found
                }
                is CapabilityExchangeResult.AuthenticationFailed -> {
                    Log.w(TAG, "Capability exchange failed on $tryHost:$tryPort: auth failed")
                    return null  // Don't retry - auth genuinely failed
                }
                is CapabilityExchangeResult.NetworkError -> {
                    Log.w(TAG, "Capability exchange failed on $tryHost:$tryPort: network error, trying next host")
                    // Continue to next host
                }
            }
        }

        Log.w(TAG, "All hosts failed for capability exchange")
        return null
    }

    override suspend fun sendOffer(
        sdp: String,
        onProgress: SignalingProgressCallback?
    ): String? {
        Log.d(TAG, "Sending SDP offer via signaler")

        // Build list of hosts to try: primary (if available), then VPN if available
        val hostsToTry = mutableListOf<Pair<String, Int>>()
        if (host != null && port != null) {
            hostsToTry.add(Pair(host, port))
        }
        if (vpnHost != null && vpnPort != null && vpnHost != host) {
            hostsToTry.add(Pair(vpnHost, vpnPort))
        }

        // If no direct hosts available, use ntfy-only path
        if (hostsToTry.isEmpty()) {
            Log.w(TAG, "[SEND_OFFER] No direct hosts available, using ntfy-only signaling")
            
            // Call exchangeSdp with empty host/port to trigger ntfy-only path
            val result = signaler.exchangeSdp(
                host = "",
                port = 0,
                masterSecret = masterSecret,
                sdpOffer = sdp,
                deviceId = deviceId,
                deviceName = deviceName,
                onProgress = onProgress
            )
            
            when (result) {
                is ReconnectionSignalerResult.Success -> {
                    Log.i(TAG, "[SEND_OFFER] Got SDP answer via ntfy-only path")
                    result.capabilities?.let { caps ->
                        lastReceivedCapabilities = ConnectionCapabilities(
                            tailscaleIp = caps.tailscaleIp.takeIf { it.isNotEmpty() },
                            tailscalePort = caps.tailscalePort.takeIf { it > 0 },
                            supportsWebRTC = caps.supportsWebrtc,
                            supportsTurn = caps.supportsTurn,
                            protocolVersion = caps.protocolVersion
                        )
                    }
                    return result.sdpAnswer
                }
                is ReconnectionSignalerResult.NtfyTimeout -> {
                    Log.e(TAG, "[SEND_OFFER] Ntfy signaling timed out")
                    return null
                }
                else -> {
                    Log.e(TAG, "[SEND_OFFER] Ntfy signaling failed: $result")
                    return null
                }
            }
        }

        for ((tryHost, tryPort) in hostsToTry) {
            val result = signaler.exchangeSdp(
                host = tryHost,
                port = tryPort,
                masterSecret = masterSecret,
                sdpOffer = sdp,
                deviceId = deviceId,
                deviceName = deviceName,
                onProgress = onProgress
            )

            when (result) {
                is ReconnectionSignalerResult.Success -> {
                    Log.i(TAG, "Got SDP answer via $tryHost:$tryPort" + if (result.usedNtfyPath) " (ntfy)" else " (direct)")

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

                    return result.sdpAnswer
                }
                is ReconnectionSignalerResult.DeviceNotFound -> {
                    Log.e(TAG, "Device not found on daemon via $tryHost:$tryPort")
                    return null  // Don't retry
                }
                is ReconnectionSignalerResult.AuthenticationFailed -> {
                    Log.e(TAG, "Authentication failed during signaling via $tryHost:$tryPort")
                    return null  // Don't retry
                }
                is ReconnectionSignalerResult.NtfyTimeout -> {
                    Log.e(TAG, "Signaling timed out via $tryHost:$tryPort, trying next host")
                    // Continue to next host
                }
                is ReconnectionSignalerResult.Error -> {
                    Log.e(TAG, "Signaling error via $tryHost:$tryPort: ${result.message}, trying next host")
                    // Continue to next host
                }
            }
        }

        Log.e(TAG, "All hosts failed for SDP exchange")
        return null
    }

    override suspend fun close() {
        Log.d(TAG, "Closing signaling channel")
        // No cleanup needed - signaler manages its own resources
    }

    /**
     * Get the last received capabilities from daemon.
     */
    fun getReceivedCapabilities(): ConnectionCapabilities? = lastReceivedCapabilities

    /**
     * Discover daemon's current IPs via ntfy.
     *
     * Sends a DISCOVER message to get all available daemon IPs (LAN, VPN, Tailscale, public).
     *
     * @param onProgress Optional callback for progress events
     * @return DiscoveryResponse with all available IPs, or null if discovery failed
     */
    suspend fun discoverHosts(
        onProgress: SignalingProgressCallback? = null
    ): DiscoveryResponse? {
        Log.d(TAG, "Discovering daemon hosts via ntfy")

        val result = signaler.discoverHosts(
            masterSecret = masterSecret,
            deviceId = deviceId,
            onProgress = onProgress
        )

        return when (result) {
            is DiscoveryResult.Success -> {
                val discovery = result.discovery
                Log.i(TAG, "Discovered hosts: lan=${discovery.lanIp}:${discovery.lanPort}, " +
                        "vpn=${discovery.vpnIp}:${discovery.vpnPort}, " +
                        "tailscale=${discovery.tailscaleIp}:${discovery.tailscalePort}")
                discovery
            }
            is DiscoveryResult.DeviceNotFound -> {
                Log.w(TAG, "Host discovery failed: device not found")
                null
            }
            is DiscoveryResult.AuthenticationFailed -> {
                Log.w(TAG, "Host discovery failed: auth failed")
                null
            }
            is DiscoveryResult.NetworkError -> {
                Log.w(TAG, "Host discovery failed: network error")
                null
            }
        }
    }
}
