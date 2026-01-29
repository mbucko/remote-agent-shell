package com.ras.data.credentials

import com.ras.data.keystore.KeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CredentialRepositoryImpl.
 * Tests credential storage/retrieval via KeyManager wrapper.
 */
class CredentialRepositoryImplTest {

    private lateinit var keyManager: KeyManager
    private lateinit var repository: CredentialRepository

    @Before
    fun setup() {
        keyManager = mockk(relaxed = true)
        repository = CredentialRepositoryImpl(keyManager)
    }

    // ==========================================================================
    // hasCredentials Tests
    // ==========================================================================

    @Test
    fun `hasCredentials returns true when master secret exists`() = runTest {
        coEvery { keyManager.hasMasterSecret() } returns true

        val result = repository.hasCredentials()

        assertTrue(result)
    }

    @Test
    fun `hasCredentials returns false when master secret missing`() = runTest {
        coEvery { keyManager.hasMasterSecret() } returns false

        val result = repository.hasCredentials()

        assertFalse(result)
    }

    // ==========================================================================
    // getCredentials Tests
    // ==========================================================================

    @Test
    fun `getCredentials returns StoredCredentials when all fields exist`() = runTest {
        val masterSecret = ByteArray(32) { it.toByte() }
        every { keyManager.getOrCreateDeviceId() } returns "device123"
        coEvery { keyManager.getMasterSecret() } returns masterSecret
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns 8765
        coEvery { keyManager.getNtfyTopic() } returns "ras-abc123"

        val result = repository.getCredentials()

        assertNotNull(result)
        assertEquals("device123", result!!.deviceId)
        assertTrue(masterSecret.contentEquals(result.masterSecret))
        assertEquals("192.168.1.100", result.daemonHost)
        assertEquals(8765, result.daemonPort)
        assertEquals("ras-abc123", result.ntfyTopic)
    }

    @Test
    fun `getCredentials returns null when master secret missing`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns null
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns 8765
        coEvery { keyManager.getNtfyTopic() } returns "ras-abc123"

        val result = repository.getCredentials()

        assertNull(result)
    }

    @Test
    fun `getCredentials returns null when daemon IP missing`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)
        coEvery { keyManager.getDaemonIp() } returns null
        coEvery { keyManager.getDaemonPort() } returns 8765
        coEvery { keyManager.getNtfyTopic() } returns "ras-abc123"

        val result = repository.getCredentials()

        assertNull(result)
    }

    @Test
    fun `getCredentials returns null when daemon port missing`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns null
        coEvery { keyManager.getNtfyTopic() } returns "ras-abc123"

        val result = repository.getCredentials()

        assertNull(result)
    }

    @Test
    fun `getCredentials returns null when ntfy topic missing`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns 8765
        coEvery { keyManager.getNtfyTopic() } returns null

        val result = repository.getCredentials()

        assertNull(result)
    }

    // ==========================================================================
    // clearCredentials Tests
    // ==========================================================================

    @Test
    fun `clearCredentials calls keyManager clearCredentials`() = runTest {
        repository.clearCredentials()

        coVerify { keyManager.clearCredentials() }
    }

    // ==========================================================================
    // Edge Cases
    // ==========================================================================

    @Test
    fun `getCredentials handles empty daemon IP`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)
        coEvery { keyManager.getDaemonIp() } returns ""
        coEvery { keyManager.getDaemonPort() } returns 8765
        coEvery { keyManager.getNtfyTopic() } returns "ras-abc123"

        // Empty string should be treated as missing
        val result = repository.getCredentials()

        assertNull(result)
    }

    @Test
    fun `getCredentials handles empty ntfy topic`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns 8765
        coEvery { keyManager.getNtfyTopic() } returns ""

        // Empty string should be treated as missing
        val result = repository.getCredentials()

        assertNull(result)
    }

    @Test
    fun `getCredentials handles zero port`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns 0
        coEvery { keyManager.getNtfyTopic() } returns "ras-abc123"

        // Zero port is invalid
        val result = repository.getCredentials()

        assertNull(result)
    }

    @Test
    fun `getCredentials handles negative port`() = runTest {
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns -1
        coEvery { keyManager.getNtfyTopic() } returns "ras-abc123"

        // Negative port is invalid
        val result = repository.getCredentials()

        assertNull(result)
    }

    @Test
    fun `getCredentials preserves device ID from KeyManager`() = runTest {
        val expectedDeviceId = "f40c395f078f4f1da3a7ce9393ddaadf"
        every { keyManager.getOrCreateDeviceId() } returns expectedDeviceId
        coEvery { keyManager.getMasterSecret() } returns ByteArray(32)
        coEvery { keyManager.getDaemonIp() } returns "192.168.1.100"
        coEvery { keyManager.getDaemonPort() } returns 8765
        coEvery { keyManager.getNtfyTopic() } returns "ras-abc123"

        val result = repository.getCredentials()

        assertEquals(expectedDeviceId, result?.deviceId)
    }
}
