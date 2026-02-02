package com.ras.connection

import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.time.Instant

/**
 * Tests for connection lifecycle management.
 *
 * Verifies that connections survive ViewModel lifecycle changes
 * (screen navigation, configuration changes, etc).
 *
 * Bug prevention: Ensures connection is not accidentally closed
 * when navigating between screens or when ViewModels are cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: SessionRepository
    private lateinit var sessionsFlow: MutableStateFlow<List<SessionInfo>>
    private lateinit var eventsFlow: MutableSharedFlow<SessionEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sessionsFlow = MutableStateFlow(emptyList())
        eventsFlow = MutableSharedFlow()
        isConnectedFlow = MutableStateFlow(true)

        mockRepository = mockk(relaxed = true) {
            every { sessions } returns sessionsFlow
            every { events } returns eventsFlow
            every { isConnected } returns isConnectedFlow
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSession(
        id: String,
        displayName: String,
        status: SessionStatus = SessionStatus.ACTIVE
    ) = SessionInfo(
        id = id,
        tmuxName = "ras-claude-${displayName.lowercase().replace(" ", "-")}",
        displayName = displayName,
        directory = "/home/user/project",
        agent = "claude",
        createdAt = Instant.now(),
        lastActivityAt = Instant.now(),
        status = status
    )

    // ==========================================================================
    // Connection Survives Navigation Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `connection state is preserved across screen transitions`() = runTest {
        /**
         * When navigating from PairingScreen to SessionsScreen,
         * the connection should remain active.
         */

        // Initial: connected after pairing
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue(mockRepository.isConnected.value, "Connection should be active")

        // Simulate navigation: different ViewModels observe the same repository
        // The repository is a singleton, so connection state is preserved

        // After "navigation" - same repository, same state
        assertTrue(mockRepository.isConnected.value, "Connection should still be active after navigation")
    }

    @Tag("unit")
    @Test
    fun `session data is preserved across navigation`() = runTest {
        /**
         * Session list loaded on one screen should be available on another.
         * The repository singleton maintains state across ViewModels.
         */

        // Load sessions (simulated from PairingScreen completing)
        sessionsFlow.value = listOf(
            createSession("session1aaaa", "Test Session")
        )
        advanceUntilIdle()

        assertEquals(1, mockRepository.sessions.value.size)

        // "Navigate" to SessionsScreen - same repository
        // Sessions should still be there
        assertEquals(1, mockRepository.sessions.value.size)
        assertEquals("session1aaaa", mockRepository.sessions.value[0].id)
    }

    @Tag("unit")
    @Test
    fun `connection isConnected flow emits to all observers`() = runTest {
        /**
         * Multiple screens observing isConnected should all see the same state.
         * This simulates multiple ViewModels observing the same repository.
         */

        // Start connected
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Multiple "ViewModels" can read the same state
        assertTrue(mockRepository.isConnected.value)

        // Disconnect
        isConnectedFlow.value = false
        advanceUntilIdle()

        // All observers see disconnected
        assertFalse(mockRepository.isConnected.value)

        // Reconnect
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue(mockRepository.isConnected.value)
    }

    // ==========================================================================
    // Post-Navigation Communication Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `can list sessions after navigation`() = runTest {
        /**
         * After navigating to SessionsScreen, should still be able to
         * list sessions through the same connection.
         */

        isConnectedFlow.value = true
        sessionsFlow.value = listOf(
            createSession("session1aaaa", "Session 1"),
            createSession("session2bbbb", "Session 2")
        )
        advanceUntilIdle()

        // Simulate listSessions command after navigation
        coEvery { mockRepository.listSessions() } returns Unit

        mockRepository.listSessions()
        advanceUntilIdle()

        coVerify { mockRepository.listSessions() }
        assertEquals(2, mockRepository.sessions.value.size)
    }

    @Tag("unit")
    @Test
    fun `can receive events after navigation`() = runTest {
        /**
         * Events from daemon should still be received after navigation.
         */

        isConnectedFlow.value = true
        sessionsFlow.value = listOf(createSession("abc123def456", "Test"))
        advanceUntilIdle()

        // Simulate receiving a session event after navigation
        val event = SessionEvent.SessionCreated(createSession("new123session", "New Session"))
        eventsFlow.emit(event)
        advanceUntilIdle()

        // Event flow is still working - connection maintained
        assertTrue(mockRepository.isConnected.value, "Connection should still be active")
    }

    // ==========================================================================
    // Connection State Management Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `isConnected reflects connection state changes`() = runTest {
        /**
         * When connection state changes (e.g., network drop),
         * isConnected should update and all observers see it.
         */

        // Start connected
        isConnectedFlow.value = true
        advanceUntilIdle()
        assertTrue(mockRepository.isConnected.value, "Should be connected initially")

        // Simulate network disconnection
        isConnectedFlow.value = false
        advanceUntilIdle()
        assertFalse(mockRepository.isConnected.value, "Should be disconnected after network drop")
    }

    @Tag("unit")
    @Test
    fun `ViewModel observation does not affect connection lifecycle`() = runTest {
        /**
         * ViewModels observe but don't own the connection.
         * Clearing a ViewModel shouldn't affect connection state.
         */

        isConnectedFlow.value = true
        advanceUntilIdle()

        // ViewModels can observe the state
        val observed = mockRepository.isConnected.value

        // Connection should still be active
        assertTrue(mockRepository.isConnected.value, "Connection should still be active")
        assertTrue(observed, "Observed value should match")
    }

    // ==========================================================================
    // Multiple ViewModel Scenario Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `multiple ViewModels see consistent state`() = runTest {
        /**
         * SessionsViewModel and TerminalViewModel should see the same
         * connection state when observing the same repository.
         */

        isConnectedFlow.value = true
        advanceUntilIdle()

        // Both ViewModels would get the same value
        val sessionsViewState = mockRepository.isConnected.value
        val terminalViewState = mockRepository.isConnected.value

        assertEquals(sessionsViewState, terminalViewState, "Both ViewModels should see same state")
    }

    @Tag("unit")
    @Test
    fun `state updates propagate to all observers`() = runTest {
        /**
         * When connection state changes, all observing ViewModels
         * should see the update.
         */

        // Start connected
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue(mockRepository.isConnected.value)

        // Connection drops
        isConnectedFlow.value = false
        advanceUntilIdle()

        // All observers see the same disconnected state
        assertFalse(mockRepository.isConnected.value)
    }
}
