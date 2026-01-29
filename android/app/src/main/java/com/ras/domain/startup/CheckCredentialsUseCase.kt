package com.ras.domain.startup

import com.ras.data.credentials.CredentialRepository
import javax.inject.Inject

/**
 * Use case to check if valid credentials are stored.
 */
interface CheckCredentialsUseCase {
    suspend operator fun invoke(): CredentialStatus
}

/**
 * Implementation of CheckCredentialsUseCase.
 */
class CheckCredentialsUseCaseImpl @Inject constructor(
    private val credentialRepository: CredentialRepository
) : CheckCredentialsUseCase {

    override suspend fun invoke(): CredentialStatus {
        if (!credentialRepository.hasCredentials()) {
            return CredentialStatus.NoCredentials
        }

        val credentials = credentialRepository.getCredentials()
            ?: return CredentialStatus.NoCredentials

        return CredentialStatus.HasCredentials(
            deviceId = credentials.deviceId,
            daemonHost = credentials.daemonHost,
            daemonPort = credentials.daemonPort
        )
    }
}
