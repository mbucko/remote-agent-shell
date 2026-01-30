package com.ras.ui.startup

import com.ras.domain.startup.AttemptReconnectionUseCase
import com.ras.domain.startup.CheckCredentialsUseCase
import com.ras.domain.startup.ClearCredentialsUseCase
import com.ras.domain.startup.CredentialStatus
import com.ras.domain.startup.ReconnectionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for StartupViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var checkCredentialsUseCase: CheckCredentialsUseCase
    private lateinit var attemptReconnectionUseCase: AttemptReconnectionUseCase
    private lateinit var clearCredentialsUseCase: ClearCredentialsUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        checkCredentialsUseCase = mockk()
        attemptReconnectionUseCase = mockk()
        clearCredentialsUseCase = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = StartupViewModel(
        checkCredentialsUseCase = checkCredentialsUseCase,
        attemptReconnectionUseCase = attemptReconnectionUseCase,
        clearCredentialsUseCase = clearCredentialsUseCase
    )

    // ==========================================================================
    // Initial State Tests
    // ==========================================================================

    @Test
    fun `initial state is Loading`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.NoCredentials

        val viewModel = createViewModel()

        assertEquals(StartupState.Loading, viewModel.state.value)
    }

    @Test
    fun `init checks credentials`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.NoCredentials

        createViewModel()
        advanceUntilIdle()

        coVerify { checkCredentialsUseCase() }
    }

    // ==========================================================================
    // No Credentials Flow
    // ==========================================================================

    @Test
    fun `when no credentials navigates to pairing`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.NoCredentials

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(StartupState.NavigateToPairing, viewModel.state.value)
    }

    // ==========================================================================
    // Has Credentials Flow
    // ==========================================================================

    @Test
    fun `when has credentials attempts reconnection`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Success

        val viewModel = createViewModel()
        advanceUntilIdle()

        coVerify { attemptReconnectionUseCase() }
    }

    @Test
    fun `when has credentials state becomes Connecting`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } coAnswers {
            // Delay to observe Connecting state
            kotlinx.coroutines.delay(100)
            ReconnectionResult.Success
        }

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceTimeBy(50)

        assertTrue(viewModel.state.value is StartupState.Connecting)
    }

    @Test
    fun `when reconnection succeeds navigates to sessions`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Success

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(StartupState.NavigateToSessions, viewModel.state.value)
    }

    @Test
    fun `when reconnection fails with DaemonUnreachable shows ConnectionFailed`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Failure.DaemonUnreachable

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is StartupState.ConnectionFailed)
        assertEquals(
            ReconnectionResult.Failure.DaemonUnreachable,
            (state as StartupState.ConnectionFailed).reason
        )
    }

    @Test
    fun `when reconnection fails with AuthFailed shows ConnectionFailed`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Failure.AuthenticationFailed

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is StartupState.ConnectionFailed)
        assertEquals(
            ReconnectionResult.Failure.AuthenticationFailed,
            (state as StartupState.ConnectionFailed).reason
        )
    }

    @Test
    fun `when reconnection fails with NetworkError shows ConnectionFailed`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Failure.NetworkError

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is StartupState.ConnectionFailed)
        assertEquals(
            ReconnectionResult.Failure.NetworkError,
            (state as StartupState.ConnectionFailed).reason
        )
    }

    // ==========================================================================
    // Retry Tests
    // ==========================================================================

    @Test
    fun `retry attempts reconnection again`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Failure.DaemonUnreachable

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Reset mock to return success on retry
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Success

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(StartupState.NavigateToSessions, viewModel.state.value)
        coVerify(exactly = 2) { attemptReconnectionUseCase() }
    }

    @Test
    fun `retry sets state to Connecting`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Failure.DaemonUnreachable

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Make reconnection take some time
        coEvery { attemptReconnectionUseCase() } coAnswers {
            kotlinx.coroutines.delay(100)
            ReconnectionResult.Success
        }

        viewModel.retry()
        testDispatcher.scheduler.advanceTimeBy(50)

        assertTrue(viewModel.state.value is StartupState.Connecting)
    }

    // ==========================================================================
    // Re-pair Tests
    // ==========================================================================

    @Test
    fun `rePair clears credentials and navigates to pairing`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } returns ReconnectionResult.Failure.DaemonUnreachable

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rePair()
        advanceUntilIdle()

        coVerify { clearCredentialsUseCase() }
        assertEquals(StartupState.NavigateToPairing, viewModel.state.value)
    }

    // ==========================================================================
    // Edge Cases
    // ==========================================================================

    @Test
    fun `when check credentials throws exception navigates to pairing`() = runTest {
        coEvery { checkCredentialsUseCase() } throws RuntimeException("DataStore error")

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Should gracefully handle and navigate to pairing
        assertEquals(StartupState.NavigateToPairing, viewModel.state.value)
    }

    @Test
    fun `when reconnection throws exception shows ConnectionFailed`() = runTest {
        coEvery { checkCredentialsUseCase() } returns CredentialStatus.HasCredentials(
            deviceId = "device123",
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { attemptReconnectionUseCase() } throws RuntimeException("WebRTC error")

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is StartupState.ConnectionFailed)
        assertTrue((state as StartupState.ConnectionFailed).reason is ReconnectionResult.Failure.Unknown)
    }
}
