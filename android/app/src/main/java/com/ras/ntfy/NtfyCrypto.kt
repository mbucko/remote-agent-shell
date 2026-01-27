package com.ras.ntfy

import com.ras.proto.IpChangeNotification
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypted IP change notification data.
 */
data class IpChangeData(
    val ip: String,
    val port: Int,
    val timestamp: Long,
    val nonce: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpChangeData) return false
        return ip == other.ip && port == other.port &&
               timestamp == other.timestamp && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = ip.hashCode()
        result = 31 * result + port
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

/**
 * Handles decryption of ntfy IP change notifications.
 *
 * Security requirements:
 * - AES-256-GCM for authenticated encryption
 * - 12-byte IV, 16-byte auth tag
 * - Minimum message size validation
 */
class NtfyCrypto(private val ntfyKey: ByteArray) {

    companion object {
        private const val IV_SIZE = 12
        private const val TAG_SIZE_BITS = 128
        // Minimum encrypted message size: 12 (IV) + 16 (tag) = 28 bytes
        const val MIN_ENCRYPTED_SIZE = 28
    }

    init {
        require(ntfyKey.size == 32) { "ntfyKey must be 32 bytes" }
    }

    /**
     * Decrypt IP change notification from base64 string.
     *
     * @param encrypted Base64-encoded encrypted message (IV || ciphertext || tag)
     * @return Decrypted IP change data
     * @throws IllegalArgumentException if message is too short or invalid base64
     * @throws javax.crypto.AEADBadTagException if decryption fails (wrong key or tampered)
     */
    fun decryptIpNotification(encrypted: String): IpChangeData {
        val data = try {
            Base64.getDecoder().decode(encrypted)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid base64: ${e.message}")
        }

        if (data.size < MIN_ENCRYPTED_SIZE) {
            throw IllegalArgumentException(
                "Message too short: ${data.size} < $MIN_ENCRYPTED_SIZE"
            )
        }

        val iv = data.sliceArray(0 until IV_SIZE)
        val ciphertext = data.sliceArray(IV_SIZE until data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(ntfyKey, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val plaintext = cipher.doFinal(ciphertext)
        val notification = IpChangeNotification.parseFrom(plaintext)

        return IpChangeData(
            ip = notification.ip,
            port = notification.port,
            timestamp = notification.timestamp,
            nonce = notification.nonce.toByteArray()
        )
    }
}
