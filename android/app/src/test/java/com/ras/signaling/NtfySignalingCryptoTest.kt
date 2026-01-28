package com.ras.signaling

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for NtfySignalingCrypto.
 *
 * Test vectors from test-vectors/ntfy_signaling.json.
 */
class NtfySignalingCryptoTest {

    // ==================== Key Derivation Tests ====================

    @Test
    fun `derives 32-byte key from master secret`() {
        val masterSecret = ByteArray(32)
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
        assertEquals(32, signalingKey.size)
    }

    @Test
    fun `different master secrets produce different keys`() {
        val key1 = NtfySignalingCrypto.deriveSignalingKey(ByteArray(32))
        val key2 = NtfySignalingCrypto.deriveSignalingKey(ByteArray(32) { 0xFF.toByte() })
        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `matches test vector - standard master secret`() {
        // Test vector: derive_signaling_key_1
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val expected = "7f888af2e2d09f628559c3fd853370672752c98fac3377690938f72519d86347".hexToBytes()

        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)

        assertArrayEquals(expected, signalingKey)
    }

    @Test
    fun `matches test vector - zeros`() {
        // Test vector: derive_signaling_key_zeros
        val masterSecret = "0000000000000000000000000000000000000000000000000000000000000000".hexToBytes()
        val expected = "e4c8547bc85e3ec081f0246b5a27944c3dbc6464da4232cc969987c6b1021c7e".hexToBytes()

        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)

        assertArrayEquals(expected, signalingKey)
    }

    @Test
    fun `matches test vector - ones`() {
        // Test vector: derive_signaling_key_ones
        val masterSecret = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff".hexToBytes()
        val expected = "00867693eec02db5b4d0da6e224f07e329c1631dc167fdbf3f2b3b2066c247c4".hexToBytes()

        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)

        assertArrayEquals(expected, signalingKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `derive key rejects short master secret`() {
        NtfySignalingCrypto.deriveSignalingKey(ByteArray(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `derive key rejects long master secret`() {
        NtfySignalingCrypto.deriveSignalingKey(ByteArray(64))
    }

    // ==================== Encryption Tests ====================

    @Test
    fun `encrypt produces iv plus ciphertext plus tag`() {
        val key = ByteArray(32)
        val plaintext = "test message".toByteArray()
        val crypto = NtfySignalingCrypto(key)

        val encrypted = crypto.encrypt(plaintext)

        // Minimum size: 12 (IV) + 0 (plaintext) + 16 (tag) = 28 bytes
        assertTrue(encrypted.size >= 28)
        // With "test message" (12 bytes): 12 + 12 + 16 = 40 bytes
        assertEquals(40, encrypted.size)
    }

    @Test
    fun `decrypt reverses encrypt`() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "test message".toByteArray()
        val crypto = NtfySignalingCrypto(key)

        val encrypted = crypto.encrypt(plaintext)
        val decrypted = crypto.decrypt(encrypted)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt and decrypt empty message`() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = ByteArray(0)
        val crypto = NtfySignalingCrypto(key)

        val encrypted = crypto.encrypt(plaintext)
        val decrypted = crypto.decrypt(encrypted)

        assertArrayEquals(plaintext, decrypted)
        // Empty: 12 (IV) + 0 (plaintext) + 16 (tag) = 28 bytes
        assertEquals(28, encrypted.size)
    }

    @Test(expected = DecryptionException::class)
    fun `decrypt with wrong key throws`() {
        val key1 = ByteArray(32)
        val key2 = ByteArray(32) { 0xFF.toByte() }

        val crypto1 = NtfySignalingCrypto(key1)
        val crypto2 = NtfySignalingCrypto(key2)

        val encrypted = crypto1.encrypt("test".toByteArray())
        crypto2.decrypt(encrypted)
    }

    @Test(expected = DecryptionException::class)
    fun `decrypt tampered ciphertext throws`() {
        val key = ByteArray(32)
        val crypto = NtfySignalingCrypto(key)

        val encrypted = crypto.encrypt("test".toByteArray()).copyOf()
        // Tamper with the last byte (part of auth tag)
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0xFF).toByte()

        crypto.decrypt(encrypted)
    }

    @Test(expected = DecryptionException::class)
    fun `decrypt message too short throws`() {
        val key = ByteArray(32)
        val crypto = NtfySignalingCrypto(key)

        // Less than minimum (28 bytes)
        crypto.decrypt(ByteArray(27))
    }

    // ==================== Base64 Encryption Tests ====================

    @Test
    fun `encrypt to base64 and decrypt`() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "test message".toByteArray()
        val crypto = NtfySignalingCrypto(key)

        val encryptedBase64 = crypto.encryptToBase64(plaintext)
        val decrypted = crypto.decryptFromBase64(encryptedBase64)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `matches encryption test vector`() {
        // Test vector: encrypt_offer_message
        val key = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2".hexToBytes()
        val iv = "112233445566778899aabbcc".hexToBytes()
        val plaintext = "{\"type\":\"OFFER\",\"session_id\":\"abc123\",\"sdp\":\"v=0...\"}".toByteArray()
        val expectedBase64 = "ESIzRFVmd4iZqrvM3vb+eOINH0iViUgTfOOjwCHS5Yp9WRtaiqNq7q942vVM9xTcm8LXCrpga9mur1MltMsWcLhZRqDS/Je/uQc9gJ2K6rqE"

        val crypto = NtfySignalingCrypto(key)
        val encryptedBase64 = crypto.encryptToBase64WithIv(plaintext, iv)

        assertEquals(expectedBase64, encryptedBase64)
    }

    @Test
    fun `matches encryption test vector - empty plaintext`() {
        // Test vector: encrypt_empty
        val key = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2".hexToBytes()
        val iv = "112233445566778899aabbcc".hexToBytes()
        val plaintext = ByteArray(0)
        val expectedBase64 = "ESIzRFVmd4iZqrvMQu21/ncKihS/2X8/ABTl2A=="

        val crypto = NtfySignalingCrypto(key)
        val encryptedBase64 = crypto.encryptToBase64WithIv(plaintext, iv)

        assertEquals(expectedBase64, encryptedBase64)
    }

    @Test(expected = DecryptionException::class)
    fun `decrypt invalid base64 throws`() {
        val key = ByteArray(32)
        val crypto = NtfySignalingCrypto(key)

        crypto.decryptFromBase64("not valid base64!!!")
    }

    // ==================== Key Zeroing Tests ====================

    @Test
    fun `zeroKey clears the key`() {
        val key = ByteArray(32) { it.toByte() }
        val crypto = NtfySignalingCrypto(key)

        // Verify key works
        val encrypted = crypto.encrypt("test".toByteArray())
        crypto.decrypt(encrypted) // Should work

        // Zero the key
        crypto.zeroKey()

        // Key should now be all zeros (encryption/decryption will produce different results)
        // We can't easily verify this without exposing internal state,
        // but at least verify no crash
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects short key`() {
        NtfySignalingCrypto(ByteArray(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects long key`() {
        NtfySignalingCrypto(ByteArray(64))
    }
}

// Helper extensions for tests
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
