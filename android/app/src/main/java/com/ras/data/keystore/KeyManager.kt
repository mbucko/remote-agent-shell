package com.ras.data.keystore

import android.content.Context
import com.ras.data.model.DeviceType
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ras_prefs")

/**
 * Manages cryptographic keys and device identity using Android Keystore.
 *
 * Master secrets are encrypted before storage using a key stored in the
 * Android Keystore, which provides hardware-backed security on supported devices.
 */
@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "ras_encryption_key"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_MASTER_SECRET = stringPreferencesKey("master_secret_encrypted")
        private val KEY_NTFY_TOPIC = stringPreferencesKey("ntfy_topic")
        private val KEY_DAEMON_IP = stringPreferencesKey("daemon_ip")
        private val KEY_DAEMON_PORT = stringPreferencesKey("daemon_port")
        private val KEY_TAILSCALE_IP = stringPreferencesKey("tailscale_ip")
        private val KEY_TAILSCALE_PORT = stringPreferencesKey("tailscale_port")
        private val KEY_VPN_IP = stringPreferencesKey("vpn_ip")
        private val KEY_VPN_PORT = stringPreferencesKey("vpn_port")
        private val KEY_IS_DISCONNECTED = booleanPreferencesKey("is_disconnected")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_DEVICE_TYPE = stringPreferencesKey("device_type")
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Get or create a unique device ID.
     * This ID persists across app reinstalls (unless storage is cleared).
     */
    fun getOrCreateDeviceId(): String {
        return runBlocking {
            context.dataStore.data.map { prefs ->
                prefs[KEY_DEVICE_ID]
            }.first() ?: run {
                val newId = UUID.randomUUID().toString().replace("-", "")
                context.dataStore.edit { prefs ->
                    prefs[KEY_DEVICE_ID] = newId
                }
                newId
            }
        }
    }

    /**
     * Store master secret securely using Android Keystore encryption.
     */
    suspend fun storeMasterSecret(masterSecret: ByteArray) {
        require(masterSecret.size == 32) { "Master secret must be 32 bytes" }

        val key = getOrCreateEncryptionKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(masterSecret)

        // Store IV + encrypted data as hex
        val combined = iv + encrypted
        context.dataStore.edit { prefs ->
            prefs[KEY_MASTER_SECRET] = combined.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Retrieve master secret from secure storage.
     * Returns null if no secret is stored.
     */
    suspend fun getMasterSecret(): ByteArray? {
        val encryptedHex = context.dataStore.data.map { prefs ->
            prefs[KEY_MASTER_SECRET]
        }.first() ?: return null

        // Parse hex to bytes
        val combined = encryptedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        if (combined.size < GCM_IV_LENGTH + 16) return null // Invalid data

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val key = getOrCreateEncryptionKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return try {
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a master secret is stored.
     */
    suspend fun hasMasterSecret(): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_MASTER_SECRET] != null
        }.first()
    }

    /**
     * Store daemon connection info.
     * IP/port are optional - daemon can be discovered via mDNS or ntfy.
     */
    suspend fun storeDaemonInfo(
        ip: String? = null,
        port: Int? = null,
        ntfyTopic: String,
        tailscaleIp: String? = null,
        tailscalePort: Int? = null,
        vpnIp: String? = null,
        vpnPort: Int? = null
    ) {
        context.dataStore.edit { prefs ->
            if (ip != null) {
                prefs[KEY_DAEMON_IP] = ip
            } else {
                prefs.remove(KEY_DAEMON_IP)
            }
            if (port != null) {
                prefs[KEY_DAEMON_PORT] = port.toString()
            } else {
                prefs.remove(KEY_DAEMON_PORT)
            }
            prefs[KEY_NTFY_TOPIC] = ntfyTopic
            if (tailscaleIp != null) {
                prefs[KEY_TAILSCALE_IP] = tailscaleIp
            } else {
                prefs.remove(KEY_TAILSCALE_IP)
            }
            if (tailscalePort != null) {
                prefs[KEY_TAILSCALE_PORT] = tailscalePort.toString()
            } else {
                prefs.remove(KEY_TAILSCALE_PORT)
            }
            if (vpnIp != null) {
                prefs[KEY_VPN_IP] = vpnIp
            } else {
                prefs.remove(KEY_VPN_IP)
            }
            if (vpnPort != null) {
                prefs[KEY_VPN_PORT] = vpnPort.toString()
            } else {
                prefs.remove(KEY_VPN_PORT)
            }
        }
    }

    /**
     * Get stored daemon IP.
     */
    suspend fun getDaemonIp(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_DAEMON_IP]
        }.first()
    }

    /**
     * Get stored daemon port.
     */
    suspend fun getDaemonPort(): Int? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_DAEMON_PORT]?.toIntOrNull()
        }.first()
    }

    /**
     * Get stored ntfy topic.
     */
    suspend fun getNtfyTopic(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_NTFY_TOPIC]
        }.first()
    }

    /**
     * Get stored Tailscale IP.
     */
    suspend fun getTailscaleIp(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_TAILSCALE_IP]
        }.first()
    }

    /**
     * Get stored Tailscale port.
     */
    suspend fun getTailscalePort(): Int? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_TAILSCALE_PORT]?.toIntOrNull()
        }.first()
    }

    /**
     * Get stored VPN IP.
     */
    suspend fun getVpnIp(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_VPN_IP]
        }.first()
    }

    /**
     * Get stored VPN port.
     */
    suspend fun getVpnPort(): Int? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_VPN_PORT]?.toIntOrNull()
        }.first()
    }

    /**
     * Set whether user has manually disconnected.
     * When true, app should not auto-reconnect on launch.
     */
    suspend fun setDisconnected(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_DISCONNECTED] = value
        }
    }

    /**
     * Flow of disconnected state.
     * Returns true if user manually disconnected and hasn't reconnected.
     */
    fun isDisconnected(): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_IS_DISCONNECTED] ?: false
        }
    }

    /**
     * Check if user is disconnected (one-shot).
     */
    suspend fun isDisconnectedOnce(): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_IS_DISCONNECTED] ?: false
        }.first()
    }

    /**
     * Store device info received from daemon during pairing.
     *
     * @param name The device hostname (e.g., "MacBook-Pro.local")
     * @param type The device type (laptop, desktop, server)
     */
    suspend fun storeDeviceInfo(name: String, type: DeviceType) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_NAME] = name
            prefs[KEY_DEVICE_TYPE] = type.name
        }
    }

    /**
     * Get stored device name.
     *
     * @return The device hostname, or null if not stored.
     */
    suspend fun getDeviceName(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_DEVICE_NAME]
        }.first()
    }

    /**
     * Get stored device type.
     *
     * @return The device type, or UNKNOWN if not stored.
     */
    suspend fun getDeviceType(): DeviceType {
        val typeName = context.dataStore.data.map { prefs ->
            prefs[KEY_DEVICE_TYPE]
        }.first()
        return DeviceType.fromString(typeName)
    }

    /**
     * Clear all stored credentials (for unpairing).
     */
    suspend fun clearCredentials() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_MASTER_SECRET)
            prefs.remove(KEY_DAEMON_IP)
            prefs.remove(KEY_DAEMON_PORT)
            prefs.remove(KEY_NTFY_TOPIC)
            prefs.remove(KEY_TAILSCALE_IP)
            prefs.remove(KEY_TAILSCALE_PORT)
            prefs.remove(KEY_VPN_IP)
            prefs.remove(KEY_VPN_PORT)
            prefs.remove(KEY_IS_DISCONNECTED)
            prefs.remove(KEY_DEVICE_NAME)
            prefs.remove(KEY_DEVICE_TYPE)
        }
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
        val existingKey = keyStore.getEntry(ENCRYPTION_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }

        // Generate new key in Android Keystore
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keySpec = KeyGenParameterSpec.Builder(
            ENCRYPTION_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
}
