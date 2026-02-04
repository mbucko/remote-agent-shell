package com.ras.ui.connecting

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.ras.data.connection.ConnectionLog
import com.ras.data.connection.ConnectionProgress
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.domain.startup.AttemptReconnectionUseCase
import com.ras.domain.startup.ReconnectionResult
import com.ras.domain.unpair.UnpairDeviceUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * TDD: Test timeout error messages for unpaired devices.
 *
 * When reconnection times out (DaemonUnreachable), the user should see a helpful
 * message suggesting the device may have been unpaired, rather than a generic timeout error.
 *
 * Scenario: Device was removed from CLI while phone was offline (disconnected or phone off).
 * Phone tries to reconnect → all HTTP methods fail → falls back to ntfy → timeout.
 * User should see: "Connection timeout. Device may have been unpaired from host."
 */
@Tag("unit")
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectingViewModelTimeoutTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var attemptReconnectionUseCase: AttemptReconnectionUseCase
    private lateinit var unpairDeviceUseCase: UnpairDeviceUseCase
    private lateinit var keyManager: KeyManager
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: ConnectingViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        credentialRepository = mockk(relaxed = true)
        attemptReconnectionUseCase = mockk(relaxed = true)
        unpairDeviceUseCase = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)

        // Mock SavedStateHandle with device ID
        savedStateHandle = SavedStateHandle(mapOf("deviceId" to "test-device-id"))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * TDD TEST 1: When reconnection times out (DaemonUnreachable), show helpful error message.
     *
     * This is the ntfy-fallback timeout scenario where the daemon was removed
     * but can't send an error response via ntfy.
     */
    @Test
    fun `when reconnection times out shows message suggesting device may be unpaired`() = runTest {
        // Given: Reconnection will fail with DaemonUnreachable (timeout)
        coEvery { attemptReconnectionUseCase(any()) } returns ReconnectionResult.Failure.DaemonUnreachable
        coEvery { credentialRepository.setSelectedDevice(any()) } returns Unit

        // When: ViewModel is created (triggers connection attempt)
        viewModel = ConnectingViewModel(
            savedStateHandle,
            credentialRepository,
            attemptReconnectionUseCase,
            unpairDeviceUseCase,
            keyManager,
            testDispatcher
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should show Failed state with helpful message
        val state = viewModel.state.value
        assertTrue(state is ConnectingState.Failed, "State should be Failed but was: ${state::class.simpleName}")

        val failedState = state as ConnectingState.Failed
        assertEquals(ReconnectionResult.Failure.DaemonUnreachable, failedState.reason)

        // Verify the error message suggests device may have been unpaired
        val message = failedState.message
        assertNotNull(message, "Failed state should have a custom message for timeout")
        assertTrue(
            message!!.contains("unpaired", ignoreCase = true) ||
            message.contains("removed", ignoreCase = true),
            "Message should mention unpair/removed but was: $message"
        )
        assertTrue(
            message.contains("timeout", ignoreCase = true) ||
            message.contains("unreachable", ignoreCase = true),
            "Message should mention timeout/unreachable but was: $message"
        )
    }

    /**
     * TDD TEST 2: When network error occurs, show different message (don't mention unpair).
     *
     * Network errors are different from daemon unreachable - this is a general connectivity issue,
     * not specific to being unpaired.
     */
    @Test
    fun `when network error occurs shows network error message without mentioning unpair`() = runTest {
        // Given: Reconnection will fail with NetworkError
        coEvery { attemptReconnectionUseCase(any()) } returns ReconnectionResult.Failure.NetworkError
        coEvery { credentialRepository.setSelectedDevice(any()) } returns Unit

        // When: ViewModel is created
        viewModel = ConnectingViewModel(
            savedStateHandle,
            credentialRepository,
            attemptReconnectionUseCase,
            unpairDeviceUseCase,
            keyManager,
            testDispatcher
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should show Failed state with network error message
        val state = viewModel.state.value
        assertTrue(state is ConnectingState.Failed)

        val failedState = state as ConnectingState.Failed
        assertEquals(ReconnectionResult.Failure.NetworkError, failedState.reason)

        // Network error message should NOT mention unpair (that's specific to DaemonUnreachable)
        val message = failedState.message
        if (message != null) {
            assertFalse(
                message.contains("unpaired", ignoreCase = true),
                "Network error message should not mention unpair: $message"
            )
        }
    }

    /**
     * TDD TEST 3: When DeviceNotFound (HTTP 404), show specific unpaired message.
     *
     * This is the direct HTTP case where daemon properly sends 404 INVALID_SESSION.
     */
    @Test
    fun `when device not found via HTTP shows specific unpaired by daemon message`() = runTest {
        // Given: Reconnection will fail with DeviceNotFound (HTTP 404)
        coEvery { attemptReconnectionUseCase(any()) } returns ReconnectionResult.Failure.DeviceNotFound
        coEvery { credentialRepository.setSelectedDevice(any()) } returns Unit

        // When: ViewModel is created
        viewModel = ConnectingViewModel(
            savedStateHandle,
            credentialRepository,
            attemptReconnectionUseCase,
            unpairDeviceUseCase,
            keyManager,
            testDispatcher
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should show Failed state with specific unpaired message
        val state = viewModel.state.value
        assertTrue(state is ConnectingState.Failed)

        val failedState = state as ConnectingState.Failed
        assertEquals(ReconnectionResult.Failure.DeviceNotFound, failedState.reason)

        // DeviceNotFound should have the existing specific message
        val message = failedState.message
        assertNotNull(message)
        assertTrue(
            message!!.contains("unpaired from daemon", ignoreCase = true),
            "DeviceNotFound message should be specific: $message"
        )
    }

    /**
     * TDD TEST 4: Retry after timeout should work.
     */
    @Test
    fun `can retry connection after timeout`() = runTest {
        // Given: First attempt times out, second succeeds
        var attemptCount = 0
        coEvery { attemptReconnectionUseCase(any()) } answers {
            if (attemptCount++ == 0) {
                ReconnectionResult.Failure.DaemonUnreachable
            } else {
                ReconnectionResult.Success
            }
        }
        coEvery { credentialRepository.setSelectedDevice(any()) } returns Unit

        viewModel = ConnectingViewModel(
            savedStateHandle,
            credentialRepository,
            attemptReconnectionUseCase,
            unpairDeviceUseCase,
            keyManager,
            testDispatcher
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // First attempt failed
        assertTrue(viewModel.state.value is ConnectingState.Failed)

        // When: User retries
        viewModel.events.test {
            viewModel.retry()
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Should navigate to sessions (success)
            val event = awaitItem()
            assertTrue(event is ConnectingUiEvent.NavigateToSessions)
        }
    }
}
