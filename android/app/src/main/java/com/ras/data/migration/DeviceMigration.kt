package com.ras.data.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles one-time migration from KeyManager (single device) to Room (multi-device).
 *
 * This migration runs once on app startup to preserve existing user pairings
 * when upgrading from the single-device to multi-device architecture.
 */
@Singleton
class DeviceMigration @Inject constructor(
    private val keyManager: KeyManager,
    private val credentialRepository: CredentialRepository,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val MIGRATION_COMPLETE_KEY = booleanPreferencesKey("devices_migrated_to_room_v1")
    }

    /**
     * Migrate old single-device credentials to Room database.
     *
     * Safe to call multiple times - migration only runs once.
     */
    suspend fun migrateIfNeeded() {
        // Check if already migrated
        val alreadyMigrated = dataStore.data.first()[MIGRATION_COMPLETE_KEY] ?: false
        if (alreadyMigrated) {
            return
        }

        // Check if old credentials exist
        if (!keyManager.hasMasterSecret()) {
            // No old data to migrate - mark as complete
            markMigrationComplete()
            return
        }

        // Migrate old device to Room
        val masterSecret = keyManager.getMasterSecret()!!
        val deviceName = keyManager.getDeviceName() ?: "My Device"
        val deviceType = keyManager.getDeviceType()
        val daemonIp = keyManager.getDaemonIp()
        val daemonPort = keyManager.getDaemonPort()
        val tailscaleIp = keyManager.getTailscaleIp()
        val tailscalePort = keyManager.getTailscalePort()
        val vpnIp = keyManager.getVpnIp()
        val vpnPort = keyManager.getVpnPort()

        // Generate deterministic device ID from master secret
        val deviceId = generateDeviceId(masterSecret)

        // Add to Room database with is_selected = true
        credentialRepository.addDevice(
            deviceId = deviceId,
            masterSecret = masterSecret,
            deviceName = deviceName,
            deviceType = deviceType,
            isSelected = true, // This is the active device
            daemonHost = daemonIp,
            daemonPort = daemonPort,
            daemonTailscaleIp = tailscaleIp,
            daemonTailscalePort = tailscalePort,
            daemonVpnIp = vpnIp,
            daemonVpnPort = vpnPort
        )

        // Mark migration complete
        // Note: We intentionally DON'T clear the old KeyManager data yet for safety.
        // It can be cleaned up in a future version after confirming migration success.
        markMigrationComplete()
    }

    /**
     * Generate a deterministic device ID from master secret.
     *
     * Uses SHA-256 hash to ensure the same master secret always produces
     * the same device ID, which is important for migration stability.
     */
    private fun generateDeviceId(masterSecret: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(masterSecret)
        // Take first 8 bytes and convert to hex
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    private suspend fun markMigrationComplete() {
        dataStore.edit { preferences ->
            preferences[MIGRATION_COMPLETE_KEY] = true
        }
    }
}
