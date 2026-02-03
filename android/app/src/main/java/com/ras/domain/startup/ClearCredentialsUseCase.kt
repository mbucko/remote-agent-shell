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
        // Unpair the selected device
        val selectedDevice = credentialRepository.getSelectedDevice()
        if (selectedDevice != null) {
            credentialRepository.unpairDevice(selectedDevice.deviceId)
        }
    }
}
