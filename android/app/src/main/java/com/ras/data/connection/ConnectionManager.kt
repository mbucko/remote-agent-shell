package com.ras.data.connection

import android.util.Log
import com.ras.crypto.BytesCodec
import com.ras.crypto.CryptoException
import com.ras.data.webrtc.WebRTCClient
import com.ras.di.IoDispatcher
import com.ras.proto.ConnectionReady
import com.ras.proto.Disconnect
import com.ras.util.GlobalConnectionTimer
import com.ras.proto.InitialState
import com.ras.proto.Heartbeat
import com.ras.proto.Ping
import com.ras.proto.RasCommand
import com.ras.proto.RasEvent
import com.ras.proto.SessionCommand
import com.ras.proto.SessionEvent as ProtoSessionEvent
import com.ras.proto.SessionListEvent
import com.ras.proto.TerminalCommand
import com.ras.proto.TerminalEvent as ProtoTerminalEvent
import com.ras.proto.UnpairNotification
import com.ras.proto.UnpairRequest
import com.ras.proto.clipboard.ClipboardMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
/**
 * Configuration for ConnectionManager.
 * Allows DI of timing parameters for testing.
 */
data class ConnectionConfig(
    val pingIntervalMs: Long = 15_000L, // Send ping every 15s (must be < SCTP ~30s timeout)
    val heartbeatCheckIntervalMs: Long = 15_000L, // Check health every 15s
    val maxIdleMs: Long = 90_000L, // Consider unhealthy after 90s idle
    val receiveTimeoutMs: Long = 60_000L, // 60 second timeout for receive
    val connectionReadyTimeoutMs: Long = 10_000L // 10 second timeout for ConnectionReady
)

@Singleton
class ConnectionManager @Inject constructor(
    private val webRtcClientFactory: WebRTCClient.Factory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val config: ConnectionConfig = ConnectionConfig()
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024 // 16 MB

        // Disconnect reason constants
        const val DISCONNECT_REASON_UNPAIR = "unpair"
        const val DISCONNECT_REASON_USER_REQUEST = "user_request"
        const val DISCONNECT_REASON_APP_CLOSING = "app_closing"
    }

    // Exception handler to prevent silent failures in coroutines
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG, "Uncaught exception in ConnectionManager scope", exception)
    }

    val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)

    private val connectionLock = Any()

    @Volatile
    private var transport: Transport? = null

    @Volatile
    private var webRtcClient: WebRTCClient? = null  // For health checking (WebRTC only)

    @Volatile
    private var codec: BytesCodec? = null

    private var eventListenerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var pingJob: Job? = null

    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Track which device we're connected to (for multi-device support)
    private val _connectedDeviceId = MutableStateFlow<String?>(null)
    val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

    // Daemon's known public IP (from pairing/credentials) - for display purposes
    @Volatile
    private var knownDaemonPublicIp: String? = null
    @Volatile
    private var knownDaemonPublicPort: Int? = null

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
    // replay = 1 ensures late subscribers get the most recent event (e.g., TerminalAttached)
    // This prevents race conditions where attach response arrives before subscriber is ready
    private val _terminalEvents = MutableSharedFlow<ProtoTerminalEvent>(
        replay = 1,
        extraBufferCapacity = 128 // Increased for terminal output bursts
    )
    val terminalEvents: SharedFlow<ProtoTerminalEvent> = _terminalEvents.asSharedFlow()

    // Initial state (emitted once when connection is ready and daemon sends InitialState)
    private val _initialState = MutableSharedFlow<InitialState>(
        replay = 1, // Keep latest for late subscribers
        extraBufferCapacity = 1
    )
    val initialState: SharedFlow<InitialState> = _initialState.asSharedFlow()

    // Unpair notifications (daemon-initiated unpair)
    private val _unpairedByDaemon = MutableSharedFlow<UnpairNotification>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val unpairedByDaemon: SharedFlow<UnpairNotification> = _unpairedByDaemon.asSharedFlow()

    // Last ping time for calculating latency
    @Volatile
    private var lastPingTimestamp: Long = 0

    // Heartbeat sequence number
    @Volatile
    private var heartbeatSequence: Long = 0

    // Connection errors
    private val _connectionErrors = MutableSharedFlow<ConnectionError>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val connectionErrors: SharedFlow<ConnectionError> = _connectionErrors.asSharedFlow()

    // Connection path visualization (ICE candidate info, path type, latency)
    private val _connectionPath = MutableStateFlow<ConnectionPath?>(null)
    val connectionPath: StateFlow<ConnectionPath?> = _connectionPath.asStateFlow()

    /**
     * Update the connection path by querying the transport for path info.
     * For WebRTC: queries ICE candidate pair
     * For Tailscale: builds path from transport IPs
     */
    suspend fun updateConnectionPath(transport: Transport? = this.transport) {
        // For Tailscale transport, build path manually
        if (transport is TailscaleTransport) {
            val localCandidate = CandidateInfo(
                type = "host",
                ip = transport.localIp,
                port = 0,  // Our local port is ephemeral
                isLocal = false  // Tailscale IPs are not "local" in the private IP sense
            )
            val remoteCandidate = CandidateInfo(
                type = "host",
                ip = transport.remoteIp,
                port = transport.remotePort,
                isLocal = false
            )
            val path = ConnectionPath(
                local = localCandidate,
                remote = remoteCandidate,
                type = PathType.TAILSCALE,
                // Include daemon's known public IP for display
                daemonPublicIp = knownDaemonPublicIp,
                daemonPublicPort = knownDaemonPublicPort
            )
            _connectionPath.value = path
            Log.i(TAG, "Connection path updated: ${path.label}")
            return
        }

        // For WebRTC, query the client
        val client = webRtcClient ?: return
        val path = client.getActivePath()
        if (path != null) {
            // Include daemon's known public IP for display
            _connectionPath.value = path.copy(
                daemonPublicIp = knownDaemonPublicIp,
                daemonPublicPort = knownDaemonPublicPort
            )
            Log.i(TAG, "Connection path updated: ${path.label}")
        }
    }

    /**
     * Update the latency in the current connection path.
     * Called when a pong is received to reflect the measured latency.
     */
    private fun updateLatency(latencyMs: Long) {
        val currentPath = _connectionPath.value ?: return
        _connectionPath.value = currentPath.copy(latencyMs = latencyMs)
    }

    /**
     * Clear the connection path. Called on disconnect.
     */
    private fun clearConnectionPath() {
        _connectionPath.value = null
        Log.d(TAG, "Connection path cleared")
    }

    /**
     * Connect using an existing WebRTC client with encryption.
     *
     * This is a suspend function that:
     * 1. Sets up the connection
     * 2. Starts event listener and heartbeat
     * 3. Sends ConnectionReady to daemon (synchronously - not fire-and-forget)
     *
     * The caller should await this function to ensure the connection is fully established.
     *
     * @param client The WebRTC client to use for communication
     * @param authKey 32-byte key for AES-256-GCM encryption (must match daemon's key)
     * @throws IllegalStateException if sending ConnectionReady fails
     */
    suspend fun connect(client: WebRTCClient, authKey: ByteArray, deviceId: String? = null) {
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
            _connectedDeviceId.value = deviceId

            // Set up disconnect callback to handle connection loss
            client.onDisconnect {
                Log.w(TAG, "WebRTC connection lost (callback)")
                scope.launch {
                    handleConnectionLost("WebRTC connection lost")
                }
            }

            startEventListener()
            startHeartbeatMonitor()
            startPingLoop()
            Log.i(TAG, "Connected to daemon with encryption")
        }

        // Send ConnectionReady SYNCHRONOUSLY (outside the lock to avoid blocking)
        // This ensures the daemon knows we're ready before this function returns
        // Use timeout to prevent hanging if daemon is unresponsive
        try {
            withTimeout(config.connectionReadyTimeoutMs) {
                sendConnectionReady()
            }
            Log.i(TAG, "Sent ConnectionReady to daemon - handoff complete")

            // Query and store the connection path for visualization
            updateConnectionPath()
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout sending ConnectionReady - daemon unresponsive")
            disconnect()
            throw IllegalStateException("ConnectionReady timeout: daemon unresponsive", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ConnectionReady", e)
            // If we can't send ConnectionReady, the connection is broken
            disconnect()
            throw IllegalStateException("Failed to send ConnectionReady: ${e.message}", e)
        }
    }

    /**
     * Connect using a Transport with encryption.
     *
     * Supports all transport types (WebRTC, Tailscale, etc).
     *
     * @param t The transport to use for communication
     * @param authKey 32-byte key for AES-256-GCM encryption
     * @param deviceId Optional device ID for multi-device tracking
     * @param daemonPublicIp Daemon's known public IP (from credentials) for display
     * @param daemonPublicPort Daemon's known public port (from credentials) for display
     */
    suspend fun connectWithTransport(
        t: Transport,
        authKey: ByteArray,
        deviceId: String? = null,
        daemonPublicIp: String? = null,
        daemonPublicPort: Int? = null
    ) {
        require(authKey.size == 32) { "Auth key must be 32 bytes" }

        synchronized(connectionLock) {
            if (transport != null) {
                Log.w(TAG, "Already connected, disconnecting first")
                disconnectInternal()
            }

            transport = t
            // Keep reference to WebRTC client for health monitoring if applicable
            webRtcClient = (t as? WebRTCTransport)?.client
            codec = BytesCodec(authKey.copyOf())
            _isConnected.value = true
            _isHealthy.value = true
            _connectedDeviceId.value = deviceId
            // Store daemon's known public IP for connection path display
            knownDaemonPublicIp = daemonPublicIp
            knownDaemonPublicPort = daemonPublicPort

            // Set up disconnect callback for WebRTC transports
            webRtcClient?.onDisconnect {
                Log.w(TAG, "WebRTC transport connection lost (callback)")
                scope.launch {
                    handleConnectionLost("WebRTC connection lost")
                }
            }

            startTransportEventListener()
            startHeartbeatMonitor()
            startPingLoop()
            Log.i(TAG, "Connected via ${t.type.displayName} with encryption")
        }

        // Send ConnectionReady
        try {
            withTimeout(config.connectionReadyTimeoutMs) {
                sendConnectionReady()
            }
            Log.i(TAG, "Sent ConnectionReady - handoff complete")

            // Query and store the connection path for visualization
            updateConnectionPath()
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout sending ConnectionReady")
            disconnect()
            throw IllegalStateException("ConnectionReady timeout", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ConnectionReady", e)
            disconnect()
            throw IllegalStateException("Failed to send ConnectionReady: ${e.message}", e)
        }
    }

    /**
     * Start event listener for Transport-based connections.
     */
    private fun startTransportEventListener() {
        eventListenerJob?.cancel()
        eventListenerJob = scope.launch {
            val t = transport ?: return@launch
            try {
                while (_isConnected.value && isActive) {
                    try {
                        val data = t.receive(config.receiveTimeoutMs)
                        routeMessage(data)
                    } catch (e: TransportException) {
                        if (e.message?.contains("timeout") == true) {
                            Log.d(TAG, "Receive timeout, checking health...")
                            checkTransportHealth()
                        } else {
                            throw e
                        }
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("timeout") == true) {
                            Log.d(TAG, "Receive timeout, checking health...")
                            checkTransportHealth()
                        } else {
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                // These exceptions are expected when connection closes gracefully:
                // - ClosedReceiveChannelException: WebRTC channel closed
                // - SocketException "Socket is closed": TailscaleTransport socket closed
                val isExpectedClose = e is kotlinx.coroutines.channels.ClosedReceiveChannelException ||
                    (e is java.net.SocketException && e.message?.contains("Socket is closed") == true)
                if (isExpectedClose) {
                    Log.i(TAG, "Transport connection closed")
                } else {
                    Log.e(TAG, "Transport connection error", e)
                }
                synchronized(connectionLock) {
                    if (_isConnected.value) {
                        _isConnected.value = false
                        _isHealthy.value = false
                        _connectionErrors.tryEmit(
                            ConnectionError.Disconnected(e.message ?: "Connection closed")
                        )
                    }
                }
            }
        }
    }

    private fun checkTransportHealth() {
        val t = transport ?: return
        if (!t.isConnected) {
            Log.w(TAG, "Transport disconnected")
            _isHealthy.value = false
            return
        }
        // For WebRTC, use the detailed health check
        webRtcClient?.let { client ->
            if (!client.isHealthy(config.maxIdleMs)) {
                Log.w(TAG, "Connection appears unhealthy")
                _isHealthy.value = false
            }
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
        pingJob?.cancel()
        pingJob = null
        _isConnected.value = false
        _isHealthy.value = false
        _connectedDeviceId.value = null
        knownDaemonPublicIp = null
        knownDaemonPublicPort = null
        clearConnectionPath()
        transport?.close()
        transport = null
        webRtcClient?.close()
        webRtcClient = null
        codec?.zeroKey()
        codec = null
        Log.i(TAG, "Disconnected from daemon")
    }

    /**
     * Handle unexpected connection loss (WebRTC channel closed, ICE failed, etc).
     * Cleans up and emits error so UI can show reconnection prompt.
     */
    private fun handleConnectionLost(reason: String) {
        synchronized(connectionLock) {
            if (!_isConnected.value) {
                // Already disconnected, ignore
                return
            }
            Log.w(TAG, "Connection lost: $reason")
            _isConnected.value = false
            _isHealthy.value = false
            clearConnectionPath()
            _connectionErrors.tryEmit(ConnectionError.Disconnected(reason))
            // Don't close the client here - it's already closed/broken
            // Just clean up our state
            eventListenerJob?.cancel()
            eventListenerJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            pingJob?.cancel()
            pingJob = null
            webRtcClient = null
            transport = null
            codec?.zeroKey()
            codec = null
        }
    }

    /**
     * Send ConnectionReady to signal daemon we're ready to receive messages.
     */
    private suspend fun sendConnectionReady() {
        GlobalConnectionTimer.logMark("connection_ready_sending")
        val command = RasCommand.newBuilder()
            .setConnectionReady(ConnectionReady.getDefaultInstance())
            .build()
        val plaintext = command.toByteArray()
        sendEncrypted(plaintext)
        GlobalConnectionTimer.logMark("connection_ready_sent")
    }

    /**
     * Send a session command to the daemon (encrypted, wrapped in RasCommand).
     *
     * @throws IllegalStateException if not connected
     * @throws IllegalArgumentException if message too large
     */
    suspend fun sendSessionCommand(command: SessionCommand) {
        val wrapped = RasCommand.newBuilder()
            .setSession(command)
            .build()
        val plaintext = wrapped.toByteArray()
        validateMessageSize(plaintext, "SessionCommand")
        sendEncrypted(plaintext)
    }

    /**
     * Send a terminal command to the daemon (encrypted, wrapped in RasCommand).
     *
     * @throws IllegalStateException if not connected
     * @throws IllegalArgumentException if message too large
     */
    suspend fun sendTerminalCommand(command: TerminalCommand) {
        val wrapped = RasCommand.newBuilder()
            .setTerminal(command)
            .build()
        val plaintext = wrapped.toByteArray()
        validateMessageSize(plaintext, "TerminalCommand")
        sendEncrypted(plaintext)
    }

    /**
     * Send a clipboard message to the daemon (encrypted, wrapped in RasCommand).
     *
     * @throws IllegalStateException if not connected
     * @throws IllegalArgumentException if message too large
     */
    suspend fun sendClipboardMessage(message: ClipboardMessage) {
        val wrapped = RasCommand.newBuilder()
            .setClipboard(message)
            .build()
        val plaintext = wrapped.toByteArray()
        validateMessageSize(plaintext, "ClipboardMessage")
        sendEncrypted(plaintext)
    }

    /**
     * Send a ping to measure latency.
     */
    suspend fun sendPing() {
        lastPingTimestamp = System.currentTimeMillis()
        val ping = Ping.newBuilder()
            .setTimestamp(lastPingTimestamp)
            .build()
        val command = RasCommand.newBuilder()
            .setPing(ping)
            .build()
        val plaintext = command.toByteArray()
        sendEncrypted(plaintext)
    }

    /**
     * Send a heartbeat to keep connection alive.
     *
     * Unlike ping, heartbeat is fire-and-forget with no response expected.
     * Used for SCTP keepalive to prevent ~30s timeout.
     */
    suspend fun sendHeartbeat() {
        heartbeatSequence++
        val heartbeat = Heartbeat.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setSequence(heartbeatSequence)
            .build()
        val command = RasCommand.newBuilder()
            .setHeartbeat(heartbeat)
            .build()
        val plaintext = command.toByteArray()
        sendEncrypted(plaintext)
    }

    /**
     * Send a graceful disconnect message to the daemon.
     *
     * This allows the daemon to clean up immediately (e.g., resize terminal windows)
     * without waiting for heartbeat timeout detection.
     *
     * @param reason Optional reason for disconnect (use DISCONNECT_REASON_* constants)
     */
    suspend fun sendGracefulDisconnect(reason: String = DISCONNECT_REASON_USER_REQUEST) {
        try {
            val disconnect = Disconnect.newBuilder()
                .setReason(reason)
                .build()
            val command = RasCommand.newBuilder()
                .setDisconnect(disconnect)
                .build()
            val plaintext = command.toByteArray()
            sendEncrypted(plaintext)
            Log.i(TAG, "Sent graceful disconnect: $reason")
        } catch (e: Exception) {
            // Best effort - don't fail the disconnect if we can't send the message
            Log.w(TAG, "Failed to send graceful disconnect message", e)
        }
    }

    /**
     * Disconnect gracefully by notifying the daemon first.
     *
     * Sends a Disconnect message to the daemon, allowing it to clean up immediately,
     * then closes the local connection.
     *
     * @param reason Optional reason for disconnect (use DISCONNECT_REASON_* constants)
     */
    suspend fun disconnectGracefully(reason: String = DISCONNECT_REASON_USER_REQUEST) {
        sendGracefulDisconnect(reason)
        // Small delay to let the message be sent
        delay(100)
        disconnect()
    }

    /**
     * Send an unpair request to the daemon.
     *
     * This notifies the daemon that the phone is unpairing, allowing it to clean up
     * the device from its paired devices list.
     *
     * @param deviceId The device ID to unpair
     */
    suspend fun sendUnpairRequest(deviceId: String) {
        try {
            val unpairRequest = UnpairRequest.newBuilder()
                .setDeviceId(deviceId)
                .build()
            val command = RasCommand.newBuilder()
                .setUnpairRequest(unpairRequest)
                .build()
            val plaintext = command.toByteArray()
            sendEncrypted(plaintext)
            Log.i(TAG, "Sent unpair request for device: $deviceId")
        } catch (e: Exception) {
            // Best effort - don't fail the unpair if we can't send the message
            Log.w(TAG, "Failed to send unpair request", e)
        }
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
        val c = codec ?: throw IllegalStateException("No encryption codec available")
        val encrypted = c.encode(plaintext)

        // Prefer transport if available, fall back to webRtcClient for legacy
        val t = transport
        val client = webRtcClient
        when {
            t != null -> t.send(encrypted)
            client != null -> client.send(encrypted)
            else -> throw IllegalStateException("Not connected to daemon")
        }
    }

    private fun validateMessageSize(data: ByteArray, type: String) {
        require(data.size <= MAX_MESSAGE_SIZE) {
            "$type too large: ${data.size} bytes exceeds maximum $MAX_MESSAGE_SIZE"
        }
    }

    private fun startEventListener() {
        eventListenerJob?.cancel()
        eventListenerJob = scope.launch {
            val client = webRtcClient ?: return@launch
            try {
                while (_isConnected.value && isActive) {
                    try {
                        val data = client.receive(config.receiveTimeoutMs)
                        routeMessage(data)
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("timeout") == true) {
                            // Timeout is expected, check connection health
                            Log.d(TAG, "Receive timeout, checking health...")
                            if (!client.isHealthy(config.maxIdleMs)) {
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
                delay(config.heartbeatCheckIntervalMs)
                val client = webRtcClient ?: continue

                val healthy = client.isHealthy(config.maxIdleMs)
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
     * Start the ping loop to keep connection alive.
     *
     * Sends periodic pings to the daemon to prevent SCTP timeout (~30s).
     * Uses Ping instead of Heartbeat because it also provides latency measurement
     * via Pong response. Both serve the same keepalive purpose.
     *
     * CONTRACT: pingIntervalMs (default 15s) < SCTP timeout (~30s)
     *
     * Industry pattern: Application-level keepalive pings are standard for
     * WebRTC data channels (used by Slack, Discord, Zoom, etc).
     */
    private fun startPingLoop() {
        if (config.pingIntervalMs <= 0) {
            Log.d(TAG, "Ping loop disabled (pingIntervalMs=${config.pingIntervalMs})")
            return
        }

        pingJob?.cancel()
        pingJob = scope.launch {
            while (_isConnected.value && isActive) {
                delay(config.pingIntervalMs)
                try {
                    sendPing()
                    Log.d(TAG, "Sent keepalive ping")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send keepalive ping", e)
                }
            }
        }
    }

    /**
     * Route incoming message to appropriate handler.
     *
     * Messages are decrypted first, then parsed as RasEvent wrapper.
     * The wrapper contains the actual event type (session, terminal, initial_state, etc).
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

        // Parse as RasEvent wrapper
        val event = try {
            RasEvent.parseFrom(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RasEvent: ${e.message}")
            return
        }

        // Route based on event type
        when (event.eventCase) {
            RasEvent.EventCase.SESSION -> {
                if (hasSessionEventContent(event.session)) {
                    _sessionEvents.emit(event.session)
                }
            }
            RasEvent.EventCase.TERMINAL -> {
                if (hasTerminalEventContent(event.terminal)) {
                    _terminalEvents.emit(event.terminal)
                }
            }
            RasEvent.EventCase.INITIAL_STATE -> {
                GlobalConnectionTimer.logMark("initial_state_received")
                Log.i(TAG, "Received InitialState: ${event.initialState.sessionsCount} sessions, ${event.initialState.agentsCount} agents")
                _initialState.emit(event.initialState)
                // Note: SessionRepository.subscribeToInitialState() handles this directly
                // No need to also emit to sessionEvents - that was causing race conditions
            }
            RasEvent.EventCase.PONG -> {
                val latency = System.currentTimeMillis() - event.pong.timestamp
                Log.d(TAG, "Pong received, latency: ${latency}ms")
                updateLatency(latency)
            }
            RasEvent.EventCase.ERROR -> {
                Log.e(TAG, "Error from daemon: ${event.error.errorCode} - ${event.error.message}")
            }
            RasEvent.EventCase.CLIPBOARD -> {
                Log.d(TAG, "Clipboard message received")
                // TODO: Handle clipboard events
            }
            RasEvent.EventCase.HEARTBEAT -> {
                // Heartbeat received from daemon - connection is alive
                // The fact that we received it already updates lastMessageTime in WebRTCClient
                val seq = event.heartbeat.sequence
                Log.d(TAG, "Heartbeat received from daemon: seq=$seq")
            }
            RasEvent.EventCase.UNPAIR_NOTIFICATION -> {
                val notification = event.unpairNotification
                Log.i(TAG, "Received unpair notification: deviceId=${notification.deviceId}, reason=${notification.reason}")
                _unpairedByDaemon.emit(notification)
            }
            RasEvent.EventCase.UNPAIR_ACK -> {
                val ack = event.unpairAck
                Log.d(TAG, "Received unpair acknowledgment: deviceId=${ack.deviceId}")
                // UnpairAck is handled implicitly - phone already cleared credentials
            }
            RasEvent.EventCase.EVENT_NOT_SET, null -> {
                Log.w(TAG, "Received RasEvent with no event set")
            }
        }
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
