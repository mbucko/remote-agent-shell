package com.ras.pairing

import com.google.protobuf.ByteString
import com.ras.crypto.CryptoRandom
import com.ras.crypto.HmacUtils
import com.ras.proto.AuthEnvelope
import com.ras.proto.AuthError
import com.ras.proto.AuthResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed class AuthResult {
    data class Success(val deviceId: String) : AuthResult()
    data class Error(val code: AuthError.ErrorCode) : AuthResult()
    object Timeout : AuthResult()
}

class AuthClient(
    private val authKey: ByteArray
) {
    companion object {
        private const val NONCE_SIZE = 32
        private const val TIMEOUT_MS = 10_000L
    }

    /**
     * Run client-side authentication handshake.
     *
     * @param sendMessage Function to send bytes over data channel
     * @param receiveMessage Function to receive bytes from data channel
     * @return Authentication result
     */
    suspend fun runHandshake(
        sendMessage: suspend (ByteArray) -> Unit,
        receiveMessage: suspend () -> ByteArray
    ): AuthResult {
        return try {
            withTimeout(TIMEOUT_MS) {
                doHandshake(sendMessage, receiveMessage)
            }
        } catch (e: TimeoutCancellationException) {
            AuthResult.Timeout
        } catch (e: Exception) {
            AuthResult.Error(AuthError.ErrorCode.PROTOCOL_ERROR)
        }
    }

    private suspend fun doHandshake(
        sendMessage: suspend (ByteArray) -> Unit,
        receiveMessage: suspend () -> ByteArray
    ): AuthResult {
        // Step 1: Wait for challenge
        val challengeBytes = receiveMessage()
        val challengeEnvelope = AuthEnvelope.parseFrom(challengeBytes)

        if (!challengeEnvelope.hasChallenge()) {
            return AuthResult.Error(AuthError.ErrorCode.PROTOCOL_ERROR)
        }

        val challenge = challengeEnvelope.challenge
        val serverNonce = challenge.nonce.toByteArray()

        // Validate nonce length
        if (serverNonce.size != NONCE_SIZE) {
            return AuthResult.Error(AuthError.ErrorCode.INVALID_NONCE)
        }

        // Step 2: Send response
        val clientHmac = HmacUtils.computeHmac(authKey, serverNonce)
        val clientNonce = CryptoRandom.nextBytes(NONCE_SIZE)

        val response = AuthEnvelope.newBuilder()
            .setResponse(
                AuthResponse.newBuilder()
                    .setHmac(ByteString.copyFrom(clientHmac))
                    .setNonce(ByteString.copyFrom(clientNonce))
            )
            .build()

        sendMessage(response.toByteArray())

        // Step 3: Wait for verify
        val verifyBytes = receiveMessage()
        val verifyEnvelope = AuthEnvelope.parseFrom(verifyBytes)

        // Could be error or verify
        if (verifyEnvelope.hasError()) {
            return AuthResult.Error(verifyEnvelope.error.code)
        }

        if (!verifyEnvelope.hasVerify()) {
            return AuthResult.Error(AuthError.ErrorCode.PROTOCOL_ERROR)
        }

        val verify = verifyEnvelope.verify
        val serverHmac = verify.hmac.toByteArray()

        // Verify server HMAC (constant-time)
        if (!HmacUtils.verifyHmac(authKey, clientNonce, serverHmac)) {
            // Server doesn't know the secret!
            return AuthResult.Error(AuthError.ErrorCode.INVALID_HMAC)
        }

        // Step 4: Wait for success
        val successBytes = receiveMessage()
        val successEnvelope = AuthEnvelope.parseFrom(successBytes)

        if (!successEnvelope.hasSuccess()) {
            return AuthResult.Error(AuthError.ErrorCode.PROTOCOL_ERROR)
        }

        return AuthResult.Success(successEnvelope.success.deviceId)
    }
}
