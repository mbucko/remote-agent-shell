package com.ras.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM codec for encrypting/decrypting messages.
 *
 * This matches the daemon's BytesCodec implementation exactly:
 * - Format: nonce (12 bytes) || ciphertext || tag (16 bytes)
 * - AES-256-GCM with 128-bit tag
 * - No associated data (AAD)
 *
 * Thread Safety: This class is thread-safe. Each encode() call generates
 * a unique nonce, and Cipher instances are created per operation.
 */
class BytesCodec(private val key: ByteArray) {

    companion object {
        private const val KEY_LENGTH = 32       // 256 bits
        private const val NONCE_LENGTH = 12     // 96 bits (GCM standard)
        private const val TAG_LENGTH_BITS = 128 // 128-bit authentication tag
        private const val TAG_LENGTH_BYTES = 16
        private const val MIN_CIPHERTEXT_LENGTH = NONCE_LENGTH + TAG_LENGTH_BYTES

        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val secureRandom = SecureRandom()

    init {
        require(key.size == KEY_LENGTH) {
            "Key must be $KEY_LENGTH bytes, got ${key.size}"
        }
    }

    /**
     * Encrypt plaintext using AES-256-GCM.
     *
     * @param plaintext Data to encrypt (can be empty)
     * @return Encrypted data: nonce (12 bytes) || ciphertext || tag (16 bytes)
     */
    fun encode(plaintext: ByteArray): ByteArray {
        // Generate random nonce
        val nonce = ByteArray(NONCE_LENGTH)
        secureRandom.nextBytes(nonce)

        // Create cipher
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, ALGORITHM)
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        // Encrypt (GCM appends tag to ciphertext)
        val ciphertext = cipher.doFinal(plaintext)

        // Return nonce || ciphertext (which includes tag)
        return nonce + ciphertext
    }

    /**
     * Decrypt data using AES-256-GCM.
     *
     * @param data Encrypted data: nonce (12 bytes) || ciphertext || tag (16 bytes)
     * @return Decrypted plaintext
     * @throws CryptoException if decryption fails (wrong key, tampered data, etc.)
     */
    fun decode(data: ByteArray): ByteArray {
        if (data.size < MIN_CIPHERTEXT_LENGTH) {
            throw CryptoException("Data too short: ${data.size} bytes, minimum $MIN_CIPHERTEXT_LENGTH")
        }

        // Extract nonce and ciphertext
        val nonce = data.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = data.copyOfRange(NONCE_LENGTH, data.size)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val keySpec = SecretKeySpec(key, ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw CryptoException("Decryption failed: ${e.message}", e)
        }
    }

    /**
     * Zero out the key material.
     * Call this when the codec is no longer needed.
     */
    fun zeroKey() {
        key.fill(0)
    }
}

/**
 * Exception thrown when cryptographic operations fail.
 */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
