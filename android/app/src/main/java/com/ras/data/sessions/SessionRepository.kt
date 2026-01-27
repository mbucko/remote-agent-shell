package com.ras.data.sessions

import com.ras.data.webrtc.WebRTCClient
import com.ras.di.IoDispatcher
import com.ras.proto.Agent
import com.ras.proto.CreateSessionCommand
import com.ras.proto.DirectoryEntry
import com.ras.proto.GetAgentsCommand
import com.ras.proto.GetDirectoriesCommand
import com.ras.proto.KillSessionCommand
import com.ras.proto.ListSessionsCommand
import com.ras.proto.RefreshAgentsCommand
import com.ras.proto.RenameSessionCommand
import com.ras.proto.Session
import com.ras.proto.SessionCommand
import com.ras.proto.SessionEvent as ProtoSessionEvent
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
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for session management.
 * Handles communication with daemon via WebRTC and maintains session state.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val webRtcClientFactory: WebRTCClient.Factory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var webRtcClient: WebRTCClient? = null

    // Session state
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    // Agents state
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents.asStateFlow()

    // Events for one-time notifications
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * Connect to the WebRTC client for receiving events.
     */
    fun connect(client: WebRTCClient) {
        webRtcClient = client
        _isConnected.value = true
        startEventListener()
    }

    /**
     * Disconnect and clean up.
     */
    fun disconnect() {
        webRtcClient = null
        _isConnected.value = false
    }

    private fun startEventListener() {
        scope.launch {
            val client = webRtcClient ?: return@launch
            try {
                while (_isConnected.value) {
                    val data = client.receive()
                    processEvent(data)
                }
            } catch (e: Exception) {
                // Channel closed or connection lost
                _isConnected.value = false
            }
        }
    }

    private fun processEvent(data: ByteArray) {
        try {
            val event = ProtoSessionEvent.parseFrom(data)
            when {
                event.hasList() -> handleSessionList(event.list)
                event.hasCreated() -> handleSessionCreated(event.created)
                event.hasKilled() -> handleSessionKilled(event.killed)
                event.hasRenamed() -> handleSessionRenamed(event.renamed)
                event.hasActivity() -> handleSessionActivity(event.activity)
                event.hasError() -> handleSessionError(event.error)
                event.hasAgents() -> handleAgentsList(event.agents)
                event.hasDirectories() -> handleDirectoriesList(event.directories)
            }
        } catch (e: Exception) {
            // Invalid protobuf, ignore
        }
    }

    private fun handleSessionList(event: com.ras.proto.SessionListEvent) {
        _sessions.value = event.sessionsList.map { it.toDomain() }
    }

    private fun handleSessionCreated(event: com.ras.proto.SessionCreatedEvent) {
        val session = event.session.toDomain()
        _sessions.value = _sessions.value + session
        _events.tryEmit(SessionEvent.SessionCreated(session))
    }

    private fun handleSessionKilled(event: com.ras.proto.SessionKilledEvent) {
        _sessions.value = _sessions.value.filter { it.id != event.sessionId }
        _events.tryEmit(SessionEvent.SessionKilled(event.sessionId))
    }

    private fun handleSessionRenamed(event: com.ras.proto.SessionRenamedEvent) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id == event.sessionId) {
                session.copy(displayName = event.newName)
            } else {
                session
            }
        }
        _events.tryEmit(SessionEvent.SessionRenamed(event.sessionId, event.newName))
    }

    private fun handleSessionActivity(event: com.ras.proto.SessionActivityEvent) {
        val timestamp = Instant.ofEpochSecond(event.timestamp)
        _sessions.value = _sessions.value.map { session ->
            if (session.id == event.sessionId) {
                session.copy(lastActivityAt = timestamp)
            } else {
                session
            }
        }
        _events.tryEmit(SessionEvent.SessionActivity(event.sessionId, timestamp))
    }

    private fun handleSessionError(event: com.ras.proto.SessionErrorEvent) {
        _events.tryEmit(
            SessionEvent.SessionError(
                code = event.errorCode,
                message = event.message,
                sessionId = event.sessionId.ifEmpty { null }
            )
        )
    }

    private fun handleAgentsList(event: com.ras.proto.AgentsListEvent) {
        _agents.value = event.agentsList.map { it.toDomain() }
    }

    private fun handleDirectoriesList(event: com.ras.proto.DirectoriesListEvent) {
        // Directory listing is handled via callback in the command methods
    }

    // ==========================================================================
    // Commands (Phone -> Daemon)
    // ==========================================================================

    /**
     * Request the current session list from daemon.
     */
    suspend fun listSessions() {
        sendCommand(
            SessionCommand.newBuilder()
                .setList(ListSessionsCommand.getDefaultInstance())
                .build()
        )
    }

    /**
     * Create a new session.
     */
    suspend fun createSession(directory: String, agent: String) {
        sendCommand(
            SessionCommand.newBuilder()
                .setCreate(
                    CreateSessionCommand.newBuilder()
                        .setDirectory(directory)
                        .setAgent(agent)
                        .build()
                )
                .build()
        )
    }

    /**
     * Kill a session.
     */
    suspend fun killSession(sessionId: String) {
        sendCommand(
            SessionCommand.newBuilder()
                .setKill(
                    KillSessionCommand.newBuilder()
                        .setSessionId(sessionId)
                        .build()
                )
                .build()
        )
    }

    /**
     * Rename a session.
     */
    suspend fun renameSession(sessionId: String, newName: String) {
        sendCommand(
            SessionCommand.newBuilder()
                .setRename(
                    RenameSessionCommand.newBuilder()
                        .setSessionId(sessionId)
                        .setNewName(newName)
                        .build()
                )
                .build()
        )
    }

    /**
     * Request the list of available agents.
     */
    suspend fun getAgents() {
        sendCommand(
            SessionCommand.newBuilder()
                .setGetAgents(GetAgentsCommand.getDefaultInstance())
                .build()
        )
    }

    /**
     * Request directory listing.
     */
    suspend fun getDirectories(parent: String = "") {
        sendCommand(
            SessionCommand.newBuilder()
                .setGetDirectories(
                    GetDirectoriesCommand.newBuilder()
                        .setParent(parent)
                        .build()
                )
                .build()
        )
    }

    /**
     * Refresh the agent list (re-scan for installed agents).
     */
    suspend fun refreshAgents() {
        sendCommand(
            SessionCommand.newBuilder()
                .setRefreshAgents(RefreshAgentsCommand.getDefaultInstance())
                .build()
        )
    }

    private suspend fun sendCommand(command: SessionCommand) {
        webRtcClient?.send(command.toByteArray())
    }
}

// ==========================================================================
// Proto to Domain Conversions
// ==========================================================================

private fun Session.toDomain(): SessionInfo = SessionInfo(
    id = id,
    tmuxName = tmuxName,
    displayName = displayName,
    directory = directory,
    agent = agent,
    createdAt = Instant.ofEpochSecond(createdAt),
    lastActivityAt = Instant.ofEpochSecond(lastActivityAt),
    status = SessionStatus.fromProto(status.number)
)

private fun Agent.toDomain(): AgentInfo = AgentInfo(
    name = name,
    binary = binary,
    path = path,
    available = available
)

private fun DirectoryEntry.toDomain(): DirectoryEntryInfo = DirectoryEntryInfo(
    name = name,
    path = path,
    isDirectory = isDirectory
)
