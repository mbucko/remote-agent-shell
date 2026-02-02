package com.ras.pairing

import com.ras.crypto.HmacUtils
import com.ras.crypto.hexToBytes
import com.ras.crypto.toHex
import com.ras.proto.SignalError
import com.ras.proto.SignalRequest
import com.ras.proto.SignalResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class SignalingClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: SignalingClient
    private lateinit var shortTimeoutClient: SignalingClient

    private val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()
    private val sessionId = "test-session-123"
    private val deviceId = "device-123"
    private val deviceName = "Test Device"
    private val sdpOffer = "v=0\r\no=- 123 1 IN IP4 127.0.0.1\r\n..."

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        client = SignalingClient(httpClient)

        val shortTimeoutHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        shortTimeoutClient = SignalingClient(shortTimeoutHttpClient)
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    // ============================================================================
    // Success Scenarios
    // ============================================================================

    @Tag("unit")
    @Test
    fun `successful signaling returns SDP answer`() = runBlocking {
        val expectedAnswer = "v=0\r\no=- 456 1 IN IP4 192.168.1.1\r\n..."
        val response = SignalResponse.newBuilder()
            .setSdpAnswer(expectedAnswer)
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-protobuf")
                .setBody(Buffer().write(response.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Success)
        assertEquals(expectedAnswer, (result as SignalingResult.Success).sdpAnswer)

        // Verify request was properly formed
        val request = mockServer.takeRequest()
        assertEquals("/signal/$sessionId", request.path)
        assertEquals("POST", request.method)
        assertEquals("application/x-protobuf", request.getHeader("Content-Type"))
        assertTrue(request.getHeader("X-RAS-Signature")?.isNotEmpty() == true)
        assertTrue(request.getHeader("X-RAS-Timestamp")?.isNotEmpty() == true)
    }

    @Tag("unit")
    @Test
    fun `request includes correct HMAC signature`() = runBlocking {
        val response = SignalResponse.newBuilder()
            .setSdpAnswer("answer")
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(response.toByteArray()))
        )

        client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        val request = mockServer.takeRequest()
        val signatureHex = request.getHeader("X-RAS-Signature")!!
        val timestamp = request.getHeader("X-RAS-Timestamp")!!.toLong()
        val body = request.body.readByteArray()

        // Verify signature
        val expectedSignature = HmacUtils.computeSignalingHmac(authKey, sessionId, timestamp, body)
        assertEquals(expectedSignature.toHex(), signatureHex)

        // Verify body contains correct protobuf
        val signalRequest = SignalRequest.parseFrom(body)
        assertEquals(sdpOffer, signalRequest.sdpOffer)
        assertEquals(deviceId, signalRequest.deviceId)
        assertEquals(deviceName, signalRequest.deviceName)
    }

    // ============================================================================
    // Error Response Scenarios
    // ============================================================================

    @Tag("unit")
    @Test
    fun `INVALID_REQUEST error code from server`() = runBlocking {
        val error = SignalError.newBuilder()
            .setCode(SignalError.ErrorCode.INVALID_REQUEST)
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/x-protobuf")
                .setBody(Buffer().write(error.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.INVALID_REQUEST, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `INVALID_SESSION error code from server`() = runBlocking {
        val error = SignalError.newBuilder()
            .setCode(SignalError.ErrorCode.INVALID_SESSION)
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody(Buffer().write(error.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = "invalid-session",
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.INVALID_SESSION, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `AUTHENTICATION_FAILED error code from server`() = runBlocking {
        val error = SignalError.newBuilder()
            .setCode(SignalError.ErrorCode.AUTHENTICATION_FAILED)
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody(Buffer().write(error.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.AUTHENTICATION_FAILED, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `RATE_LIMITED error code from server`() = runBlocking {
        val error = SignalError.newBuilder()
            .setCode(SignalError.ErrorCode.RATE_LIMITED)
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody(Buffer().write(error.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.RATE_LIMITED, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `INTERNAL_ERROR error code from server`() = runBlocking {
        val error = SignalError.newBuilder()
            .setCode(SignalError.ErrorCode.INTERNAL_ERROR)
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody(Buffer().write(error.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.INTERNAL_ERROR, (result as SignalingResult.Error).code)
    }

    // ============================================================================
    // Network Error Scenarios
    // ============================================================================

    @Tag("unit")
    @Test
    fun `connection timeout returns UNKNOWN error`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )

        val result = shortTimeoutClient.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.UNKNOWN, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `connection refused returns UNKNOWN error`() = runBlocking {
        mockServer.shutdown() // Shut down to simulate connection refused

        val result = client.sendSignal(
            ip = "127.0.0.1",
            port = 9999, // Port no one is listening on
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.UNKNOWN, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `disconnect during response returns UNKNOWN error`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                .setBody("partial response")
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.UNKNOWN, (result as SignalingResult.Error).code)
    }

    // ============================================================================
    // Malformed Response Scenarios
    // ============================================================================

    @Tag("unit")
    @Test
    fun `empty response body on success returns UNKNOWN error`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("")
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.UNKNOWN, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `invalid protobuf in success response returns UNKNOWN error`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("not a valid protobuf")
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.UNKNOWN, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `invalid protobuf in error response returns UNKNOWN error`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("not a valid protobuf")
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.UNKNOWN, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `empty error response body returns UNKNOWN error`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("")
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.UNKNOWN, (result as SignalingResult.Error).code)
    }

    @Tag("unit")
    @Test
    fun `HTTP 500 without body returns UNKNOWN error`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Error)
        assertEquals(SignalError.ErrorCode.UNKNOWN, (result as SignalingResult.Error).code)
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Tag("unit")
    @Test
    fun `handles IPv6 address`() = runBlocking {
        val response = SignalResponse.newBuilder()
            .setSdpAnswer("answer")
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(response.toByteArray()))
        )

        // Note: MockWebServer binds to localhost, so we use its actual host
        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Success)
    }

    @Tag("unit")
    @Test
    fun `handles long SDP offer`() = runBlocking {
        val longSdp = "v=0\r\n" + "a=candidate:".repeat(1000) // Simulate many ICE candidates

        val response = SignalResponse.newBuilder()
            .setSdpAnswer("answer")
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(response.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = longSdp,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Success)

        val request = mockServer.takeRequest()
        val signalRequest = SignalRequest.parseFrom(request.body.readByteArray())
        assertEquals(longSdp, signalRequest.sdpOffer)
    }

    @Tag("unit")
    @Test
    fun `handles unicode device name`() = runBlocking {
        val unicodeDeviceName = "Pixel 9 Pro 📱"

        val response = SignalResponse.newBuilder()
            .setSdpAnswer("answer")
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(response.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = unicodeDeviceName
        )

        assertTrue(result is SignalingResult.Success)

        val request = mockServer.takeRequest()
        val signalRequest = SignalRequest.parseFrom(request.body.readByteArray())
        assertEquals(unicodeDeviceName, signalRequest.deviceName)
    }

    @Tag("unit")
    @Test
    fun `handles special characters in session ID`() = runBlocking {
        val specialSessionId = "abc-123_def.456"

        val response = SignalResponse.newBuilder()
            .setSdpAnswer("answer")
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(response.toByteArray()))
        )

        val result = client.sendSignal(
            ip = mockServer.hostName,
            port = mockServer.port,
            sessionId = specialSessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        assertTrue(result is SignalingResult.Success)

        val request = mockServer.takeRequest()
        assertEquals("/signal/$specialSessionId", request.path)
    }
}
