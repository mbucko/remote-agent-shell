package com.ras.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
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

    // --- Pairing HMAC tests ---

    @Tag("unit")
    @Test
    fun `computePairRequestHmac is deterministic`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val sessionId = "session-42"
        val deviceId = "device-abc"
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        val result1 = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce)
        val result2 = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce)

        assertArrayEquals(result1, result2)
    }

    @Tag("unit")
    @Test
    fun `computePairRequestHmac different keys produce different HMACs`() {
        val key1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val key2 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210".hexToBytes()
        val sessionId = "session-42"
        val deviceId = "device-abc"
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        val result1 = HmacUtils.computePairRequestHmac(key1, sessionId, deviceId, nonce)
        val result2 = HmacUtils.computePairRequestHmac(key2, sessionId, deviceId, nonce)

        assertFalse(result1.contentEquals(result2))
    }

    @Tag("unit")
    @Test
    fun `computePairRequestHmac different nonces produce different HMACs`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val sessionId = "session-42"
        val deviceId = "device-abc"
        val nonce1 = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()
        val nonce2 = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff".hexToBytes()

        val result1 = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce1)
        val result2 = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce2)

        assertFalse(result1.contentEquals(result2))
    }

    @Tag("unit")
    @Test
    fun `computePairRequestHmac different device IDs produce different HMACs`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val sessionId = "session-42"
        val deviceId1 = "device-abc"
        val deviceId2 = "device-xyz"
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        val result1 = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId1, nonce)
        val result2 = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId2, nonce)

        assertFalse(result1.contentEquals(result2))
    }

    @Tag("unit")
    @Test
    fun `computePairRequestHmac different session IDs produce different HMACs`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val sessionId1 = "session-42"
        val sessionId2 = "session-99"
        val deviceId = "device-abc"
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        val result1 = HmacUtils.computePairRequestHmac(authKey, sessionId1, deviceId, nonce)
        val result2 = HmacUtils.computePairRequestHmac(authKey, sessionId2, deviceId, nonce)

        assertFalse(result1.contentEquals(result2))
    }

    @Tag("unit")
    @Test
    fun `computePairRequestHmac output is 32 bytes`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val sessionId = "session-42"
        val deviceId = "device-abc"
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        val result = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce)

        assertEquals(32, result.size)
    }

    @Tag("unit")
    @Test
    fun `computePairResponseHmac is deterministic`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        val result1 = HmacUtils.computePairResponseHmac(authKey, nonce)
        val result2 = HmacUtils.computePairResponseHmac(authKey, nonce)

        assertArrayEquals(result1, result2)
    }

    @Tag("unit")
    @Test
    fun `computePairResponseHmac different keys produce different HMACs`() {
        val key1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val key2 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210".hexToBytes()
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        val result1 = HmacUtils.computePairResponseHmac(key1, nonce)
        val result2 = HmacUtils.computePairResponseHmac(key2, nonce)

        assertFalse(result1.contentEquals(result2))
    }

    @Tag("unit")
    @Test
    fun `request and response HMACs are different`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val sessionId = "session-42"
        val deviceId = "device-abc"
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        val requestHmac = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce)
        val responseHmac = HmacUtils.computePairResponseHmac(authKey, nonce)

        assertFalse(requestHmac.contentEquals(responseHmac))
    }

    @Tag("unit")
    @Test
    fun `mutual verification round trip`() {
        val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val sessionId = "session-42"
        val deviceId = "device-abc"
        val nonce = "aabbccddee00112233445566778899aabbccddee00112233445566778899aabb".hexToBytes()

        // Phone computes request HMAC and sends it
        val requestHmac = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce)

        // Daemon recomputes and verifies the request HMAC
        val daemonRequestHmac = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce)
        assertTrue(HmacUtils.constantTimeEquals(requestHmac, daemonRequestHmac))

        // Daemon computes response HMAC and sends it back
        val responseHmac = HmacUtils.computePairResponseHmac(authKey, nonce)

        // Phone recomputes and verifies the response HMAC
        val phoneResponseHmac = HmacUtils.computePairResponseHmac(authKey, nonce)
        assertTrue(HmacUtils.constantTimeEquals(responseHmac, phoneResponseHmac))
    }
}
