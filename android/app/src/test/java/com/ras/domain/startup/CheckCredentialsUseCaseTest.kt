package com.ras.domain.startup

import com.ras.crypto.KeyDerivation
import com.ras.data.credentials.CredentialRepository
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.time.Instant

/**
 * Unit tests for CheckCredentialsUseCase.
 * Updated for multi-device support (getSelectedDevice instead of hasCredentials).
 */
class CheckCredentialsUseCaseTest {

    private lateinit var credentialRepository: CredentialRepository
    private lateinit var useCase: CheckCredentialsUseCase

    @BeforeEach
    fun setup() {
        credentialRepository = mockk()
        useCase = CheckCredentialsUseCaseImpl(credentialRepository)
    }

    @Tag("unit")
    @Test
    fun `invoke returns NoCredentials when no selected device`() = runTest {
        coEvery { credentialRepository.getSelectedDevice() } returns null

        val result = useCase()

        assertTrue(result is CredentialStatus.NoCredentials)
    }

    @Tag("unit")
    @Test
    fun `invoke returns HasCredentials when selected device exists`() = runTest {
        val device = PairedDevice(
            deviceId = "device123",
            masterSecret = ByteArray(32),
            deviceName = "Test Device",
            deviceType = DeviceType.DESKTOP,
            status = DeviceStatus.PAIRED,
            isSelected = true,
            pairedAt = Instant.now(),
            daemonHost = "192.168.1.100",
            daemonPort = 8765
        )
        coEvery { credentialRepository.getSelectedDevice() } returns device

        val result = useCase()

        assertTrue(result is CredentialStatus.HasCredentials)
        val hasCredentials = result as CredentialStatus.HasCredentials
        assertEquals("device123", hasCredentials.deviceId)
        assertEquals("192.168.1.100", hasCredentials.daemonHost)
        assertEquals(8765, hasCredentials.daemonPort)
    }

    @Tag("unit")
    @Test
    fun `invoke preserves all credential info in HasCredentials`() = runTest {
        val device = PairedDevice(
            deviceId = "f40c395f078f4f1da3a7ce9393ddaadf",
            masterSecret = ByteArray(32) { it.toByte() },
            deviceName = "My Laptop",
            deviceType = DeviceType.LAPTOP,
            status = DeviceStatus.PAIRED,
            isSelected = true,
            pairedAt = Instant.now(),
            daemonHost = "10.0.0.50",
            daemonPort = 9000
        )
        coEvery { credentialRepository.getSelectedDevice() } returns device

        val result = useCase() as CredentialStatus.HasCredentials

        assertEquals("f40c395f078f4f1da3a7ce9393ddaadf", result.deviceId)
        assertEquals("10.0.0.50", result.daemonHost)
        assertEquals(9000, result.daemonPort)
    }
}
