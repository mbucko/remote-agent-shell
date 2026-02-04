package com.ras.data.sessions

import android.util.Log
import com.ras.data.connection.ConnectionManager
import com.ras.di.IoDispatcher
import com.ras.proto.Agent
import com.ras.proto.CreateSessionCommand
import com.ras.proto.DirectoryEntry
import com.ras.proto.GetAgentsCommand
import com.ras.proto.GetDirectoriesCommand
import com.ras.proto.KillSessionCommand
import com.ras.proto.ListSessionsCommand
import com.ras.proto.RasCommand
import com.ras.proto.RefreshAgentsCommand
import com.ras.proto.RenameSessionCommand
import com.ras.proto.Session
import com.ras.proto.SessionCommand
import com.ras.proto.SessionEvent as ProtoSessionEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SessionRepository"

/**
 * Repository for session management.
 * Subscribes to ConnectionManager for receiving session events.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val connectionManager: ConnectionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    // Exception handler to prevent silent failures in coroutines
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG, "Uncaught exception in SessionRepository scope", exception)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)

    // Mutex to synchronize state updates from InitialState and SessionEvents
    // Prevents race conditions when both arrive nearly simultaneously
    private val stateMutex = Mutex()

    // Session state
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    // Agents state
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents.asStateFlow()

    // Events for one-time notifications
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    // Connection state - derived from ConnectionManager
    val isConnected: StateFlow<Boolean> = connectionManager.isConnected

    init {
        // Subscribe to ConnectionManager's session events
        subscribeToSessionEvents()
        // Subscribe to InitialState for startup data
        subscribeToInitialState()
    }

    /**
     * Subscribe to session events from ConnectionManager.
     * Uses mutex to synchronize with InitialState updates.
     */
    private fun subscribeToSessionEvents() {
        scope.launch {
            connectionManager.sessionEvents.collect { event ->
                stateMutex.withLock {
                    processEvent(event)
                }
            }
        }
    }

    /**
     * Subscribe to InitialState from ConnectionManager.
     * InitialState is sent once when connection is established.
     * Uses mutex to synchronize with session event updates.
     */
    private fun subscribeToInitialState() {
        scope.launch {
            connectionManager.initialState.collect { initialState ->
                stateMutex.withLock {
                    Log.i(TAG, "Received InitialState: ${initialState.sessionsCount} sessions, ${initialState.agentsCount} agents")

                    // Update sessions (always update, even if empty, to clear stale data)
                    try {
                        val sessions = initialState.sessionsList.map { it.toDomain() }
                        Log.i(TAG, "Mapped ${sessions.size} sessions, updating _sessions")
                        _sessions.value = sessions
                        Log.i(TAG, "_sessions updated, now has ${_sessions.value.size} sessions")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error mapping sessions", e)
                    }

                    // Update agents
                    if (initialState.agentsCount > 0) {
                        try {
                            val agents = initialState.agentsList.map { it.toDomain() }
                            Log.i(TAG, "Mapped ${agents.size} agents")
                            _agents.value = agents
                            _events.tryEmit(SessionEvent.AgentsLoaded(agents))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error mapping agents", e)
                        }
                    }
                }
            }
        }
    }

    private fun processEvent(event: ProtoSessionEvent) {
        try {
            // Each handler is wrapped to prevent one failure from stopping event processing
            when {
                event.hasList() -> runCatching { handleSessionList(event.list) }
                event.hasCreated() -> runCatching { handleSessionCreated(event.created) }
                event.hasKilled() -> runCatching { handleSessionKilled(event.killed) }
                event.hasRenamed() -> runCatching { handleSessionRenamed(event.renamed) }
                event.hasActivity() -> runCatching { handleSessionActivity(event.activity) }
                event.hasError() -> runCatching { handleSessionError(event.error) }
                event.hasAgents() -> runCatching { handleAgentsList(event.agents) }
                event.hasDirectories() -> runCatching { handleDirectoriesList(event.directories) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing session event", e)
        }
    }

    private fun handleSessionList(event: com.ras.proto.SessionListEvent) {
        _sessions.value = event.sessionsList.map { it.toDomain() }
    }

    private fun handleSessionCreated(event: com.ras.proto.SessionCreatedEvent) {
        val session = event.session.toDomain()
        // Check if session already exists to prevent duplicates
        // This can happen if SessionListEvent arrives before SessionCreatedEvent
        if (_sessions.value.none { it.id == session.id }) {
            _sessions.value = _sessions.value + session
        }
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
        val agents = event.agentsList.map { it.toDomain() }
        _agents.value = agents
        _events.tryEmit(SessionEvent.AgentsLoaded(agents))
    }

    private fun handleDirectoriesList(event: com.ras.proto.DirectoriesListEvent) {
        val entries = event.entriesList.map { it.toDomain() }
        val recent = event.recentList.toList()
        _events.tryEmit(
            SessionEvent.DirectoriesLoaded(
                parent = event.parent,
                entries = entries,
                recentDirectories = recent
            )
        )
    }

    // ==========================================================================
    // Commands (Phone -> Daemon)
    // ==========================================================================

    /**
     * Request the current session list from daemon.
     */
    suspend fun listSessions() {
        Log.i(TAG, "listSessions: sending ListSessionsCommand")
        sendCommand(
            SessionCommand.newBuilder()
                .setList(ListSessionsCommand.getDefaultInstance())
                .build()
        )
        Log.i(TAG, "listSessions: command sent")
    }

    /**
     * Create a new session.
     *
     * @param directory The working directory (must be valid absolute path)
     * @param agent The agent binary name (must be valid agent name)
     * @throws IllegalArgumentException if directory or agent is invalid
     */
    suspend fun createSession(directory: String, agent: String) {
        DirectoryPathValidator.requireValid(directory)
        AgentNameValidator.requireValid(agent)
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
     *
     * @param sessionId The session ID (must be valid 12-char alphanumeric)
     * @throws IllegalArgumentException if sessionId is invalid
     */
    suspend fun killSession(sessionId: String) {
        SessionIdValidator.requireValid(sessionId)
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
     *
     * @param sessionId The session ID (must be valid 12-char alphanumeric)
     * @param newName The new display name (1-64 chars, alphanumeric/dash/underscore/space)
     * @throws IllegalArgumentException if sessionId or newName is invalid
     */
    suspend fun renameSession(sessionId: String, newName: String) {
        SessionIdValidator.requireValid(sessionId)
        DisplayNameValidator.requireValid(newName)
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

    /**
     * Send a command to the daemon.
     * Commands are wrapped in RasCommand for proper routing.
     *
     * @throws IllegalStateException if not connected
     */
    private suspend fun sendCommand(command: SessionCommand) {
        Log.d(TAG, "sendCommand: isConnected=${connectionManager.isConnected.value}")
        if (!connectionManager.isConnected.value) {
            Log.e(TAG, "sendCommand: Not connected to daemon!")
            throw IllegalStateException("Not connected to daemon")
        }
        // Wrap in RasCommand for proper message envelope
        val rasCommand = RasCommand.newBuilder()
            .setSession(command)
            .build()
        connectionManager.send(rasCommand.toByteArray())
        Log.d(TAG, "sendCommand: sent ${rasCommand.serializedSize} bytes")
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
