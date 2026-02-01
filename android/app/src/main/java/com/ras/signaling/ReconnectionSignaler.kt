package com.ras.signaling

import android.util.Log
import com.ras.crypto.KeyDerivation
import com.ras.data.connection.ConnectionProgress
import com.ras.data.connection.SdpInfo
import com.ras.proto.ConnectionCapabilities
import com.ras.proto.DiscoveryResponse
import com.ras.proto.NtfySignalMessage
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom

private const val TAG = "ReconnectionSignaler"

/**
 * Callback for signaling progress events.
 */
typealias SignalingProgressCallback = (ConnectionProgress) -> Unit

/**
 * Result of a reconnection signaling exchange.
 */
sealed class ReconnectionSignalerResult {
    /**
     * Signaling succeeded.
     */
    data class Success(
        val sdpAnswer: String,
        val usedDirectPath: Boolean,
        val usedNtfyPath: Boolean,
        val capabilities: ConnectionCapabilities? = null
    ) : ReconnectionSignalerResult()

    /**
     * Direct signaling failed and ntfy timed out.
     */
    object NtfyTimeout : ReconnectionSignalerResult()

    /**
     * Device not found on daemon (needs re-pairing).
     */
    object DeviceNotFound : ReconnectionSignalerResult()

    /**
     * Authentication failed (secret mismatch).
     */
    object AuthenticationFailed : ReconnectionSignalerResult()

    /**
     * Both signaling methods failed.
     */
    data class Error(val message: String) : ReconnectionSignalerResult()
}

/**
 * Result of capability exchange.
 */
sealed class CapabilityExchangeResult {
    data class Success(val capabilities: ConnectionCapabilities) : CapabilityExchangeResult()
    object NetworkError : CapabilityExchangeResult()
    object DeviceNotFound : CapabilityExchangeResult()
    object AuthenticationFailed : CapabilityExchangeResult()
}

/**
 * Result of host discovery.
 */
sealed class DiscoveryResult {
    data class Success(val discovery: com.ras.proto.DiscoveryResponse) : DiscoveryResult()
    object NetworkError : DiscoveryResult()
    object DeviceNotFound : DiscoveryResult()
    object AuthenticationFailed : DiscoveryResult()
}

/**
 * Interface for direct reconnection signaling (HTTP).
 *
 * Abstracts the HTTP call for testing.
 */
interface DirectReconnectionClient {
    /**
     * Exchange capabilities with daemon via direct HTTP.
     *
     * @return Daemon's capabilities on success
     * @throws DeviceNotFoundException if device not registered
     * @throws AuthenticationException if auth fails
     */
    suspend fun exchangeCapabilities(
        host: String,
        port: Int,
        deviceId: String,
        authKey: ByteArray,
        ourCapabilities: ConnectionCapabilities
    ): ConnectionCapabilities?

    /**
     * Send reconnection request via direct HTTP.
     *
     * @return SDP answer on success, null on network error
     * @throws DeviceNotFoundException if device not registered
     * @throws AuthenticationException if auth fails
     */
    suspend fun sendReconnect(
        host: String,
        port: Int,
        deviceId: String,
        authKey: ByteArray,
        sdpOffer: String,
        deviceName: String
    ): String?
}

class DeviceNotFoundException : Exception("Device not found")
class AuthenticationException : Exception("Authentication failed")

/**
 * Orchestrates signaling for reconnection: tries direct HTTP first, falls back to ntfy.
 *
 * Flow:
 * 1. Try direct HTTP POST to daemon's /reconnect/{device_id}
 * 2. If timeout (default 3s) or network failure: fall back to ntfy relay
 * 3. Subscribe to ntfy topic (blocks until connected)
 * 4. Publish encrypted OFFER message (with empty session_id, set device_id)
 * 5. Wait for encrypted ANSWER message
 * 6. Return SDP answer
 *
 * Note: For reconnection, session_id is left empty and device_id identifies the device.
 * The daemon distinguishes pairing vs reconnection by checking if session_id is set.
 */
class ReconnectionSignaler(
    private val directClient: DirectReconnectionClient,
    private val ntfyClient: NtfyClientInterface,
    private val directTimeoutMs: Long = DEFAULT_DIRECT_TIMEOUT_MS,
    private val maxNtfyRetries: Int = DEFAULT_MAX_NTFY_RETRIES
) {
    companion object {
        const val DEFAULT_DIRECT_TIMEOUT_MS = 1000L  // LAN should respond in <100ms, 1s is generous
        const val DEFAULT_NTFY_TIMEOUT_MS = 30000L
        const val DEFAULT_CAPABILITY_NTFY_TIMEOUT_MS = 10000L
        const val DEFAULT_MAX_NTFY_RETRIES = 3  // Retry WebSocket failures
        const val NTFY_RETRY_DELAY_MS = 500L  // Initial delay between retries
        const val NONCE_SIZE = 16

        private val secureRandom = SecureRandom()
    }

    /**
     * Exchange capabilities with daemon before trying connection strategies.
     *
     * Uses parallel racing: starts both direct HTTP and ntfy in parallel.
     * - Direct HTTP is fast path for LAN (~50ms if on same network)
     * - ntfy starts warming up immediately (don't wait for direct to timeout)
     * - First successful response wins
     *
     * @param host Daemon IP address
     * @param port Daemon port
     * @param masterSecret Master secret from keystore
     * @param deviceId This device's ID
     * @param ourCapabilities Our local capabilities
     * @param ntfyTimeoutMs Timeout for ntfy fallback (default 10s)
     * @param onProgress Optional callback for detailed progress events
     * @return Daemon's capabilities, or error if exchange failed
     */
    suspend fun exchangeCapabilities(
        host: String,
        port: Int,
        masterSecret: ByteArray,
        deviceId: String,
        ourCapabilities: ConnectionCapabilities,
        ntfyTimeoutMs: Long = DEFAULT_CAPABILITY_NTFY_TIMEOUT_MS,
        onProgress: SignalingProgressCallback? = null
    ): CapabilityExchangeResult = coroutineScope {
        val authKey = KeyDerivation.deriveKey(masterSecret, "auth")

        // Start both probes in parallel
        onProgress?.invoke(ConnectionProgress.CapabilityTryingDirect(host, port))

        // Start ntfy subscription warming up immediately (in parallel with direct)
        val ntfyDeferred = async {
            tryNtfyCapabilityExchange(
                masterSecret = masterSecret,
                deviceId = deviceId,
                ourCapabilities = ourCapabilities,
                timeoutMs = ntfyTimeoutMs,
                onProgress = onProgress
            )
        }

        // Try direct HTTP with short timeout (LAN should respond in <100ms)
        try {
            val result = withTimeoutOrNull(directTimeoutMs) {
                directClient.exchangeCapabilities(
                    host = host,
                    port = port,
                    deviceId = deviceId,
                    authKey = authKey,
                    ourCapabilities = ourCapabilities
                )
            }
            if (result != null) {
                Log.i(TAG, "Direct capability exchange succeeded: tailscale=${result.tailscaleIp}:${result.tailscalePort}")
                onProgress?.invoke(ConnectionProgress.CapabilityDirectSuccess(host, port))
                authKey.fill(0)
                // Cancel ntfy - we don't need it
                ntfyDeferred.cancel()
                return@coroutineScope CapabilityExchangeResult.Success(result)
            } else {
                Log.d(TAG, "Direct capability exchange timed out (${directTimeoutMs}ms), using ntfy")
                onProgress?.invoke(ConnectionProgress.CapabilityDirectTimeout(host, port))
            }
        } catch (e: DeviceNotFoundException) {
            Log.w(TAG, "Device not found during capability exchange")
            authKey.fill(0)
            ntfyDeferred.cancel()
            return@coroutineScope CapabilityExchangeResult.DeviceNotFound
        } catch (e: AuthenticationException) {
            Log.w(TAG, "Auth failed during capability exchange")
            authKey.fill(0)
            ntfyDeferred.cancel()
            return@coroutineScope CapabilityExchangeResult.AuthenticationFailed
        } catch (e: CancellationException) {
            authKey.fill(0)
            ntfyDeferred.cancel()
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Direct capability exchange error: ${e.message}, using ntfy")
            onProgress?.invoke(ConnectionProgress.CapabilityDirectTimeout(host, port))
        }

        // Zero auth key - no longer needed for ntfy path
        authKey.fill(0)

        // Direct failed/timed out - ntfy was already warming up, await its result
        ntfyDeferred.await()
    }

    /**
     * Discover daemon's current IPs via ntfy.
     *
     * Sends a DISCOVER message to get all available daemon IPs (LAN, VPN, Tailscale, public).
     * This allows the phone to update its connection targets when IPs have changed.
     *
     * @param masterSecret Master secret from keystore
     * @param deviceId This device's ID
     * @param timeoutMs Timeout for discovery (default 10s)
     * @param onProgress Optional callback for progress events
     * @return Discovery result with all available IPs, or error
     */
    suspend fun discoverHosts(
        masterSecret: ByteArray,
        deviceId: String,
        timeoutMs: Long = DEFAULT_CAPABILITY_NTFY_TIMEOUT_MS,
        onProgress: SignalingProgressCallback? = null
    ): DiscoveryResult {
        // Derive signaling key
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        // Create validator for incoming DISCOVER_RESPONSE
        val validator = NtfySignalMessageValidator(
            pendingSessionId = "", // Empty for reconnection mode
            expectedType = NtfySignalMessage.MessageType.DISCOVER_RESPONSE
        )

        // Compute topic
        val topic = NtfySignalingClient.computeTopic(masterSecret)
        Log.d(TAG, "Ntfy host discovery on topic: $topic")

        try {
            return withTimeout(timeoutMs) {
                // Subscribe first
                Log.d(TAG, "Subscribing to ntfy for host discovery...")
                onProgress?.invoke(ConnectionProgress.HostDiscoveryStarted(topic))

                val messageFlow = ntfyClient.subscribe(topic)
                Log.d(TAG, "Connected to ntfy for host discovery")

                // Create and encrypt DISCOVER request
                val discoverMsg = NtfySignalMessage.newBuilder()
                    .setType(NtfySignalMessage.MessageType.DISCOVER)
                    .setSessionId("") // Empty = reconnection mode
                    .setDeviceId(deviceId)
                    .setTimestamp(System.currentTimeMillis() / 1000)
                    .setNonce(ByteString.copyFrom(generateNonce()))
                    .build()

                val encryptedMsg = crypto.encryptToBase64(discoverMsg.toByteArray())

                // Publish discover request
                Log.d(TAG, "Publishing discovery request via ntfy")
                ntfyClient.publishWithRetry(topic, encryptedMsg)
                Log.d(TAG, "Discovery request published, waiting for response")

                // Wait for valid DISCOVER_RESPONSE
                messageFlow.collect { ntfyMessage ->
                    if (ntfyMessage.event != "message" || ntfyMessage.message.isEmpty()) {
                        return@collect
                    }

                    // Try to decrypt and validate message
                    val discovery = processDiscoveryResponse(ntfyMessage.message, crypto, validator)
                    if (discovery != null) {
                        throw DiscoveryResponseReceivedException(discovery)
                    }
                }

                // If we get here without a response, timeout
                DiscoveryResult.NetworkError
            }
        } catch (e: DiscoveryResponseReceivedException) {
            Log.i(TAG, "Received discovery via ntfy: lan=${e.discovery.lanIp}:${e.discovery.lanPort}, " +
                    "vpn=${e.discovery.vpnIp}:${e.discovery.vpnPort}, " +
                    "tailscale=${e.discovery.tailscaleIp}:${e.discovery.tailscalePort}")
            // Clean up
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            return DiscoveryResult.Success(e.discovery)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Ntfy host discovery timed out")
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            return DiscoveryResult.NetworkError
        } catch (e: CancellationException) {
            Log.d(TAG, "Ntfy host discovery cancelled")
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ntfy host discovery failed: ${e.message}", e)
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            return DiscoveryResult.NetworkError
        }
    }

    /**
     * Process a discovery response message.
     */
    private fun processDiscoveryResponse(
        encryptedMessage: String,
        crypto: NtfySignalingCrypto,
        validator: NtfySignalMessageValidator
    ): DiscoveryResponse? {
        return try {
            // Decrypt
            val decrypted = crypto.decryptFromBase64(encryptedMessage)

            // Parse protobuf
            val msg = NtfySignalMessage.parseFrom(decrypted)

            // Filter out requests (which have device_id set)
            // Responses from daemon have empty device_id
            if (msg.deviceId.isNotEmpty()) {
                Log.d(TAG, "Ignoring DISCOVER request (has device_id)")
                return null
            }

            // Validate
            val result = validator.validate(msg)
            if (!result.isValid) {
                return null
            }

            // Return discovery response
            if (msg.hasDiscovery()) msg.discovery else null
        } catch (e: Exception) {
            // Silent ignore - could be wrong key, tampered, etc.
            null
        }
    }

    /**
     * Try capability exchange via ntfy relay.
     */
    private suspend fun tryNtfyCapabilityExchange(
        masterSecret: ByteArray,
        deviceId: String,
        ourCapabilities: ConnectionCapabilities,
        timeoutMs: Long,
        onProgress: SignalingProgressCallback? = null
    ): CapabilityExchangeResult {
        // Derive signaling key
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        // Create validator for incoming CAPABILITIES response
        val validator = NtfySignalMessageValidator(
            pendingSessionId = "", // Empty for reconnection mode
            expectedType = NtfySignalMessage.MessageType.CAPABILITIES
        )

        // Compute topic
        val topic = NtfySignalingClient.computeTopic(masterSecret)
        Log.d(TAG, "Ntfy capability exchange on topic: $topic")

        try {
            return withTimeout(timeoutMs) {
                // Subscribe first - this BLOCKS until WebSocket is connected
                Log.d(TAG, "Subscribing to ntfy for capability exchange...")
                onProgress?.invoke(ConnectionProgress.CapabilityNtfySubscribing(topic))

                val messageFlow = ntfyClient.subscribe(topic)
                Log.d(TAG, "Connected to ntfy for capability exchange")
                onProgress?.invoke(ConnectionProgress.CapabilityNtfySubscribed(topic))

                // Create and encrypt CAPABILITIES request
                val capsMsg = NtfySignalMessage.newBuilder()
                    .setType(NtfySignalMessage.MessageType.CAPABILITIES)
                    .setSessionId("") // Empty = reconnection mode
                    .setDeviceId(deviceId)
                    .setTimestamp(System.currentTimeMillis() / 1000)
                    .setNonce(ByteString.copyFrom(generateNonce()))
                    .setCapabilities(ourCapabilities)
                    .build()

                val encryptedMsg = crypto.encryptToBase64(capsMsg.toByteArray())

                // Publish capabilities request
                Log.d(TAG, "Publishing capability request via ntfy")
                onProgress?.invoke(ConnectionProgress.CapabilityNtfySending(topic))

                ntfyClient.publishWithRetry(topic, encryptedMsg)
                Log.d(TAG, "Capability request published, waiting for response")
                onProgress?.invoke(ConnectionProgress.CapabilityNtfyWaiting(topic))

                // Wait for valid CAPABILITIES response
                messageFlow.collect { ntfyMessage ->
                    if (ntfyMessage.event != "message" || ntfyMessage.message.isEmpty()) {
                        return@collect
                    }

                    // Try to decrypt and validate message
                    val caps = processCapabilityResponse(ntfyMessage.message, crypto, validator)
                    if (caps != null) {
                        throw CapabilityResponseReceivedException(caps)
                    }
                }

                // If we get here without a response, timeout
                CapabilityExchangeResult.NetworkError
            }
        } catch (e: CapabilityResponseReceivedException) {
            Log.i(TAG, "Received capabilities via ntfy: tailscale=${e.capabilities.tailscaleIp}:${e.capabilities.tailscalePort}")
            onProgress?.invoke(ConnectionProgress.CapabilityNtfyReceived(topic))
            // Clean up
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            return CapabilityExchangeResult.Success(e.capabilities)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Ntfy capability exchange timed out")
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            return CapabilityExchangeResult.NetworkError
        } catch (e: CancellationException) {
            Log.d(TAG, "Ntfy capability exchange cancelled")
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ntfy capability exchange failed: ${e.message}", e)
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            return CapabilityExchangeResult.NetworkError
        }
    }

    /**
     * Process a capability response message.
     */
    private fun processCapabilityResponse(
        encryptedMessage: String,
        crypto: NtfySignalingCrypto,
        validator: NtfySignalMessageValidator
    ): ConnectionCapabilities? {
        return try {
            // Decrypt
            val decrypted = crypto.decryptFromBase64(encryptedMessage)

            // Parse protobuf
            val msg = NtfySignalMessage.parseFrom(decrypted)

            // Filter out requests (which have device_id set)
            // Responses from daemon have empty device_id
            if (msg.deviceId.isNotEmpty()) {
                Log.d(TAG, "Ignoring CAPABILITIES request (has device_id)")
                return null
            }

            // Validate
            val result = validator.validate(msg)
            if (!result.isValid) {
                return null
            }

            // Return capabilities
            if (msg.hasCapabilities()) msg.capabilities else null
        } catch (e: Exception) {
            // Silent ignore - could be wrong key, tampered, etc.
            null
        }
    }

    /**
     * Exchange SDP offer for answer using parallel racing.
     *
     * Starts both direct HTTP and ntfy in parallel:
     * - Direct HTTP is fast path for LAN (~50ms)
     * - ntfy starts warming up immediately
     * - First successful response wins
     *
     * @param host Daemon IP address
     * @param port Daemon port
     * @param masterSecret Master secret from keystore
     * @param sdpOffer WebRTC SDP offer
     * @param deviceId This device's ID
     * @param deviceName This device's name
     * @param ntfyTimeoutMs Timeout for ntfy signaling (default 30s)
     * @param onProgress Optional callback for detailed progress events
     */
    suspend fun exchangeSdp(
        host: String,
        port: Int,
        masterSecret: ByteArray,
        sdpOffer: String,
        deviceId: String,
        deviceName: String,
        ntfyTimeoutMs: Long = DEFAULT_NTFY_TIMEOUT_MS,
        onProgress: SignalingProgressCallback? = null
    ): ReconnectionSignalerResult = coroutineScope {
        // Derive auth key for direct signaling
        val authKey = KeyDerivation.deriveKey(masterSecret, "auth")

        // Start both probes in parallel
        Log.d(TAG, "Trying direct reconnection to $host:$port (parallel with ntfy)")
        onProgress?.invoke(ConnectionProgress.TryingDirectSignaling(host, port))

        // Start ntfy signaling warming up immediately (in parallel with direct)
        val ntfyDeferred = async {
            tryNtfySignaling(
                masterSecret = masterSecret,
                sdpOffer = sdpOffer,
                deviceId = deviceId,
                deviceName = deviceName,
                timeoutMs = ntfyTimeoutMs,
                onProgress = onProgress
            )
        }

        // Try direct signaling with short timeout
        val directResult = tryDirectSignaling(
            host = host,
            port = port,
            authKey = authKey,
            sdpOffer = sdpOffer,
            deviceId = deviceId,
            deviceName = deviceName
        )

        when (directResult) {
            is DirectResult.Success -> {
                Log.d(TAG, "Direct reconnection succeeded")
                authKey.fill(0)
                ntfyDeferred.cancel()
                return@coroutineScope ReconnectionSignalerResult.Success(
                    sdpAnswer = directResult.sdpAnswer,
                    usedDirectPath = true,
                    usedNtfyPath = false
                )
            }
            is DirectResult.DeviceNotFound -> {
                Log.w(TAG, "Device not found on daemon - needs re-pairing")
                authKey.fill(0)
                ntfyDeferred.cancel()
                return@coroutineScope ReconnectionSignalerResult.DeviceNotFound
            }
            is DirectResult.AuthFailed -> {
                Log.w(TAG, "Authentication failed - secret mismatch")
                authKey.fill(0)
                ntfyDeferred.cancel()
                return@coroutineScope ReconnectionSignalerResult.AuthenticationFailed
            }
            is DirectResult.NetworkError -> {
                Log.d(TAG, "Direct reconnection timed out (${directTimeoutMs}ms), using ntfy")
                onProgress?.invoke(ConnectionProgress.DirectSignalingTimeout(host, port))
            }
        }

        // Zero auth key - no longer needed
        authKey.fill(0)

        // Direct failed/timed out - ntfy was already warming up, await its result
        ntfyDeferred.await()
    }

    /**
     * Result of direct HTTP signaling attempt.
     */
    private sealed class DirectResult {
        data class Success(val sdpAnswer: String) : DirectResult()
        object NetworkError : DirectResult()
        object DeviceNotFound : DirectResult()
        object AuthFailed : DirectResult()
    }

    /**
     * Try direct HTTP signaling with timeout.
     */
    private suspend fun tryDirectSignaling(
        host: String,
        port: Int,
        authKey: ByteArray,
        sdpOffer: String,
        deviceId: String,
        deviceName: String
    ): DirectResult {
        return try {
            val result = withTimeoutOrNull(directTimeoutMs) {
                directClient.sendReconnect(
                    host = host,
                    port = port,
                    deviceId = deviceId,
                    authKey = authKey,
                    sdpOffer = sdpOffer,
                    deviceName = deviceName
                )
            }
            if (result != null) {
                DirectResult.Success(result)
            } else {
                DirectResult.NetworkError
            }
        } catch (e: DeviceNotFoundException) {
            DirectResult.DeviceNotFound
        } catch (e: AuthenticationException) {
            DirectResult.AuthFailed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Direct signaling error: ${e.message}")
            DirectResult.NetworkError
        }
    }

    /**
     * Try ntfy signaling relay with automatic retry on WebSocket failures.
     *
     * For reconnection, we send an OFFER with:
     * - session_id = "" (empty, signals reconnection mode)
     * - device_id = the registered device ID
     *
     * The daemon checks if session_id is empty to determine this is reconnection,
     * then validates device_id against its device store.
     *
     * Includes automatic retry logic for transient WebSocket failures like
     * "Software caused connection abort" - retries up to maxNtfyRetries times
     * with exponential backoff.
     */
    private suspend fun tryNtfySignaling(
        masterSecret: ByteArray,
        sdpOffer: String,
        deviceId: String,
        deviceName: String,
        timeoutMs: Long,
        onProgress: SignalingProgressCallback? = null
    ): ReconnectionSignalerResult {
        // Derive signaling key
        val signalingKey = NtfySignalingCrypto.deriveSignalingKey(masterSecret)
        val crypto = NtfySignalingCrypto(signalingKey)

        // Create validator for incoming messages
        // For reconnection, we expect ANSWER with empty session_id
        val validator = NtfySignalMessageValidator(
            pendingSessionId = "", // Empty for reconnection mode
            expectedType = NtfySignalMessage.MessageType.ANSWER
        )

        // Compute topic
        val topic = NtfySignalingClient.computeTopic(masterSecret)
        Log.d(TAG, "Ntfy topic: $topic")

        var lastError: Exception? = null

        try {
            return withTimeout(timeoutMs) {
                // Retry loop for transient WebSocket failures
                repeat(maxNtfyRetries) { attempt ->
                    try {
                        val result = attemptNtfyExchange(
                            topic = topic,
                            crypto = crypto,
                            validator = validator,
                            sdpOffer = sdpOffer,
                            deviceId = deviceId,
                            deviceName = deviceName,
                            attemptNumber = attempt + 1,
                            onProgress = onProgress
                        )
                        // Success - return immediately
                        return@withTimeout result
                    } catch (e: ReconnectionAnswerReceivedException) {
                        // Got an answer - success!
                        throw e
                    } catch (e: CancellationException) {
                        // Cancelled - propagate
                        throw e
                    } catch (e: Exception) {
                        // Check if this is a retriable error
                        val isRetriable = isRetriableNtfyError(e)
                        lastError = e

                        if (isRetriable && attempt < maxNtfyRetries - 1) {
                            val delayMs = NTFY_RETRY_DELAY_MS * (1 shl attempt)
                            Log.w(TAG, "Ntfy attempt ${attempt + 1} failed (${e.message}), retrying in ${delayMs}ms")
                            onProgress?.invoke(ConnectionProgress.NtfyRetrying(topic, attempt + 1, maxNtfyRetries))
                            ntfyClient.unsubscribe()
                            delay(delayMs)
                        } else if (!isRetriable) {
                            // Non-retriable error - fail immediately
                            Log.e(TAG, "Ntfy non-retriable error: ${e.message}")
                            throw e
                        } else {
                            // Last attempt failed
                            Log.e(TAG, "Ntfy failed after $maxNtfyRetries attempts")
                            throw e
                        }
                    }
                }

                // All retries exhausted
                ReconnectionSignalerResult.Error(lastError?.message ?: "Ntfy signaling failed")
            }
        } catch (e: ReconnectionAnswerReceivedException) {
            Log.d(TAG, "Received answer via ntfy" +
                    if (e.capabilities != null) " with capabilities" else "")
            // Clean up
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()

            return ReconnectionSignalerResult.Success(
                sdpAnswer = e.sdpAnswer,
                usedDirectPath = false,
                usedNtfyPath = true,
                capabilities = e.capabilities
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Ntfy reconnection timed out")
            // Clean up
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()

            return ReconnectionSignalerResult.NtfyTimeout
        } catch (e: CancellationException) {
            Log.d(TAG, "Ntfy reconnection cancelled")
            // Clean up and rethrow
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ntfy reconnection failed: ${e.message}", e)
            // Clean up
            ntfyClient.unsubscribe()
            crypto.zeroKey()
            validator.clearNonceCache()

            return ReconnectionSignalerResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Check if an ntfy error is retriable (transient network issue).
     */
    private fun isRetriableNtfyError(e: Exception): Boolean {
        // IOException is always retriable, regardless of message
        if (e is IOException) return true

        val message = e.message?.lowercase() ?: return false
        return message.contains("connection abort") ||
               message.contains("connection reset") ||
               message.contains("broken pipe") ||
               message.contains("socket closed") ||
               message.contains("network unreachable") ||
               message.contains("host unreachable") ||
               message.contains("timeout")
    }

    /**
     * Single attempt at ntfy exchange.
     */
    private suspend fun attemptNtfyExchange(
        topic: String,
        crypto: NtfySignalingCrypto,
        validator: NtfySignalMessageValidator,
        sdpOffer: String,
        deviceId: String,
        deviceName: String,
        attemptNumber: Int,
        onProgress: SignalingProgressCallback?
    ): ReconnectionSignalerResult {
        // Subscribe first - this BLOCKS until WebSocket is connected
        if (attemptNumber > 1) {
            Log.d(TAG, "Subscribing to ntfy topic (attempt $attemptNumber)...")
        } else {
            Log.d(TAG, "Subscribing to ntfy topic (waiting for connection)...")
        }
        onProgress?.invoke(ConnectionProgress.NtfySubscribing(topic))

        val messageFlow = ntfyClient.subscribe(topic)
        Log.d(TAG, "Subscribed and connected to ntfy")
        onProgress?.invoke(ConnectionProgress.NtfySubscribed(topic))

        // Create and encrypt OFFER message
        // Note: session_id is empty to signal reconnection mode
        // Generate fresh nonce for each attempt
        val offerMsg = NtfySignalMessage.newBuilder()
            .setType(NtfySignalMessage.MessageType.OFFER)
            .setSessionId("") // Empty = reconnection mode
            .setSdp(sdpOffer)
            .setDeviceId(deviceId)
            .setDeviceName(deviceName)
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setNonce(ByteString.copyFrom(generateNonce()))
            .build()

        val encryptedOffer = crypto.encryptToBase64(offerMsg.toByteArray())

        // Parse SDP for UI display
        val sdpInfo = SdpInfo.parse(sdpOffer)

        // Publish offer with retry
        Log.d(TAG, "Publishing reconnection offer with retry")
        onProgress?.invoke(ConnectionProgress.NtfySendingOffer(topic, sdpInfo))

        ntfyClient.publishWithRetry(topic, encryptedOffer)
        Log.d(TAG, "Offer published, waiting for answer")
        onProgress?.invoke(ConnectionProgress.NtfyWaitingForAnswer(topic))

        // Wait for valid answer
        messageFlow.collect { ntfyMessage ->
            if (ntfyMessage.event != "message" || ntfyMessage.message.isEmpty()) {
                return@collect
            }

            // Try to decrypt and validate message
            val processed = processNtfyMessage(ntfyMessage.message, crypto, validator)
            if (processed != null) {
                // Count ICE candidates in the answer
                val candidateCount = processed.sdpAnswer.lines()
                    .count { it.startsWith("a=candidate:") }
                onProgress?.invoke(ConnectionProgress.NtfyReceivedAnswer(topic, candidateCount))

                throw ReconnectionAnswerReceivedException(
                    sdpAnswer = processed.sdpAnswer,
                    capabilities = processed.capabilities
                )
            }
        }

        // If we get here without an answer, the flow ended (e.g., WebSocket closed)
        throw IOException("WebSocket closed before receiving answer")
    }

    /**
     * Result of processing an ntfy message.
     */
    private data class ProcessedMessage(
        val sdpAnswer: String,
        val capabilities: ConnectionCapabilities?
    )

    /**
     * Process an ntfy message and return SDP answer with capabilities if valid.
     */
    private fun processNtfyMessage(
        encryptedMessage: String,
        crypto: NtfySignalingCrypto,
        validator: NtfySignalMessageValidator
    ): ProcessedMessage? {
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

            // Return SDP and capabilities
            ProcessedMessage(
                sdpAnswer = msg.sdp,
                capabilities = if (msg.hasCapabilities()) msg.capabilities else null
            )
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
private class ReconnectionAnswerReceivedException(
    val sdpAnswer: String,
    val capabilities: ConnectionCapabilities?
) : Exception()

/**
 * Internal exception used to break out of flow collection when capability response is received.
 */
private class CapabilityResponseReceivedException(
    val capabilities: ConnectionCapabilities
) : Exception()

/**
 * Internal exception used to break out of flow collection when discovery response is received.
 */
private class DiscoveryResponseReceivedException(
    val discovery: DiscoveryResponse
) : Exception()
