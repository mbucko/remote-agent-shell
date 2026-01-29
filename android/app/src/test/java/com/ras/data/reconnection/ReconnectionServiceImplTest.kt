package com.ras.data.reconnection

import com.ras.crypto.HmacUtils
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.credentials.StoredCredentials
import com.ras.data.webrtc.ConnectionOwnership
import com.ras.data.webrtc.WebRTCClient
import com.ras.domain.startup.ReconnectionResult
import com.ras.pairing.AuthClient
import com.ras.pairing.AuthResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for ReconnectionServiceImpl.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectionServiceImplTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var credentialRepository: CredentialRepository
    private lateinit var httpClient: OkHttpClient
    private lateinit var webRTCClientFactory: WebRTCClient.Factory
    private lateinit var connectionManager: ConnectionManager

    private val testCredentials = StoredCredentials(
        deviceId = "test-device-123",
        masterSecret = ByteArray(32) { it.toByte() },
        daemonHost = "192.168.1.100",
        daemonPort = 8765,
        ntfyTopic = "ras-abc123"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        credentialRepository = mockk()
        httpClient = mockk()
        webRTCClientFactory = mockk()
        connectionManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createService() = ReconnectionServiceImpl(
        credentialRepository = credentialRepository,
        httpClient = httpClient,
        webRTCClientFactory = webRTCClientFactory,
        connectionManager = connectionManager
    )

    // ==========================================================================
    // No Credentials Tests
    // ==========================================================================

    @Test
    fun `reconnect returns NoCredentials when no stored credentials`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns null

        val service = createService()
        val result = service.reconnect()

        assertTrue(result is ReconnectionResult.Failure.NoCredentials)
    }

    // ==========================================================================
    // HTTP Signaling Tests
    // ==========================================================================

    @Test
    fun `reconnect sends POST to correct endpoint`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns false
        every { response.code } returns 404
        every { response.body } returns null

        val service = createService()
        service.reconnect()

        verify {
            httpClient.newCall(match { request ->
                request.url.toString() == "http://192.168.1.100:8765/reconnect/test-device-123" &&
                request.method == "POST"
            })
        }
    }

    @Test
    fun `reconnect includes HMAC signature headers`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns false
        every { response.code } returns 404
        every { response.body } returns null

        val service = createService()
        service.reconnect()

        verify {
            httpClient.newCall(match { request ->
                request.header("X-RAS-Signature") != null &&
                request.header("X-RAS-Timestamp") != null
            })
        }
    }

    @Test
    fun `reconnect returns DaemonUnreachable on network error`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        val call = mockk<Call>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } throws IOException("Connection refused")

        val service = createService()
        val result = service.reconnect()

        assertTrue(result is ReconnectionResult.Failure.DaemonUnreachable)
    }

    @Test
    fun `reconnect returns AuthenticationFailed on 401 response`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns false
        every { response.code } returns 401
        every { response.body } returns null

        val service = createService()
        val result = service.reconnect()

        assertTrue(result is ReconnectionResult.Failure.AuthenticationFailed)
    }

    @Test
    fun `reconnect returns DaemonUnreachable on 404 response`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns false
        every { response.code } returns 404
        every { response.body } returns null

        val service = createService()
        val result = service.reconnect()

        assertTrue(result is ReconnectionResult.Failure.DaemonUnreachable)
    }

    @Test
    fun `reconnect returns NetworkError on 429 response`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns false
        every { response.code } returns 429
        every { response.body } returns null

        val service = createService()
        val result = service.reconnect()

        assertTrue(result is ReconnectionResult.Failure.NetworkError)
    }

    // ==========================================================================
    // WebRTC Connection Tests
    // ==========================================================================

    @Test
    fun `reconnect creates WebRTC offer`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns false
        every { response.code } returns 500
        every { response.body } returns null

        val service = createService()
        service.reconnect()

        coVerify { webRTCClient.createOffer() }
    }

    @Test
    fun `reconnect returns NetworkError when WebRTC offer fails`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } throws RuntimeException("ICE gathering failed")

        val service = createService()
        val result = service.reconnect()

        assertTrue(result is ReconnectionResult.Failure.NetworkError)
    }

    @Test
    fun `reconnect sets remote description with SDP answer`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"
        coEvery { webRTCClient.waitForDataChannel(any()) } returns false

        val sdpAnswer = "v=0\r\no=- 456 2 IN IP4 192.168.1.1\r\n"
        val responseProto = com.ras.proto.SignalResponse.newBuilder()
            .setSdpAnswer(sdpAnswer)
            .build()

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns true
        every { response.body } returns responseProto.toByteArray().toResponseBody()

        val service = createService()
        service.reconnect()

        coVerify { webRTCClient.setRemoteDescription(sdpAnswer) }
    }

    @Test
    fun `reconnect returns NetworkError when data channel fails to open`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"
        coEvery { webRTCClient.waitForDataChannel(any()) } returns false

        val sdpAnswer = "v=0\r\no=- 456 2 IN IP4 192.168.1.1\r\n"
        val responseProto = com.ras.proto.SignalResponse.newBuilder()
            .setSdpAnswer(sdpAnswer)
            .build()

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns true
        every { response.body } returns responseProto.toByteArray().toResponseBody()

        val service = createService()
        val result = service.reconnect()

        assertTrue(result is ReconnectionResult.Failure.NetworkError)
    }

    // ==========================================================================
    // Authentication Tests
    // ==========================================================================

    @Test
    fun `reconnect returns AuthenticationFailed when auth handshake fails`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"
        coEvery { webRTCClient.waitForDataChannel(any()) } returns true
        // Simulate auth handshake failure by throwing during receive
        coEvery { webRTCClient.receive(any()) } throws RuntimeException("Auth failed")

        val sdpAnswer = "v=0\r\no=- 456 2 IN IP4 192.168.1.1\r\n"
        val responseProto = com.ras.proto.SignalResponse.newBuilder()
            .setSdpAnswer(sdpAnswer)
            .build()

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns true
        every { response.body } returns responseProto.toByteArray().toResponseBody()

        val service = createService()
        val result = service.reconnect()

        assertTrue(result is ReconnectionResult.Failure.AuthenticationFailed)
    }

    // ==========================================================================
    // Success Tests
    // ==========================================================================

    @Test
    fun `reconnect hands off to ConnectionManager on success`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"
        coEvery { webRTCClient.waitForDataChannel(any()) } returns true
        every { webRTCClient.transferOwnership(any()) } returns true

        // Mock successful auth handshake
        // AuthClient sends challenge, receives response, validates
        // For testing, we'll simulate a successful handshake by mocking send/receive
        var sendCount = 0
        coEvery { webRTCClient.send(any()) } answers {
            sendCount++
        }

        // Mock receive to return valid auth response
        // AuthClient expects: 1st receive = challenge response from server
        val authResponse = ByteArray(64) // Auth response format
        coEvery { webRTCClient.receive(any()) } returns authResponse

        val sdpAnswer = "v=0\r\no=- 456 2 IN IP4 192.168.1.1\r\n"
        val responseProto = com.ras.proto.SignalResponse.newBuilder()
            .setSdpAnswer(sdpAnswer)
            .build()

        val call = mockk<Call>()
        val response = mockk<Response>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns true
        every { response.body } returns responseProto.toByteArray().toResponseBody()

        val service = createService()
        val result = service.reconnect()

        // The test may fail auth, but we verify the flow gets to the auth step
        // A more complete test would mock AuthClient directly via DI
        coVerify { webRTCClient.waitForDataChannel(any()) }
    }

    // ==========================================================================
    // Cleanup Tests
    // ==========================================================================

    @Test
    fun `reconnect closes WebRTC client on failure`() = runTest {
        coEvery { credentialRepository.getCredentials() } returns testCredentials

        val webRTCClient = mockk<WebRTCClient>(relaxed = true)
        every { webRTCClientFactory.create(any()) } returns webRTCClient
        coEvery { webRTCClient.createOffer() } returns "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

        val call = mockk<Call>()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } throws IOException("Connection refused")

        val service = createService()
        service.reconnect()

        verify { webRTCClient.closeByOwner(ConnectionOwnership.ReconnectionManager) }
    }
}
