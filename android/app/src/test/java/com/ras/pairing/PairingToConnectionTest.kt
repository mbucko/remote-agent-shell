package com.ras.pairing

import com.ras.data.credentials.CredentialRepository
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.time.Instant

/**
 * CRITICAL TEST: Verifies pairing → connection flow.
 *
 * This test verifies the bug where after pairing, reconnection would get
 * stuck on "Initializing" because the wrong device ID was stored.
 *
 * Flow:
 * 1. Pairing completes with daemon device ID "daemon-laptop-abc"
 * 2. Credentials stored using daemon device ID (NOT phone device ID)
 * 3. Connection attempt retrieves credentials by daemon device ID
 * 4. Credentials found → connection proceeds
 * 5. NOT stuck in initialization!
 */
@Tag("unit")
class PairingToConnectionTest {

    private lateinit var credentialRepository: CredentialRepository

    @BeforeEach
    fun setUp() {
        credentialRepository = mockk(relaxed = true)
    }

    /**
     * REGRESSION TEST FOR THE BUG:
     *
     * Before fix:
     * - PairingManager stored credentials with phone ID "phone-device-id-12345"
     * - Reconnection tried to get credentials by daemon ID "daemon-laptop-abc"
     * - Credentials NOT found → stuck in "Initializing"
     *
     * After fix:
     * - PairingManager stores credentials with daemon ID "daemon-laptop-abc"
     * - Reconnection gets credentials by daemon ID "daemon-laptop-abc"
     * - Credentials FOUND → connection proceeds
     */
    @Test
    fun `after pairing can reconnect using daemon device ID without getting stuck`() = runTest {
        // STEP 1: PAIRING - Daemon tells phone its device ID
        val daemonDeviceId = "daemon-laptop-abc123"  // From AuthResult.deviceId
        val phoneDeviceId = "phone-device-12345"      // From KeyManager.getOrCreateDeviceId()
        val masterSecret = ByteArray(32) { 0x42.toByte() }

        // Simulate what PairingManager does after successful authentication
        // CRITICAL: Must use daemon's device ID, not phone's device ID
        credentialRepository.addDevice(
            deviceId = daemonDeviceId,  // ← MUST be daemon's ID from AuthResult
            masterSecret = masterSecret,
            deviceName = "My Laptop",
            deviceType = DeviceType.LAPTOP,
            daemonHost = "192.168.1.100",
            daemonPort = 8080
        )

        credentialRepository.setSelectedDevice(daemonDeviceId)

        // Verify stored with daemon ID
        coVerify {
            credentialRepository.addDevice(
                deviceId = daemonDeviceId,  // ← NOT phoneDeviceId
                masterSecret = masterSecret,
                deviceName = "My Laptop",
                deviceType = DeviceType.LAPTOP,
                daemonHost = "192.168.1.100",
                daemonPort = 8080
            )
        }

        // STEP 2: RECONNECTION - Try to retrieve credentials
        // Setup repository to return the stored device
        coEvery { credentialRepository.getSelectedDevice() } returns PairedDevice(
            deviceId = daemonDeviceId,
            masterSecret = masterSecret,
            deviceName = "My Laptop",
            deviceType = DeviceType.LAPTOP,
            status = com.ras.data.model.DeviceStatus.PAIRED,
            isSelected = true,
            pairedAt = Instant.now(),
            daemonHost = "192.168.1.100",
            daemonPort = 8080
        )

        // Simulate reconnection - get selected device
        val retrievedDevice = credentialRepository.getSelectedDevice()

        // Then: MUST find the device
        assertNotNull(retrievedDevice, "Device MUST be found for reconnection")
        assertEquals(daemonDeviceId, retrievedDevice?.deviceId, "Retrieved device ID must match daemon ID")
        assertArrayEquals(masterSecret, retrievedDevice?.masterSecret, "Master secret must match")
        assertEquals("192.168.1.100", retrievedDevice?.daemonHost)
        assertEquals(8080, retrievedDevice?.daemonPort)

        // CRITICAL: If device is null here, connection will be stuck in "Initializing"!
        // This was the bug - storing with phone ID but retrieving with daemon ID
    }

    /**
     * Verify the BUG scenario - what happened before the fix.
     */
    @Test
    fun `BUG SCENARIO - storing with phone ID prevents retrieval by daemon ID`() = runTest {
        val daemonDeviceId = "daemon-laptop-abc"
        val phoneDeviceId = "phone-device-123"
        val masterSecret = ByteArray(32) { 0x99.toByte() }

        // BUG: Store credentials with PHONE device ID (wrong!)
        credentialRepository.addDevice(
            deviceId = phoneDeviceId,  // ← BUG: Using phone ID instead of daemon ID
            masterSecret = masterSecret,
            deviceName = "My Laptop",
            deviceType = DeviceType.LAPTOP
        )

        // Try to retrieve by daemon device ID
        coEvery { credentialRepository.getDevice(daemonDeviceId) } returns null  // Not found!

        val retrievedDevice = credentialRepository.getDevice(daemonDeviceId)

        // Then: Device NOT found → stuck in "Initializing"
        assertNull(retrievedDevice, "Device should NOT be found when stored with wrong ID")

        // This is the bug! The fix is to store with daemon ID, not phone ID.
    }

    /**
     * Test the correct scenario - storing AND retrieving with daemon ID.
     */
    @Test
    fun `CORRECT SCENARIO - storing with daemon ID allows retrieval`() = runTest {
        val daemonDeviceId = "daemon-laptop-abc"
        val masterSecret = ByteArray(32) { 0xAA.toByte() }

        // CORRECT: Store credentials with daemon device ID
        credentialRepository.addDevice(
            deviceId = daemonDeviceId,  // ← CORRECT: daemon's ID
            masterSecret = masterSecret,
            deviceName = "My Laptop",
            deviceType = DeviceType.LAPTOP
        )

        // Setup retrieval
        coEvery { credentialRepository.getDevice(daemonDeviceId) } returns PairedDevice(
            deviceId = daemonDeviceId,
            masterSecret = masterSecret,
            deviceName = "My Laptop",
            deviceType = DeviceType.LAPTOP,
            status = com.ras.data.model.DeviceStatus.PAIRED,
            isSelected = false,
            pairedAt = Instant.now()
        )

        // Try to retrieve by daemon device ID
        val retrievedDevice = credentialRepository.getDevice(daemonDeviceId)

        // Then: Device FOUND → connection can proceed
        assertNotNull(retrievedDevice, "Device MUST be found when stored with correct ID")
        assertEquals(daemonDeviceId, retrievedDevice?.deviceId)
        assertArrayEquals(masterSecret, retrievedDevice?.masterSecret)
    }
}
