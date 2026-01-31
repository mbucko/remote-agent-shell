package com.ras.data.credentials

import com.ras.data.keystore.KeyManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CredentialRepository that wraps KeyManager.
 */
@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val keyManager: KeyManager
) : CredentialRepository {

    override suspend fun hasCredentials(): Boolean {
        return keyManager.hasMasterSecret()
    }

    override suspend fun getCredentials(): StoredCredentials? {
        val masterSecret = keyManager.getMasterSecret() ?: return null
        val daemonIp = keyManager.getDaemonIp()
        val daemonPort = keyManager.getDaemonPort()
        val ntfyTopic = keyManager.getNtfyTopic()

        // Validate all required fields are present and valid
        if (daemonIp.isNullOrEmpty()) return null
        if (daemonPort == null || daemonPort <= 0) return null
        if (ntfyTopic.isNullOrEmpty()) return null

        // Get optional Tailscale info
        val tailscaleIp = keyManager.getTailscaleIp()
        val tailscalePort = keyManager.getTailscalePort()

        return StoredCredentials(
            deviceId = keyManager.getOrCreateDeviceId(),
            masterSecret = masterSecret,
            daemonHost = daemonIp,
            daemonPort = daemonPort,
            ntfyTopic = ntfyTopic,
            daemonTailscaleIp = tailscaleIp,
            daemonTailscalePort = tailscalePort
        )
    }

    override suspend fun clearCredentials() {
        keyManager.clearCredentials()
    }
}
