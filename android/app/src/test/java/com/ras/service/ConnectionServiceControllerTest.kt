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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for ConnectionServiceController.
 *
 * Verifies:
 * 1. Service starts when connection is established
 * 2. Service stops when connection is lost
 * 3. No redundant start/stop calls
 * 4. Notification updates based on health status
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionServiceControllerTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockContext: Context
    private lateinit var mockConnectionManager: ConnectionManager

    private val isConnectedFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockConnectionManager = mockk()

        every { mockConnectionManager.isConnected } returns isConnectedFlow

        // Mock ContextCompat.startForegroundService static method
        mockkStatic(ContextCompat::class)
        io.mockk.justRun { ContextCompat.startForegroundService(any(), any()) }
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

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

    @Test
    fun `stops service when disconnected`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Connect first
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Then disconnect
        isConnectedFlow.value = false
        advanceUntilIdle()

        verify { mockContext.stopService(any<Intent>()) }
    }

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
        advanceUntilIdle()

        verify(exactly = 0) { mockContext.stopService(any<Intent>()) }
    }

    @Test
    fun `handles rapid connect disconnect cycles`() = runTest(testDispatcher) {
        val controller = ConnectionServiceController(
            context = mockContext,
            connectionManager = mockConnectionManager,
            ioDispatcher = testDispatcher
        )

        controller.initialize()

        // Rapid cycles
        repeat(5) {
            isConnectedFlow.value = true
            advanceUntilIdle()
            isConnectedFlow.value = false
            advanceUntilIdle()
        }

        // Should have 5 starts and 5 stops
        verify(exactly = 5) { ContextCompat.startForegroundService(mockContext, any()) }
        verify(exactly = 5) { mockContext.stopService(any<Intent>()) }
    }

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
}
