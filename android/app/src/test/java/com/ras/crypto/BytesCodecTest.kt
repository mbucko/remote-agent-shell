package com.ras.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class BytesCodecTest {

    private fun generateKey(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return key
    }

    @Tag("unit")
    @Test
    fun `encode then decode returns original data`() {
        val key = generateKey()
        val codec = BytesCodec(key)
        val original = "Hello, World!".toByteArray()

        val encrypted = codec.encode(original)
        val decrypted = codec.decode(encrypted)

        assertArrayEquals(original, decrypted)
    }

    @Tag("unit")
    @Test
    fun `encode produces different ciphertext each time`() {
        val key = generateKey()
        val codec = BytesCodec(key)
        val plaintext = "Same plaintext".toByteArray()

        val encrypted1 = codec.encode(plaintext)
        val encrypted2 = codec.encode(plaintext)

        // Due to random nonce, ciphertext should be different
        assertFalse(encrypted1.contentEquals(encrypted2))

        // But both should decrypt to same plaintext
        assertArrayEquals(plaintext, codec.decode(encrypted1))
        assertArrayEquals(plaintext, codec.decode(encrypted2))
    }

    @Tag("unit")
    @Test
    fun `encrypted data has correct format`() {
        val key = generateKey()
        val codec = BytesCodec(key)
        val plaintext = "Test".toByteArray()

        val encrypted = codec.encode(plaintext)

        // Format: nonce (12) + ciphertext (same as plaintext) + tag (16)
        // Minimum: 12 + 0 + 16 = 28 bytes for empty plaintext
        assertTrue(encrypted.size >= 28)
        // For "Test" (4 bytes): 12 + 4 + 16 = 32 bytes
        assertEquals(32, encrypted.size)
    }

    @Tag("unit")
    @Test
    fun `empty plaintext encrypts and decrypts correctly`() {
        val key = generateKey()
        val codec = BytesCodec(key)
        val empty = ByteArray(0)

        val encrypted = codec.encode(empty)
        val decrypted = codec.decode(encrypted)

        assertEquals(0, decrypted.size)
        // Encrypted should be: nonce (12) + tag (16) = 28 bytes
        assertEquals(28, encrypted.size)
    }

    @Tag("unit")
    @Test
    fun `large data encrypts and decrypts correctly`() {
        val key = generateKey()
        val codec = BytesCodec(key)
        val large = ByteArray(1024 * 1024) // 1 MB
        SecureRandom().nextBytes(large)

        val encrypted = codec.encode(large)
        val decrypted = codec.decode(encrypted)

        assertArrayEquals(large, decrypted)
    }

    @Tag("unit")
    @Test
    fun `wrong key fails to decrypt`() {
        val key1 = generateKey()
        val key2 = generateKey()
        val codec1 = BytesCodec(key1)
        val codec2 = BytesCodec(key2)
        val plaintext = "Secret data".toByteArray()

        val encrypted = codec1.encode(plaintext)

        assertThrows(CryptoException::class.java) {
            codec2.decode(encrypted)
        }
    }

    @Tag("unit")
    @Test
    fun `tampered ciphertext fails to decrypt`() {
        val key = generateKey()
        val codec = BytesCodec(key)
        val plaintext = "Important data".toByteArray()

        val encrypted = codec.encode(plaintext)

        // Tamper with the ciphertext (flip a bit in the middle)
        val tampered = encrypted.copyOf()
        tampered[20] = (tampered[20].toInt() xor 0x01).toByte()

        assertThrows(CryptoException::class.java) {
            codec.decode(tampered)
        }
    }

    @Tag("unit")
    @Test
    fun `tampered nonce fails to decrypt`() {
        val key = generateKey()
        val codec = BytesCodec(key)
        val plaintext = "Important data".toByteArray()

        val encrypted = codec.encode(plaintext)

        // Tamper with the nonce (flip a bit)
        val tampered = encrypted.copyOf()
        tampered[5] = (tampered[5].toInt() xor 0x01).toByte()

        assertThrows(CryptoException::class.java) {
            codec.decode(tampered)
        }
    }

    @Tag("unit")
    @Test
    fun `tampered tag fails to decrypt`() {
        val key = generateKey()
        val codec = BytesCodec(key)
        val plaintext = "Important data".toByteArray()

        val encrypted = codec.encode(plaintext)

        // Tamper with the tag (last 16 bytes)
        val tampered = encrypted.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        assertThrows(CryptoException::class.java) {
            codec.decode(tampered)
        }
    }

    @Test
    fun `data too short throws CryptoException`() {
        val key = generateKey()
        val codec = BytesCodec(key)

        // Less than minimum 28 bytes
        val tooShort = ByteArray(20)
        assertThrows(CryptoException::class.java) {
            codec.decode(tooShort)
        }
    }

    @Test
    fun `key too short throws exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            BytesCodec(ByteArray(16)) // 16 bytes instead of 32
        }
    }

    @Test
    fun `key too long throws exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            BytesCodec(ByteArray(64)) // 64 bytes instead of 32
        }
    }

    @Tag("unit")
    @Test
    fun `zeroKey clears key material`() {
        val key = generateKey()
        val codec = BytesCodec(key)

        // Verify codec works before zeroing
        val plaintext = "Test".toByteArray()
        val encrypted = codec.encode(plaintext)
        assertArrayEquals(plaintext, codec.decode(encrypted))

        // Zero the key
        codec.zeroKey()

        // Key should be all zeros now
        assertTrue(key.all { it == 0.toByte() })
    }

    @Tag("unit")
    @Test
    fun `binary data with all byte values`() {
        val key = generateKey()
        val codec = BytesCodec(key)

        // Create data with all possible byte values
        val allBytes = ByteArray(256) { it.toByte() }

        val encrypted = codec.encode(allBytes)
        val decrypted = codec.decode(encrypted)

        assertArrayEquals(allBytes, decrypted)
    }
}
