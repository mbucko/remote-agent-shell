package com.ras.domain.unpair

import android.util.Log
import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import javax.inject.Inject

/**
 * Use case to unpair the current device.
 *
 * Handles the complete unpair flow:
 * 1. Send UnpairRequest to daemon if connected (best-effort)
 * 2. Clear credentials locally (critical path - always succeeds)
 * 3. Disconnect gracefully from daemon
 *
 * The unpair always succeeds locally even if the daemon is unreachable,
 * ensuring the user can always unpair their device.
 */
interface UnpairDeviceUseCase {
    /**
     * Unpair the device.
     *
     * @param deviceId The device ID to unpair. If null, skips sending UnpairRequest to daemon.
     */
    suspend operator fun invoke(deviceId: String?)
}

/**
 * Implementation of UnpairDeviceUseCase.
 */
class UnpairDeviceUseCaseImpl @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val connectionManager: ConnectionManager
) : UnpairDeviceUseCase {

    companion object {
        private const val TAG = "UnpairDeviceUseCase"
    }

    override suspend fun invoke(deviceId: String?) {
        try {
            // Determine which device to unpair
            val targetDeviceId = deviceId ?: credentialRepository.getSelectedDevice()?.deviceId
            if (targetDeviceId == null) {
                Log.w(TAG, "No device to unpair")
                return
            }

            // Best-effort notification - we don't wait for ack because unpair
            // should always succeed locally even if daemon is unreachable.
            // The disconnect may close the connection before the message is sent,
            // but this is acceptable since local credential clearing is the critical path.
            if (connectionManager.isConnected.value) {
                try {
                    connectionManager.sendUnpairRequest(targetDeviceId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to notify daemon of unpair: ${e.message}")
                    // Continue with local unpair
                }
            }

            // Mark device as unpaired locally (critical invariant)
            credentialRepository.unpairDevice(targetDeviceId)

            // Disconnect gracefully if connected
            if (connectionManager.isConnected.value) {
                connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR)
            }

        } catch (e: Exception) {
            // Even if something fails, try to unpair the device
            Log.e(TAG, "Error during unpair, attempting to unpair anyway", e)
            val targetDeviceId = deviceId ?: credentialRepository.getSelectedDevice()?.deviceId
            if (targetDeviceId != null) {
                try {
                    credentialRepository.unpairDevice(targetDeviceId)
                } catch (unpairError: Exception) {
                    Log.e(TAG, "Failed to unpair device", unpairError)
                    throw unpairError // Re-throw because this is critical
                }
            }
        }
    }
}
