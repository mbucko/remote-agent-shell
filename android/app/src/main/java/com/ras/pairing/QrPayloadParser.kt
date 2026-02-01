package com.ras.pairing

import android.util.Base64
import com.ras.crypto.KeyDerivation
import com.ras.proto.QrPayload

sealed class QrParseResult {
    data class Success(val payload: ParsedQrPayload) : QrParseResult()
    data class Error(val code: ErrorCode) : QrParseResult()

    enum class ErrorCode {
        INVALID_BASE64,
        PARSE_ERROR,
        UNSUPPORTED_VERSION,
        MISSING_FIELD,
        INVALID_SECRET_LENGTH,
        INVALID_PORT
    }
}

data class ParsedQrPayload(
    val version: Int,
    val ip: String?,  // Now optional - can use mDNS/ntfy discovery instead
    val port: Int?,   // Now optional - can use mDNS/ntfy discovery instead
    val masterSecret: ByteArray,
    val sessionId: String,
    val ntfyTopic: String,
    val tailscaleIp: String? = null,
    val tailscalePort: Int? = null,
    val vpnIp: String? = null,
    val vpnPort: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedQrPayload) return false
        return version == other.version &&
               ip == other.ip &&
               port == other.port &&
               masterSecret.contentEquals(other.masterSecret) &&
               sessionId == other.sessionId &&
               ntfyTopic == other.ntfyTopic &&
               tailscaleIp == other.tailscaleIp &&
               tailscalePort == other.tailscalePort &&
               vpnIp == other.vpnIp &&
               vpnPort == other.vpnPort
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + (ip?.hashCode() ?: 0)
        result = 31 * result + (port ?: 0)
        result = 31 * result + masterSecret.contentHashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + ntfyTopic.hashCode()
        result = 31 * result + (tailscaleIp?.hashCode() ?: 0)
        result = 31 * result + (tailscalePort ?: 0)
        result = 31 * result + (vpnIp?.hashCode() ?: 0)
        result = 31 * result + (vpnPort ?: 0)
        return result
    }

    /**
     * Check if this payload has any direct IP (LAN, VPN, or Tailscale).
     * If false, discovery via mDNS or ntfy DISCOVER is required.
     */
    fun hasDirectIp(): Boolean = ip != null || vpnIp != null || tailscaleIp != null
}

object QrPayloadParser {

    private const val SUPPORTED_VERSION = 1
    private const val EXPECTED_SECRET_LENGTH = 32
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535

    /**
     * Parse QR code content into payload.
     *
     * @param qrContent Raw string from QR code (base64 encoded protobuf)
     */
    fun parse(qrContent: String): QrParseResult {
        // Decode base64
        val bytes = try {
            Base64.decode(qrContent.trim(), Base64.DEFAULT)
        } catch (e: Exception) {
            return QrParseResult.Error(QrParseResult.ErrorCode.INVALID_BASE64)
        }

        // Parse protobuf
        val payload = try {
            QrPayload.parseFrom(bytes)
        } catch (e: Exception) {
            return QrParseResult.Error(QrParseResult.ErrorCode.PARSE_ERROR)
        }

        // Validate version
        if (payload.version != SUPPORTED_VERSION) {
            return QrParseResult.Error(QrParseResult.ErrorCode.UNSUPPORTED_VERSION)
        }

        // Validate secret length
        if (payload.masterSecret.size() != EXPECTED_SECRET_LENGTH) {
            return QrParseResult.Error(QrParseResult.ErrorCode.INVALID_SECRET_LENGTH)
        }

        val masterSecretBytes = payload.masterSecret.toByteArray()

        // Derive session_id and ntfy_topic from master_secret if not provided
        // (QR code v2 only contains master_secret)
        val sessionId = if (payload.sessionId.isNotBlank()) {
            payload.sessionId
        } else {
            KeyDerivation.deriveSessionId(masterSecretBytes)
        }

        val ntfyTopic = if (payload.ntfyTopic.isNotBlank()) {
            payload.ntfyTopic
        } else {
            KeyDerivation.deriveNtfyTopic(masterSecretBytes)
        }

        // Extract optional LAN IP/port (discovered via mDNS)
        val ip = if (payload.ip.isNotBlank()) payload.ip else null
        val port = if (payload.port in MIN_PORT..MAX_PORT) payload.port else null

        // Extract optional Tailscale fields
        val tailscaleIp = if (payload.tailscaleIp.isNotBlank()) payload.tailscaleIp else null
        val tailscalePort = if (payload.tailscalePort > 0) payload.tailscalePort else null

        // Extract optional VPN fields
        val vpnIp = if (payload.vpnIp.isNotBlank()) payload.vpnIp else null
        val vpnPort = if (payload.vpnPort > 0) payload.vpnPort else null

        return QrParseResult.Success(
            ParsedQrPayload(
                version = payload.version,
                ip = ip,
                port = port,
                masterSecret = masterSecretBytes,
                sessionId = sessionId,
                ntfyTopic = ntfyTopic,
                tailscaleIp = tailscaleIp,
                tailscalePort = tailscalePort,
                vpnIp = vpnIp,
                vpnPort = vpnPort
            )
        )
    }
}
