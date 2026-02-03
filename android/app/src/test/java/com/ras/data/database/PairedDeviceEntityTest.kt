package com.ras.data.database

import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class PairedDeviceEntityTest {

    @Test
    fun `entity can be created with all required fields`() {
        val entity = PairedDeviceEntity(
            deviceId = "device-123",
            masterSecretEncrypted = byteArrayOf(1, 2, 3, 4),
            masterSecretIv = byteArrayOf(5, 6, 7, 8),
            deviceName = "My Laptop",
            deviceType = DeviceType.LAPTOP.name,
            status = DeviceStatus.PAIRED.name,
            isSelected = true,
            pairedAt = 1234567890L,
            lastConnectedAt = null,
            daemonHost = null,
            daemonPort = null,
            daemonTailscaleIp = null,
            daemonTailscalePort = null,
            daemonVpnIp = null,
            daemonVpnPort = null
        )

        assertEquals("device-123", entity.deviceId)
        assertEquals("My Laptop", entity.deviceName)
        assertEquals(DeviceType.LAPTOP.name, entity.deviceType)
        assertEquals(DeviceStatus.PAIRED.name, entity.status)
        assertTrue(entity.isSelected)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), entity.masterSecretEncrypted)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), entity.masterSecretIv)
    }

    @Test
    fun `entity can be created with optional connection info`() {
        val entity = PairedDeviceEntity(
            deviceId = "device-456",
            masterSecretEncrypted = byteArrayOf(1, 2, 3, 4),
            masterSecretIv = byteArrayOf(5, 6, 7, 8),
            deviceName = "Desktop",
            deviceType = DeviceType.DESKTOP.name,
            status = DeviceStatus.PAIRED.name,
            isSelected = false,
            pairedAt = 1234567890L,
            lastConnectedAt = 1234567900L,
            daemonHost = "192.168.1.100",
            daemonPort = 8080,
            daemonTailscaleIp = "100.64.1.2",
            daemonTailscalePort = 8080,
            daemonVpnIp = "10.0.0.5",
            daemonVpnPort = 8080
        )

        assertEquals("192.168.1.100", entity.daemonHost)
        assertEquals(8080, entity.daemonPort)
        assertEquals("100.64.1.2", entity.daemonTailscaleIp)
        assertEquals(8080, entity.daemonTailscalePort)
        assertEquals("10.0.0.5", entity.daemonVpnIp)
        assertEquals(8080, entity.daemonVpnPort)
        assertEquals(1234567900L, entity.lastConnectedAt)
    }

    @Test
    fun `entity supports all device statuses`() {
        val paired = PairedDeviceEntity(
            deviceId = "d1",
            masterSecretEncrypted = byteArrayOf(),
            masterSecretIv = byteArrayOf(),
            deviceName = "Device 1",
            deviceType = DeviceType.LAPTOP.name,
            status = DeviceStatus.PAIRED.name,
            isSelected = false,
            pairedAt = 0L
        )

        val unpairedByUser = paired.copy(status = DeviceStatus.UNPAIRED_BY_USER.name)
        val unpairedByDaemon = paired.copy(status = DeviceStatus.UNPAIRED_BY_DAEMON.name)

        assertEquals(DeviceStatus.PAIRED.name, paired.status)
        assertEquals(DeviceStatus.UNPAIRED_BY_USER.name, unpairedByUser.status)
        assertEquals(DeviceStatus.UNPAIRED_BY_DAEMON.name, unpairedByDaemon.status)
    }

    @Test
    fun `entity supports all device types`() {
        DeviceType.values().forEach { type ->
            val entity = PairedDeviceEntity(
                deviceId = "d-${type.name}",
                masterSecretEncrypted = byteArrayOf(),
                masterSecretIv = byteArrayOf(),
                deviceName = "Device",
                deviceType = type.name,
                status = DeviceStatus.PAIRED.name,
                isSelected = false,
                pairedAt = 0L
            )

            assertEquals(type.name, entity.deviceType)
        }
    }
}
