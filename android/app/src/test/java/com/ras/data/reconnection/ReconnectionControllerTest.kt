package com.ras.data.reconnection

import com.ras.data.connection.ConnectionError
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.time.Instant

/**
 * Tests for ReconnectionController.
 *
 * Verifies:
 * 1. Guards against duplicate reconnection attempts
 * 2. Guards when already connected
 * 3. Guards when no credentials (no selected device)
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

    private val mockDevice = PairedDevice(
        deviceId = "test-device-123",
        masterSecret = ByteArray(32),
        deviceName = "Test Device",
        deviceType = DeviceType.DESKTOP,
        status = DeviceStatus.PAIRED,
        isSelected = true,
        pairedAt = Instant.now()
    )

    @BeforeEach
    fun setup() {
        mockConnectionManager = mockk(relaxed = true)
        mockReconnectionService = mockk()
        mockCredentialRepository = mockk()
        mockKeyManager = mockk()
        mockLifecycleObserver = mockk()

        every { mockConnectionManager.isConnected } returns isConnectedFlow
        every { mockConnectionManager.connectionErrors } returns connectionErrorsFlow
        every { mockLifecycleObserver.appInForeground } returns appInForegroundFlow
        coEvery { mockCredentialRepository.getSelectedDevice() } returns mockDevice
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

    @Tag("unit")
    @Test
    fun `attemptReconnectIfNeeded does nothing when already connected`() = runTest(testDispatcher) {
        isConnectedFlow.value = true

        val result = controller.attemptReconnectIfNeeded()

        assertFalse(result)
        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
    @Test
    fun `attemptReconnectIfNeeded does nothing when no credentials`() = runTest(testDispatcher) {
        coEvery { mockCredentialRepository.getSelectedDevice() } returns null

        val result = controller.attemptReconnectIfNeeded()

        assertFalse(result)
        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
    @Test
    fun `attemptReconnectIfNeeded does nothing when user manually disconnected`() = runTest(testDispatcher) {
        coEvery { mockKeyManager.isDisconnectedOnce() } returns true

        val result = controller.attemptReconnectIfNeeded()

        assertFalse(result)
        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
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

        assertFalse(secondResult, "Second attempt should be rejected")
        advanceUntilIdle()
        coVerify(exactly = 1) { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
    @Test
    fun `attemptReconnectIfNeeded succeeds when all conditions met`() = runTest(testDispatcher) {
        val result = controller.attemptReconnectIfNeeded()

        assertTrue(result)
        coVerify(exactly = 1) { mockReconnectionService.reconnect(any()) }
    }

    // ============================================================================
    // SECTION 2: Auto-Reconnect on Foreground
    // ============================================================================

    @Tag("unit")
    @Test
    fun `does not reconnect on initialize due to initial foreground value`() = runTest(testDispatcher) {
        // appInForegroundFlow starts as true (default)
        controller.initialize()
        advanceUntilIdle()

        // Should NOT have triggered reconnection from initial value
        // (StartupViewModel handles initial connection)
        coVerify(exactly = 0) { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
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

    @Tag("unit")
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

    @Tag("unit")
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

    @Tag("unit")
    @Test
    fun `reconnects when connection error occurs`() = runTest(testDispatcher) {
        controller.initialize()
        advanceUntilIdle()

        // Emit connection error
        connectionErrorsFlow.emit(ConnectionError.Disconnected("Network lost"))
        advanceUntilIdle()

        coVerify { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
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

    @Tag("unit")
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

        assertTrue(wasReconnecting, "isReconnecting should be true during reconnection")
        assertFalse(controller.isReconnecting.value, "isReconnecting should be false after reconnection")
    }

    @Tag("unit")
    @Test
    fun `isReconnecting starts as false`() = runTest(testDispatcher) {
        assertFalse(controller.isReconnecting.value)
    }

    // ============================================================================
    // SECTION 5: Failure Handling
    // ============================================================================

    @Tag("unit")
    @Test
    fun `attemptReconnectIfNeeded returns false on failure`() = runTest(testDispatcher) {
        coEvery { mockReconnectionService.reconnect(any()) } returns
            ReconnectionResult.Failure.NetworkError

        val result = controller.attemptReconnectIfNeeded()

        assertFalse(result)
    }

    @Tag("unit")
    @Test
    fun `isReconnecting resets to false on failure`() = runTest(testDispatcher) {
        coEvery { mockReconnectionService.reconnect(any()) } returns
            ReconnectionResult.Failure.NetworkError

        controller.attemptReconnectIfNeeded()
        advanceUntilIdle()

        assertFalse(controller.isReconnecting.value)
    }

    // ============================================================================
    // SECTION 6: Mutex-Based Race Condition Prevention
    // ============================================================================

    @Tag("unit")
    @Test
    fun `concurrent reconnection attempts from foreground and error are deduplicated`() = runTest(testDispatcher) {
        // Make reconnection slow so we can test concurrent attempts
        coEvery { mockReconnectionService.reconnect(any()) } coAnswers {
            delay(2000)
            ReconnectionResult.Success
        }

        controller.initialize()
        advanceTimeBy(100) // Let initialize complete

        // Simulate both triggers firing close together
        // (app comes to foreground + connection error at same time)
        launch {
            appInForegroundFlow.value = false
            advanceTimeBy(50)
            appInForegroundFlow.value = true
        }
        launch {
            advanceTimeBy(100)
            connectionErrorsFlow.emit(ConnectionError.Disconnected("Network lost"))
        }

        advanceUntilIdle()

        // Only ONE reconnection should have happened despite two triggers
        coVerify(exactly = 1) { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
    @Test
    fun `mutex prevents multiple simultaneous reconnection attempts`() = runTest(testDispatcher) {
        var reconnectCallCount = 0

        coEvery { mockReconnectionService.reconnect(any()) } coAnswers {
            reconnectCallCount++
            delay(500)
            ReconnectionResult.Success
        }

        // Launch 5 concurrent reconnection attempts
        repeat(5) {
            launch { controller.attemptReconnectIfNeeded() }
        }

        advanceUntilIdle()

        // Only one should have succeeded due to mutex
        assertTrue(reconnectCallCount == 1, "Only one reconnect call should be made")
    }

    @Tag("unit")
    @Test
    fun `mutex is released after reconnection completes allowing subsequent attempts`() = runTest(testDispatcher) {
        coEvery { mockReconnectionService.reconnect(any()) } coAnswers {
            delay(100)
            ReconnectionResult.Success
        }

        // First attempt
        val firstResult = controller.attemptReconnectIfNeeded()
        advanceUntilIdle()
        assertTrue(firstResult, "First attempt should succeed")

        // Disconnect so we can reconnect again
        isConnectedFlow.value = false

        // Second attempt after first completes
        val secondResult = controller.attemptReconnectIfNeeded()
        advanceUntilIdle()
        assertTrue(secondResult, "Second attempt should succeed after first completes")

        coVerify(exactly = 2) { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
    @Test
    fun `mutex is released even on reconnection failure`() = runTest(testDispatcher) {
        coEvery { mockReconnectionService.reconnect(any()) } returns
            ReconnectionResult.Failure.NetworkError

        // First attempt (fails)
        val firstResult = controller.attemptReconnectIfNeeded()
        advanceUntilIdle()
        assertFalse(firstResult, "First attempt should fail")

        // Second attempt should be able to proceed
        controller.attemptReconnectIfNeeded()
        advanceUntilIdle()

        // Both attempts should have been made
        coVerify(exactly = 2) { mockReconnectionService.reconnect(any()) }
    }

    @Tag("unit")
    @Test
    fun `tryLock provides fail-fast behavior for concurrent attempts`() = runTest(testDispatcher) {
        val attemptResults = mutableListOf<Boolean>()

        coEvery { mockReconnectionService.reconnect(any()) } coAnswers {
            delay(1000)
            ReconnectionResult.Success
        }

        // Start first attempt
        launch {
            val result = controller.attemptReconnectIfNeeded()
            attemptResults.add(result)
        }

        // Give first attempt time to acquire mutex
        advanceTimeBy(50)

        // Try second attempt - should fail fast (not wait for mutex)
        launch {
            val result = controller.attemptReconnectIfNeeded()
            attemptResults.add(result)
        }

        // Let everything complete
        advanceUntilIdle()

        // One success (first attempt), one fail-fast (second attempt)
        assertTrue(attemptResults.size == 2, "Should have exactly 2 results")
        assertTrue(attemptResults.count { it } == 1, "One should succeed")
        assertTrue(attemptResults.count { !it } == 1, "One should fail fast")
    }
}
