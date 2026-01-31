package com.ras.domain.startup

/**
 * Result of attempting to reconnect to the daemon.
 */
sealed class ReconnectionResult {
    /**
     * Successfully reconnected to daemon.
     */
    object Success : ReconnectionResult()

    /**
     * Reconnection failed.
     */
    sealed class Failure : ReconnectionResult() {
        /**
         * No credentials stored - cannot reconnect.
         */
        object NoCredentials : Failure()

        /**
         * Daemon is not reachable (network error, timeout, etc).
         */
        object DaemonUnreachable : Failure()

        /**
         * Authentication failed (invalid signature).
         */
        object AuthenticationFailed : Failure()

        /**
         * Device not found on daemon - needs re-pairing.
         */
        object DeviceNotFound : Failure()

        /**
         * Network error (no connectivity, etc).
         */
        object NetworkError : Failure()

        /**
         * Unknown error with message.
         */
        data class Unknown(val message: String) : Failure()
    }
}
