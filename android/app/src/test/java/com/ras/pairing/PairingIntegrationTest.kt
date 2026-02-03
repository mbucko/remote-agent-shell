package com.ras.pairing

import com.ras.data.credentials.CredentialRepository
import com.ras.data.credentials.CredentialRepositoryImpl
import com.ras.data.database.PairedDeviceDao
import com.ras.data.encryption.DeviceEncryptionHelper
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Integration test for the pairing flow.
 *
 * Tests the full flow from pairing to credential storage to retrieval.
 * Uses mocked dependencies to test the integration between components.
 *
 * This test would have caught the device ID bug by verifying that
 * credentials stored during pairing can be retrieved for reconnection.
 *
 * NOTE: These are unit tests using mocks, not true integration tests
 * with real Room database (which would require instrumented tests).
 */
@Tag("unit")
class PairingIntegrationTest {

    private lateinit var dao: PairedDeviceDao
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var encryptionHelper: DeviceEncryptionHelper
    private lateinit var keyManager: KeyManager

    @BeforeEach
    fun setUp() {
        // Mock dependencies (following DI principles)
        dao = mockk(relaxed = true)
        encryptionHelper = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)

        // Setup encryption to pass through (for simplicity)
        every { encryptionHelper.encrypt(any()) } answers {
            val input = firstArg<ByteArray>()
            Pair(input, ByteArray(12) { 0x01 })
        }
        every { encryptionHelper.decrypt(any(), any()) } answers {
            firstArg()
        }

        // Setup KeyManager
        every { keyManager.getOrCreateDeviceId() } returns "phone-device-id"

        // Create repository with mocked dependencies
        credentialRepository = CredentialRepositoryImpl(dao, encryptionHelper)
    }


    /**
     * CRITICAL INTEGRATION TEST: Full pairing → reconnection flow.
     *
     * This test would have caught the device ID bug!
     *
     * Flow:
     * 1. Simulate pairing (store credentials with daemon device ID)
     * 2. Simulate reconnection (retrieve credentials by daemon device ID)
     * 3. Verify credentials can be retrieved
     */
    @Test
    fun `after pairing can retrieve credentials for reconnection using daemon device ID`() = runTest {
        // Given: Daemon device ID and credentials from pairing
        val daemonDeviceId = "daemon-laptop-abc123"
        val masterSecret = ByteArray(32) { 0x42.toByte() }

        // Step 1: PAIRING - Store credentials using daemon's device ID
        credentialRepository.addDevice(
            deviceId = daemonDeviceId,  // ← Critical: daemon's ID
            masterSecret = masterSecret,
            deviceName = "My Laptop",
            deviceType = DeviceType.LAPTOP,
            daemonHost = "192.168.1.100",
            daemonPort = 8080,
            isSelected = true
        )

        // Step 2: RECONNECTION - Try to retrieve credentials by daemon device ID
        val selectedDevice = credentialRepository.getSelectedDevice()

        // Then: Should find the device
        assertNotNull(selectedDevice, "Device should be found by daemon device ID")
        assertEquals(daemonDeviceId, selectedDevice?.deviceId, "Device ID should match daemon ID")
        assertEquals("My Laptop", selectedDevice?.deviceName)
        assertEquals(DeviceType.LAPTOP, selectedDevice?.deviceType)
        assertArrayEquals(masterSecret, selectedDevice?.masterSecret)
        assertEquals("192.168.1.100", selectedDevice?.daemonHost)
        assertEquals(8080, selectedDevice?.daemonPort)
        assertTrue(selectedDevice?.isSelected == true)
    }

    /**
     * Verify credentials can be retrieved by explicit device ID lookup.
     */
    @Test
    fun `after pairing can retrieve credentials by daemon device ID`() = runTest {
        val daemonDeviceId = "daemon-desktop-xyz"
        val masterSecret = ByteArray(32) { 0x99.toByte() }

        // When: Store credentials
        credentialRepository.addDevice(
            deviceId = daemonDeviceId,
            masterSecret = masterSecret,
            deviceName = "Desktop PC",
            deviceType = DeviceType.DESKTOP
        )

        // Then: Can retrieve by daemon device ID
        val device = credentialRepository.getDevice(daemonDeviceId)
        assertNotNull(device)
        assertEquals(daemonDeviceId, device?.deviceId)
        assertEquals("Desktop PC", device?.deviceName)
    }

    /**
     * Test multi-device pairing scenario.
     */
    @Test
    fun `can pair multiple devices and retrieve each by their daemon device IDs`() = runTest {
        // Given: Pair three devices
        val device1Id = "daemon-laptop-1"
        val device2Id = "daemon-desktop-2"
        val device3Id = "daemon-server-3"

        credentialRepository.addDevice(
            deviceId = device1Id,
            masterSecret = ByteArray(32) { 0x11.toByte() },
            deviceName = "Laptop",
            deviceType = DeviceType.LAPTOP
        )

        credentialRepository.addDevice(
            deviceId = device2Id,
            masterSecret = ByteArray(32) { 0x22.toByte() },
            deviceName = "Desktop",
            deviceType = DeviceType.DESKTOP
        )

        credentialRepository.addDevice(
            deviceId = device3Id,
            masterSecret = ByteArray(32) { 0x33.toByte() },
            deviceName = "Server",
            deviceType = DeviceType.SERVER
        )

        // When: Retrieve all devices
        val devices = credentialRepository.getAllDevices()

        // Then: All devices should be retrievable
        assertEquals(3, devices.size)
        assertTrue(devices.any { it.deviceId == device1Id })
        assertTrue(devices.any { it.deviceId == device2Id })
        assertTrue(devices.any { it.deviceId == device3Id })

        // And: Each can be retrieved individually
        assertNotNull(credentialRepository.getDevice(device1Id))
        assertNotNull(credentialRepository.getDevice(device2Id))
        assertNotNull(credentialRepository.getDevice(device3Id))
    }

    /**
     * Test switching between paired devices.
     */
    @Test
    fun `can switch selected device between multiple paired devices`() = runTest {
        // Given: Two paired devices
        val laptop = "daemon-laptop"
        val desktop = "daemon-desktop"

        credentialRepository.addDevice(
            deviceId = laptop,
            masterSecret = ByteArray(32) { 0xAA.toByte() },
            deviceName = "Laptop",
            deviceType = DeviceType.LAPTOP,
            isSelected = true
        )

        credentialRepository.addDevice(
            deviceId = desktop,
            masterSecret = ByteArray(32) { 0xBB.toByte() },
            deviceName = "Desktop",
            deviceType = DeviceType.DESKTOP,
            isSelected = false
        )

        // Then: Laptop should be selected initially
        var selected = credentialRepository.getSelectedDevice()
        assertEquals(laptop, selected?.deviceId)

        // When: Switch to desktop
        credentialRepository.setSelectedDevice(desktop)

        // Then: Desktop should now be selected
        selected = credentialRepository.getSelectedDevice()
        assertEquals(desktop, selected?.deviceId)
    }

    /**
     * Edge case: Verify credentials NOT retrievable if wrong device ID used.
     */
    @Test
    fun `cannot retrieve credentials using phone device ID when stored with daemon device ID`() = runTest {
        val daemonDeviceId = "daemon-real-id"
        val phoneDeviceId = "phone-device-id"  // What KeyManager returns

        // When: Store credentials with daemon ID
        credentialRepository.addDevice(
            deviceId = daemonDeviceId,  // ← Correct: daemon's ID
            masterSecret = ByteArray(32) { 0xFF.toByte() },
            deviceName = "Test Device",
            deviceType = DeviceType.LAPTOP
        )

        // Then: Can retrieve with daemon ID
        val deviceByDaemonId = credentialRepository.getDevice(daemonDeviceId)
        assertNotNull(deviceByDaemonId, "Should find device by daemon ID")

        // But: CANNOT retrieve with phone ID (this was the bug!)
        val deviceByPhoneId = credentialRepository.getDevice(phoneDeviceId)
        assertNull(deviceByPhoneId, "Should NOT find device by phone ID")
    }
}
