package com.ras.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

object HmacUtils {

    private const val ALGORITHM = "HmacSHA256"
    const val PAIR_NONCE_LENGTH = 32

    /**
     * Compute HMAC-SHA256.
     */
    fun computeHmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return mac.doFinal(message)
    }

    /**
     * Verify HMAC using constant-time comparison.
     *
     * SECURITY: Always use this instead of == or contentEquals()
     */
    fun verifyHmac(key: ByteArray, message: ByteArray, expected: ByteArray): Boolean {
        val computed = computeHmac(key, message)
        return constantTimeEquals(computed, expected)
    }

    /**
     * Compute HMAC for HTTP signaling request.
     *
     * Format: UTF8(sessionId) || BigEndian64(timestamp) || body
     */
    fun computeSignalingHmac(
        authKey: ByteArray,
        sessionId: String,
        timestamp: Long,
        body: ByteArray
    ): ByteArray {
        val sessionIdBytes = sessionId.toByteArray(Charsets.UTF_8)
        val timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array()

        val input = sessionIdBytes + timestampBytes + body
        return computeHmac(authKey, input)
    }

    /**
     * Compute HMAC for pairing request auth_proof.
     *
     * Format: "pair-request" || session_id || device_id || nonce
     */
    fun computePairRequestHmac(
        authKey: ByteArray,
        sessionId: String,
        deviceId: String,
        nonce: ByteArray
    ): ByteArray {
        val input = "pair-request".toByteArray(Charsets.UTF_8) +
            sessionId.toByteArray(Charsets.UTF_8) +
            deviceId.toByteArray(Charsets.UTF_8) +
            nonce
        return computeHmac(authKey, input)
    }

    /**
     * Compute HMAC for pairing response auth_proof.
     *
     * Format: "pair-response" || nonce
     */
    fun computePairResponseHmac(authKey: ByteArray, nonce: ByteArray): ByteArray {
        val input = "pair-response".toByteArray(Charsets.UTF_8) + nonce
        return computeHmac(authKey, input)
    }

    /**
     * Constant-time byte array comparison.
     *
     * Prevents timing attacks by always comparing all bytes.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false

        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
