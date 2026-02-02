package com.ras.domain.startup

import com.ras.data.credentials.CredentialRepository
import com.ras.data.credentials.StoredCredentials
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for CheckCredentialsUseCase.
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
    fun `invoke returns NoCredentials when repository has no credentials`() = runTest {
        coEvery { credentialRepository.hasCredentials() } returns false

        val result = useCase()

        assertTrue(result is CredentialStatus.NoCredentials)
    }

    @Tag("unit")
    @Test
    fun `invoke returns HasCredentials when repository has credentials`() = runTest {
        val credentials = StoredCredentials(
            deviceId = "device123",
            masterSecret = ByteArray(32),
            daemonHost = "192.168.1.100",
            daemonPort = 8765,
            ntfyTopic = "ras-abc123"
        )
        coEvery { credentialRepository.hasCredentials() } returns true
        coEvery { credentialRepository.getCredentials() } returns credentials

        val result = useCase()

        assertTrue(result is CredentialStatus.HasCredentials)
        val hasCredentials = result as CredentialStatus.HasCredentials
        assertEquals("device123", hasCredentials.deviceId)
        assertEquals("192.168.1.100", hasCredentials.daemonHost)
        assertEquals(8765, hasCredentials.daemonPort)
    }

    @Tag("unit")
    @Test
    fun `invoke returns NoCredentials when hasCredentials true but getCredentials returns null`() = runTest {
        // Edge case: hasCredentials returns true but getCredentials fails (partial data)
        coEvery { credentialRepository.hasCredentials() } returns true
        coEvery { credentialRepository.getCredentials() } returns null

        val result = useCase()

        assertTrue(result is CredentialStatus.NoCredentials)
    }

    @Tag("unit")
    @Test
    fun `invoke preserves all credential info in HasCredentials`() = runTest {
        val credentials = StoredCredentials(
            deviceId = "f40c395f078f4f1da3a7ce9393ddaadf",
            masterSecret = ByteArray(32) { it.toByte() },
            daemonHost = "10.0.0.50",
            daemonPort = 9000,
            ntfyTopic = "ras-xyz789"
        )
        coEvery { credentialRepository.hasCredentials() } returns true
        coEvery { credentialRepository.getCredentials() } returns credentials

        val result = useCase() as CredentialStatus.HasCredentials

        assertEquals("f40c395f078f4f1da3a7ce9393ddaadf", result.deviceId)
        assertEquals("10.0.0.50", result.daemonHost)
        assertEquals(9000, result.daemonPort)
    }
}
