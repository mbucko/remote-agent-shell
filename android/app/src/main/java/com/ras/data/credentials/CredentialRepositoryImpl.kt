package com.ras.data.credentials

import com.ras.crypto.KeyDerivation
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CredentialRepository that wraps KeyManager.
 *
 * Only master_secret is required to be stored. Everything else is either:
 * - Derived from master_secret (ntfyTopic, sessionId, authKey)
 * - Discovered dynamically (daemon IP/port via mDNS or ntfy DISCOVER)
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

        // Derive ntfyTopic from master_secret (not stored)
        val ntfyTopic = KeyDerivation.deriveNtfyTopic(masterSecret)

        // Get optional daemon IP/port (discovered via mDNS, may be cached)
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

    override suspend fun getDeviceName(): String? {
        return keyManager.getDeviceName()
    }

    override suspend fun getDeviceType(): DeviceType {
        return keyManager.getDeviceType()
    }

    override suspend fun clearCredentials() {
        keyManager.clearCredentials()
    }
}
