package com.ras.data.credentials

import com.ras.data.database.PairedDeviceDao
import com.ras.data.database.PairedDeviceEntity
import com.ras.data.encryption.DeviceEncryptionHelper
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.time.Instant

@Tag("unit")
class CredentialRepositoryImplTest {

    private lateinit var repository: CredentialRepositoryImpl
    private lateinit var dao: PairedDeviceDao
    private lateinit var encryptionHelper: DeviceEncryptionHelper
    private lateinit var keyManager: KeyManager

    private val masterSecret = ByteArray(32) { it.toByte() }
    private val encryptedSecret = byteArrayOf(9, 9, 9)
    private val iv = byteArrayOf(8, 8, 8)

    @BeforeEach
    fun setUp() {
        dao = mockk()
        encryptionHelper = mockk()
        keyManager = mockk()
        repository = CredentialRepositoryImpl(dao, encryptionHelper, keyManager)

        // Default encryption behavior
        every { encryptionHelper.encrypt(any()) } returns Pair(encryptedSecret, iv)
        every { encryptionHelper.decrypt(any(), any()) } returns masterSecret
    }

    @Test
    fun `addDevice encrypts and stores device`() = runTest {
        coEvery { dao.insertDevice(any()) } just Runs

        repository.addDevice(
            deviceId = "device-1",
            masterSecret = masterSecret,
            deviceName = "Test Device",
            deviceType = DeviceType.LAPTOP,
            isSelected = true
        )

        coVerify {
            encryptionHelper.encrypt(masterSecret)
            dao.insertDevice(
                withArg { entity ->
                    assertEquals("device-1", entity.deviceId)
                    assertEquals("Test Device", entity.deviceName)
                    assertEquals(DeviceType.LAPTOP.name, entity.deviceType)
                    assertEquals(DeviceStatus.PAIRED.name, entity.status)
                    assertTrue(entity.isSelected)
                    assertArrayEquals(encryptedSecret, entity.masterSecretEncrypted)
                    assertArrayEquals(iv, entity.masterSecretIv)
                }
            )
        }
    }

    @Test
    fun `getDevice returns decrypted device`() = runTest {
        val entity = PairedDeviceEntity(
            deviceId = "device-1",
            masterSecretEncrypted = encryptedSecret,
            masterSecretIv = iv,
            deviceName = "Test Device",
            deviceType = DeviceType.LAPTOP.name,
            status = DeviceStatus.PAIRED.name,
            isSelected = true,
            pairedAt = 1000L
        )
        coEvery { dao.getDevice("device-1") } returns entity

        val device = repository.getDevice("device-1")

        assertNotNull(device)
        assertEquals("device-1", device?.deviceId)
        assertEquals("Test Device", device?.deviceName)
        assertEquals(DeviceType.LAPTOP, device?.deviceType)
        assertEquals(DeviceStatus.PAIRED, device?.status)
        assertTrue(device?.isSelected == true)
        assertArrayEquals(masterSecret, device?.masterSecret)
        verify { encryptionHelper.decrypt(encryptedSecret, iv) }
    }

    @Test
    fun `getDevice returns null for non-existent device`() = runTest {
        coEvery { dao.getDevice("missing") } returns null

        val device = repository.getDevice("missing")

        assertNull(device)
    }

    @Test
    fun `getAllDevices returns decrypted devices`() = runTest {
        val entities = listOf(
            PairedDeviceEntity(
                deviceId = "device-1",
                masterSecretEncrypted = encryptedSecret,
                masterSecretIv = iv,
                deviceName = "Device 1",
                deviceType = DeviceType.LAPTOP.name,
                status = DeviceStatus.PAIRED.name,
                isSelected = true,
                pairedAt = 1000L
            ),
            PairedDeviceEntity(
                deviceId = "device-2",
                masterSecretEncrypted = encryptedSecret,
                masterSecretIv = iv,
                deviceName = "Device 2",
                deviceType = DeviceType.DESKTOP.name,
                status = DeviceStatus.UNPAIRED_BY_USER.name,
                isSelected = false,
                pairedAt = 2000L
            )
        )
        coEvery { dao.getAllPairedDevices() } returns flowOf(entities)

        val devices = repository.getAllDevicesFlow().first()

        assertEquals(2, devices.size)
        assertEquals("device-1", devices[0].deviceId)
        assertEquals("device-2", devices[1].deviceId)
    }

    @Test
    fun `getSelectedDevice returns selected device`() = runTest {
        val entity = PairedDeviceEntity(
            deviceId = "device-1",
            masterSecretEncrypted = encryptedSecret,
            masterSecretIv = iv,
            deviceName = "Selected Device",
            deviceType = DeviceType.LAPTOP.name,
            status = DeviceStatus.PAIRED.name,
            isSelected = true,
            pairedAt = 1000L
        )
        coEvery { dao.getSelectedDevice() } returns entity

        val device = repository.getSelectedDevice()

        assertNotNull(device)
        assertEquals("device-1", device?.deviceId)
        assertTrue(device?.isSelected == true)
    }

    @Test
    fun `getSelectedDevice returns null when no device selected`() = runTest {
        coEvery { dao.getSelectedDevice() } returns null

        val device = repository.getSelectedDevice()

        assertNull(device)
    }

    @Test
    fun `setSelectedDevice updates dao`() = runTest {
        coEvery { dao.setSelectedDevice("device-2") } just Runs

        repository.setSelectedDevice("device-2")

        coVerify { dao.setSelectedDevice("device-2") }
    }

    @Test
    fun `updateDeviceStatus updates status in dao`() = runTest {
        coEvery { dao.updateDeviceStatus("device-1", DeviceStatus.UNPAIRED_BY_DAEMON.name) } just Runs

        repository.updateDeviceStatus("device-1", DeviceStatus.UNPAIRED_BY_DAEMON)

        coVerify { dao.updateDeviceStatus("device-1", DeviceStatus.UNPAIRED_BY_DAEMON.name) }
    }

    @Test
    fun `removeDevice deletes from dao`() = runTest {
        coEvery { dao.deleteDevice("device-1") } just Runs

        repository.removeDevice("device-1")

        coVerify { dao.deleteDevice("device-1") }
    }

    @Test
    fun `unpairDevice marks device as unpaired by user`() = runTest {
        coEvery { dao.updateDeviceStatus("device-1", DeviceStatus.UNPAIRED_BY_USER.name) } just Runs

        repository.unpairDevice("device-1")

        coVerify { dao.updateDeviceStatus("device-1", DeviceStatus.UNPAIRED_BY_USER.name) }
    }

    @Test
    fun `hasCredentials returns true when selected device exists`() = runTest {
        val entity = mockk<PairedDeviceEntity>()
        coEvery { dao.getSelectedDevice() } returns entity

        val result = repository.hasCredentials()

        assertTrue(result)
    }

    @Test
    fun `hasCredentials returns false when no selected device`() = runTest {
        coEvery { dao.getSelectedDevice() } returns null

        val result = repository.hasCredentials()

        assertFalse(result)
    }

    @Test
    fun `getCredentials returns StoredCredentials for selected device`() = runTest {
        val entity = PairedDeviceEntity(
            deviceId = "device-1",
            masterSecretEncrypted = encryptedSecret,
            masterSecretIv = iv,
            deviceName = "Test Device",
            deviceType = DeviceType.LAPTOP.name,
            status = DeviceStatus.PAIRED.name,
            isSelected = true,
            pairedAt = 1000L,
            daemonHost = "192.168.1.100",
            daemonPort = 8080
        )
        coEvery { dao.getSelectedDevice() } returns entity

        val credentials = repository.getCredentials()

        assertNotNull(credentials)
        assertEquals("device-1", credentials?.deviceId)
        assertArrayEquals(masterSecret, credentials?.masterSecret)
        assertEquals("192.168.1.100", credentials?.daemonHost)
        assertEquals(8080, credentials?.daemonPort)
    }
}
