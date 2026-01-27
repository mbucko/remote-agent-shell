package com.ras.pairing

import android.util.Base64
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
    val ip: String,
    val port: Int,
    val masterSecret: ByteArray,
    val sessionId: String,
    val ntfyTopic: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedQrPayload) return false
        return version == other.version &&
               ip == other.ip &&
               port == other.port &&
               masterSecret.contentEquals(other.masterSecret) &&
               sessionId == other.sessionId &&
               ntfyTopic == other.ntfyTopic
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + ip.hashCode()
        result = 31 * result + port
        result = 31 * result + masterSecret.contentHashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + ntfyTopic.hashCode()
        return result
    }
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

        // Validate required fields
        if (payload.ip.isBlank()) {
            return QrParseResult.Error(QrParseResult.ErrorCode.MISSING_FIELD)
        }
        if (payload.sessionId.isBlank()) {
            return QrParseResult.Error(QrParseResult.ErrorCode.MISSING_FIELD)
        }
        if (payload.ntfyTopic.isBlank()) {
            return QrParseResult.Error(QrParseResult.ErrorCode.MISSING_FIELD)
        }

        // Validate secret length
        if (payload.masterSecret.size() != EXPECTED_SECRET_LENGTH) {
            return QrParseResult.Error(QrParseResult.ErrorCode.INVALID_SECRET_LENGTH)
        }

        // Validate port
        if (payload.port < MIN_PORT || payload.port > MAX_PORT) {
            return QrParseResult.Error(QrParseResult.ErrorCode.INVALID_PORT)
        }

        return QrParseResult.Success(
            ParsedQrPayload(
                version = payload.version,
                ip = payload.ip,
                port = payload.port,
                masterSecret = payload.masterSecret.toByteArray(),
                sessionId = payload.sessionId,
                ntfyTopic = payload.ntfyTopic
            )
        )
    }
}
