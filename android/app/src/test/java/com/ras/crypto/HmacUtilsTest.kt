package com.ras.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class HmacUtilsTest {

    @Tag("unit")
    @Test
    fun `compute hmac matches test vector - auth challenge`() {
        val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val message = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210".hexToBytes()
        val expected = "fc620ba9fee2a44f2ea7a4cdf04348f2fa7299feb84ea028c48f80bba0bdddb0"

        val result = HmacUtils.computeHmac(key, message)

        assertEquals(expected, result.toHex())
    }

    @Tag("unit")
    @Test
    fun `compute hmac matches test vector - empty message`() {
        val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val message = ByteArray(0)
        val expected = "c7b5e12ec029a887022abbdc648f8380db2f41e44220ec1530553c24d81d2fee"

        val result = HmacUtils.computeHmac(key, message)

        assertEquals(expected, result.toHex())
    }

    @Tag("unit")
    @Test
    fun `compute hmac matches test vector - long message`() {
        val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val message = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f".hexToBytes()
        val expected = "cecc4a1eb9d85d1e061c0d16f0e830c3a50d76cfab314fc420e149e83133e91d"

        val result = HmacUtils.computeHmac(key, message)

        assertEquals(expected, result.toHex())
    }

    @Tag("unit")
    @Test
    fun `verify hmac returns true for valid hmac`() {
        val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val message = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210".hexToBytes()
        val validHmac = "fc620ba9fee2a44f2ea7a4cdf04348f2fa7299feb84ea028c48f80bba0bdddb0".hexToBytes()

        assertTrue(HmacUtils.verifyHmac(key, message, validHmac))
    }

    @Tag("unit")
    @Test
    fun `verify hmac returns false for invalid hmac`() {
        val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val message = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210".hexToBytes()
        val invalidHmac = "0000000000000000000000000000000000000000000000000000000000000000".hexToBytes()

        assertFalse(HmacUtils.verifyHmac(key, message, invalidHmac))
    }

    @Tag("unit")
    @Test
    fun `constant time equals returns true for equal arrays`() {
        val a = "0123456789abcdef".hexToBytes()
        val b = "0123456789abcdef".hexToBytes()

        assertTrue(HmacUtils.constantTimeEquals(a, b))
    }

    @Tag("unit")
    @Test
    fun `constant time equals returns false for different arrays`() {
        val a = "0123456789abcdef".hexToBytes()
        val b = "0123456789abcdee".hexToBytes() // One bit different

        assertFalse(HmacUtils.constantTimeEquals(a, b))
    }

    @Tag("unit")
    @Test
    fun `constant time equals returns false for different lengths`() {
        val a = "0123456789".hexToBytes()
        val b = "0123456789abcdef".hexToBytes()

        assertFalse(HmacUtils.constantTimeEquals(a, b))
    }

    @Tag("unit")
    @Test
    fun `constant time equals handles empty arrays`() {
        val a = ByteArray(0)
        val b = ByteArray(0)

        assertTrue(HmacUtils.constantTimeEquals(a, b))
    }

    @Tag("unit")
    @Test
    fun `compute signaling hmac produces correct format`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val sessionId = "abc123"
        val timestamp = 1704067200L // 2024-01-01 00:00:00 UTC
        val body = "test body".toByteArray()

        // Manually construct the expected input
        val sessionIdBytes = sessionId.toByteArray(Charsets.UTF_8)
        val timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array()
        val expectedInput = sessionIdBytes + timestampBytes + body

        val expectedHmac = HmacUtils.computeHmac(authKey, expectedInput)
        val result = HmacUtils.computeSignalingHmac(authKey, sessionId, timestamp, body)

        assertTrue(expectedHmac.contentEquals(result))
    }
}
