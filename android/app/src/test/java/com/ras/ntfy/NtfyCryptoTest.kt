package com.ras.ntfy

import com.ras.crypto.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

class NtfyCryptoTest {

    // Test vectors from Phase 8a
    private val ntfyKey = "e3d801b5755b78c380d59c1285c1a65290db0334cc2994dfd048ebff2df8781f".hexToBytes()

    // ============================================================================
    // Success Cases - Test Vectors from Phase 8a
    // ============================================================================

    @Tag("unit")
    @Test
    fun `decrypt valid IPv4 message from test vector`() {
        // Test vector: IPv4 standard
        // ip: "192.168.1.100", port: 8821, timestamp: 1706384400
        // nonce: 00112233445566778899aabbccddeeff
        // iv: aabbccddeeff001122334455
        val encrypted = "qrvM3e7/ABEiM0RVVlpzDel9pfRPh6QGaeOsmbCwn8cPDv0oeA78kIGlfNwSZBAlVpOmfjem7SWgirh1NoQs8F/7VsMhKQ=="

        val crypto = NtfyCrypto(ntfyKey)
        val result = crypto.decryptIpNotification(encrypted)

        assertEquals("192.168.1.100", result.ip)
        assertEquals(8821, result.port)
        assertEquals(1706384400L, result.timestamp)
        assertEquals(16, result.nonce.size)
        assertEquals("00112233445566778899aabbccddeeff", result.nonce.toHexString())
    }

    @Tag("unit")
    @Test
    fun `decrypt valid IPv6 message from test vector`() {
        // Test vector: IPv6 compressed
        // ip: "2001:db8::1", port: 8821, timestamp: 1706384400
        // nonce: ffeeddccbbaa99887766554433221100
        // iv: 112233445566778899aabbcc
        val encrypted = "ESIzRFVmd4iZqrvMUFLGHHfDR0a8yNtvx8d0LGk471KMFXrJpsoyDO07UR8hPjhY4FBPYcKKYhuuScpjEcKKleO3tww="

        val crypto = NtfyCrypto(ntfyKey)
        val result = crypto.decryptIpNotification(encrypted)

        assertEquals("2001:db8::1", result.ip)
        assertEquals(8821, result.port)
        assertEquals(1706384400L, result.timestamp)
        assertEquals("ffeeddccbbaa99887766554433221100", result.nonce.toHexString())
    }

    // ============================================================================
    // Key Validation
    // ============================================================================

    @Tag("unit")
    @Test
    fun `reject key that is too short`() {
        val shortKey = ByteArray(16)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            NtfyCrypto(shortKey)
        }

        assertTrue(exception.message?.contains("32 bytes") == true)
    }

    @Tag("unit")
    @Test
    fun `reject key that is too long`() {
        val longKey = ByteArray(64)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            NtfyCrypto(longKey)
        }

        assertTrue(exception.message?.contains("32 bytes") == true)
    }

    // ============================================================================
    // Message Validation
    // ============================================================================

    @Tag("unit")
    @Test
    fun `reject message that is too short`() {
        val crypto = NtfyCrypto(ntfyKey)

        // 20 bytes after base64 decode - less than MIN_ENCRYPTED_SIZE (28)
        val tooShort = "AAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 20 bytes decoded

        val exception = assertThrows(IllegalArgumentException::class.java) {
            crypto.decryptIpNotification(tooShort)
        }

        assertTrue(exception.message?.contains("too short") == true)
    }

    @Tag("unit")
    @Test
    fun `reject invalid base64`() {
        val crypto = NtfyCrypto(ntfyKey)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            crypto.decryptIpNotification("not-valid-base64!!!")
        }

        assertTrue(exception.message?.contains("base64") == true)
    }

    @Tag("unit")
    @Test
    fun `reject empty string`() {
        val crypto = NtfyCrypto(ntfyKey)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            crypto.decryptIpNotification("")
        }

        assertTrue(exception.message?.contains("too short") == true)
    }

    // ============================================================================
    // Decryption Failures
    // ============================================================================

    @Tag("unit")
    @Test
    fun `reject message with wrong key`() {
        val wrongKey = ByteArray(32) { 0xFF.toByte() }
        val crypto = NtfyCrypto(wrongKey)

        // Valid encrypted message but wrong key
        val encrypted = "qrvM3e7/ABEiM0RVVlpzDel9pfRPh6QGaeOsmbCwn8cPDv0oeA78kIGlfNwSZBAlVpOmfjem7SWgirh1NoQs8F/7VsMhKQ=="

        // Should throw AEADBadTagException (or its superclass)
        assertThrows(Exception::class.java) {
            crypto.decryptIpNotification(encrypted)
        }
    }

    @Tag("unit")
    @Test
    fun `reject tampered message`() {
        val crypto = NtfyCrypto(ntfyKey)

        // Tamper with the ciphertext (change character in the middle of the message)
        // Original starts with "qrvM3e7/" - change first character to flip bits in IV/ciphertext
        val tampered = "XrvM3e7/ABEiM0RVVlpzDel9pfRPh6QGaeOsmbCwn8cPDv0oeA78kIGlfNwSZBAlVpOmfjem7SWgirh1NoQs8F/7VsMhKQ=="

        assertThrows(Exception::class.java) {
            crypto.decryptIpNotification(tampered)
        }
    }

    // ============================================================================
    // IpChangeData Tests
    // ============================================================================

    @Tag("unit")
    @Test
    fun `IpChangeData equals works correctly`() {
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val data1 = IpChangeData("1.2.3.4", 8821, 1234567890L, nonce)
        val data2 = IpChangeData("1.2.3.4", 8821, 1234567890L, nonce.copyOf())
        val data3 = IpChangeData("1.2.3.4", 8821, 1234567890L, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

        assertEquals(data1, data2)
        assertTrue(data1 != data3)
    }

    @Tag("unit")
    @Test
    fun `IpChangeData hashCode works correctly`() {
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val data1 = IpChangeData("1.2.3.4", 8821, 1234567890L, nonce)
        val data2 = IpChangeData("1.2.3.4", 8821, 1234567890L, nonce.copyOf())

        assertEquals(data1.hashCode(), data2.hashCode())
    }
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
