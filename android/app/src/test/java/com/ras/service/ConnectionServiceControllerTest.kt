package com.ras.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ras.data.connection.ConnectionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for ConnectionServiceController.
 *
 * Verifies:
 * 1. Service starts when connection is established
 * 2. Service stops when connection is lost (with debounce)
 * 3. No redundant start/stop calls
 * 4. Debounce cancellation on reconnect
 * 5. Proper handling of rapid connection cycles
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionServiceControllerTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockContext: Context
    private lateinit var mockConnectionManager: ConnectionManager

    private val isConnectedFlow = MutableStateFlow(false)

    // Debounce delay constant from ConnectionServiceController
    private val STOP_DELAY_MS = 3000L

    @BeforeEach
    fun setup() {
        // Set Main dispatcher for tests (service calls use withContext(Dispatchers.Main))
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true)
        mockConnectionManager = mockk()

        every { mockConnectionManager.isConnected } returns isConnectedFlow

        // Mock ContextCompat.startForegroundService static method
        mockkStatic(ContextCompat::class)
        io.mockk.justRun { ContextCompat.startForegroundService(any(), any()) }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(ContextCompat::class)
    }

    // ==========================================================================
    // Basic Service Lifecycle
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `starts service when connection established`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { ContextCompat.startForegroundService(mockContext, any()) }
    }

    @Tag("unit")
    @Test
    fun `stops service when disconnected after debounce delay`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect first
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Disconnect - use runCurrent() to process the flow emission without advancing time
        isConnectedFlow.value = false
        runCurrent()

        // Service should NOT be stopped yet (debounce delay hasn't elapsed)
        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }

        // Advance past debounce delay
        advanceTimeBy(STOP_DELAY_MS + 100)
        runCurrent()

        // Now service should be stopped
        verify(exactly = 1) { mockContext.stopService(any<Intent>()) }
    }

    @Tag("unit")
    @Test
    fun `does not start service when already started`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect multiple times
        isConnectedFlow.value = true
        advanceUntilIdle()
        isConnectedFlow.value = true
        advanceUntilIdle()
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Should only start once
        verify(exactly = 1) { ContextCompat.startForegroundService(mockContext, any()) }
    }

    @Tag("unit")
    @Test
    fun `does not stop service when already stopped`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Never connected, so nothing to stop
        isConnectedFlow.value = false
        runCurrent()
        advanceTimeBy(STOP_DELAY_MS + 100)
        runCurrent()

        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }
    }

    @Tag("unit")
    @Test
    fun `does nothing before initialize is called`() = runTest(testDispatcher) {
        ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        // Don't call initialize

        isConnectedFlow.value = true
        advanceUntilIdle()

        verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
    }

    // ==========================================================================
    // Debounce Behavior
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `reconnect before debounce delay cancels pending stop`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Disconnect
        isConnectedFlow.value = false
        runCurrent()

        // Wait less than debounce delay
        advanceTimeBy(1000)
        runCurrent()

        // Reconnect before debounce completes
        isConnectedFlow.value = true
        runCurrent()

        // Wait past the original debounce time
        advanceTimeBy(STOP_DELAY_MS + 100)
        runCurrent()

        // Service should NOT have been stopped (reconnect cancelled the pending stop)
        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }
    }

    @Tag("unit")
    @Test
    fun `service stops after debounce delay when still disconnected`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Disconnect
        isConnectedFlow.value = false
        runCurrent()

        // Advance time to just before debounce delay
        advanceTimeBy(STOP_DELAY_MS - 100)
        runCurrent()

        // Still should NOT be stopped
        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }

        // Advance past debounce delay
        advanceTimeBy(200)
        runCurrent()

        // Now should be stopped
        verify(exactly = 1) { mockContext.stopService(any<Intent>()) }
    }

    @Tag("unit")
    @Test
    fun `multiple disconnects within debounce resets timer`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect
        isConnectedFlow.value = true
        advanceUntilIdle()

        // First disconnect
        isConnectedFlow.value = false
        runCurrent()

        // Wait 2 seconds
        advanceTimeBy(2000)
        runCurrent()

        // Quick reconnect/disconnect cycle
        isConnectedFlow.value = true
        runCurrent()
        isConnectedFlow.value = false
        runCurrent()

        // Wait another 2 seconds (total 4 seconds since first disconnect)
        advanceTimeBy(2000)
        runCurrent()

        // Still should NOT be stopped (second disconnect reset the timer)
        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }

        // Wait remaining time to trigger stop
        advanceTimeBy(STOP_DELAY_MS - 2000 + 100)
        runCurrent()

        // Now should be stopped
        verify(exactly = 1) { mockContext.stopService(any<Intent>()) }
    }

    // ==========================================================================
    // Rapid Connection Cycles
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `handles rapid connect disconnect without debounce completion`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Rapid cycles (each within debounce window)
        // Service starts on first connect, stays running since debounce never completes
        repeat(5) {
            isConnectedFlow.value = true
            runCurrent()
            isConnectedFlow.value = false
            runCurrent()
            advanceTimeBy(500) // Less than debounce delay
            runCurrent()
        }

        // Should have started only ONCE (service stays running through rapid cycles)
        verify(exactly = 1) { ContextCompat.startForegroundService(mockContext, any()) }

        // No stops yet (all within debounce window and last one still pending)
        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }

        // Now let debounce complete
        advanceTimeBy(STOP_DELAY_MS + 100)
        runCurrent()

        // Only 1 stop (the last pending one)
        verify(exactly = 1) { mockContext.stopService(any<Intent>()) }
    }

    @Tag("unit")
    @Test
    fun `handles rapid connect disconnect with debounce completion`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Cycle 1: connect, disconnect, wait for debounce
        isConnectedFlow.value = true
        runCurrent()
        isConnectedFlow.value = false
        runCurrent()
        advanceTimeBy(STOP_DELAY_MS + 100)
        runCurrent()

        // Cycle 2: connect, disconnect, wait for debounce
        isConnectedFlow.value = true
        runCurrent()
        isConnectedFlow.value = false
        runCurrent()
        advanceTimeBy(STOP_DELAY_MS + 100)
        runCurrent()

        // Should have 2 starts and 2 stops
        verify(exactly = 2) { ContextCompat.startForegroundService(mockContext, any()) }
        verify(exactly = 2) { mockContext.stopService(any<Intent>()) }
    }

    // ==========================================================================
    // Edge Cases
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `reconnect immediately after disconnect cancels stop`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Immediate disconnect and reconnect (network hiccup scenario)
        isConnectedFlow.value = false
        runCurrent()
        isConnectedFlow.value = true
        runCurrent()

        // Wait past debounce
        advanceTimeBy(STOP_DELAY_MS + 100)
        runCurrent()

        // Service should NOT have stopped
        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }
        // Should only have started once (reconnect doesn't re-start)
        verify(exactly = 1) { ContextCompat.startForegroundService(mockContext, any()) }
    }

    @Tag("unit")
    @Test
    fun `service not stopped if reconnected after partial debounce`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Disconnect
        isConnectedFlow.value = false
        runCurrent()

        // Wait almost the full debounce (2999ms of 3000ms)
        advanceTimeBy(STOP_DELAY_MS - 1)
        runCurrent()

        // Reconnect at last moment
        isConnectedFlow.value = true
        runCurrent()

        // Wait long past original debounce
        advanceTimeBy(STOP_DELAY_MS * 2)
        runCurrent()

        // Should never have stopped
        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }
    }

    @Tag("unit")
    @Test
    fun `multiple initializations are safe`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        // Initialize multiple times (defensive coding scenario)
        controller.initialize()
        controller.initialize()
        controller.initialize()

        isConnectedFlow.value = true
        advanceUntilIdle()

        // Each initialization creates a new collector, so we might get multiple starts
        // This test documents the behavior (may want to add idempotency guard)
        verify(atLeast = 1) { ContextCompat.startForegroundService(mockContext, any()) }
    }

    @Tag("unit")
    @Test
    fun `stop not triggered when connection restored just before debounce completes`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Disconnect
        isConnectedFlow.value = false
        runCurrent()

        // Advance to just before debounce time (1ms before)
        advanceTimeBy(STOP_DELAY_MS - 1)
        runCurrent()

        // Reconnect before debounce completes
        isConnectedFlow.value = true
        runCurrent()

        // Now advance past the original debounce time
        advanceTimeBy(100)
        runCurrent()

        // Should not have stopped (reconnected cancelled the pending stop)
        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }
    }
}
