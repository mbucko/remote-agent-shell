package com.ras.data.model

/**
 * Device type for UI display.
 *
 * Maps to proto DeviceType enum values.
 */
enum class DeviceType {
    UNKNOWN,
    LAPTOP,
    DESKTOP,
    SERVER;

    companion object {
        /**
         * Convert from proto DeviceType to this enum.
         */
        fun fromProto(proto: com.ras.proto.DeviceType): DeviceType = when (proto) {
            com.ras.proto.DeviceType.DEVICE_TYPE_LAPTOP -> LAPTOP
            com.ras.proto.DeviceType.DEVICE_TYPE_DESKTOP -> DESKTOP
            com.ras.proto.DeviceType.DEVICE_TYPE_SERVER -> SERVER
            else -> UNKNOWN
        }

        /**
         * Parse from stored string value.
         */
        fun fromString(value: String?): DeviceType {
            return value?.let {
                try {
                    valueOf(it)
                } catch (e: IllegalArgumentException) {
                    UNKNOWN
                }
            } ?: UNKNOWN
        }
    }
}
