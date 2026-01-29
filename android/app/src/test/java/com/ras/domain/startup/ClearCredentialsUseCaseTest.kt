package com.ras.domain.startup

import com.ras.data.credentials.CredentialRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ClearCredentialsUseCase.
 */
class ClearCredentialsUseCaseTest {

    private lateinit var credentialRepository: CredentialRepository
    private lateinit var useCase: ClearCredentialsUseCase

    @Before
    fun setup() {
        credentialRepository = mockk(relaxed = true)
        useCase = ClearCredentialsUseCaseImpl(credentialRepository)
    }

    @Test
    fun `invoke clears credentials via repository`() = runTest {
        useCase()

        coVerify { credentialRepository.clearCredentials() }
    }
}
