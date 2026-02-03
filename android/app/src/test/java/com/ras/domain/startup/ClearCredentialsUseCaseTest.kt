package com.ras.domain.startup

import com.ras.data.credentials.CredentialRepository
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.time.Instant

/**
 * Unit tests for ClearCredentialsUseCase.
 * Updated for multi-device support (unpairDevice instead of clearCredentials).
 */
class ClearCredentialsUseCaseTest {

    private lateinit var credentialRepository: CredentialRepository
    private lateinit var useCase: ClearCredentialsUseCase

    @BeforeEach
    fun setup() {
        credentialRepository = mockk(relaxed = true)
        useCase = ClearCredentialsUseCaseImpl(credentialRepository)
    }

    @Tag("unit")
    @Test
    fun `invoke unpairs the selected device`() = runTest {
        val selectedDevice = PairedDevice(
            deviceId = "test-device-123",
            masterSecret = ByteArray(32),
            deviceName = "Test Device",
            deviceType = DeviceType.DESKTOP,
            status = DeviceStatus.PAIRED,
            isSelected = true,
            pairedAt = Instant.now()
        )
        coEvery { credentialRepository.getSelectedDevice() } returns selectedDevice

        useCase()

        coVerify { credentialRepository.unpairDevice("test-device-123") }
    }

    @Tag("unit")
    @Test
    fun `invoke does nothing when no selected device`() = runTest {
        coEvery { credentialRepository.getSelectedDevice() } returns null

        useCase()

        coVerify(exactly = 0) { credentialRepository.unpairDevice(any()) }
    }
}
