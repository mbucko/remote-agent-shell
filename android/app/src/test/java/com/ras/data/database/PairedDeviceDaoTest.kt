package com.ras.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@Tag("unit")
@ExtendWith(RobolectricExtension::class)
class PairedDeviceDaoTest {

    private lateinit var database: DeviceDatabase
    private lateinit var dao: PairedDeviceDao

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DeviceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.pairedDeviceDao()
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    private fun createTestDevice(
        deviceId: String = "device-1",
        deviceName: String = "Test Device",
        status: String = DeviceStatus.PAIRED.name,
        isSelected: Boolean = false
    ) = PairedDeviceEntity(
        deviceId = deviceId,
        masterSecretEncrypted = byteArrayOf(1, 2, 3),
        masterSecretIv = byteArrayOf(4, 5, 6),
        deviceName = deviceName,
        deviceType = DeviceType.LAPTOP.name,
        status = status,
        isSelected = isSelected,
        pairedAt = System.currentTimeMillis() / 1000
    )

    @Test
    fun `insertDevice and getDevice returns same device`() = runTest {
        val device = createTestDevice()

        dao.insertDevice(device)
        val retrieved = dao.getDevice("device-1")

        assertNotNull(retrieved)
        assertEquals(device.deviceId, retrieved?.deviceId)
        assertEquals(device.deviceName, retrieved?.deviceName)
        assertEquals(device.status, retrieved?.status)
    }

    @Test
    fun `getDevice returns null for non-existent device`() = runTest {
        val retrieved = dao.getDevice("non-existent")

        assertNull(retrieved)
    }

    @Test
    fun `getAllPairedDevices returns only PAIRED devices`() = runTest {
        dao.insertDevice(createTestDevice("device-1", status = DeviceStatus.PAIRED.name))
        dao.insertDevice(createTestDevice("device-2", status = DeviceStatus.UNPAIRED_BY_USER.name))
        dao.insertDevice(createTestDevice("device-3", status = DeviceStatus.UNPAIRED_BY_DAEMON.name))
        dao.insertDevice(createTestDevice("device-4", status = DeviceStatus.PAIRED.name))

        val devices = dao.getAllPairedDevices().first()

        assertEquals(2, devices.size)
        assertTrue(devices.all { it.status == DeviceStatus.PAIRED.name })
    }

    @Test
    fun `getAllPairedDevices returns devices ordered by paired_at DESC`() = runTest {
        val now = System.currentTimeMillis() / 1000
        dao.insertDevice(createTestDevice("device-1").copy(pairedAt = now - 100))
        dao.insertDevice(createTestDevice("device-2").copy(pairedAt = now))
        dao.insertDevice(createTestDevice("device-3").copy(pairedAt = now - 200))

        val devices = dao.getAllPairedDevices().first()

        assertEquals("device-2", devices[0].deviceId) // Most recent first
        assertEquals("device-1", devices[1].deviceId)
        assertEquals("device-3", devices[2].deviceId)
    }

    @Test
    fun `getSelectedDevice returns device with is_selected true`() = runTest {
        dao.insertDevice(createTestDevice("device-1", isSelected = false))
        dao.insertDevice(createTestDevice("device-2", isSelected = true))
        dao.insertDevice(createTestDevice("device-3", isSelected = false))

        val selected = dao.getSelectedDevice()

        assertNotNull(selected)
        assertEquals("device-2", selected?.deviceId)
        assertTrue(selected?.isSelected == true)
    }

    @Test
    fun `getSelectedDevice returns null when no device selected`() = runTest {
        dao.insertDevice(createTestDevice("device-1", isSelected = false))
        dao.insertDevice(createTestDevice("device-2", isSelected = false))

        val selected = dao.getSelectedDevice()

        assertNull(selected)
    }

    @Test
    fun `getSelectedDevice ignores unpaired devices`() = runTest {
        dao.insertDevice(
            createTestDevice(
                "device-1",
                status = DeviceStatus.UNPAIRED_BY_USER.name,
                isSelected = true
            )
        )

        val selected = dao.getSelectedDevice()

        assertNull(selected)
    }

    @Test
    fun `updateDeviceStatus changes device status`() = runTest {
        dao.insertDevice(createTestDevice("device-1", status = DeviceStatus.PAIRED.name))

        dao.updateDeviceStatus("device-1", DeviceStatus.UNPAIRED_BY_DAEMON.name)

        val updated = dao.getDevice("device-1")
        assertEquals(DeviceStatus.UNPAIRED_BY_DAEMON.name, updated?.status)
    }

    @Test
    fun `setSelectedDevice clears other selections and sets new one`() = runTest {
        dao.insertDevice(createTestDevice("device-1", isSelected = true))
        dao.insertDevice(createTestDevice("device-2", isSelected = false))
        dao.insertDevice(createTestDevice("device-3", isSelected = false))

        dao.setSelectedDevice("device-2")

        val device1 = dao.getDevice("device-1")
        val device2 = dao.getDevice("device-2")
        val device3 = dao.getDevice("device-3")

        assertFalse(device1?.isSelected == true)
        assertTrue(device2?.isSelected == true)
        assertFalse(device3?.isSelected == true)
    }

    @Test
    fun `deleteDevice removes device from database`() = runTest {
        dao.insertDevice(createTestDevice("device-1"))

        dao.deleteDevice("device-1")

        val deleted = dao.getDevice("device-1")
        assertNull(deleted)
    }

    @Test
    fun `deleteUnpairedDevices removes only unpaired devices`() = runTest {
        dao.insertDevice(createTestDevice("device-1", status = DeviceStatus.PAIRED.name))
        dao.insertDevice(createTestDevice("device-2", status = DeviceStatus.UNPAIRED_BY_USER.name))
        dao.insertDevice(createTestDevice("device-3", status = DeviceStatus.UNPAIRED_BY_DAEMON.name))

        dao.deleteUnpairedDevices()

        assertNotNull(dao.getDevice("device-1"))
        assertNull(dao.getDevice("device-2"))
        assertNull(dao.getDevice("device-3"))
    }

    @Test
    fun `insertDevice with same deviceId replaces existing device`() = runTest {
        dao.insertDevice(createTestDevice("device-1", deviceName = "Old Name"))
        dao.insertDevice(createTestDevice("device-1", deviceName = "New Name"))

        val device = dao.getDevice("device-1")
        assertEquals("New Name", device?.deviceName)
    }

    @Test
    fun `clearAllSelections clears all is_selected flags`() = runTest {
        dao.insertDevice(createTestDevice("device-1", isSelected = true))
        dao.insertDevice(createTestDevice("device-2", isSelected = true))

        dao.clearAllSelections()

        val device1 = dao.getDevice("device-1")
        val device2 = dao.getDevice("device-2")
        assertFalse(device1?.isSelected == true)
        assertFalse(device2?.isSelected == true)
    }

    @Test
    fun `updateIsSelected updates only specified device`() = runTest {
        dao.insertDevice(createTestDevice("device-1", isSelected = false))
        dao.insertDevice(createTestDevice("device-2", isSelected = false))

        dao.updateIsSelected("device-1", true)

        val device1 = dao.getDevice("device-1")
        val device2 = dao.getDevice("device-2")
        assertTrue(device1?.isSelected == true)
        assertFalse(device2?.isSelected == true)
    }
}
