package com.ras.signaling

import com.google.protobuf.ByteString
import com.ras.proto.NtfySignalMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for NtfySignalMessageValidator.
 *
 * Test vectors from test-vectors/ntfy_signaling.json.
 */
class NtfySignalMessageValidatorTest {

    private lateinit var validator: NtfySignalMessageValidator

    @BeforeEach
    fun setup() {
        validator = NtfySignalMessageValidator(
            pendingSessionId = "abc123",
            expectedType = NtfySignalMessage.MessageType.ANSWER,
            timestampWindowSeconds = 30
        )
    }

    // ==================== Valid Message Tests ====================

    @Tag("unit")
    @Test
    fun `valid answer passes validation`() {
        val msg = validAnswerMessage()
        val result = validator.validate(msg)
        assertTrue(result.isValid)
        assertEquals(null, result.error)
    }

    @Tag("unit")
    @Test
    fun `valid answer at timestamp boundary passes`() {
        // 30 seconds in past (inclusive boundary)
        val msg = validAnswerMessage(timestamp = currentTimestamp() - 30)
        val result = validator.validate(msg)
        assertTrue(result.isValid)
    }

    @Tag("unit")
    @Test
    fun `valid answer at future timestamp boundary passes`() {
        // 30 seconds in future (inclusive boundary)
        val msg = validAnswerMessage(timestamp = currentTimestamp() + 30)
        val result = validator.validate(msg)
        assertTrue(result.isValid)
    }

    // ==================== Session ID Tests ====================

    @Tag("unit")
    @Test
    fun `wrong session id rejected`() {
        val msg = validAnswerMessage(sessionId = "wrong-session")
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_SESSION, result.error)
    }

    @Tag("unit")
    @Test
    fun `empty session id rejected when expecting non-empty`() {
        val msg = validAnswerMessage(sessionId = "")
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_SESSION, result.error)
    }

    @Tag("unit")
    @Test
    fun `empty session id accepted in reconnection mode`() {
        // Reconnection mode uses empty session ID for both pending and message
        val reconnectionValidator = NtfySignalMessageValidator(
            pendingSessionId = "",  // Empty for reconnection mode
            expectedType = NtfySignalMessage.MessageType.ANSWER,
            timestampWindowSeconds = 30
        )

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.ANSWER)
            .setSessionId("")  // Empty in daemon response
            .setSdp("v=0\r\nm=application 9\r\n")
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(ByteArray(16) { it.toByte() }))
            .build()

        val result = reconnectionValidator.validate(msg)
        assertTrue(result.isValid, "Reconnection mode should accept empty session IDs")
    }

    // ==================== Timestamp Tests ====================

    @Tag("unit")
    @Test
    fun `timestamp too old rejected`() {
        // 31 seconds in past (outside boundary)
        val msg = validAnswerMessage(timestamp = currentTimestamp() - 31)
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_TIMESTAMP, result.error)
    }

    @Tag("unit")
    @Test
    fun `timestamp too new rejected`() {
        // 31 seconds in future (outside boundary)
        val msg = validAnswerMessage(timestamp = currentTimestamp() + 31)
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_TIMESTAMP, result.error)
    }

    @Tag("unit")
    @Test
    fun `timestamp far in past rejected`() {
        val msg = validAnswerMessage(timestamp = currentTimestamp() - 3600)
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_TIMESTAMP, result.error)
    }

    @Tag("unit")
    @Test
    fun `timestamp far in future rejected`() {
        val msg = validAnswerMessage(timestamp = currentTimestamp() + 3600)
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_TIMESTAMP, result.error)
    }

    // ==================== Nonce Tests ====================

    @Tag("unit")
    @Test
    fun `nonce replay rejected`() {
        val nonce = ByteArray(16) { it.toByte() }
        val msg1 = validAnswerMessage(nonce = nonce)

        // First time succeeds
        val result1 = validator.validate(msg1)
        assertTrue(result1.isValid)

        // Same nonce rejected
        val msg2 = validAnswerMessage(nonce = nonce)
        val result2 = validator.validate(msg2)
        assertFalse(result2.isValid)
        assertEquals(ValidationError.NONCE_REPLAY, result2.error)
    }

    @Tag("unit")
    @Test
    fun `nonce too short rejected`() {
        val msg = validAnswerMessage(nonce = ByteArray(15))
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_NONCE, result.error)
    }

    @Tag("unit")
    @Test
    fun `nonce too long rejected`() {
        val msg = validAnswerMessage(nonce = ByteArray(17))
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_NONCE, result.error)
    }

    @Tag("unit")
    @Test
    fun `empty nonce rejected`() {
        val msg = validAnswerMessage(nonce = ByteArray(0))
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_NONCE, result.error)
    }

    // ==================== SDP Tests ====================

    @Tag("unit")
    @Test
    fun `empty sdp rejected`() {
        val msg = validAnswerMessage(sdp = "")
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_SDP, result.error)
    }

    @Tag("unit")
    @Test
    fun `sdp missing version rejected`() {
        val msg = validAnswerMessage(sdp = "m=application 9 UDP/DTLS/SCTP webrtc-datachannel")
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_SDP, result.error)
    }

    @Tag("unit")
    @Test
    fun `sdp missing media line rejected`() {
        val msg = validAnswerMessage(sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n")
        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.INVALID_SDP, result.error)
    }

    @Tag("unit")
    @Test
    fun `valid sdp with version and media passes`() {
        val msg = validAnswerMessage(sdp = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n")
        val result = validator.validate(msg)
        assertTrue(result.isValid)
    }

    // ==================== Message Type Tests ====================

    @Tag("unit")
    @Test
    fun `wrong message type rejected`() {
        // Validator expects ANSWER, but receives OFFER
        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.OFFER)
            .setSessionId("abc123")
            .setSdp("v=0\r\nm=application 9\r\n")
            .setDeviceId("device-1")
            .setDeviceName("My Phone")
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(ByteArray(16) { it.toByte() }))
            .build()

        val result = validator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.WRONG_MESSAGE_TYPE, result.error)
    }

    // ==================== OFFER Validation Tests ====================

    @Tag("unit")
    @Test
    fun `offer without device id rejected`() {
        val offerValidator = NtfySignalMessageValidator(
            pendingSessionId = "abc123",
            expectedType = NtfySignalMessage.MessageType.OFFER,
            timestampWindowSeconds = 30
        )

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.OFFER)
            .setSessionId("abc123")
            .setSdp("v=0\r\nm=application 9\r\n")
            .setDeviceId("")  // Missing
            .setDeviceName("My Phone")
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(ByteArray(16) { it.toByte() }))
            .build()

        val result = offerValidator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.MISSING_DEVICE_ID, result.error)
    }

    @Tag("unit")
    @Test
    fun `offer without device name rejected`() {
        val offerValidator = NtfySignalMessageValidator(
            pendingSessionId = "abc123",
            expectedType = NtfySignalMessage.MessageType.OFFER,
            timestampWindowSeconds = 30
        )

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.OFFER)
            .setSessionId("abc123")
            .setSdp("v=0\r\nm=application 9\r\n")
            .setDeviceId("device-1")
            .setDeviceName("")  // Missing
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(ByteArray(16) { it.toByte() }))
            .build()

        val result = offerValidator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.MISSING_DEVICE_NAME, result.error)
    }

    @Tag("unit")
    @Test
    fun `offer with device id containing control chars rejected`() {
        val offerValidator = NtfySignalMessageValidator(
            pendingSessionId = "abc123",
            expectedType = NtfySignalMessage.MessageType.OFFER,
            timestampWindowSeconds = 30
        )

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.OFFER)
            .setSessionId("abc123")
            .setSdp("v=0\r\nm=application 9\r\n")
            .setDeviceId("device\u0000id")  // Contains null char
            .setDeviceName("My Phone")
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(ByteArray(16) { it.toByte() }))
            .build()

        val result = offerValidator.validate(msg)
        assertFalse(result.isValid)
        assertEquals(ValidationError.MISSING_DEVICE_ID, result.error)
    }

    @Tag("unit")
    @Test
    fun `valid offer passes`() {
        val offerValidator = NtfySignalMessageValidator(
            pendingSessionId = "abc123",
            expectedType = NtfySignalMessage.MessageType.OFFER,
            timestampWindowSeconds = 30
        )

        val msg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.OFFER)
            .setSessionId("abc123")
            .setSdp("v=0\r\nm=application 9\r\n")
            .setDeviceId("device-1")
            .setDeviceName("My Phone")
            .setTimestamp(currentTimestamp())
            .setNonce(ByteString.copyFrom(ByteArray(16) { it.toByte() }))
            .build()

        val result = offerValidator.validate(msg)
        assertTrue(result.isValid)
    }

    // ==================== Nonce Cache Tests ====================

    @Tag("unit")
    @Test
    fun `clear nonce cache allows replay`() {
        val nonce = ByteArray(16) { it.toByte() }
        val msg = validAnswerMessage(nonce = nonce)

        // First validation succeeds
        validator.validate(msg)

        // Clear cache
        validator.clearNonceCache()

        // Same nonce now accepted
        val result = validator.validate(msg)
        assertTrue(result.isValid)
    }

    @Tag("unit")
    @Test
    fun `nonce cache evicts oldest when full`() {
        // Fill cache with MAX_NONCES entries (100)
        for (i in 0 until 100) {
            // Create unique nonces - put index in first two bytes
            val nonce = ByteArray(16)
            nonce[0] = (i shr 8).toByte()
            nonce[1] = (i and 0xFF).toByte()
            val msg = validAnswerMessage(nonce = nonce)
            validator.validate(msg)
        }

        // Add one more to trigger eviction
        val extraNonce = ByteArray(16)
        extraNonce[0] = 0x01
        extraNonce[1] = 0x00.toByte()
        val extraMsg = validAnswerMessage(nonce = extraNonce)
        validator.validate(extraMsg)

        // First nonce (i=0) should have been evicted
        val firstNonce = ByteArray(16)
        firstNonce[0] = 0
        firstNonce[1] = 0
        val msg = validAnswerMessage(nonce = firstNonce)
        val result = validator.validate(msg)
        assertTrue(result.isValid, "First nonce should be evicted and accepted again")
    }

    // ==================== Device Name Sanitization Tests ====================

    @Tag("unit")
    @Test
    fun `sanitize device name trims whitespace`() {
        assertEquals("My Phone", sanitizeDeviceName("  My Phone  "))
    }

    @Tag("unit")
    @Test
    fun `sanitize device name removes control chars`() {
        assertEquals("Phone Test", sanitizeDeviceName("Phone\u0000\u0001\u0002Test"))
    }

    @Tag("unit")
    @Test
    fun `sanitize device name preserves unicode`() {
        assertEquals("📱 Téléphone 日本語", sanitizeDeviceName("📱 Téléphone 日本語"))
    }

    @Tag("unit")
    @Test
    fun `sanitize device name truncates to max length`() {
        val longName = "A".repeat(100)
        val sanitized = sanitizeDeviceName(longName)
        assertEquals(64, sanitized.length)
    }

    @Tag("unit")
    @Test
    fun `sanitize device name returns original for normal input`() {
        assertEquals("My Phone", sanitizeDeviceName("My Phone"))
    }

    // ==================== Helper Functions ====================

    private fun currentTimestamp(): Long = System.currentTimeMillis() / 1000

    private fun validAnswerMessage(
        sessionId: String = "abc123",
        sdp: String = "v=0\r\nm=application 9\r\n",
        timestamp: Long = currentTimestamp(),
        nonce: ByteArray = ByteArray(16) { (System.nanoTime() and 0xFF).toByte() }
    ): NtfySignalMessage {
        return NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.ANSWER)
            .setSessionId(sessionId)
            .setSdp(sdp)
            .setTimestamp(timestamp)
            .setNonce(ByteString.copyFrom(nonce))
            .build()
    }
}
