package com.ras.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class KeyDerivationTest {

    @Tag("unit")
    @Test
    fun `derive auth key matches test vector 1`() {
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val expected = "bec0c3289e346d890ea330014e23e6e7cf95f82c8bd7f5f133850c89ac165a43"

        val result = KeyDerivation.deriveKey(masterSecret, "auth")

        assertEquals(expected, result.toHex())
    }

    @Tag("unit")
    @Test
    fun `derive encrypt key matches test vector 1`() {
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val expected = "fdb096356d535edd24a3eee6f2126b77018c51dff15c86ccf6bc3c76f086c2a0"

        val result = KeyDerivation.deriveKey(masterSecret, "encrypt")

        assertEquals(expected, result.toHex())
    }

    @Tag("unit")
    @Test
    fun `derive ntfy key matches test vector 1`() {
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val expected = "e3d801b5755b78c380d59c1285c1a65290db0334cc2994dfd048ebff2df8781f"

        val result = KeyDerivation.deriveKey(masterSecret, "ntfy")

        assertEquals(expected, result.toHex())
    }

    @Tag("unit")
    @Test
    fun `derive auth key with zeros matches test vector`() {
        val masterSecret = "0000000000000000000000000000000000000000000000000000000000000000".hexToBytes()
        val expected = "31df6cff2f7200af61bee50e3b01fad553d8e430c2b0c376e498598956d7e809"

        val result = KeyDerivation.deriveKey(masterSecret, "auth")

        assertEquals(expected, result.toHex())
    }

    @Tag("unit")
    @Test
    fun `derive auth key with ones matches test vector`() {
        val masterSecret = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff".hexToBytes()
        val expected = "c24fcd4ea4a6a0c8c02fb2417b5fc998cd8191e681808cc9fa5209c9e65b0790"

        val result = KeyDerivation.deriveKey(masterSecret, "auth")

        assertEquals(expected, result.toHex())
    }

    @Tag("unit")
    @Test
    fun `derive ntfy topic matches test vector`() {
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val expected = "ras-4884fdaafea4"

        val result = KeyDerivation.deriveNtfyTopic(masterSecret)

        assertEquals(expected, result)
    }

    @Tag("unit")
    @Test
    fun `derive key rejects short master secret`() {
        val shortSecret = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.deriveKey(shortSecret, "auth")
        }
    }

    @Tag("unit")
    @Test
    fun `derive key rejects long master secret`() {
        val longSecret = ByteArray(64)
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.deriveKey(longSecret, "auth")
        }
    }

    @Tag("unit")
    @Test
    fun `derive topic rejects short master secret`() {
        val shortSecret = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.deriveNtfyTopic(shortSecret)
        }
    }
}
