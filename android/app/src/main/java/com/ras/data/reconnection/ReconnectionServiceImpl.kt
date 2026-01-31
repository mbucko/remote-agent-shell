package com.ras.data.reconnection

import android.content.Context
import android.util.Log
import com.ras.crypto.HmacUtils
import com.ras.crypto.KeyDerivation
import com.ras.crypto.toHex
import com.ras.data.connection.ConnectionContext
import com.ras.data.connection.ConnectionManager
import com.ras.data.connection.ConnectionOrchestrator
import com.ras.data.connection.ConnectionProgress
import com.ras.data.connection.NtfySignalingChannel
import com.ras.data.connection.Transport
import com.ras.data.credentials.CredentialRepository
import com.ras.domain.startup.ReconnectionResult
import com.ras.pairing.AuthClient
import com.ras.pairing.AuthResult
import com.ras.proto.AuthError
import com.ras.proto.SignalRequest
import com.ras.proto.SignalResponse
import com.ras.di.DirectSignalingClient
import com.ras.signaling.AuthenticationException
import com.ras.signaling.DeviceNotFoundException
import com.ras.signaling.DirectReconnectionClient
import com.ras.signaling.NtfyClientInterface
import com.ras.signaling.ReconnectionSignaler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ReconnectionService using ConnectionOrchestrator.
 *
 * Orchestrates connection attempts using multiple strategies:
 * 1. TailscaleStrategy - Direct VPN connection (if both on Tailscale)
 * 2. WebRTCStrategy - Standard P2P via ICE/STUN
 *
 * After transport is established, performs authentication handshake
 * and hands off to ConnectionManager.
 */
@Singleton
class ReconnectionServiceImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val credentialRepository: CredentialRepository,
    private val httpClient: OkHttpClient,
    @DirectSignalingClient private val directSignalingHttpClient: OkHttpClient,
    private val connectionManager: ConnectionManager,
    private val ntfyClient: NtfyClientInterface,
    private val orchestrator: ConnectionOrchestrator
) : ReconnectionService {

    companion object {
        private const val TAG = "ReconnectionService"
    }

    override suspend fun reconnect(onProgress: (ConnectionProgress) -> Unit): ReconnectionResult {
        // 1. Get stored credentials
        val credentials = try {
            credentialRepository.getCredentials()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get credentials", e)
            return ReconnectionResult.Failure.Unknown(e.message ?: "Credential error")
        }
        if (credentials == null) {
            Log.d(TAG, "No stored credentials")
            return ReconnectionResult.Failure.NoCredentials
        }

        Log.d(TAG, "Attempting reconnection to ${credentials.daemonHost}:${credentials.daemonPort}")

        // Derive auth key from master secret
        val authKey = KeyDerivation.deriveKey(credentials.masterSecret, "auth")

        try {
            // 2. Create signaling channel (use short-timeout client for direct probing)
            val directClient = HttpDirectReconnectionClient(directSignalingHttpClient)
            val signaler = ReconnectionSignaler(
                directClient = directClient,
                ntfyClient = ntfyClient
            )

            val signalingChannel = NtfySignalingChannel(
                signaler = signaler,
                host = credentials.daemonHost,
                port = credentials.daemonPort,
                masterSecret = credentials.masterSecret,
                deviceId = credentials.deviceId,
                deviceName = android.os.Build.MODEL ?: "Unknown Device"
            )

            // 3. Create connection context
            val context = ConnectionContext(
                androidContext = appContext,
                deviceId = credentials.deviceId,
                daemonHost = credentials.daemonHost,
                daemonPort = credentials.daemonPort,
                daemonTailscaleIp = credentials.daemonTailscaleIp,
                daemonTailscalePort = credentials.daemonTailscalePort,
                signaling = signalingChannel,
                authToken = authKey,
                signalingProgress = onProgress  // Pass progress for detailed signaling events
            )

            // 4. Connect using orchestrator
            val transport = orchestrator.connect(context, onProgress)

            if (transport == null) {
                Log.e(TAG, "All connection strategies failed")
                cleanup(authKey)
                return ReconnectionResult.Failure.NetworkError
            }

            // 5. Perform authentication handshake (only for WebRTC)
            // TailscaleStrategy already does auth during connect(), so skip for Tailscale
            val isTailscale = transport::class.simpleName?.contains("Tailscale") == true

            if (!isTailscale) {
                onProgress(ConnectionProgress.Authenticating())
                val authResult = runAuthentication(transport, authKey)

                when (authResult) {
                    is AuthResult.Success -> {
                        Log.i(TAG, "Authentication successful")
                        onProgress(ConnectionProgress.Authenticated)
                    }
                    is AuthResult.Error -> {
                        Log.e(TAG, "Authentication failed: ${authResult.code}")
                        onProgress(ConnectionProgress.AuthenticationFailed("Error: ${authResult.code}"))
                        transport.close()
                        cleanup(authKey)
                        return ReconnectionResult.Failure.AuthenticationFailed
                    }
                    AuthResult.Timeout -> {
                        Log.e(TAG, "Authentication timed out")
                        onProgress(ConnectionProgress.AuthenticationFailed("Timeout"))
                        transport.close()
                        cleanup(authKey)
                        return ReconnectionResult.Failure.AuthenticationFailed
                    }
                }
            } else {
                Log.i(TAG, "Tailscale auth already completed during connect")
                onProgress(ConnectionProgress.Authenticated)
            }

            // 6. Hand off to ConnectionManager
            Log.i(TAG, "Handing off to ConnectionManager")
            connectionManager.connectWithTransport(transport, authKey)

            // Clear auth key after handoff
            authKey.fill(0)
            return ReconnectionResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection failed: ${e.message}", e)
            cleanup(authKey)
            return ReconnectionResult.Failure.Unknown(e.message ?: "Unknown error")
        }
    }

    private suspend fun runAuthentication(
        transport: Transport,
        authKey: ByteArray
    ): AuthResult {
        return try {
            val authClient = AuthClient(authKey)
            authClient.runHandshake(
                sendMessage = { data -> transport.send(data) },
                receiveMessage = { transport.receive() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Auth handshake error: ${e.message}")
            AuthResult.Error(AuthError.ErrorCode.PROTOCOL_ERROR)
        }
    }

    private fun cleanup(authKey: ByteArray) {
        authKey.fill(0)
    }
}

/**
 * HTTP implementation of DirectReconnectionClient.
 * Used for direct HTTP signaling before falling back to ntfy.
 */
internal class HttpDirectReconnectionClient(
    private val httpClient: OkHttpClient
) : DirectReconnectionClient {

    companion object {
        private const val TAG = "HttpDirectReconnect"
        private val PROTOBUF_MEDIA_TYPE = "application/x-protobuf".toMediaType()
    }

    override suspend fun exchangeCapabilities(
        host: String,
        port: Int,
        deviceId: String,
        authKey: ByteArray,
        ourCapabilities: com.ras.proto.ConnectionCapabilities
    ): com.ras.proto.ConnectionCapabilities? {
        try {
            val body = ourCapabilities.toByteArray()

            // Compute HMAC signature
            val timestamp = System.currentTimeMillis() / 1000
            val signature = HmacUtils.computeSignalingHmac(
                authKey,
                deviceId,
                timestamp,
                body
            )

            val request = Request.Builder()
                .url("http://$host:$port/capabilities/$deviceId")
                .post(body.toRequestBody(PROTOBUF_MEDIA_TYPE))
                .header("X-RAS-Signature", signature.toHex())
                .header("X-RAS-Timestamp", timestamp.toString())
                .build()

            val response = httpClient.newCall(request).await()

            return when {
                response.isSuccessful -> {
                    val responseBody = response.body?.bytes()
                    if (responseBody == null || responseBody.isEmpty()) {
                        Log.w(TAG, "Empty capabilities response")
                        null
                    } else {
                        try {
                            com.ras.proto.ConnectionCapabilities.parseFrom(responseBody)
                        } catch (e: Exception) {
                            Log.w(TAG, "Invalid capabilities response format")
                            null
                        }
                    }
                }
                response.code == 401 -> throw AuthenticationException()
                response.code == 404 -> throw DeviceNotFoundException()
                else -> {
                    Log.w(TAG, "Capabilities exchange failed: ${response.code}")
                    null
                }
            }
        } catch (e: DeviceNotFoundException) {
            throw e
        } catch (e: AuthenticationException) {
            throw e
        } catch (e: IOException) {
            Log.d(TAG, "Network error during capabilities: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Capabilities exchange error: ${e.message}")
            return null
        }
    }

    override suspend fun sendReconnect(
        host: String,
        port: Int,
        deviceId: String,
        authKey: ByteArray,
        sdpOffer: String,
        deviceName: String
    ): String? {
        try {
            // Build protobuf request
            val signalRequest = SignalRequest.newBuilder()
                .setSdpOffer(sdpOffer)
                .setDeviceId(deviceId)
                .setDeviceName(deviceName)
                .build()

            val body = signalRequest.toByteArray()

            // Compute HMAC signature
            val timestamp = System.currentTimeMillis() / 1000
            val signature = HmacUtils.computeSignalingHmac(
                authKey,
                deviceId,
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

            val response = httpClient.newCall(request).await()

            return when {
                response.isSuccessful -> {
                    val responseBody = response.body?.bytes()
                    if (responseBody == null || responseBody.isEmpty()) {
                        Log.w(TAG, "Empty response from daemon")
                        null
                    } else {
                        val signalResponse = try {
                            SignalResponse.parseFrom(responseBody)
                        } catch (e: Exception) {
                            Log.w(TAG, "Invalid response format")
                            return null
                        }

                        if (signalResponse.sdpAnswer.isBlank()) {
                            Log.w(TAG, "Empty SDP answer")
                            null
                        } else {
                            signalResponse.sdpAnswer
                        }
                    }
                }
                response.code == 401 -> {
                    throw AuthenticationException()
                }
                response.code == 404 -> {
                    throw DeviceNotFoundException()
                }
                else -> {
                    Log.w(TAG, "Unexpected response code: ${response.code}")
                    null
                }
            }
        } catch (e: DeviceNotFoundException) {
            throw e
        } catch (e: AuthenticationException) {
            throw e
        } catch (e: IOException) {
            Log.d(TAG, "Network error: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error: ${e.message}")
            return null
        }
    }
}

/**
 * Suspending extension for OkHttp Call that properly supports cancellation.
 * Unlike execute() which blocks, this suspends and can be cancelled by coroutine timeout.
 */
private suspend fun okhttp3.Call.await(): okhttp3.Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        cancel()  // Cancel the HTTP call when coroutine is cancelled
    }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            if (cont.isActive) {
                cont.resume(response)
            }
        }
    })
}
