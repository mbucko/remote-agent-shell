package com.ras.signaling

import android.util.Log
import com.google.protobuf.ByteString
import com.ras.crypto.HmacUtils
import com.ras.crypto.KeyDerivation
import com.ras.data.model.DeviceType
import com.ras.proto.NtfySignalMessage
import com.ras.proto.PairRequest
import com.ras.proto.PairResponse
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

sealed class PairExchangeResult {
    data class Success(
        val daemonDeviceId: String,
        val hostname: String,
        val deviceType: DeviceType
    ) : PairExchangeResult()

    object Timeout : PairExchangeResult()
    object AuthFailed : PairExchangeResult()
    data class Error(val message: String) : PairExchangeResult()
}

/**
 * Exchanges pairing credentials via ntfy.
 *
 * Sends PAIR_REQUEST and waits for PAIR_RESPONSE.
 * No WebRTC connection is created during pairing.
 */
class PairExchanger(
    private val ntfyClient: NtfyClientInterface,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "PairExchanger"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private val secureRandom = SecureRandom()
    }

    /**
     * Exchange pairing credentials with the daemon.
     *
     * @param masterSecret 32-byte master secret from QR code
     * @param sessionId Session ID from QR code
     * @param deviceId Phone's device ID
     * @param deviceName Phone's device name
     * @param ntfyTopic Ntfy topic for signaling
     * @return Result of the exchange
     */
    suspend fun exchange(
        masterSecret: ByteArray,
        sessionId: String,
        deviceId: String,
        deviceName: String,
        ntfyTopic: String
    ): PairExchangeResult {
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
        val authKey = KeyDerivation.deriveKey(masterSecret, "auth")
        val crypto = NtfySignalingCrypto(signalingKey)

        try {
            // Generate nonce for this pairing request
            val nonce = ByteArray(HmacUtils.PAIR_NONCE_LENGTH)
            secureRandom.nextBytes(nonce)

            // Subscribe to ntfy topic
            val messageFlow = try {
                ntfyClient.subscribe(ntfyTopic)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe to ntfy: ${e.message}")
                return PairExchangeResult.Error("Failed to subscribe: ${e.message}")
            }

            // Build and send PAIR_REQUEST
            val authProof = HmacUtils.computePairRequestHmac(authKey, sessionId, deviceId, nonce)

            val pairRequest = PairRequest.newBuilder()
                .setDeviceId(deviceId)
                .setDeviceName(deviceName)
                .setAuthProof(ByteString.copyFrom(authProof))
                .setNonce(ByteString.copyFrom(nonce))
                .setSessionId(sessionId)
                .build()

            val signalMessage = NtfySignalMessage.newBuilder()
                .setType(NtfySignalMessage.MessageType.PAIR_REQUEST)
                .setSessionId(sessionId)
                .setTimestamp(System.currentTimeMillis() / 1000)
                .setNonce(ByteString.copyFrom(ByteArray(16).also { secureRandom.nextBytes(it) }))
                .setPairRequest(pairRequest)
                .build()

            val encrypted = crypto.encryptToBase64(signalMessage.toByteArray())

            try {
                ntfyClient.publishWithRetry(ntfyTopic, encrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish PAIR_REQUEST: ${e.message}")
                ntfyClient.unsubscribe()
                return PairExchangeResult.Error("Failed to publish: ${e.message}")
            }

            Log.d(TAG, "PAIR_REQUEST sent, waiting for PAIR_RESPONSE...")

            // Wait for PAIR_RESPONSE
            val validator = NtfySignalMessageValidator(
                pendingSessionId = sessionId,
                expectedType = NtfySignalMessage.MessageType.PAIR_RESPONSE
            )

            val result = try {
                withTimeout(timeoutMs) {
                    messageFlow.firstOrNull { ntfyMsg ->
                        if (ntfyMsg.event != "message") return@firstOrNull false

                        val response = try {
                            val decrypted = crypto.decryptFromBase64(ntfyMsg.message)
                            NtfySignalMessage.parseFrom(decrypted)
                        } catch (e: Exception) {
                            Log.d(TAG, "Ignoring undecryptable message")
                            return@firstOrNull false
                        }

                        val validation = validator.validate(response)
                        if (!validation.isValid) {
                            Log.d(TAG, "Ignoring invalid message: ${validation.error}")
                            return@firstOrNull false
                        }

                        true
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "Timed out waiting for PAIR_RESPONSE")
                ntfyClient.unsubscribe()
                return PairExchangeResult.Timeout
            }

            ntfyClient.unsubscribe()

            if (result == null) {
                return PairExchangeResult.Error("No valid response received")
            }

            // Decrypt and parse the response again to extract PairResponse
            val decrypted = crypto.decryptFromBase64(result.message)
            val responseMsg = NtfySignalMessage.parseFrom(decrypted)
            val pairResponse = responseMsg.pairResponse

            // Validate daemon's auth_proof
            val expectedHmac = HmacUtils.computePairResponseHmac(authKey, nonce)
            if (!HmacUtils.constantTimeEquals(pairResponse.authProof.toByteArray(), expectedHmac)) {
                Log.e(TAG, "PAIR_RESPONSE auth_proof HMAC mismatch")
                return PairExchangeResult.AuthFailed
            }

            if (pairResponse.daemonDeviceId.isEmpty()) {
                Log.e(TAG, "PAIR_RESPONSE has empty daemon_device_id")
                return PairExchangeResult.Error("Empty daemon device ID")
            }

            Log.i(TAG, "Pairing exchange successful: device=${pairResponse.daemonDeviceId}")

            return PairExchangeResult.Success(
                daemonDeviceId = pairResponse.daemonDeviceId,
                hostname = pairResponse.hostname,
                deviceType = DeviceType.fromProto(pairResponse.deviceType)
            )
        } finally {
            crypto.zeroKey()
            authKey.fill(0)
        }
    }
}
