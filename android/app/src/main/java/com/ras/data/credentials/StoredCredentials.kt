package com.ras.data.credentials

/**
 * Stored credentials from a previous pairing session.
 * Used for automatic reconnection on app startup.
 */
data class StoredCredentials(
    val deviceId: String,
    val masterSecret: ByteArray,
    val daemonHost: String,
    val daemonPort: Int,
    val ntfyTopic: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StoredCredentials

        if (deviceId != other.deviceId) return false
        if (!masterSecret.contentEquals(other.masterSecret)) return false
        if (daemonHost != other.daemonHost) return false
        if (daemonPort != other.daemonPort) return false
        if (ntfyTopic != other.ntfyTopic) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + masterSecret.contentHashCode()
        result = 31 * result + daemonHost.hashCode()
        result = 31 * result + daemonPort
        result = 31 * result + ntfyTopic.hashCode()
        return result
    }
}
