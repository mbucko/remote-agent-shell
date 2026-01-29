package com.ras.data.reconnection

import android.util.Log
import com.ras.crypto.HmacUtils
import com.ras.crypto.KeyDerivation
import com.ras.crypto.toHex
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.webrtc.ConnectionOwnership
import com.ras.data.webrtc.WebRTCClient
import com.ras.domain.startup.ReconnectionResult
import com.ras.pairing.AuthClient
import com.ras.pairing.AuthResult
import com.ras.proto.SignalRequest
import com.ras.proto.SignalResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ReconnectionService.
 * Handles the full reconnection flow:
 * 1. Get stored credentials
 * 2. Create WebRTC offer
 * 3. Send reconnect request to daemon
 * 4. Establish WebRTC connection
 * 5. Perform authentication handshake
 * 6. Hand off to ConnectionManager
 */
@Singleton
class ReconnectionServiceImpl @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val httpClient: OkHttpClient,
    private val webRTCClientFactory: WebRTCClient.Factory,
    private val connectionManager: ConnectionManager
) : ReconnectionService {

    companion object {
        private const val TAG = "ReconnectionService"
        private val PROTOBUF_MEDIA_TYPE = "application/x-protobuf".toMediaType()
        private const val DATA_CHANNEL_TIMEOUT_MS = 30_000L
    }

    private var webRTCClient: WebRTCClient? = null
    private var authKey: ByteArray? = null

    override suspend fun reconnect(): ReconnectionResult {
        // 1. Get stored credentials
        val credentials = credentialRepository.getCredentials()
        if (credentials == null) {
            Log.d(TAG, "No stored credentials")
            return ReconnectionResult.Failure.NoCredentials
        }

        Log.d(TAG, "Attempting reconnection to ${credentials.daemonHost}:${credentials.daemonPort}")

        // Derive auth key from master secret
        authKey = KeyDerivation.deriveKey(credentials.masterSecret, "auth")

        // 2. Create WebRTC client and offer
        val client: WebRTCClient
        val sdpOffer: String
        try {
            client = webRTCClientFactory.create(ConnectionOwnership.ReconnectionManager)
            webRTCClient = client
            sdpOffer = client.createOffer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebRTC offer: ${e.message}")
            cleanup()
            return ReconnectionResult.Failure.NetworkError
        }

        // 3. Send reconnect request to daemon
        val sdpAnswer = try {
            sendReconnectRequest(
                host = credentials.daemonHost,
                port = credentials.daemonPort,
                deviceId = credentials.deviceId,
                sdpOffer = sdpOffer
            )
        } catch (e: IOException) {
            Log.e(TAG, "Network error during reconnect: ${e.message}")
            cleanup()
            return ReconnectionResult.Failure.DaemonUnreachable
        } catch (e: ReconnectionException) {
            Log.e(TAG, "Reconnection failed: ${e.message}")
            cleanup()
            return e.result
        }

        // 4. Establish WebRTC connection
        try {
            client.setRemoteDescription(sdpAnswer)
            val channelOpened = client.waitForDataChannel(DATA_CHANNEL_TIMEOUT_MS)
            if (!channelOpened) {
                Log.e(TAG, "Data channel failed to open")
                cleanup()
                return ReconnectionResult.Failure.NetworkError
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC connection failed: ${e.message}")
            cleanup()
            return ReconnectionResult.Failure.NetworkError
        }

        // 5. Perform authentication handshake
        val key = authKey
        if (key == null) {
            Log.e(TAG, "Auth key is null")
            cleanup()
            return ReconnectionResult.Failure.AuthenticationFailed
        }

        val authResult = try {
            val authClient = AuthClient(key)
            authClient.runHandshake(
                sendMessage = { data -> client.send(data) },
                receiveMessage = { client.receive() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Auth handshake error: ${e.message}")
            cleanup()
            return ReconnectionResult.Failure.AuthenticationFailed
        }

        when (authResult) {
            is AuthResult.Success -> {
                Log.i(TAG, "Authentication successful, handing off to ConnectionManager")

                // 6. Hand off to ConnectionManager
                try {
                    client.transferOwnership(ConnectionOwnership.ConnectionManager)
                    webRTCClient = null
                    connectionManager.connect(client, key)

                    // Clear auth key after handoff
                    authKey?.fill(0)
                    authKey = null

                    return ReconnectionResult.Success
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to hand off to ConnectionManager: ${e.message}")
                    cleanup()
                    return ReconnectionResult.Failure.NetworkError
                }
            }
            is AuthResult.Error -> {
                Log.e(TAG, "Authentication failed: ${authResult.code}")
                cleanup()
                return ReconnectionResult.Failure.AuthenticationFailed
            }
            AuthResult.Timeout -> {
                Log.e(TAG, "Authentication timed out")
                cleanup()
                return ReconnectionResult.Failure.AuthenticationFailed
            }
        }
    }

    private suspend fun sendReconnectRequest(
        host: String,
        port: Int,
        deviceId: String,
        sdpOffer: String
    ): String = withContext(Dispatchers.IO) {
        val key = authKey ?: throw ReconnectionException(
            "Auth key not available",
            ReconnectionResult.Failure.AuthenticationFailed
        )

        // Build protobuf request
        val signalRequest = SignalRequest.newBuilder()
            .setSdpOffer(sdpOffer)
            .setDeviceId(deviceId)
            .setDeviceName(android.os.Build.MODEL ?: "Unknown Device")
            .build()

        val body = signalRequest.toByteArray()

        // Compute HMAC signature
        val timestamp = System.currentTimeMillis() / 1000
        val signature = HmacUtils.computeSignalingHmac(
            key,
            deviceId,  // For reconnect, we use deviceId instead of sessionId
            timestamp,
            body
        )

        // Build HTTP request
        val request = Request.Builder()
            .url("http://$host:$port/reconnect/$deviceId")
            .post(body.toRequestBody(PROTOBUF_MEDIA_TYPE))
            .header("X-RAS-Signature", signature.toHex())
            .header("X-RAS-Timestamp", timestamp.toString())
            .build()

        val response = httpClient.newCall(request).execute()

        when {
            response.isSuccessful -> {
                val responseBody = response.body?.bytes()
                if (responseBody == null || responseBody.isEmpty()) {
                    throw ReconnectionException(
                        "Empty response from daemon",
                        ReconnectionResult.Failure.DaemonUnreachable
                    )
                }

                val signalResponse = try {
                    SignalResponse.parseFrom(responseBody)
                } catch (e: Exception) {
                    throw ReconnectionException(
                        "Invalid response format",
                        ReconnectionResult.Failure.DaemonUnreachable
                    )
                }

                if (signalResponse.sdpAnswer.isBlank()) {
                    throw ReconnectionException(
                        "Empty SDP answer",
                        ReconnectionResult.Failure.DaemonUnreachable
                    )
                }

                signalResponse.sdpAnswer
            }
            response.code == 401 -> {
                throw ReconnectionException(
                    "Authentication failed (401)",
                    ReconnectionResult.Failure.AuthenticationFailed
                )
            }
            response.code == 404 -> {
                throw ReconnectionException(
                    "Device not found (404)",
                    ReconnectionResult.Failure.DaemonUnreachable
                )
            }
            response.code == 429 -> {
                throw ReconnectionException(
                    "Rate limited (429)",
                    ReconnectionResult.Failure.NetworkError
                )
            }
            else -> {
                throw ReconnectionException(
                    "Unexpected response code: ${response.code}",
                    ReconnectionResult.Failure.DaemonUnreachable
                )
            }
        }
    }

    private fun cleanup() {
        webRTCClient?.closeByOwner(ConnectionOwnership.ReconnectionManager)
        webRTCClient = null
        authKey?.fill(0)
        authKey = null
    }

    private class ReconnectionException(
        message: String,
        val result: ReconnectionResult.Failure
    ) : Exception(message)
}
