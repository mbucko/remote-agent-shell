package com.ras.data.connection

import android.util.Log
import com.ras.crypto.BytesCodec
import com.ras.crypto.CryptoException
import com.ras.data.webrtc.WebRTCClient
import com.ras.di.IoDispatcher
import com.ras.proto.SessionCommand
import com.ras.proto.SessionEvent as ProtoSessionEvent
import com.ras.proto.TerminalCommand
import com.ras.proto.TerminalEvent as ProtoTerminalEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central manager for WebRTC connection and message routing.
 *
 * Routes incoming protobuf messages to appropriate handlers:
 * - SessionEvent -> sessionEvents flow
 * - TerminalEvent -> terminalEvents flow
 *
 * Provides unified interface for sending commands.
 *
 * Thread Safety:
 * - This class is thread-safe for concurrent access.
 * - Connection state changes are synchronized.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val webRtcClientFactory: WebRTCClient.Factory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024 // 16 MB
        private const val RECEIVE_TIMEOUT_MS = 60_000L // 60 second timeout for receive
        private const val HEARTBEAT_CHECK_INTERVAL_MS = 30_000L // Check health every 30s
        private const val MAX_IDLE_MS = 90_000L // Consider unhealthy after 90s idle
    }

    val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val connectionLock = Any()

    @Volatile
    private var webRtcClient: WebRTCClient? = null

    @Volatile
    private var codec: BytesCodec? = null

    private var eventListenerJob: Job? = null
    private var heartbeatJob: Job? = null

    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Connection health
    private val _isHealthy = MutableStateFlow(true)
    val isHealthy: StateFlow<Boolean> = _isHealthy.asStateFlow()

    // Session events (emitted when a SessionEvent proto is received)
    private val _sessionEvents = MutableSharedFlow<ProtoSessionEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val sessionEvents: SharedFlow<ProtoSessionEvent> = _sessionEvents.asSharedFlow()

    // Terminal events (emitted when a TerminalEvent proto is received)
    private val _terminalEvents = MutableSharedFlow<ProtoTerminalEvent>(
        replay = 0,
        extraBufferCapacity = 128 // Increased for terminal output bursts
    )
    val terminalEvents: SharedFlow<ProtoTerminalEvent> = _terminalEvents.asSharedFlow()

    // Connection errors
    private val _connectionErrors = MutableSharedFlow<ConnectionError>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val connectionErrors: SharedFlow<ConnectionError> = _connectionErrors.asSharedFlow()

    /**
     * Connect using an existing WebRTC client with encryption.
     *
     * @param client The WebRTC client to use for communication
     * @param authKey 32-byte key for AES-256-GCM encryption (must match daemon's key)
     */
    fun connect(client: WebRTCClient, authKey: ByteArray) {
        require(authKey.size == 32) { "Auth key must be 32 bytes" }

        synchronized(connectionLock) {
            if (webRtcClient != null) {
                Log.w(TAG, "Already connected, disconnecting first")
                disconnectInternal()
            }

            webRtcClient = client
            codec = BytesCodec(authKey.copyOf())  // Copy to avoid external mutation
            _isConnected.value = true
            _isHealthy.value = true
            startEventListener()
            startHeartbeatMonitor()
            Log.i(TAG, "Connected to daemon with encryption")
        }
    }

    /**
     * Disconnect and clean up resources.
     */
    fun disconnect() {
        synchronized(connectionLock) {
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        eventListenerJob?.cancel()
        eventListenerJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        _isConnected.value = false
        _isHealthy.value = false
        webRtcClient?.close()
        webRtcClient = null
        codec?.zeroKey()
        codec = null
        Log.i(TAG, "Disconnected from daemon")
    }

    /**
     * Send a session command to the daemon (encrypted).
     *
     * @throws IllegalStateException if not connected
     * @throws IllegalArgumentException if message too large
     */
    suspend fun sendSessionCommand(command: SessionCommand) {
        val plaintext = command.toByteArray()
        validateMessageSize(plaintext, "SessionCommand")
        sendEncrypted(plaintext)
    }

    /**
     * Send a terminal command to the daemon (encrypted).
     *
     * @throws IllegalStateException if not connected
     * @throws IllegalArgumentException if message too large
     */
    suspend fun sendTerminalCommand(command: TerminalCommand) {
        val plaintext = command.toByteArray()
        validateMessageSize(plaintext, "TerminalCommand")
        sendEncrypted(plaintext)
    }

    /**
     * Send raw bytes to the daemon (encrypted).
     *
     * @throws IllegalStateException if not connected
     * @throws IllegalArgumentException if message too large
     */
    suspend fun send(data: ByteArray) {
        validateMessageSize(data, "raw data")
        sendEncrypted(data)
    }

    /**
     * Encrypt and send data.
     */
    private suspend fun sendEncrypted(plaintext: ByteArray) {
        val client = requireConnected()
        val c = codec ?: throw IllegalStateException("No encryption codec available")
        val encrypted = c.encode(plaintext)
        client.send(encrypted)
    }

    private fun validateMessageSize(data: ByteArray, type: String) {
        require(data.size <= MAX_MESSAGE_SIZE) {
            "$type too large: ${data.size} bytes exceeds maximum $MAX_MESSAGE_SIZE"
        }
    }

    private fun requireConnected(): WebRTCClient {
        return webRtcClient ?: throw IllegalStateException("Not connected to daemon")
    }

    private fun startEventListener() {
        eventListenerJob?.cancel()
        eventListenerJob = scope.launch {
            val client = webRtcClient ?: return@launch
            try {
                while (_isConnected.value && isActive) {
                    try {
                        val data = client.receive(RECEIVE_TIMEOUT_MS)
                        routeMessage(data)
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("timeout") == true) {
                            // Timeout is expected, check connection health
                            Log.d(TAG, "Receive timeout, checking health...")
                            if (!client.isHealthy(MAX_IDLE_MS)) {
                                Log.w(TAG, "Connection appears unhealthy")
                                _isHealthy.value = false
                            }
                        } else {
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                synchronized(connectionLock) {
                    if (_isConnected.value) {
                        _isConnected.value = false
                        _isHealthy.value = false
                        _connectionErrors.tryEmit(
                            ConnectionError.Disconnected(e.message ?: "Unknown error")
                        )
                    }
                }
            }
        }
    }

    private fun startHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (_isConnected.value && isActive) {
                delay(HEARTBEAT_CHECK_INTERVAL_MS)
                val client = webRtcClient ?: continue

                val healthy = client.isHealthy(MAX_IDLE_MS)
                if (_isHealthy.value != healthy) {
                    _isHealthy.value = healthy
                    if (!healthy) {
                        Log.w(TAG, "Connection health degraded, idle: ${client.getIdleTimeMs()}ms")
                    } else {
                        Log.d(TAG, "Connection health restored")
                    }
                }
            }
        }
    }

    /**
     * Route incoming message to appropriate handler.
     *
     * Messages are decrypted first, then parsed as protobuf.
     * Protocol uses distinct message types, so we try parsing in order.
     * If one succeeds, we emit to the appropriate flow.
     */
    private suspend fun routeMessage(encryptedData: ByteArray) {
        // Validate message size
        if (encryptedData.size > MAX_MESSAGE_SIZE) {
            Log.e(TAG, "Received oversized message: ${encryptedData.size} bytes, dropping")
            return
        }

        if (encryptedData.isEmpty()) {
            Log.w(TAG, "Received empty message, ignoring")
            return
        }

        // Decrypt the message
        val c = codec
        if (c == null) {
            Log.e(TAG, "Received message but no codec available")
            return
        }

        val data = try {
            c.decode(encryptedData)
        } catch (e: CryptoException) {
            Log.e(TAG, "Failed to decrypt message: ${e.message}")
            return
        }

        // Try parsing as SessionEvent first (more common)
        try {
            val sessionEvent = ProtoSessionEvent.parseFrom(data)
            // Check if it has any known field set (not an empty/default message)
            if (hasSessionEventContent(sessionEvent)) {
                _sessionEvents.emit(sessionEvent)
                return
            }
        } catch (e: Exception) {
            // Not a valid SessionEvent, try TerminalEvent
        }

        // Try parsing as TerminalEvent
        try {
            val terminalEvent = ProtoTerminalEvent.parseFrom(data)
            if (hasTerminalEventContent(terminalEvent)) {
                _terminalEvents.emit(terminalEvent)
                return
            }
        } catch (e: Exception) {
            // Not a valid TerminalEvent either
        }

        // Unknown message type - log and ignore
        Log.w(TAG, "Received unknown message type (${data.size} bytes)")
    }

    /**
     * Check if a SessionEvent has actual content (not just default values).
     */
    private fun hasSessionEventContent(event: ProtoSessionEvent): Boolean {
        return event.hasList() ||
            event.hasCreated() ||
            event.hasKilled() ||
            event.hasRenamed() ||
            event.hasActivity() ||
            event.hasError() ||
            event.hasAgents() ||
            event.hasDirectories()
    }

    /**
     * Check if a TerminalEvent has actual content (not just default values).
     */
    private fun hasTerminalEventContent(event: ProtoTerminalEvent): Boolean {
        return event.hasOutput() ||
            event.hasAttached() ||
            event.hasDetached() ||
            event.hasError() ||
            event.hasSkipped() ||
            event.hasNotification() // Added notification handling
    }
}

/**
 * Connection errors that can occur.
 */
sealed class ConnectionError {
    data class Disconnected(val reason: String) : ConnectionError()
    data class SendFailed(val message: String) : ConnectionError()
    data class Unhealthy(val idleTimeMs: Long) : ConnectionError()
}
