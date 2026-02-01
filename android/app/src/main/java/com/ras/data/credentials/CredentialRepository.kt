package com.ras.data.credentials

import com.ras.data.model.DeviceType

/**
 * Repository for managing stored pairing credentials.
 * Abstracts the KeyManager for testability.
 */
interface CredentialRepository {
    /**
     * Check if valid credentials are stored.
     * @return true if master secret exists
     */
    suspend fun hasCredentials(): Boolean

    /**
     * Get stored credentials for reconnection.
     * @return StoredCredentials if master secret exists, null otherwise
     */
    suspend fun getCredentials(): StoredCredentials?

    /**
     * Get stored device name (hostname).
     * @return device name, or null if not stored
     */
    suspend fun getDeviceName(): String?

    /**
     * Get stored device type.
     * @return device type, or UNKNOWN if not stored
     */
    suspend fun getDeviceType(): DeviceType

    /**
     * Clear all stored credentials (for unpairing/re-pairing).
     */
    suspend fun clearCredentials()
}
