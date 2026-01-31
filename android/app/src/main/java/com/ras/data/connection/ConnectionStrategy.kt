package com.ras.data.connection

import android.content.Context

/**
 * A strategy for establishing a connection to the daemon.
 *
 * Each strategy (Tailscale, WebRTC, TURN) implements this interface.
 * The ConnectionOrchestrator tries strategies in priority order until
 * one succeeds.
 */
interface ConnectionStrategy {
    /**
     * Human-readable name for UI display.
     */
    val name: String

    /**
     * Priority for ordering. Lower values are tried first.
     * Recommended values:
     * - Tailscale: 10 (try first - fastest if available)
     * - WebRTC: 20 (standard P2P)
     * - TURN: 30 (last resort - adds latency)
     */
    val priority: Int

    /**
     * Phase 1: Detection
     *
     * Check if this strategy can be attempted. This should be fast
     * and not involve network calls.
     *
     * Examples:
     * - Tailscale: Check if VPN interface exists
     * - WebRTC: Always available
     * - TURN: Check if credentials are configured
     *
     * @return Detection result with availability info
     */
    suspend fun detect(): DetectionResult

    /**
     * Phase 2: Connection
     *
     * Actually attempt to establish a connection. Reports progress
     * via the callback for UI feedback.
     *
     * @param context Connection context with daemon info and signaling
     * @param onProgress Callback for progress updates
     * @return Connection result (success with transport, or failure)
     */
    suspend fun connect(
        context: ConnectionContext,
        onProgress: (ConnectionStep) -> Unit
    ): ConnectionResult
}

/**
 * Context provided to strategies for connection.
 */
data class ConnectionContext(
    val androidContext: Context,  // Android context for system services
    val deviceId: String,
    val daemonHost: String?,  // Direct host if known (e.g., from QR code)
    val daemonPort: Int?,
    val daemonTailscaleIp: String? = null,  // Daemon's Tailscale IP if known
    val daemonTailscalePort: Int? = null,   // Daemon's Tailscale port if known
    val signaling: SignalingChannel,
    val authToken: ByteArray,
    val signalingProgress: SignalingProgressCallback? = null  // For detailed signaling events
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConnectionContext
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}

/**
 * Callback for signaling progress events.
 */
typealias SignalingProgressCallback = (ConnectionProgress) -> Unit

/**
 * Abstraction for signaling (ntfy or direct HTTP).
 */
interface SignalingChannel {
    /**
     * Exchange capabilities with daemon before connection attempts.
     * This is a quick exchange to discover what connection methods are available.
     *
     * @param capabilities Our local capabilities
     * @param onProgress Optional callback for detailed signaling progress
     * @return Daemon's capabilities, or null if exchange failed
     */
    suspend fun exchangeCapabilities(
        capabilities: ConnectionCapabilities,
        onProgress: SignalingProgressCallback? = null
    ): ConnectionCapabilities?

    /**
     * Send SDP offer and get answer for WebRTC connection.
     *
     * @param sdp The SDP offer
     * @param onProgress Optional callback for detailed signaling progress
     * @return SDP answer from daemon, or null if failed
     */
    suspend fun sendOffer(
        sdp: String,
        onProgress: SignalingProgressCallback? = null
    ): String?

    /**
     * Close the signaling channel.
     */
    suspend fun close()
}

/**
 * Capabilities exchanged during connection negotiation.
 */
data class ConnectionCapabilities(
    val tailscaleIp: String? = null,
    val tailscalePort: Int? = null,
    val supportsWebRTC: Boolean = true,
    val supportsTurn: Boolean = false,
    val protocolVersion: Int = 1
)

/**
 * Result of the detection phase.
 */
sealed class DetectionResult {
    /**
     * Strategy is available and can be attempted.
     * @param info Optional info for logging (e.g., detected IP)
     */
    data class Available(val info: String? = null) : DetectionResult()

    /**
     * Strategy is not available.
     * @param reason Human-readable reason for UI
     */
    data class Unavailable(val reason: String) : DetectionResult()
}

/**
 * Result of the connection phase.
 */
sealed class ConnectionResult {
    /**
     * Connection succeeded.
     * @param transport The established transport
     */
    data class Success(val transport: Transport) : ConnectionResult()

    /**
     * Connection failed.
     * @param error Human-readable error message
     * @param exception Optional underlying exception
     * @param canRetry Whether this failure might succeed on retry
     */
    data class Failed(
        val error: String,
        val exception: Throwable? = null,
        val canRetry: Boolean = false
    ) : ConnectionResult()
}

/**
 * A step in the connection process (for UI feedback).
 */
data class ConnectionStep(
    val step: String,
    val detail: String? = null,
    val progress: Float? = null  // 0.0 to 1.0, null if indeterminate
)
