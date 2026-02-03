package com.ras.domain.startup

import com.ras.crypto.KeyDerivation
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
        val selectedDevice = credentialRepository.getSelectedDevice()
            ?: return CredentialStatus.NoCredentials

        // Derive ntfyTopic from master_secret
        val ntfyTopic = KeyDerivation.deriveNtfyTopic(selectedDevice.masterSecret)

        return CredentialStatus.HasCredentials(
            deviceId = selectedDevice.deviceId,
            daemonHost = selectedDevice.daemonHost,
            daemonPort = selectedDevice.daemonPort
        )
    }
}
