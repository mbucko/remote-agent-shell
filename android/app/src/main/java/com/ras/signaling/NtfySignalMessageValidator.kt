package com.ras.signaling

import com.ras.proto.NtfySignalMessage
import java.util.Collections
import java.util.LinkedHashSet
import kotlin.math.abs

/**
 * Validation errors for ntfy signaling messages.
 */
enum class ValidationError {
    INVALID_SESSION,
    INVALID_TIMESTAMP,
    NONCE_REPLAY,
    INVALID_NONCE,
    INVALID_SDP,
    WRONG_MESSAGE_TYPE,
    MISSING_DEVICE_ID,
    MISSING_DEVICE_NAME,
}

/**
 * Result of message validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val error: ValidationError? = null
)

/**
 * Validates ntfy signaling messages.
 *
 * Security features:
 * - Session ID verification
 * - Timestamp validation (±30 seconds default)
 * - Nonce replay protection with FIFO eviction
 * - SDP format validation
 * - Message type validation
 * - Device ID/name validation for OFFER messages
 */
class NtfySignalMessageValidator(
    private val pendingSessionId: String,
    private val expectedType: NtfySignalMessage.MessageType,
    private val timestampWindowSeconds: Long = DEFAULT_TIMESTAMP_WINDOW_SECONDS
) {
    companion object {
        const val DEFAULT_TIMESTAMP_WINDOW_SECONDS = 30L
        const val NONCE_SIZE = 16
        const val MAX_NONCES = 100
        const val MAX_DEVICE_NAME_LENGTH = 64
    }

    // Thread-safe nonce cache with FIFO eviction
    private val seenNonces: MutableSet<String> = Collections.synchronizedSet(
        LinkedHashSet<String>()
    )

    /**
     * Validate an ntfy signaling message.
     *
     * @param msg The message to validate
     * @return ValidationResult with isValid=true if valid, or error if invalid
     */
    fun validate(msg: NtfySignalMessage): ValidationResult {
        // Check message type
        if (msg.type != expectedType) {
            return ValidationResult(false, ValidationError.WRONG_MESSAGE_TYPE)
        }

        // Check session ID
        // Note: For reconnection mode, both pendingSessionId and msg.sessionId are empty
        // Only fail if they don't match
        if (msg.sessionId != pendingSessionId) {
            return ValidationResult(false, ValidationError.INVALID_SESSION)
        }

        // Check timestamp
        val now = System.currentTimeMillis() / 1000
        if (abs(now - msg.timestamp) > timestampWindowSeconds) {
            return ValidationResult(false, ValidationError.INVALID_TIMESTAMP)
        }

        // Check nonce size
        val nonceBytes = msg.nonce.toByteArray()
        if (nonceBytes.size != NONCE_SIZE) {
            return ValidationResult(false, ValidationError.INVALID_NONCE)
        }

        // Check nonce replay
        val nonceHex = nonceBytes.toHexString()
        synchronized(seenNonces) {
            if (nonceHex in seenNonces) {
                return ValidationResult(false, ValidationError.NONCE_REPLAY)
            }
        }

        // Validate SDP (only for OFFER/ANSWER messages, not CAPABILITIES or DISCOVER_RESPONSE)
        if (expectedType != NtfySignalMessage.MessageType.CAPABILITIES &&
            expectedType != NtfySignalMessage.MessageType.DISCOVER_RESPONSE) {
            if (!isValidSdp(msg.sdp)) {
                return ValidationResult(false, ValidationError.INVALID_SDP)
            }
        }

        // For OFFER messages, validate device ID and name
        if (expectedType == NtfySignalMessage.MessageType.OFFER) {
            if (!isValidDeviceId(msg.deviceId)) {
                return ValidationResult(false, ValidationError.MISSING_DEVICE_ID)
            }
            if (msg.deviceName.isEmpty() || msg.deviceName.isBlank()) {
                return ValidationResult(false, ValidationError.MISSING_DEVICE_NAME)
            }
        }

        // All checks passed - add nonce to cache
        synchronized(seenNonces) {
            seenNonces.add(nonceHex)
            if (seenNonces.size > MAX_NONCES) {
                // FIFO eviction - remove oldest
                val iterator = seenNonces.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }

        return ValidationResult(true)
    }

    /**
     * Clear the nonce cache.
     *
     * Call when session ends.
     */
    fun clearNonceCache() {
        seenNonces.clear()
    }

    /**
     * Check if a device ID is valid.
     *
     * - Not empty
     * - No control characters
     */
    private fun isValidDeviceId(deviceId: String): Boolean {
        if (deviceId.isEmpty() || deviceId.isBlank()) {
            return false
        }
        // Check for control characters
        return !deviceId.any { it.code < 32 }
    }

    /**
     * Validate SDP format.
     *
     * Requires:
     * - Non-empty
     * - Starts with "v=0" (version line)
     * - Contains "m=" (media line)
     */
    private fun isValidSdp(sdp: String): Boolean {
        if (sdp.isEmpty()) {
            return false
        }
        // Must have version line
        if (!sdp.startsWith("v=0")) {
            return false
        }
        // Must have media line
        if (!sdp.contains("m=")) {
            return false
        }
        return true
    }
}

/**
 * Sanitize device name for display.
 *
 * - Trims whitespace
 * - Replaces control characters with space
 * - Preserves unicode
 * - Truncates to max length
 */
fun sanitizeDeviceName(name: String): String {
    // Replace control characters with space (keep unicode)
    val filtered = name.map { if (it.code < 32 && it != '\t') ' ' else it }.joinToString("")
    // Replace multiple spaces with single space
    val collapsed = filtered.replace(Regex("\\s+"), " ")
    // Trim and truncate
    return collapsed.trim().take(NtfySignalMessageValidator.MAX_DEVICE_NAME_LENGTH)
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
