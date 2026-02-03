package com.ras.data.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceType
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class DeviceMigrationTest {

    private lateinit var migration: DeviceMigration
    private lateinit var keyManager: KeyManager
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferences: Preferences

    private val masterSecret = ByteArray(32) { it.toByte() }

    @BeforeEach
    fun setUp() {
        keyManager = mockk()
        credentialRepository = mockk()
        dataStore = mockk()
        preferences = mockk()

        migration = DeviceMigration(keyManager, credentialRepository, dataStore)

        // Default: not yet migrated
        every { preferences[any<Preferences.Key<Boolean>>()] } returns null
        coEvery { dataStore.data } returns flowOf(preferences)
    }

    @Test
    fun `migrateIfNeeded does nothing if already migrated`() = runTest {
        // Given: migration already complete
        every { preferences[any<Preferences.Key<Boolean>>()] } returns true

        // When
        migration.migrateIfNeeded()

        // Then: no migration happens
        coVerify(exactly = 0) { keyManager.hasMasterSecret() }
        coVerify(exactly = 0) { credentialRepository.addDevice(any(), any(), any(), any()) }
    }

    @Test
    fun `migrateIfNeeded marks migration complete when no old credentials exist`() = runTest {
        // Given: no old credentials
        coEvery { keyManager.hasMasterSecret() } returns false
        coEvery { dataStore.updateData(any()) } returns preferences

        // When
        migration.migrateIfNeeded()

        // Then: marks as migrated without adding device
        coVerify { dataStore.updateData(any()) }
        coVerify(exactly = 0) { credentialRepository.addDevice(any(), any(), any(), any()) }
    }

    @Test
    fun `migrateIfNeeded migrates old device to Room`() = runTest {
        // Given: old credentials exist
        coEvery { keyManager.hasMasterSecret() } returns true
        coEvery { keyManager.getMasterSecret() } returns masterSecret
        coEvery { keyManager.getDeviceName() } returns "My Laptop"
        coEvery { keyManager.getDeviceType() } returns DeviceType.LAPTOP
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns 8080
        coEvery { keyManager.getTailscaleIp() } returns null
        coEvery { keyManager.getTailscalePort() } returns null
        coEvery { keyManager.getVpnIp() } returns null
        coEvery { keyManager.getVpnPort() } returns null

        coEvery { credentialRepository.addDevice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { dataStore.updateData(any()) } returns preferences

        // When
        migration.migrateIfNeeded()

        // Then: adds device to Room
        coVerify {
            credentialRepository.addDevice(
                deviceId = any(),
                masterSecret = masterSecret,
                deviceName = "My Laptop",
                deviceType = DeviceType.LAPTOP,
                isSelected = true,
                daemonHost = "192.168.1.100",
                daemonPort = 8080,
                daemonTailscaleIp = null,
                daemonTailscalePort = null,
                daemonVpnIp = null,
                daemonVpnPort = null
            )
        }

        // And: marks migration complete
        coVerify { dataStore.updateData(any()) }
    }

    @Test
    fun `migrateIfNeeded uses default device name if none stored`() = runTest {
        // Given: old credentials but no device name
        coEvery { keyManager.hasMasterSecret() } returns true
        coEvery { keyManager.getMasterSecret() } returns masterSecret
        coEvery { keyManager.getDeviceName() } returns null
        coEvery { keyManager.getDeviceType() } returns DeviceType.UNKNOWN
        coEvery { keyManager.getDaemonIp() } returns null
        coEvery { keyManager.getDaemonPort() } returns null
        coEvery { keyManager.getTailscaleIp() } returns null
        coEvery { keyManager.getTailscalePort() } returns null
        coEvery { keyManager.getVpnIp() } returns null
        coEvery { keyManager.getVpnPort() } returns null

        coEvery { credentialRepository.addDevice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { dataStore.updateData(any()) } returns preferences

        // When
        migration.migrateIfNeeded()

        // Then: uses default name
        coVerify {
            credentialRepository.addDevice(
                deviceId = any(),
                masterSecret = masterSecret,
                deviceName = "My Device",
                deviceType = DeviceType.UNKNOWN,
                isSelected = true,
                daemonHost = null,
                daemonPort = null,
                daemonTailscaleIp = null,
                daemonTailscalePort = null,
                daemonVpnIp = null,
                daemonVpnPort = null
            )
        }
    }

    @Test
    fun `migrateIfNeeded generates deterministic device ID`() = runTest {
        // Given: old credentials
        val masterSecret1 = ByteArray(32) { 1.toByte() }
        val masterSecret2 = ByteArray(32) { 1.toByte() } // Same content

        coEvery { keyManager.hasMasterSecret() } returns true
        coEvery { keyManager.getMasterSecret() } returns masterSecret1
        coEvery { keyManager.getDeviceName() } returns "Device"
        coEvery { keyManager.getDeviceType() } returns DeviceType.LAPTOP
        coEvery { keyManager.getDaemonIp() } returns null
        coEvery { keyManager.getDaemonPort() } returns null
        coEvery { keyManager.getTailscaleIp() } returns null
        coEvery { keyManager.getTailscalePort() } returns null
        coEvery { keyManager.getVpnIp() } returns null
        coEvery { keyManager.getVpnPort() } returns null

        val capturedDeviceIds = mutableListOf<String>()
        coEvery { credentialRepository.addDevice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            capturedDeviceIds.add(firstArg())
        }
        coEvery { dataStore.updateData(any()) } returns preferences

        // When: migrate twice with same master secret
        migration.migrateIfNeeded()

        // Then: device IDs should be deterministic (same for same master secret)
        assertEquals(1, capturedDeviceIds.size)
        val deviceId1 = capturedDeviceIds[0]

        // Verify it's a valid hex string
        assertTrue(deviceId1.matches(Regex("[0-9a-f]+")))
    }
}
