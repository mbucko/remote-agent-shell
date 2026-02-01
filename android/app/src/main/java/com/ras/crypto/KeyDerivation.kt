package com.ras.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * HKDF key derivation (RFC 5869).
 *
 * Implements full HKDF-Extract-Expand algorithm.
 */
object KeyDerivation {

    private const val HASH_ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32
    private const val KEY_LENGTH = 32

    /**
     * Derive a purpose-specific key from master secret using HKDF-SHA256.
     *
     * @param masterSecret 32-byte master secret (IKM)
     * @param purpose One of "auth", "encrypt", "ntfy" (info parameter)
     * @return 32-byte derived key
     */
    fun deriveKey(masterSecret: ByteArray, purpose: String): ByteArray {
        require(masterSecret.size == 32) { "Master secret must be 32 bytes" }

        val info = purpose.toByteArray(Charsets.UTF_8)

        // HKDF-Extract with empty salt (RFC 5869 specifies using HashLen zeros)
        val salt = ByteArray(HASH_LENGTH) // All zeros
        val prk = hmac(salt, masterSecret)

        // HKDF-Expand
        return hkdfExpand(prk, info, KEY_LENGTH)
    }

    /**
     * Derive ntfy topic from master secret.
     *
     * @return "ras-" + first 12 hex chars of SHA256(masterSecret)
     */
    fun deriveNtfyTopic(masterSecret: ByteArray): String {
        require(masterSecret.size == 32) { "Master secret must be 32 bytes" }

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(masterSecret)
        val prefix = hash.copyOf(6).toHex()

        return "ras-$prefix"
    }

    /**
     * Derive session ID from master secret.
     *
     * Uses HKDF with "session" purpose, returns first 24 hex chars.
     *
     * @return 24-character hex session ID
     */
    fun deriveSessionId(masterSecret: ByteArray): String {
        require(masterSecret.size == 32) { "Master secret must be 32 bytes" }

        val info = "session".toByteArray(Charsets.UTF_8)

        // HKDF-Extract with empty salt
        val salt = ByteArray(HASH_LENGTH)
        val prk = hmac(salt, masterSecret)

        // HKDF-Expand
        val derived = hkdfExpand(prk, info, KEY_LENGTH)
        return derived.copyOf(12).toHex()  // 24 hex chars
    }

    /**
     * HMAC-SHA256.
     */
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HASH_ALGORITHM)
        mac.init(SecretKeySpec(key, HASH_ALGORITHM))
        return mac.doFinal(data)
    }

    /**
     * HKDF-Expand step.
     *
     * T(0) = empty
     * T(i) = HMAC(PRK, T(i-1) || info || i)
     * OKM = first L bytes of T(1) || T(2) || ...
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val n = (length + HASH_LENGTH - 1) / HASH_LENGTH
        var t = ByteArray(0)
        val okm = ByteArray(n * HASH_LENGTH)

        for (i in 1..n) {
            // T(i) = HMAC(PRK, T(i-1) || info || i)
            val input = t + info + byteArrayOf(i.toByte())
            t = hmac(prk, input)
            System.arraycopy(t, 0, okm, (i - 1) * HASH_LENGTH, HASH_LENGTH)
        }

        return okm.copyOf(length)
    }
}
