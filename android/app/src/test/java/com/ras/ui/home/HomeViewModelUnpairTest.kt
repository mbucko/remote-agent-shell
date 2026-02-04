package com.ras.ui.home

import app.cash.turbine.test
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
import com.ras.fakes.FakeSettingsRepository
import com.ras.proto.UnpairNotification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.time.Instant

/**
 * CRITICAL TEST: Verifies unpair from CLI flow.
 *
 * This tests the scenario where:
 * 1. User runs `ras remove <device>` on CLI
 * 2. Daemon sends UnpairNotification to phone
 * 3. Phone marks device as UNPAIRED_BY_DAEMON
 * 4. Phone disconnects from daemon
 * 5. UI shows device with "Unpaired by host" status
 */
@Tag("unit")
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelUnpairTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var keyManager: KeyManager
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var sessionRepository: com.ras.data.sessions.SessionRepository
    private lateinit var unpairDeviceUseCase: com.ras.domain.unpair.UnpairDeviceUseCase
    private lateinit var unpairedByDaemonFlow: MutableSharedFlow<UnpairNotification>
    private lateinit var viewModel: HomeViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock dependencies
        credentialRepository = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        unpairDeviceUseCase = mockk(relaxed = true)
        unpairedByDaemonFlow = MutableSharedFlow()

        // Default: user hasn't manually disconnected
        coEvery { keyManager.isDisconnectedOnce() } returns false

        // Setup connection manager to emit unpair notifications
        every { connectionManager.unpairedByDaemon } returns unpairedByDaemonFlow
        every { connectionManager.isConnected } returns MutableStateFlow(false)
        coEvery { connectionManager.disconnectGracefully(any()) } just Runs

        // Use fake settings repository instead of mock
        settingsRepository = FakeSettingsRepository()

        // Setup initial state: one paired device
        val pairedDevices = listOf(
            PairedDevice(
                deviceId = "daemon-laptop-abc",
                masterSecret = ByteArray(32),
                deviceName = "My Laptop",
                deviceType = DeviceType.LAPTOP,
                status = DeviceStatus.PAIRED,
                isSelected = true,
                pairedAt = Instant.now()
            )
        )
        coEvery { credentialRepository.getAllDevices() } returns pairedDevices
        coEvery { credentialRepository.getAllDevicesFlow() } returns flowOf(pairedDevices)
        coEvery { credentialRepository.getSelectedDevice() } returns pairedDevices[0]
        every { sessionRepository.sessions } returns MutableStateFlow(emptyList())

        viewModel = HomeViewModel(
            credentialRepository,
            connectionManager,
            keyManager,
            settingsRepository,
            sessionRepository,
            unpairDeviceUseCase
        )
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * REGRESSION TEST: When daemon sends UnpairNotification, phone must:
     * 1. Mark device as UNPAIRED_BY_DAEMON
     * 2. Show snackbar message
     * 3. Device appears in list with unpaired status
     */
    @Test
    fun `when daemon unpairs device marks it as UNPAIRED_BY_DAEMON and shows message`() = runTest {
        // Given: Device is initially paired
        val initialState = viewModel.state.value
        assertTrue(initialState is HomeState.HasDevices)
        val devices = (initialState as HomeState.HasDevices).devices
        assertEquals(1, devices.size)
        assertEquals(DeviceStatus.PAIRED, devices[0].status)

        // When: Daemon sends unpair notification
        val unpairNotification = UnpairNotification.newBuilder()
            .setDeviceId("daemon-laptop-abc")
            .setReason("Removed from CLI")
            .build()

        viewModel.events.test {
            // Emit the unpair notification
            unpairedByDaemonFlow.emit(unpairNotification)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Should emit snackbar event
            val event = awaitItem()
            assertTrue(event is HomeUiEvent.ShowSnackbar)
            assertTrue((event as HomeUiEvent.ShowSnackbar).message.contains("Unpaired by host"))
        }

        // And: Should update device status in repository
        coVerify {
            credentialRepository.updateDeviceStatus(
                "daemon-laptop-abc",
                DeviceStatus.UNPAIRED_BY_DAEMON
            )
        }

        // And: Should disconnect from daemon
        coVerify { connectionManager.disconnectGracefully("unpaired_by_daemon") }
    }

    /**
     * Test that unpair notification for unknown device is handled gracefully.
     */
    @Test
    fun `when daemon unpairs unknown device handles gracefully`() = runTest {
        // When: Daemon sends unpair for device we don't have
        val unpairNotification = UnpairNotification.newBuilder()
            .setDeviceId("unknown-device")
            .setReason("Not found")
            .build()

        unpairedByDaemonFlow.emit(unpairNotification)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should still call updateDeviceStatus (may be no-op in repository)
        coVerify {
            credentialRepository.updateDeviceStatus(
                "unknown-device",
                DeviceStatus.UNPAIRED_BY_DAEMON
            )
        }

        // And: Should show snackbar
        viewModel.events.test {
            // Need to re-emit since we already consumed the event
            unpairedByDaemonFlow.emit(unpairNotification)
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is HomeUiEvent.ShowSnackbar)
        }
    }

    /**
     * Test removing an unpaired device (user clicks "Remove" button).
     */
    @Test
    fun `removing unpaired device calls removeDevice in repository`() = runTest {
        // Given: Device is unpaired
        val unpairedDeviceId = "daemon-unpaired"

        // When: User removes the device
        viewModel.removeDevice(unpairedDeviceId)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Should delete from repository
        coVerify {
            credentialRepository.removeDevice(unpairedDeviceId)
        }

        // Note: Device list refreshes automatically via Flow observation
    }

    /**
     * CRITICAL TEST: User-initiated unpair should immediately remove device.
     *
     * When user clicks "Unpair" on a paired device, it should:
     * 1. Call unpairDeviceUseCase (sends UnpairRequest to daemon)
     * 2. Call removeDevice (hard delete from DB)
     * 3. NOT show as unpaired in UI
     *
     * This is different from daemon-initiated unpair which marks as UNPAIRED_BY_DAEMON
     * and keeps device visible in UI with "Unpaired by host" status.
     */
    @Test
    fun `user-initiated unpair removes device immediately without showing unpaired status`() = runTest {
        // Given: Device is paired
        val deviceId = "daemon-laptop-abc"
        coEvery { unpairDeviceUseCase(deviceId) } just Runs
        coEvery { credentialRepository.removeDevice(deviceId) } just Runs

        // When: User unpairs the device
        viewModel.events.test {
            viewModel.unpairDevice(deviceId)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Should call unpair use case (sends UnpairRequest to daemon)
            coVerify(exactly = 1) { unpairDeviceUseCase(deviceId) }

            // And: Should immediately remove from database (hard delete)
            coVerify(exactly = 1) { credentialRepository.removeDevice(deviceId) }

            // And: Should NOT call updateDeviceStatus (no unpaired status shown)
            coVerify(exactly = 0) {
                credentialRepository.updateDeviceStatus(
                    deviceId,
                    DeviceStatus.UNPAIRED_BY_USER
                )
            }
            coVerify(exactly = 0) {
                credentialRepository.updateDeviceStatus(
                    deviceId,
                    DeviceStatus.UNPAIRED_BY_DAEMON
                )
            }

            // And: Should show success message
            val event = awaitItem()
            assertTrue(event is HomeUiEvent.ShowSnackbar)
            assertEquals("Device unpaired", (event as HomeUiEvent.ShowSnackbar).message)
        }
    }
}
