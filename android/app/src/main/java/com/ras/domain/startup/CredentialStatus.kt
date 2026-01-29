package com.ras.domain.startup

/**
 * Result of checking for stored credentials.
 */
sealed class CredentialStatus {
    /**
     * No valid credentials are stored.
     * User needs to pair with daemon.
     */
    object NoCredentials : CredentialStatus()

    /**
     * Valid credentials exist.
     * App can attempt reconnection.
     */
    data class HasCredentials(
        val deviceId: String,
        val daemonHost: String,
        val daemonPort: Int
    ) : CredentialStatus()
}
