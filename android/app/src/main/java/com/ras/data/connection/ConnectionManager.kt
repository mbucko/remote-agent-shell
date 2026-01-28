package com.ras.data.connection

import android.util.Log
import com.ras.data.webrtc.WebRTCClient
import com.ras.di.IoDispatcher
import com.ras.proto.SessionCommand
import com.ras.proto.SessionEvent as ProtoSessionEvent
import com.ras.proto.TerminalCommand
import com.ras.proto.TerminalEvent as ProtoTerminalEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val webRtcClientFactory: WebRTCClient.Factory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ConnectionManager"
    }

    val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var webRtcClient: WebRTCClient? = null

    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Session events (emitted when a SessionEvent proto is received)
    private val _sessionEvents = MutableSharedFlow<ProtoSessionEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val sessionEvents: SharedFlow<ProtoSessionEvent> = _sessionEvents.asSharedFlow()

    // Terminal events (emitted when a TerminalEvent proto is received)
    private val _terminalEvents = MutableSharedFlow<ProtoTerminalEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val terminalEvents: SharedFlow<ProtoTerminalEvent> = _terminalEvents.asSharedFlow()

    // Connection errors
    private val _connectionErrors = MutableSharedFlow<ConnectionError>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val connectionErrors: SharedFlow<ConnectionError> = _connectionErrors.asSharedFlow()

    /**
     * Connect using an existing WebRTC client.
     */
    fun connect(client: WebRTCClient) {
        if (webRtcClient != null) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        webRtcClient = client
        _isConnected.value = true
        startEventListener()
        Log.i(TAG, "Connected to daemon")
    }

    /**
     * Disconnect and clean up resources.
     */
    fun disconnect() {
        _isConnected.value = false
        webRtcClient?.close()
        webRtcClient = null
        Log.i(TAG, "Disconnected from daemon")
    }

    /**
     * Send a session command to the daemon.
     *
     * @throws IllegalStateException if not connected
     */
    suspend fun sendSessionCommand(command: SessionCommand) {
        val client = requireConnected()
        client.send(command.toByteArray())
    }

    /**
     * Send a terminal command to the daemon.
     *
     * @throws IllegalStateException if not connected
     */
    suspend fun sendTerminalCommand(command: TerminalCommand) {
        val client = requireConnected()
        client.send(command.toByteArray())
    }

    /**
     * Send raw bytes to the daemon.
     *
     * @throws IllegalStateException if not connected
     */
    suspend fun send(data: ByteArray) {
        val client = requireConnected()
        client.send(data)
    }

    private fun requireConnected(): WebRTCClient {
        return webRtcClient ?: throw IllegalStateException("Not connected to daemon")
    }

    private fun startEventListener() {
        scope.launch {
            val client = webRtcClient ?: return@launch
            try {
                while (_isConnected.value) {
                    val data = client.receive()
                    routeMessage(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _isConnected.value = false
                _connectionErrors.tryEmit(ConnectionError.Disconnected(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Route incoming message to appropriate handler.
     *
     * Protocol uses distinct message types, so we try parsing in order.
     * If one succeeds, we emit to the appropriate flow.
     */
    private suspend fun routeMessage(data: ByteArray) {
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
            event.hasSkipped()
    }
}

/**
 * Connection errors that can occur.
 */
sealed class ConnectionError {
    data class Disconnected(val reason: String) : ConnectionError()
    data class SendFailed(val message: String) : ConnectionError()
}
