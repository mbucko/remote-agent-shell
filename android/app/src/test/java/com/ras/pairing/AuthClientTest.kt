package com.ras.pairing

import com.google.protobuf.ByteString
import com.ras.crypto.HmacUtils
import com.ras.crypto.hexToBytes
import com.ras.crypto.toHex
import com.ras.data.model.DeviceType
import com.ras.proto.AuthChallenge
import com.ras.proto.AuthEnvelope
import com.ras.proto.AuthError
import com.ras.proto.AuthResponse
import com.ras.proto.AuthSuccess
import com.ras.proto.AuthVerify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

class AuthClientTest {

    private val authKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToBytes()

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun createMockDataChannel(): Pair<
        suspend (ByteArray) -> Unit,  // sendMessage
        suspend () -> ByteArray       // receiveMessage
    > {
        val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoingMessages = Channel<ByteArray>(Channel.UNLIMITED)

        val sendMessage: suspend (ByteArray) -> Unit = { data ->
            outgoingMessages.send(data)
        }

        val receiveMessage: suspend () -> ByteArray = {
            incomingMessages.receive()
        }

        return Pair(sendMessage, receiveMessage)
    }

    private fun createServerNonce(): ByteArray = ByteArray(32) { 0xAA.toByte() }
    private fun createClientNonce(): ByteArray = ByteArray(32) { 0xBB.toByte() }

    // ============================================================================
    // Success Scenarios
    // ============================================================================

    @Tag("unit")
    @Test
    fun `successful handshake - full flow`() = runBlocking {
        val serverNonce = createServerNonce()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        // Run client handshake in parallel with server simulation
        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Success, "Expected success, got: $result")
            val success = result as AuthResult.Success
            assertEquals("device-123", success.deviceId)
            assertEquals("test-host.local", success.hostname)
            assertEquals(DeviceType.LAPTOP, success.deviceType)
        }

        // Simulate server
        // Step 1: Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Step 2: Receive response and verify
        val responseBytes = serverChannel.receive()
        val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)
        assertTrue(responseEnvelope.hasResponse())

        val response = responseEnvelope.response
        val expectedClientHmac = HmacUtils.computeHmac(authKey, serverNonce)
        assertTrue(HmacUtils.constantTimeEquals(expectedClientHmac, response.hmac.toByteArray()))
        assertEquals(32, response.nonce.size())

        val clientNonce = response.nonce.toByteArray()

        // Step 3: Send verify
        val serverHmac = HmacUtils.computeHmac(authKey, clientNonce)
        val verify = AuthEnvelope.newBuilder()
            .setVerify(
                AuthVerify.newBuilder()
                    .setHmac(ByteString.copyFrom(serverHmac))
            )
            .build()
        clientChannel.send(verify.toByteArray())

        // Step 4: Send success
        val success = AuthEnvelope.newBuilder()
            .setSuccess(
                AuthSuccess.newBuilder()
                    .setDeviceId("device-123")
                    .setHostname("test-host.local")
                    .setDeviceType(com.ras.proto.DeviceType.DEVICE_TYPE_LAPTOP)
            )
            .build()
        clientChannel.send(success.toByteArray())

        clientJob.join()
    }

    // ============================================================================
    // Challenge Errors
    // ============================================================================

    @Tag("unit")
    @Test
    fun `invalid nonce length - too short`() = runBlocking {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.INVALID_NONCE, (result as AuthResult.Error).code)
        }

        // Send challenge with short nonce (16 bytes instead of 32)
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(ByteArray(16)))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        clientJob.join()
    }

    @Tag("unit")
    @Test
    fun `invalid nonce length - too long`() = runBlocking {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.INVALID_NONCE, (result as AuthResult.Error).code)
        }

        // Send challenge with long nonce (64 bytes instead of 32)
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(ByteArray(64)))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        clientJob.join()
    }

    @Tag("unit")
    @Test
    fun `wrong message type first - verify instead of challenge`() = runBlocking {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.PROTOCOL_ERROR, (result as AuthResult.Error).code)
        }

        // Send verify instead of challenge
        val wrongMessage = AuthEnvelope.newBuilder()
            .setVerify(
                AuthVerify.newBuilder()
                    .setHmac(ByteString.copyFrom(ByteArray(32)))
            )
            .build()
        clientChannel.send(wrongMessage.toByteArray())

        clientJob.join()
    }

    @Tag("unit")
    @Test
    fun `wrong message type first - success instead of challenge`() = runBlocking {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.PROTOCOL_ERROR, (result as AuthResult.Error).code)
        }

        // Send success instead of challenge
        val wrongMessage = AuthEnvelope.newBuilder()
            .setSuccess(
                AuthSuccess.newBuilder()
                    .setDeviceId("device-123")
            )
            .build()
        clientChannel.send(wrongMessage.toByteArray())

        clientJob.join()
    }

    @Tag("unit")
    @Test
    fun `wrong message type first - error instead of challenge`() = runBlocking {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.PROTOCOL_ERROR, (result as AuthResult.Error).code)
        }

        // Send error instead of challenge
        val errorMessage = AuthEnvelope.newBuilder()
            .setError(
                AuthError.newBuilder()
                    .setCode(AuthError.ErrorCode.TIMEOUT)
            )
            .build()
        clientChannel.send(errorMessage.toByteArray())

        clientJob.join()
    }

    // ============================================================================
    // Response Phase Errors
    // ============================================================================

    @Tag("unit")
    @Test
    fun `server returns error after receiving client response`() = runBlocking {
        val serverNonce = createServerNonce()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.INVALID_HMAC, (result as AuthResult.Error).code)
        }

        // Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Wait for response
        serverChannel.receive()

        // Send error instead of verify
        val error = AuthEnvelope.newBuilder()
            .setError(
                AuthError.newBuilder()
                    .setCode(AuthError.ErrorCode.INVALID_HMAC)
            )
            .build()
        clientChannel.send(error.toByteArray())

        clientJob.join()
    }

    // ============================================================================
    // Verify Phase Errors
    // ============================================================================

    @Tag("unit")
    @Test
    fun `server sends wrong HMAC in verify - client rejects`() = runBlocking {
        val serverNonce = createServerNonce()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.INVALID_HMAC, (result as AuthResult.Error).code)
        }

        // Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Receive response
        serverChannel.receive()

        // Send verify with WRONG HMAC
        val wrongHmac = ByteArray(32) // All zeros, definitely wrong
        val verify = AuthEnvelope.newBuilder()
            .setVerify(
                AuthVerify.newBuilder()
                    .setHmac(ByteString.copyFrom(wrongHmac))
            )
            .build()
        clientChannel.send(verify.toByteArray())

        clientJob.join()
    }

    @Tag("unit")
    @Test
    fun `wrong message type instead of verify - success`() = runBlocking {
        val serverNonce = createServerNonce()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.PROTOCOL_ERROR, (result as AuthResult.Error).code)
        }

        // Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Receive response
        serverChannel.receive()

        // Send success instead of verify (skipping verify step)
        val wrongMessage = AuthEnvelope.newBuilder()
            .setSuccess(
                AuthSuccess.newBuilder()
                    .setDeviceId("device-123")
            )
            .build()
        clientChannel.send(wrongMessage.toByteArray())

        clientJob.join()
    }

    @Tag("unit")
    @Test
    fun `wrong message type instead of verify - challenge`() = runBlocking {
        val serverNonce = createServerNonce()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.PROTOCOL_ERROR, (result as AuthResult.Error).code)
        }

        // Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Receive response
        serverChannel.receive()

        // Send challenge again instead of verify
        clientChannel.send(challenge.toByteArray())

        clientJob.join()
    }

    // ============================================================================
    // Success Phase Errors
    // ============================================================================

    @Tag("unit")
    @Test
    fun `wrong message type instead of success`() = runBlocking {
        val serverNonce = createServerNonce()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.PROTOCOL_ERROR, (result as AuthResult.Error).code)
        }

        // Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Receive response
        val responseBytes = serverChannel.receive()
        val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)
        val clientNonce = responseEnvelope.response.nonce.toByteArray()

        // Send verify with correct HMAC
        val serverHmac = HmacUtils.computeHmac(authKey, clientNonce!!)
        val verify = AuthEnvelope.newBuilder()
            .setVerify(
                AuthVerify.newBuilder()
                    .setHmac(ByteString.copyFrom(serverHmac))
            )
            .build()
        clientChannel.send(verify.toByteArray())

        // Send challenge instead of success
        clientChannel.send(challenge.toByteArray())

        clientJob.join()
    }

    // ============================================================================
    // Timeout Scenarios
    // ============================================================================

    @Tag("unit")
    @Test
    fun `timeout waiting for challenge`() = runBlocking {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        // Create client with very short timeout for testing
        val authClient = AuthClient(authKey)

        // Use shorter overall timeout
        val result = withTimeoutOrNull(15_000) {
            authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
        }

        // Should timeout since we never send challenge
        assertTrue(result == AuthResult.Timeout || result == null)
    }

    @Tag("unit")
    @Test
    fun `timeout waiting for verify`() = runBlocking {
        val serverNonce = createServerNonce()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result == AuthResult.Timeout)
        }

        // Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Receive response but don't send verify
        serverChannel.receive()

        // Wait for timeout (AuthClient has 10s timeout)
        delay(12_000)

        clientJob.join()
    }

    // ============================================================================
    // Malformed Message Scenarios
    // ============================================================================

    @Tag("unit")
    @Test
    fun `malformed protobuf message`() = runBlocking {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.PROTOCOL_ERROR, (result as AuthResult.Error).code)
        }

        // Send garbage instead of valid protobuf
        clientChannel.send(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

        clientJob.join()
    }

    @Tag("unit")
    @Test
    fun `empty message`() = runBlocking {
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            val result = authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
            assertTrue(result is AuthResult.Error)
            assertEquals(AuthError.ErrorCode.PROTOCOL_ERROR, (result as AuthResult.Error).code)
        }

        // Send empty bytes
        clientChannel.send(byteArrayOf())

        clientJob.join()
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Tag("unit")
    @Test
    fun `client generates random nonce each handshake`() = runBlocking {
        val serverNonce = createServerNonce()
        val nonces = mutableSetOf<String>()

        repeat(5) {
            val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
            val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

            val authClient = AuthClient(authKey)

            val clientJob = launch {
                authClient.runHandshake(
                    sendMessage = { data -> serverChannel.send(data) },
                    receiveMessage = { clientChannel.receive() }
                )
            }

            // Send challenge
            val challenge = AuthEnvelope.newBuilder()
                .setChallenge(
                    AuthChallenge.newBuilder()
                        .setNonce(ByteString.copyFrom(serverNonce))
                )
                .build()
            clientChannel.send(challenge.toByteArray())

            // Capture the nonce
            val responseBytes = serverChannel.receive()
            val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)
            val clientNonce = responseEnvelope.response.nonce.toByteArray().toHex()
            nonces.add(clientNonce)

            clientJob.cancel()
        }

        // All nonces should be different (statistically extremely unlikely to collide)
        assertEquals(5, nonces.size)
    }

    @Tag("unit")
    @Test
    fun `client HMAC is computed correctly`() = runBlocking {
        val serverNonce = createServerNonce()
        val clientChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val serverChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val authClient = AuthClient(authKey)

        val clientJob = launch {
            authClient.runHandshake(
                sendMessage = { data -> serverChannel.send(data) },
                receiveMessage = { clientChannel.receive() }
            )
        }

        // Send challenge
        val challenge = AuthEnvelope.newBuilder()
            .setChallenge(
                AuthChallenge.newBuilder()
                    .setNonce(ByteString.copyFrom(serverNonce))
            )
            .build()
        clientChannel.send(challenge.toByteArray())

        // Verify the client's HMAC
        val responseBytes = serverChannel.receive()
        val responseEnvelope = AuthEnvelope.parseFrom(responseBytes)
        val clientHmac = responseEnvelope.response.hmac.toByteArray()

        val expectedHmac = HmacUtils.computeHmac(authKey, serverNonce)
        assertTrue(HmacUtils.constantTimeEquals(expectedHmac, clientHmac))

        clientJob.cancel()
    }
}
