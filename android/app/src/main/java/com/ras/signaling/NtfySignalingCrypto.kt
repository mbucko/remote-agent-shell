package com.ras.signaling

import com.ras.crypto.KeyDerivation
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exception thrown when decryption fails.
 */
class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Cryptographic operations for ntfy signaling relay.
 *
 * Provides:
 * - Signaling key derivation using HKDF-SHA256 with info="signaling"
 * - AES-256-GCM encryption/decryption
 * - Base64 encoding/decoding for wire format
 *
 * Security:
 * - Uses SecureRandom for IV generation (12 bytes)
 * - 128-bit authentication tag
 * - Call zeroKey() when done to clear key from memory
 */
class NtfySignalingCrypto(private val key: ByteArray) {

    companion object {
        private const val IV_SIZE = 12
        private const val TAG_SIZE_BITS = 128
        private const val KEY_SIZE = 32
        const val MIN_ENCRYPTED_SIZE = IV_SIZE + TAG_SIZE_BITS / 8  // 12 + 16 = 28 bytes
        const val MAX_MESSAGE_SIZE = 64 * 1024  // 64 KB - DoS protection

        private val secureRandom = SecureRandom()

        /**
         * Derive signaling key from master secret using HKDF-SHA256.
         *
         * @param masterSecret 32-byte master secret from QR code
         * @return 32-byte signaling key
         */
        fun deriveSignalingKey(masterSecret: ByteArray): ByteArray {
            require(masterSecret.size == KEY_SIZE) { "Master secret must be 32 bytes" }
            return KeyDerivation.deriveKey(masterSecret, "signaling")
        }
    }

    init {
        require(key.size == KEY_SIZE) { "Key must be 32 bytes" }
    }

    /**
     * Encrypt plaintext using AES-256-GCM.
     *
     * @param plaintext Data to encrypt
     * @return IV + ciphertext + tag (minimum 28 bytes)
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE)
        secureRandom.nextBytes(iv)
        return encryptWithIv(plaintext, iv)
    }

    /**
     * Encrypt plaintext with specified IV (for testing with test vectors).
     *
     * @param plaintext Data to encrypt
     * @param iv 12-byte IV
     * @return IV + ciphertext + tag
     */
    internal fun encryptWithIv(plaintext: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == IV_SIZE) { "IV must be $IV_SIZE bytes" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertextWithTag = cipher.doFinal(plaintext)

        // Prepend IV: IV + ciphertext + tag
        return iv + ciphertextWithTag
    }

    /**
     * Decrypt ciphertext using AES-256-GCM.
     *
     * @param encrypted IV + ciphertext + tag (minimum 28 bytes)
     * @return Decrypted plaintext
     * @throws DecryptionException if decryption fails
     */
    fun decrypt(encrypted: ByteArray): ByteArray {
        if (encrypted.size < MIN_ENCRYPTED_SIZE) {
            throw DecryptionException("Message too short: ${encrypted.size} < $MIN_ENCRYPTED_SIZE")
        }
        if (encrypted.size > MAX_MESSAGE_SIZE) {
            throw DecryptionException("Message too large: ${encrypted.size} > $MAX_MESSAGE_SIZE")
        }

        val iv = encrypted.sliceArray(0 until IV_SIZE)
        val ciphertextWithTag = encrypted.sliceArray(IV_SIZE until encrypted.size)

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            return cipher.doFinal(ciphertextWithTag)
        } catch (e: AEADBadTagException) {
            throw DecryptionException("Authentication failed", e)
        } catch (e: Exception) {
            throw DecryptionException("Decryption failed: ${e.message}", e)
        }
    }

    /**
     * Encrypt to base64 string (for wire format).
     *
     * @param plaintext Data to encrypt
     * @return Base64-encoded IV + ciphertext + tag
     */
    fun encryptToBase64(plaintext: ByteArray): String {
        val encrypted = encrypt(plaintext)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    /**
     * Encrypt to base64 with specified IV (for testing).
     */
    internal fun encryptToBase64WithIv(plaintext: ByteArray, iv: ByteArray): String {
        val encrypted = encryptWithIv(plaintext, iv)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    /**
     * Decrypt from base64 string.
     *
     * @param encryptedBase64 Base64-encoded encrypted data
     * @return Decrypted plaintext
     * @throws DecryptionException if decryption fails or invalid base64
     */
    fun decryptFromBase64(encryptedBase64: String): ByteArray {
        val encrypted = try {
            Base64.getDecoder().decode(encryptedBase64)
        } catch (e: Exception) {
            throw DecryptionException("Invalid base64: ${e.message}", e)
        }
        return decrypt(encrypted)
    }

    /**
     * Zero the key from memory.
     *
     * Call this when the session ends.
     */
    fun zeroKey() {
        key.fill(0)
    }
}
