package com.ras.domain.unpair

import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Instant

/**
 * Unit tests for UnpairDeviceUseCase.
 *
 * Verifies the complete unpair business logic in isolation.
 * Updated for multi-device support (unpairDevice instead of clearCredentials).
 */
class UnpairDeviceUseCaseTest {

    private lateinit var credentialRepository: CredentialRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var useCase: UnpairDeviceUseCase

    private val testDevice = PairedDevice(
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
        credentialRepository = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        isConnectedFlow = MutableStateFlow(false)

        every { connectionManager.isConnected } returns isConnectedFlow
        coEvery { credentialRepository.getSelectedDevice() } returns testDevice

        useCase = UnpairDeviceUseCaseImpl(
            credentialRepository = credentialRepository,
            connectionManager = connectionManager
        )
    }

    @Tag("unit")
    @Test
    fun `unpair sends request when connected and deviceId provided`() = runTest {
        // Given: connected with device ID
        isConnectedFlow.value = true

        // When: unpair with specific device ID
        useCase("test-device-123")

        // Then: should send unpair request
        coVerify { connectionManager.sendUnpairRequest("test-device-123") }
        // And: should unpair locally
        coVerify { credentialRepository.unpairDevice("test-device-123") }
        // And: should disconnect
        coVerify { connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR) }
    }

    @Tag("unit")
    @Test
    fun `unpair sends request for selected device when no deviceId provided`() = runTest {
        // Given: connected, selected device exists
        isConnectedFlow.value = true

        // When: unpair without device ID
        useCase(null)

        // Then: should send unpair request for selected device
        coVerify { connectionManager.sendUnpairRequest("test-device-123") }
        // And: should unpair locally
        coVerify { credentialRepository.unpairDevice("test-device-123") }
        // And: should disconnect
        coVerify { connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR) }
    }

    @Tag("unit")
    @Test
    fun `unpair does not send request when disconnected`() = runTest {
        // Given: disconnected
        isConnectedFlow.value = false

        // When: unpair
        useCase("test-device-123")

        // Then: should not send unpair request
        coVerify(exactly = 0) { connectionManager.sendUnpairRequest(any()) }
        // But: should still unpair locally
        coVerify { credentialRepository.unpairDevice("test-device-123") }
        // And: should not try to disconnect
        coVerify(exactly = 0) { connectionManager.disconnectGracefully(any()) }
    }

    @Tag("unit")
    @Test
    fun `unpair does nothing when no device to unpair`() = runTest {
        // Given: no selected device
        coEvery { credentialRepository.getSelectedDevice() } returns null

        // When: unpair without device ID
        useCase(null)

        // Then: should not send unpair request
        coVerify(exactly = 0) { connectionManager.sendUnpairRequest(any()) }
        // And: should not unpair (no device)
        coVerify(exactly = 0) { credentialRepository.unpairDevice(any()) }
    }

    @Tag("unit")
    @Test
    fun `unpair continues even if notification fails`() = runTest {
        // Given: connected, but notification fails
        isConnectedFlow.value = true
        coEvery { connectionManager.sendUnpairRequest(any()) } throws IOException("Network error")

        // When: unpair
        useCase("test-device-123")

        // Then: should still unpair locally (critical invariant)
        coVerify { credentialRepository.unpairDevice("test-device-123") }
        // And: should still disconnect
        coVerify { connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR) }
    }

    @Tag("unit")
    @Test
    fun `unpair throws when credential unpairing fails`() = runTest {
        // Given: unpair will fail
        coEvery { credentialRepository.unpairDevice(any()) } throws IOException("Storage error")

        // When/Then: should throw
        assertThrows<IOException> {
            useCase("test-device-123")
        }
    }

    @Tag("unit")
    @Test
    fun `unpair recovers and unpairs even when notification and disconnect fail`() = runTest {
        // Given: connected, notification and disconnect fail
        isConnectedFlow.value = true
        coEvery { connectionManager.sendUnpairRequest(any()) } throws IOException("Network error")
        coEvery { connectionManager.disconnectGracefully(any()) } throws IOException("Disconnect error")

        // When: unpair
        useCase("test-device-123")

        // Then: should still unpair locally (critical invariant preserved)
        coVerify { credentialRepository.unpairDevice("test-device-123") }
    }

    @Tag("unit")
    @Test
    fun `unpair with specific device ID uses that ID not selected device`() = runTest {
        // Given: selected device is different from target
        val selectedDevice = PairedDevice(
            deviceId = "selected-device",
            masterSecret = ByteArray(32),
            deviceName = "Selected",
            deviceType = DeviceType.DESKTOP,
            status = DeviceStatus.PAIRED,
            isSelected = true,
            pairedAt = Instant.now()
        )
        coEvery { credentialRepository.getSelectedDevice() } returns selectedDevice
        isConnectedFlow.value = true

        // When: unpair specific device
        useCase("other-device-123")

        // Then: should use the specified device ID
        coVerify { connectionManager.sendUnpairRequest("other-device-123") }
        coVerify { credentialRepository.unpairDevice("other-device-123") }
    }
}
