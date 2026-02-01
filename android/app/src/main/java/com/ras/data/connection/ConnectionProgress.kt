package com.ras.data.connection

/**
 * Progress updates from the ConnectionOrchestrator.
 *
 * Used for UI feedback showing what's happening during connection.
 */
sealed class ConnectionProgress {

    // ==================== Capability Discovery Phase ====================

    /**
     * Starting capability discovery.
     */
    object DiscoveryStarted : ConnectionProgress()

    /**
     * Detecting local Tailscale VPN.
     */
    object TailscaleDetecting : ConnectionProgress()

    /**
     * Local capabilities detected.
     */
    data class LocalCapabilities(
        val tailscaleIp: String?,
        val tailscaleInterface: String?,
        val supportsWebRTC: Boolean = true
    ) : ConnectionProgress()

    /**
     * Exchanging capabilities with daemon.
     */
    object ExchangingCapabilities : ConnectionProgress()

    /**
     * Trying direct HTTP for capability exchange.
     */
    data class CapabilityTryingDirect(val host: String, val port: Int) : ConnectionProgress()

    /**
     * Direct capability exchange timed out.
     */
    data class CapabilityDirectTimeout(val host: String, val port: Int) : ConnectionProgress()

    /**
     * Direct capability exchange succeeded (proves local network connectivity).
     */
    data class CapabilityDirectSuccess(val host: String, val port: Int) : ConnectionProgress()

    /**
     * Subscribing to ntfy for capability exchange.
     */
    data class CapabilityNtfySubscribing(val topic: String) : ConnectionProgress()

    /**
     * Subscribed to ntfy for capability exchange.
     */
    data class CapabilityNtfySubscribed(val topic: String) : ConnectionProgress()

    /**
     * Sending capability request via ntfy.
     */
    data class CapabilityNtfySending(val topic: String) : ConnectionProgress()

    /**
     * Waiting for capability response.
     */
    data class CapabilityNtfyWaiting(val topic: String) : ConnectionProgress()

    /**
     * Received capability response via ntfy.
     */
    data class CapabilityNtfyReceived(val topic: String) : ConnectionProgress()

    // ==================== Host Discovery Phase ====================

    /**
     * Starting host discovery via ntfy.
     */
    data class HostDiscoveryStarted(val topic: String) : ConnectionProgress()

    /**
     * Received host discovery response with all available IPs.
     */
    data class HostDiscoveryReceived(
        val lanIp: String?,
        val lanPort: Int?,
        val vpnIp: String?,
        val vpnPort: Int?,
        val tailscaleIp: String?,
        val tailscalePort: Int?
    ) : ConnectionProgress()

    /**
     * Host discovery failed.
     */
    data class HostDiscoveryFailed(val reason: String) : ConnectionProgress()

    /**
     * Daemon capabilities received.
     */
    data class DaemonCapabilities(
        val tailscaleIp: String?,
        val tailscalePort: Int?,
        val supportsWebRTC: Boolean,
        val protocolVersion: Int
    ) : ConnectionProgress()

    /**
     * Capability exchange failed (will continue with strategies anyway).
     */
    data class CapabilityExchangeFailed(val reason: String) : ConnectionProgress()

    /**
     * Capability exchange was skipped (e.g., no local Tailscale available).
     */
    data class CapabilityExchangeSkipped(val reason: String) : ConnectionProgress()

    // ==================== Strategy Detection Phase ====================

    /**
     * Starting to detect if a strategy is available.
     */
    data class Detecting(val strategyName: String) : ConnectionProgress()

    /**
     * Strategy was detected as available.
     */
    data class StrategyAvailable(
        val strategyName: String,
        val info: String? = null
    ) : ConnectionProgress()

    /**
     * Strategy was detected as unavailable.
     */
    data class StrategyUnavailable(
        val strategyName: String,
        val reason: String
    ) : ConnectionProgress()

    // ==================== Signaling Phase ====================

    /**
     * Subscribing to ntfy topic for signaling.
     */
    data class NtfySubscribing(val topic: String) : ConnectionProgress()

    /**
     * Successfully subscribed to ntfy topic.
     */
    data class NtfySubscribed(val topic: String) : ConnectionProgress()

    /**
     * Sending SDP offer via ntfy.
     */
    data class NtfySendingOffer(
        val topic: String,
        val sdpInfo: SdpInfo? = null
    ) : ConnectionProgress()

    /**
     * SDP offer sent, waiting for answer.
     */
    data class NtfyWaitingForAnswer(val topic: String) : ConnectionProgress()

    /**
     * Received SDP answer via ntfy.
     */
    data class NtfyReceivedAnswer(val topic: String, val candidateCount: Int) : ConnectionProgress()

    /**
     * Ntfy WebSocket failed, retrying.
     */
    data class NtfyRetrying(val topic: String, val attempt: Int, val maxAttempts: Int) : ConnectionProgress()

    /**
     * Trying direct HTTP signaling before ntfy.
     */
    data class TryingDirectSignaling(val host: String, val port: Int) : ConnectionProgress()

    /**
     * Direct signaling timed out, falling back to ntfy.
     */
    data class DirectSignalingTimeout(val host: String, val port: Int) : ConnectionProgress()

    // ==================== Connection Phase ====================

    /**
     * Attempting to connect with a strategy.
     */
    data class Connecting(
        val strategyName: String,
        val step: String,
        val detail: String? = null,
        val progress: Float? = null
    ) : ConnectionProgress()

    /**
     * Strategy connection attempt failed, may try next.
     */
    data class StrategyFailed(
        val strategyName: String,
        val error: String,
        val durationMs: Long,
        val willTryNext: Boolean
    ) : ConnectionProgress()

    /**
     * Successfully connected!
     */
    data class Connected(
        val strategyName: String,
        val transport: Transport,
        val durationMs: Long
    ) : ConnectionProgress()

    // ==================== Authentication Phase ====================

    /**
     * Authenticating with daemon after transport established.
     */
    data class Authenticating(
        val step: String = "Verifying connection"
    ) : ConnectionProgress()

    /**
     * Authentication completed successfully.
     */
    object Authenticated : ConnectionProgress()

    /**
     * Authentication failed.
     */
    data class AuthenticationFailed(
        val reason: String
    ) : ConnectionProgress()

    // ==================== Final States ====================

    /**
     * All strategies failed.
     */
    data class AllFailed(
        val attempts: List<FailedAttempt>
    ) : ConnectionProgress()

    /**
     * Connection was cancelled.
     */
    object Cancelled : ConnectionProgress()
}

/**
 * Record of a failed connection attempt.
 */
data class FailedAttempt(
    val strategyName: String,
    val error: String,
    val durationMs: Long
)

/**
 * Overall connection state for UI.
 */
enum class ConnectionState {
    IDLE,
    DETECTING,
    CONNECTING,
    CONNECTED,
    FAILED,
    CANCELLED
}

/**
 * Parsed SDP offer information for UI display.
 */
data class SdpInfo(
    val mediaTypes: List<String>,  // e.g., ["video", "audio", "data"]
    val iceCandidates: List<IceCandidateInfo>
) {
    companion object {
        /**
         * Parse SDP string to extract media types and ICE candidates.
         */
        fun parse(sdp: String): SdpInfo {
            val mediaTypes = mutableListOf<String>()
            val candidates = mutableListOf<IceCandidateInfo>()

            for (line in sdp.lines()) {
                // Parse media types (m=audio, m=video, m=application for data channel)
                if (line.startsWith("m=")) {
                    val mediaType = line.substringAfter("m=").substringBefore(" ")
                    when (mediaType) {
                        "audio" -> mediaTypes.add("audio")
                        "video" -> mediaTypes.add("video")
                        "application" -> mediaTypes.add("data")
                    }
                }

                // Parse ICE candidates
                // Format: a=candidate:... typ host/srflx/relay ... <ip> <port> ...
                if (line.startsWith("a=candidate:")) {
                    parseIceCandidate(line)?.let { candidates.add(it) }
                }
            }

            return SdpInfo(mediaTypes, candidates)
        }

        private fun parseIceCandidate(line: String): IceCandidateInfo? {
            // a=candidate:1 1 UDP 2130706431 192.168.1.100 54321 typ host
            // a=candidate:2 1 UDP 1694498815 203.0.113.5 54322 typ srflx raddr 192.168.1.100 rport 54321
            return try {
                val parts = line.substringAfter("a=candidate:").split(" ")
                if (parts.size >= 8) {
                    val ip = parts[4]
                    val port = parts[5].toIntOrNull() ?: 0
                    val typeIndex = parts.indexOf("typ")
                    val type = if (typeIndex >= 0 && typeIndex + 1 < parts.size) {
                        parts[typeIndex + 1]
                    } else "unknown"
                    IceCandidateInfo(ip, port, type)
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * ICE candidate information.
 */
data class IceCandidateInfo(
    val ip: String,
    val port: Int,
    val type: String  // "host", "srflx", "prflx", "relay"
) {
    /**
     * Human-readable label for the candidate source.
     */
    val label: String
        get() = when {
            // Tailscale range: 100.64.0.0/10 (100.64.x.x - 100.127.x.x)
            type == "host" && isTailscaleIp(ip) -> "tailscale"
            // Local/LAN addresses
            type == "host" && isPrivateIp(ip) -> "local"
            // Public host (rare but possible)
            type == "host" -> "host"
            // Server reflexive = public IP discovered via STUN
            type == "srflx" -> "public"
            // Peer reflexive
            type == "prflx" -> "peer"
            // TURN relay
            type == "relay" -> "relay"
            else -> type
        }

    private fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }

    private fun isPrivateIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return when {
            first == 10 -> true                          // 10.0.0.0/8
            first == 172 && second in 16..31 -> true     // 172.16.0.0/12
            first == 192 && second == 168 -> true        // 192.168.0.0/16
            first == 127 -> true                         // localhost
            else -> false
        }
    }
}
