package com.ras.signaling

import android.util.Log
import com.ras.crypto.KeyDerivation
import com.ras.pairing.SignalingClient
import com.ras.pairing.SignalingResult
import com.ras.proto.NtfySignalMessage
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

private const val TAG = "PairingSignaler"

/**
 * Result of a pairing signaling exchange.
 */
sealed class PairingSignalerResult {
    /**
     * Signaling succeeded.
     */
    data class Success(
        val sdpAnswer: String,
        val usedDirectPath: Boolean,
        val usedNtfyPath: Boolean
    ) : PairingSignalerResult()

    /**
     * Direct signaling failed and ntfy timed out.
     */
    object NtfyTimeout : PairingSignalerResult()

    /**
     * Both signaling methods failed.
     */
    data class Error(val message: String) : PairingSignalerResult()
}

/**
 * Orchestrates signaling for pairing: tries direct HTTP first, falls back to ntfy.
 *
 * Flow:
 * 1. Try direct HTTP POST to daemon
 * 2. If timeout (default 3s) or failure: fall back to ntfy relay
 * 3. Subscribe to ntfy topic
 * 4. Publish encrypted OFFER message
 * 5. Wait for encrypted ANSWER message
 * 6. Return SDP answer
 */
class PairingSignaler(
    private val directClient: SignalingClient,
    private val ntfyClient: NtfyClientInterface,
    private val directTimeoutMs: Long = DEFAULT_DIRECT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_DIRECT_TIMEOUT_MS = 3000L
        const val DEFAULT_NTFY_TIMEOUT_MS = 30000L
        const val NONCE_SIZE = 16

        private val secureRandom = SecureRandom()
    }

    /**
     * Exchange SDP offer for answer.
     *
     * @param ip Daemon IP address
     * @param port Daemon port
     * @param sessionId Session ID from QR code
     * @param masterSecret Master secret from QR code
     * @param sdpOffer WebRTC SDP offer
     * @param deviceId This device's ID
     * @param deviceName This device's name
     * @param ntfyTimeoutMs Timeout for ntfy signaling (default 30s)
     */
    suspend fun exchangeSdp(
        ip: String,
        port: Int,
        sessionId: String,
        masterSecret: ByteArray,
        sdpOffer: String,
        deviceId: String,
        deviceName: String,
        ntfyTimeoutMs: Long = DEFAULT_NTFY_TIMEOUT_MS
    ): PairingSignalerResult {
        // Derive auth key for direct signaling
        val authKey = KeyDerivation.deriveKey(masterSecret, "auth")

        // Try direct signaling first
        Log.d(TAG, "Trying direct signaling to $ip:$port")
        val directResult = tryDirectSignaling(
            ip = ip,
            port = port,
            sessionId = sessionId,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        // If direct succeeded, return
        if (directResult is SignalingResult.Success) {
            Log.d(TAG, "Direct signaling succeeded")
            // Zero auth key
            authKey.fill(0)
            return PairingSignalerResult.Success(
                sdpAnswer = directResult.sdpAnswer,
                usedDirectPath = true,
                usedNtfyPath = false
            )
        }

        Log.d(TAG, "Direct signaling failed, falling back to ntfy")

        // Zero auth key - no longer needed
        authKey.fill(0)

        // Fall back to ntfy
        return tryNtfySignaling(
            sessionId = sessionId,
            masterSecret = masterSecret,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName,
            timeoutMs = ntfyTimeoutMs
        )
    }

    /**
     * Try direct HTTP signaling with timeout.
     */
    private suspend fun tryDirectSignaling(
        ip: String,
        port: Int,
        sessionId: String,
        authKey: ByteArray,
        sdpOffer: String,
        deviceId: String,
        deviceName: String
    ): SignalingResult {
        return try {
            withTimeoutOrNull(directTimeoutMs) {
                directClient.sendSignal(
                    ip = ip,
                    port = port,
                    sessionId = sessionId,
                    authKey = authKey,
                    sdpOffer = sdpOffer,
                    deviceId = deviceId,
                    deviceName = deviceName
                )
            } ?: SignalingResult.Error(com.ras.proto.SignalError.ErrorCode.UNKNOWN)
        } catch (e: CancellationException) {
            throw e  // Don't catch cancellation
        } catch (e: Exception) {
            SignalingResult.Error(com.ras.proto.SignalError.ErrorCode.UNKNOWN)
        }
    }

    /**
     * Try ntfy signaling relay.
     */
    private suspend fun tryNtfySignaling(
        sessionId: String,
        masterSecret: ByteArray,
        sdpOffer: String,
        deviceId: String,
        deviceName: String,
        timeoutMs: Long
    ): PairingSignalerResult {
        // Derive signaling key
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        // Create validator for incoming messages
        val validator = NtfySignalMessageValidator(
            pendingSessionId = sessionId,
            expectedType = NtfySignalMessage.MessageType.ANSWER
        )

        // Compute topic
        val topic = NtfySignalingClient.computeTopic(masterSecret)
        Log.d(TAG, "Ntfy topic: $topic")

        try {
            return withTimeout(timeoutMs) {
                Log.d(TAG, "Creating encrypted offer message")
                // Create and encrypt OFFER message
                val offerMsg = NtfySignalMessage.newBuilder()
                    .setType(NtfySignalMessage.MessageType.OFFER)
                    .setSessionId(sessionId)
                    .setSdp(sdpOffer)
                    .setDeviceId(deviceId)
                    .setDeviceName(deviceName)
                    .setTimestamp(System.currentTimeMillis() / 1000)
                    .setNonce(ByteString.copyFrom(generateNonce()))
                    .build()

                val encryptedOffer = crypto.encryptToBase64(offerMsg.toByteArray())
                Log.d(TAG, "Offer encrypted, subscribing to ntfy topic")

                // Subscribe to topic
                val messageFlow = ntfyClient.subscribe(topic)
                Log.d(TAG, "Subscribed to ntfy, publishing offer")

                // Publish offer
                ntfyClient.publish(topic, encryptedOffer)
                Log.d(TAG, "Offer published, waiting for answer")

                // Wait for valid answer
                messageFlow.collect { ntfyMessage ->
                    if (ntfyMessage.event != "message" || ntfyMessage.message.isEmpty()) {
                        return@collect
                    }

                    // Try to decrypt and validate
                    val answer = processNtfyMessage(ntfyMessage.message, crypto, validator)
                    if (answer != null) {
                        // Cancel the flow collection
                        throw AnswerReceivedException(answer)
                    }
                }

                // If we get here without an answer, timeout
                PairingSignalerResult.NtfyTimeout
            }
        } catch (e: AnswerReceivedException) {
            Log.d(TAG, "Received answer via ntfy")
            // Clean up
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()

            return PairingSignalerResult.Success(
                sdpAnswer = e.sdpAnswer,
                usedDirectPath = false,
                usedNtfyPath = true
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Ntfy signaling timed out")
            // Clean up
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()

            return PairingSignalerResult.NtfyTimeout
        } catch (e: CancellationException) {
            Log.d(TAG, "Ntfy signaling cancelled")
            // Clean up and rethrow
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ntfy signaling failed: ${e.message}", e)
            // Clean up
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()

            return PairingSignalerResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Process an ntfy message and return SDP answer if valid.
     */
    private fun processNtfyMessage(
        encryptedMessage: String,
        crypto: NtfySignalingCrypto,
        validator: NtfySignalMessageValidator
    ): String? {
        return try {
            // Decrypt
            val decrypted = crypto.decryptFromBase64(encryptedMessage)

            // Parse protobuf
            val msg = NtfySignalMessage.parseFrom(decrypted)

            // Validate
            val result = validator.validate(msg)
            if (!result.isValid) {
                return null
            }

            // Return SDP
            msg.sdp
        } catch (e: Exception) {
            // Silent ignore - could be wrong key, tampered, etc.
            null
        }
    }

    private fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        secureRandom.nextBytes(nonce)
        return nonce
    }
}

/**
 * Internal exception used to break out of flow collection when answer is received.
 */
private class AnswerReceivedException(val sdpAnswer: String) : Exception()
