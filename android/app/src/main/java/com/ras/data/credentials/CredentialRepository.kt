package com.ras.data.credentials

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
     * @return StoredCredentials if all required fields exist, null otherwise
     */
    suspend fun getCredentials(): StoredCredentials?

    /**
     * Clear all stored credentials (for unpairing/re-pairing).
     */
    suspend fun clearCredentials()
}
