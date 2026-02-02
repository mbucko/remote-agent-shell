package com.ras.domain.startup

import com.ras.data.credentials.CredentialRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for ClearCredentialsUseCase.
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
    fun `invoke clears credentials via repository`() = runTest {
        useCase()

        coVerify { credentialRepository.clearCredentials() }
    }
}
