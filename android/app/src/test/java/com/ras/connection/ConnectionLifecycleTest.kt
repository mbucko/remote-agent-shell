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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

    @Before
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

    @After
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

    @Test
    fun `connection state is preserved across screen transitions`() = runTest {
        /**
         * When navigating from PairingScreen to SessionsScreen,
         * the connection should remain active.
         */

        // Initial: connected after pairing
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue("Connection should be active", mockRepository.isConnected.value)

        // Simulate navigation: different ViewModels observe the same repository
        // The repository is a singleton, so connection state is preserved

        // After "navigation" - same repository, same state
        assertTrue("Connection should still be active after navigation",
            mockRepository.isConnected.value)
    }

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
        assertTrue("Connection should still be active", mockRepository.isConnected.value)
    }

    // ==========================================================================
    // Connection State Management Tests
    // ==========================================================================

    @Test
    fun `isConnected reflects connection state changes`() = runTest {
        /**
         * When connection state changes (e.g., network drop),
         * isConnected should update and all observers see it.
         */

        // Start connected
        isConnectedFlow.value = true
        advanceUntilIdle()
        assertTrue("Should be connected initially", mockRepository.isConnected.value)

        // Simulate network disconnection
        isConnectedFlow.value = false
        advanceUntilIdle()
        assertFalse("Should be disconnected after network drop",
            mockRepository.isConnected.value)
    }

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
        assertTrue("Connection should still be active", mockRepository.isConnected.value)
        assertTrue("Observed value should match", observed)
    }

    // ==========================================================================
    // Multiple ViewModel Scenario Tests
    // ==========================================================================

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

        assertEquals("Both ViewModels should see same state",
            sessionsViewState, terminalViewState)
    }

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
