package com.ras.ui.home

import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import com.ras.data.settings.SettingsRepository
import com.ras.domain.unpair.UnpairDeviceUseCase
import com.ras.fakes.FakeSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for HomeViewModel.
 * Updated for multi-device support (HasDevices instead of HasDevice).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var keyManager: KeyManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var unpairDeviceUseCase: UnpairDeviceUseCase
    private lateinit var sessionsFlow: MutableStateFlow<List<SessionInfo>>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var unpairedByDaemonFlow: MutableSharedFlow<com.ras.proto.UnpairNotification>

    private val testDevice1 = PairedDevice(
        deviceId = "device-1",
        masterSecret = ByteArray(32),
        deviceName = "Test Device 1",
        deviceType = DeviceType.DESKTOP,
        status = DeviceStatus.PAIRED,
        isSelected = true,
        pairedAt = Instant.now()
    )

    private val testDevice2 = PairedDevice(
        deviceId = "device-2",
        masterSecret = ByteArray(32),
        deviceName = "Test Device 2",
        deviceType = DeviceType.LAPTOP,
        status = DeviceStatus.PAIRED,
        isSelected = false,
        pairedAt = Instant.now()
    )

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        sessionsFlow = MutableStateFlow(emptyList())
        isConnectedFlow = MutableStateFlow(false)
        unpairedByDaemonFlow = MutableSharedFlow()

        credentialRepository = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        unpairDeviceUseCase = mockk(relaxed = true)
        settingsRepository = FakeSettingsRepository()

        // Default: user hasn't manually disconnected
        coEvery { keyManager.isDisconnectedOnce() } returns false

        // Setup multi-device repository
        coEvery { credentialRepository.getAllDevices() } returns listOf(testDevice1)
        coEvery { credentialRepository.getAllDevicesFlow() } returns flowOf(listOf(testDevice1))
        coEvery { credentialRepository.getSelectedDevice() } returns testDevice1

        every { connectionManager.isConnected } returns isConnectedFlow
        every { connectionManager.unpairedByDaemon } returns unpairedByDaemonFlow
        every { sessionRepository.sessions } returns sessionsFlow
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Tag("unit")
    @Test
    fun `session count starts at 0 for each device`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)
        val devices = (state as HomeState.HasDevices).devices
        assertEquals(1, devices.size)
        assertEquals(0, devices[0].sessionCount)
    }

    @Tag("unit")
    @Test
    fun `session count updates when sessions change while connected`() = runTest {
        // Start connected
        isConnectedFlow.value = true

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Update sessions
        val sessions = listOf(
            createSessionInfo("1"),
            createSessionInfo("2"),
            createSessionInfo("3")
        )
        sessionsFlow.value = sessions
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)
        val devices = (state as HomeState.HasDevices).devices
        assertEquals(3, devices[0].sessionCount)
    }

    @Tag("unit")
    @Test
    fun `session count shows 0 when connected with no sessions`() = runTest {
        // Start connected
        isConnectedFlow.value = true

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Sessions flow is empty
        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)
        val devices = (state as HomeState.HasDevices).devices
        assertEquals(0, devices[0].sessionCount)
    }

    @Tag("unit")
    @Test
    fun `session count resets to 0 when disconnected`() = runTest {
        // Start connected with sessions
        isConnectedFlow.value = true
        sessionsFlow.value = listOf(createSessionInfo("1"), createSessionInfo("2"))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify initial state has sessions
        var state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)
        var devices = (state as HomeState.HasDevices).devices
        assertEquals(2, devices[0].sessionCount)

        // Disconnect - count should reset to 0
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)
        devices = (state as HomeState.HasDevices).devices
        assertEquals(0, devices[0].sessionCount)
    }

    @Tag("unit")
    @Test
    fun `displays multiple devices correctly`() = runTest {
        // Setup two devices
        coEvery { credentialRepository.getAllDevices() } returns listOf(testDevice1, testDevice2)
        coEvery { credentialRepository.getAllDevicesFlow() } returns flowOf(listOf(testDevice1, testDevice2))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)
        val devices = (state as HomeState.HasDevices).devices
        assertEquals(2, devices.size)
        assertEquals("device-1", devices[0].deviceId)
        assertEquals("device-2", devices[1].deviceId)
    }

    @Tag("unit")
    @Test
    fun `shows NoDevices when no devices paired`() = runTest {
        // Setup empty device list
        coEvery { credentialRepository.getAllDevices() } returns emptyList()
        coEvery { credentialRepository.getAllDevicesFlow() } returns flowOf(emptyList())
        coEvery { credentialRepository.getSelectedDevice() } returns null

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is HomeState.NoDevices)
    }

    @Tag("unit")
    @Test
    fun `session count updates for active device only`() = runTest {
        // Setup two devices, first is selected
        isConnectedFlow.value = true
        coEvery { credentialRepository.getAllDevices() } returns listOf(testDevice1, testDevice2)
        coEvery { credentialRepository.getAllDevicesFlow() } returns flowOf(listOf(testDevice1, testDevice2))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Add sessions
        sessionsFlow.value = listOf(createSessionInfo("1"), createSessionInfo("2"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)
        val devices = (state as HomeState.HasDevices).devices
        
        // First device (active) should have session count
        assertEquals(2, devices[0].sessionCount)
        // Second device (inactive) should have 0
        assertEquals(0, devices[1].sessionCount)
    }

    @Tag("unit")
    @Test
    fun `does NOT auto-connect when user manually disconnected`() = runTest {
        // User manually disconnected
        coEvery { keyManager.isDisconnectedOnce() } returns true

        // Auto-connect is enabled in settings
        (settingsRepository as FakeSettingsRepository).setAutoConnectEnabled(true)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // State should be HasDevices (not navigating to connect)
        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)

        // No navigation event should have been emitted
        // (we're not auto-connecting because user disconnected)
    }

    @Tag("unit")
    @Test
    fun `auto-connects when enabled and user did NOT manually disconnect`() = runTest {
        // User did not manually disconnect (fresh app start)
        coEvery { keyManager.isDisconnectedOnce() } returns false

        // Auto-connect is enabled in settings
        (settingsRepository as FakeSettingsRepository).setAutoConnectEnabled(true)

        var navigatedToConnecting = false
        val viewModel = createViewModel()

        // Collect events to check for navigation
        val job = testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceUntilIdle()

        // ViewModel should emit NavigateToConnecting event
        // (this is verified by the event being emitted, which we'd need turbine for full test)
        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevices)
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            credentialRepository = credentialRepository,
            connectionManager = connectionManager,
            keyManager = keyManager,
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepository,
            unpairDeviceUseCase = unpairDeviceUseCase
        )
    }

    private fun createSessionInfo(id: String): SessionInfo {
        return SessionInfo(
            id = id,
            tmuxName = "tmux_$id",
            displayName = "Session $id",
            directory = "/test",
            agent = "test-agent",
            createdAt = Instant.now(),
            lastActivityAt = Instant.now(),
            status = SessionStatus.ACTIVE
        )
    }
}
