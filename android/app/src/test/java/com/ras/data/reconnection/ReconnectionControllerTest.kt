package com.ras.data.reconnection

import com.ras.data.connection.ConnectionError
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.domain.startup.ReconnectionResult
import com.ras.lifecycle.AppLifecycleObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for ReconnectionController.
 *
 * Verifies:
 * 1. Guards against duplicate reconnection attempts
 * 2. Guards when already connected
 * 3. Guards when no credentials
 * 4. Guards when user manually disconnected
 * 5. Auto-reconnect on foreground
 * 6. Auto-reconnect on connection error
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectionControllerTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var controller: ReconnectionController
    private lateinit var mockConnectionManager: ConnectionManager
    private lateinit var mockReconnectionService: ReconnectionService
    private lateinit var mockCredentialRepository: CredentialRepository
    private lateinit var mockKeyManager: KeyManager
    private lateinit var mockLifecycleObserver: AppLifecycleObserver

    private val isConnectedFlow = MutableStateFlow(false)
    private val appInForegroundFlow = MutableStateFlow(true)
    private val connectionErrorsFlow = MutableSharedFlow<ConnectionError>()

    @Before
    fun setup() {
        mockConnectionManager = mockk(relaxed = true)
        mockReconnectionService = mockk()
        mockCredentialRepository = mockk()
        mockKeyManager = mockk()
        mockLifecycleObserver = mockk()

        every { mockConnectionManager.isConnected } returns isConnectedFlow
        every { mockConnectionManager.connectionErrors } returns connectionErrorsFlow
        every { mockLifecycleObserver.appInForeground } returns appInForegroundFlow
        coEvery { mockCredentialRepository.hasCredentials() } returns true
        coEvery { mockKeyManager.isDisconnectedOnce() } returns false
        coEvery { mockReconnectionService.reconnect(any()) } returns ReconnectionResult.Success

        controller = ReconnectionController(
            connectionManager = mockConnectionManager,
            reconnectionService = mockReconnectionService,
            credentialRepository = mockCredentialRepository,
            keyManager = mockKeyManager,
            appLifecycleObserver = mockLifecycleObserver,
            ioDispatcher = testDispatcher
        )
    }

    // ============================================================================
    // SECTION 1: Guard Tests
    // ============================================================================

    @Test
    fun `attemptReconnectIfNeeded does nothing when already connected`() = runTest(testDispatcher) {
        isConnectedFlow.value = true

        val result = controller.attemptReconnectIfNeeded()

        assertFalse(result)
        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Test
    fun `attemptReconnectIfNeeded does nothing when no credentials`() = runTest(testDispatcher) {
        coEvery { mockCredentialRepository.hasCredentials() } returns false

        val result = controller.attemptReconnectIfNeeded()

        assertFalse(result)
        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Test
    fun `attemptReconnectIfNeeded does nothing when user manually disconnected`() = runTest(testDispatcher) {
        coEvery { mockKeyManager.isDisconnectedOnce() } returns true

        val result = controller.attemptReconnectIfNeeded()

        assertFalse(result)
        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Test
    fun `attemptReconnectIfNeeded prevents duplicate attempts`() = runTest(testDispatcher) {
        // Make reconnection take a while
        coEvery { mockReconnectionService.reconnect(any()) } coAnswers {
            delay(1000)
            ReconnectionResult.Success
        }

        // Start first attempt
        launch { controller.attemptReconnectIfNeeded() }
        advanceTimeBy(100)

        // Try second attempt while first is running
        val secondResult = controller.attemptReconnectIfNeeded()

        assertFalse("Second attempt should be rejected", secondResult)
        advanceUntilIdle()
        coVerify(exactly = 1) { mockReconnectionService.reconnect(any()) }
    }

    @Test
    fun `attemptReconnectIfNeeded succeeds when all conditions met`() = runTest(testDispatcher) {
        val result = controller.attemptReconnectIfNeeded()

        assertTrue(result)
        coVerify(exactly = 1) { mockReconnectionService.reconnect(any()) }
    }

    // ============================================================================
    // SECTION 2: Auto-Reconnect on Foreground
    // ============================================================================

    @Test
    fun `does not reconnect on initialize due to initial foreground value`() = runTest(testDispatcher) {
        // appInForegroundFlow starts as true (default)
        controller.initialize()
        advanceUntilIdle()

        // Should NOT have triggered reconnection from initial value
        // (StartupViewModel handles initial connection)
        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Test
    fun `reconnects when app comes to foreground and disconnected`() = runTest(testDispatcher) {
        controller.initialize()

        // App goes to background
        appInForegroundFlow.value = false
        advanceUntilIdle()

        // App comes to foreground
        appInForegroundFlow.value = true
        advanceUntilIdle()

        coVerify { mockReconnectionService.reconnect(any()) }
    }

    @Test
    fun `does not reconnect on foreground when already connected`() = runTest(testDispatcher) {
        isConnectedFlow.value = true

        controller.initialize()

        // App goes to background then foreground
        appInForegroundFlow.value = false
        advanceUntilIdle()
        appInForegroundFlow.value = true
        advanceUntilIdle()

        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Test
    fun `does not reconnect on foreground when user manually disconnected`() = runTest(testDispatcher) {
        coEvery { mockKeyManager.isDisconnectedOnce() } returns true

        controller.initialize()

        // App goes to background then foreground
        appInForegroundFlow.value = false
        advanceUntilIdle()
        appInForegroundFlow.value = true
        advanceUntilIdle()

        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    // ============================================================================
    // SECTION 3: Auto-Reconnect on Connection Error
    // ============================================================================

    @Test
    fun `reconnects when connection error occurs`() = runTest(testDispatcher) {
        controller.initialize()
        advanceUntilIdle()

        // Emit connection error
        connectionErrorsFlow.emit(ConnectionError.Disconnected("Network lost"))
        advanceUntilIdle()

        coVerify { mockReconnectionService.reconnect(any()) }
    }

    @Test
    fun `does not reconnect on connection error when user manually disconnected`() = runTest(testDispatcher) {
        coEvery { mockKeyManager.isDisconnectedOnce() } returns true

        controller.initialize()
        advanceUntilIdle()

        // Emit connection error
        connectionErrorsFlow.emit(ConnectionError.Disconnected("Network lost"))
        advanceUntilIdle()

        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    // ============================================================================
    // SECTION 4: State Exposure
    // ============================================================================

    @Test
    fun `isReconnecting is true during reconnection`() = runTest(testDispatcher) {
        var wasReconnecting = false

        coEvery { mockReconnectionService.reconnect(any()) } coAnswers {
            wasReconnecting = controller.isReconnecting.value
            delay(100)
            ReconnectionResult.Success
        }

        controller.attemptReconnectIfNeeded()
        advanceUntilIdle()

        assertTrue("isReconnecting should be true during reconnection", wasReconnecting)
        assertFalse("isReconnecting should be false after reconnection", controller.isReconnecting.value)
    }

    @Test
    fun `isReconnecting starts as false`() = runTest(testDispatcher) {
        assertFalse(controller.isReconnecting.value)
    }

    // ============================================================================
    // SECTION 5: Failure Handling
    // ============================================================================

    @Test
    fun `attemptReconnectIfNeeded returns false on failure`() = runTest(testDispatcher) {
        coEvery { mockReconnectionService.reconnect(any()) } returns
            ReconnectionResult.Failure.NetworkError

        val result = controller.attemptReconnectIfNeeded()

        assertFalse(result)
    }

    @Test
    fun `isReconnecting resets to false on failure`() = runTest(testDispatcher) {
        coEvery { mockReconnectionService.reconnect(any()) } returns
            ReconnectionResult.Failure.NetworkError

        controller.attemptReconnectIfNeeded()
        advanceUntilIdle()

        assertFalse(controller.isReconnecting.value)
    }
}
