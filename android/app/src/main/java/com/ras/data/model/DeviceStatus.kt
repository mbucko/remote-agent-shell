package com.ras.data.model

/**
 * Status of a paired device.
 *
 * Tracks the lifecycle state from pairing through to unpair.
 */
enum class DeviceStatus {
    /**
     * Device is actively paired and can be connected to.
     */
    PAIRED,

    /**
     * User initiated unpair from phone.
     * Device marked for removal but not yet deleted.
     */
    UNPAIRED_BY_USER,

    /**
     * Daemon rejected connection or removed device.
     * Phone should show warning and allow removal.
     */
    UNPAIRED_BY_DAEMON
}
