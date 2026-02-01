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
        val ntfyTopic = keyManager.getNtfyTopic()

        // Only ntfyTopic is required - IP/port can be discovered via mDNS
        if (ntfyTopic.isNullOrEmpty()) return null

        // Get optional daemon IP/port (may be null if using mDNS discovery)
        val daemonIp = keyManager.getDaemonIp()
        val daemonPort = keyManager.getDaemonPort()

        // Get optional Tailscale info
        val tailscaleIp = keyManager.getTailscaleIp()
        val tailscalePort = keyManager.getTailscalePort()

        // Get optional VPN info
        val vpnIp = keyManager.getVpnIp()
        val vpnPort = keyManager.getVpnPort()

        return StoredCredentials(
            deviceId = keyManager.getOrCreateDeviceId(),
            masterSecret = masterSecret,
            daemonHost = daemonIp,
            daemonPort = daemonPort,
            ntfyTopic = ntfyTopic,
            daemonTailscaleIp = tailscaleIp,
            daemonTailscalePort = tailscalePort,
            daemonVpnIp = vpnIp,
            daemonVpnPort = vpnPort
        )
    }

    override suspend fun clearCredentials() {
        keyManager.clearCredentials()
    }
}
