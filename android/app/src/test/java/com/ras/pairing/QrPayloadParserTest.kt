package com.ras.pairing

import android.util.Base64
import com.ras.crypto.hexToBytes
import com.ras.proto.QrPayload
import com.google.protobuf.ByteString
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QrPayloadParserTest {

    @Before
    fun setup() {
        // Mock Android's Base64 since it's not available in unit tests
        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers {
            val input = firstArg<String>()
            java.util.Base64.getDecoder().decode(input)
        }
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `parse valid IPv4 payload`() {
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val payload = QrPayload.newBuilder()
            .setVersion(1)
            .setIp("192.168.1.100")
            .setPort(8821)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123def456")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Success)
        val success = result as QrParseResult.Success
        assertEquals("192.168.1.100", success.payload.ip)
        assertEquals(8821, success.payload.port)
        assertEquals("abc123def456", success.payload.sessionId)
        assertEquals("ras-abc123", success.payload.ntfyTopic)
        assertTrue(masterSecret.contentEquals(success.payload.masterSecret))
    }

    @Test
    fun `parse valid IPv6 payload`() {
        val masterSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
        val payload = QrPayload.newBuilder()
            .setVersion(1)
            .setIp("::1")
            .setPort(8821)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123def456")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Success)
        val success = result as QrParseResult.Success
        assertEquals("::1", success.payload.ip)
    }

    @Test
    fun `reject invalid base64`() {
        val result = QrPayloadParser.parse("not-valid-base64!!!")

        assertTrue(result is QrParseResult.Error)
        assertEquals(QrParseResult.ErrorCode.INVALID_BASE64, (result as QrParseResult.Error).code)
    }

    @Test
    fun `reject invalid version`() {
        val masterSecret = ByteArray(32)
        val payload = QrPayload.newBuilder()
            .setVersion(999)
            .setIp("192.168.1.1")
            .setPort(8821)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Error)
        assertEquals(QrParseResult.ErrorCode.UNSUPPORTED_VERSION, (result as QrParseResult.Error).code)
    }

    @Test
    fun `reject missing IP`() {
        val masterSecret = ByteArray(32)
        val payload = QrPayload.newBuilder()
            .setVersion(1)
            .setIp("")
            .setPort(8821)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Error)
        assertEquals(QrParseResult.ErrorCode.MISSING_FIELD, (result as QrParseResult.Error).code)
    }

    @Test
    fun `reject invalid secret length - too short`() {
        val masterSecret = ByteArray(16) // Should be 32
        val payload = QrPayload.newBuilder()
            .setVersion(1)
            .setIp("192.168.1.1")
            .setPort(8821)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Error)
        assertEquals(QrParseResult.ErrorCode.INVALID_SECRET_LENGTH, (result as QrParseResult.Error).code)
    }

    @Test
    fun `reject invalid port - zero`() {
        val masterSecret = ByteArray(32)
        val payload = QrPayload.newBuilder()
            .setVersion(1)
            .setIp("192.168.1.1")
            .setPort(0)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Error)
        assertEquals(QrParseResult.ErrorCode.INVALID_PORT, (result as QrParseResult.Error).code)
    }

    @Test
    fun `reject invalid port - too high`() {
        val masterSecret = ByteArray(32)
        val payload = QrPayload.newBuilder()
            .setVersion(1)
            .setIp("192.168.1.1")
            .setPort(65536)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Error)
        assertEquals(QrParseResult.ErrorCode.INVALID_PORT, (result as QrParseResult.Error).code)
    }

    @Test
    fun `accept valid port boundary - port 1`() {
        val masterSecret = ByteArray(32)
        val payload = QrPayload.newBuilder()
            .setVersion(1)
            .setIp("192.168.1.1")
            .setPort(1)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Success)
    }

    @Test
    fun `accept valid port boundary - port 65535`() {
        val masterSecret = ByteArray(32)
        val payload = QrPayload.newBuilder()
            .setVersion(1)
            .setIp("192.168.1.1")
            .setPort(65535)
            .setMasterSecret(ByteString.copyFrom(masterSecret))
            .setSessionId("abc123")
            .setNtfyTopic("ras-abc123")
            .build()

        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())

        val result = QrPayloadParser.parse(base64)

        assertTrue(result is QrParseResult.Success)
    }
}
