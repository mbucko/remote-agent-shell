package com.ras.domain.startup

import com.ras.data.credentials.CredentialRepository
import javax.inject.Inject

/**
 * Use case to clear stored credentials (for re-pairing).
 */
interface ClearCredentialsUseCase {
    suspend operator fun invoke()
}

/**
 * Implementation of ClearCredentialsUseCase.
 */
class ClearCredentialsUseCaseImpl @Inject constructor(
    private val credentialRepository: CredentialRepository
) : ClearCredentialsUseCase {

    override suspend fun invoke() {
        credentialRepository.clearCredentials()
    }
}
