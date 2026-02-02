package com.ras.data.credentials

import com.ras.crypto.KeyDerivation
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for CredentialRepositoryImpl.
 *
 * Tests credential storage/retrieval via KeyManager wrapper.
 * Key behaviors:
 * - ntfyTopic is DERIVED from master_secret, not stored
 * - daemon IP/port are OPTIONAL (discovered via mDNS)
 * - only master_secret is required for getCredentials to succeed
 */
class CredentialRepositoryImplTest {

    private lateinit var keyManager: KeyManager
    private lateinit var repository: CredentialRepository

    @BeforeEach
    fun setup() {
        keyManager = mockk(relaxed = true)
        repository = CredentialRepositoryImpl(keyManager)
    }

    // ==========================================================================
    // hasCredentials Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `hasCredentials returns true when master secret exists`() = runTest {
        coEvery { keyManager.hasMasterSecret() } returns true

        val result = repository.hasCredentials()

        assertTrue(result)
    }

    @Tag("unit")
    @Test
    fun `hasCredentials returns false when master secret missing`() = runTest {
        coEvery { keyManager.hasMasterSecret() } returns false

        val result = repository.hasCredentials()

        assertFalse(result)
    }

    // ==========================================================================
    // getCredentials Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `getCredentials returns StoredCredentials when master secret exists`() = runTest {
        val masterSecret = ByteArray(32) { it.toByte() }
        every { keyManager.getOrCreateDeviceId() } returns "device123"
        coEvery { keyManager.getMasterSecret() } returns masterSecret
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns 8765

        val result = repository.getCredentials()

        assertNotNull(result)
        assertEquals("device123", result!!.deviceId)
        assertTrue(masterSecret.contentEquals(result.masterSecret))
        assertEquals("192.168.1.100", result.daemonHost)
        assertEquals(8765, result.daemonPort)
    }

    @Tag("unit")
    @Test
    fun `getCredentials derives ntfyTopic from master secret`() = runTest {
        val masterSecret = ByteArray(32) { it.toByte() }
        val expectedTopic = KeyDerivation.deriveNtfyTopic(masterSecret)
        every { keyManager.getOrCreateDeviceId() } returns "device123"
        coEvery { keyManager.getMasterSecret() } returns masterSecret

        val result = repository.getCredentials()

        assertNotNull(result)
        assertEquals(expectedTopic, result!!.ntfyTopic)
    }

    @Tag("unit")
    @Test
    fun `getCredentials returns null when master secret missing`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns null

        val result = repository.getCredentials()

        assertNull(result)
    }

    @Tag("unit")
    @Test
    fun `getCredentials succeeds when daemon IP is null`() = runTest {
        val masterSecret = ByteArray(32) { it.toByte() }
        every { keyManager.getOrCreateDeviceId() } returns "device123"
        coEvery { keyManager.getMasterSecret() } returns masterSecret
        coEvery { keyManager.getDaemonIp() } returns null
        coEvery { keyManager.getDaemonPort() } returns 8765

        val result = repository.getCredentials()

        assertNotNull(result)
        assertNull(result!!.daemonHost)
        assertEquals(8765, result.daemonPort)
    }

    @Tag("unit")
    @Test
    fun `getCredentials succeeds when daemon port is null`() = runTest {
        val masterSecret = ByteArray(32) { it.toByte() }
        every { keyManager.getOrCreateDeviceId() } returns "device123"
        coEvery { keyManager.getMasterSecret() } returns masterSecret
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns null

        val result = repository.getCredentials()

        assertNotNull(result)
        assertEquals("192.168.1.100", result!!.daemonHost)
        assertNull(result.daemonPort)
    }

    @Tag("unit")
    @Test
    fun `getCredentials succeeds when all optional IPs are null`() = runTest {
        val masterSecret = ByteArray(32) { it.toByte() }
        every { keyManager.getOrCreateDeviceId() } returns "device123"
        coEvery { keyManager.getMasterSecret() } returns masterSecret
        coEvery { keyManager.getDaemonIp() } returns null
        coEvery { keyManager.getDaemonPort() } returns null
        coEvery { keyManager.getTailscaleIp() } returns null
        coEvery { keyManager.getTailscalePort() } returns null
        coEvery { keyManager.getVpnIp() } returns null
        coEvery { keyManager.getVpnPort() } returns null

        val result = repository.getCredentials()

        assertNotNull(result)
        assertNull(result!!.daemonHost)
        assertNull(result.daemonPort)
        assertNull(result.daemonTailscaleIp)
        assertNull(result.daemonTailscalePort)
        assertNull(result.daemonVpnIp)
        assertNull(result.daemonVpnPort)
        // ntfyTopic is still derived
        assertNotNull(result.ntfyTopic)
    }

    @Tag("unit")
    @Test
    fun `getCredentials includes tailscale and vpn info when present`() = runTest {
        val masterSecret = ByteArray(32) { it.toByte() }
        every { keyManager.getOrCreateDeviceId() } returns "device123"
        coEvery { keyManager.getMasterSecret() } returns masterSecret
        coEvery { keyManager.getDaemonIp() } returns null
        coEvery { keyManager.getDaemonPort() } returns null
        coEvery { keyManager.getTailscaleIp() } returns "100.64.0.1"
        coEvery { keyManager.getTailscalePort() } returns 8765
        coEvery { keyManager.getVpnIp() } returns "10.0.0.5"
        coEvery { keyManager.getVpnPort() } returns 8766

        val result = repository.getCredentials()

        assertNotNull(result)
        assertEquals("100.64.0.1", result!!.daemonTailscaleIp)
        assertEquals(8765, result.daemonTailscalePort)
        assertEquals("10.0.0.5", result.daemonVpnIp)
        assertEquals(8766, result.daemonVpnPort)
    }

    // ==========================================================================
    // getDeviceName Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `getDeviceName returns name from keyManager`() = runTest {
        coEvery { keyManager.getDeviceName() } returns "MacBook-Pro.local"

        val result = repository.getDeviceName()

        assertEquals("MacBook-Pro.local", result)
    }

    @Tag("unit")
    @Test
    fun `getDeviceName returns null when not stored`() = runTest {
        coEvery { keyManager.getDeviceName() } returns null

        val result = repository.getDeviceName()

        assertNull(result)
    }

    // ==========================================================================
    // getDeviceType Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `getDeviceType returns type from keyManager`() = runTest {
        coEvery { keyManager.getDeviceType() } returns DeviceType.LAPTOP

        val result = repository.getDeviceType()

        assertEquals(DeviceType.LAPTOP, result)
    }

    @Tag("unit")
    @Test
    fun `getDeviceType returns UNKNOWN when not stored`() = runTest {
        coEvery { keyManager.getDeviceType() } returns DeviceType.UNKNOWN

        val result = repository.getDeviceType()

        assertEquals(DeviceType.UNKNOWN, result)
    }

    // ==========================================================================
    // clearCredentials Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `clearCredentials calls keyManager clearCredentials`() = runTest {
        repository.clearCredentials()

        coVerify { keyManager.clearCredentials() }
    }

    // ==========================================================================
    // Device ID Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `getCredentials preserves device ID from KeyManager`() = runTest {
        val expectedDeviceId = "f40c395f078f4f1da3a7ce9393ddaadf"
        every { keyManager.getOrCreateDeviceId() } returns expectedDeviceId
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)

        val result = repository.getCredentials()

        assertEquals(expectedDeviceId, result?.deviceId)
    }
}
