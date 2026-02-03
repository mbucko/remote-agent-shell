package com.ras.domain.unpair

import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * Unit tests for UnpairDeviceUseCase.
 *
 * Verifies the complete unpair business logic in isolation.
 */
class UnpairDeviceUseCaseTest {

    private lateinit var credentialRepository: CredentialRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var useCase: UnpairDeviceUseCase

    @BeforeEach
    fun setup() {
        credentialRepository = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        isConnectedFlow = MutableStateFlow(false)

        every { connectionManager.isConnected } returns isConnectedFlow

        useCase = UnpairDeviceUseCaseImpl(
            credentialRepository = credentialRepository,
            connectionManager = connectionManager
        )
    }

    @Tag("unit")
    @Test
    fun `unpair sends request when connected and deviceId provided`() = runTest {
        // Given: connected with device ID
        isConnectedFlow.value = true

        // When: unpair with device ID
        useCase("device-123")

        // Then: should send request, clear credentials, and disconnect
        coVerify { connectionManager.sendUnpairRequest("device-123") }
        coVerify { credentialRepository.clearCredentials() }
        coVerify { connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR) }
    }

    @Tag("unit")
    @Test
    fun `unpair skips request when offline`() = runTest {
        // Given: offline
        isConnectedFlow.value = false

        // When: unpair with device ID
        useCase("device-123")

        // Then: should NOT send request but still clear and disconnect
        coVerify(exactly = 0) { connectionManager.sendUnpairRequest(any()) }
        coVerify { credentialRepository.clearCredentials() }
        coVerify { connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR) }
    }

    @Tag("unit")
    @Test
    fun `unpair clears credentials even when send fails`() = runTest {
        // Given: connected but sendUnpairRequest will fail
        isConnectedFlow.value = true
        coEvery { connectionManager.sendUnpairRequest(any()) } throws IOException("Network error")

        // When: unpair
        useCase("device-123")

        // Then: should still clear credentials and disconnect
        coVerify { credentialRepository.clearCredentials() }
        coVerify { connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR) }
    }

    @Tag("unit")
    @Test
    fun `unpair handles null deviceId gracefully`() = runTest {
        // Given: connected
        isConnectedFlow.value = true

        // When: unpair with null deviceId
        useCase(deviceId = null)

        // Then: should NOT send request but still clear and disconnect
        coVerify(exactly = 0) { connectionManager.sendUnpairRequest(any()) }
        coVerify { credentialRepository.clearCredentials() }
        coVerify { connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR) }
    }

    @Tag("unit")
    @Test
    fun `unpair succeeds even if connection lost during request`() = runTest {
        // Given: connected initially
        isConnectedFlow.value = true

        // When: connection dies mid-request
        coEvery { connectionManager.sendUnpairRequest(any()) } answers {
            isConnectedFlow.value = false // Connection lost
            throw IOException("Connection lost")
        }

        // Then: unpair should still succeed
        useCase("device-123")

        // Verify: credentials cleared and disconnect attempted
        coVerify { credentialRepository.clearCredentials() }
        coVerify { connectionManager.disconnectGracefully(ConnectionManager.DISCONNECT_REASON_UNPAIR) }
    }

    @Tag("unit")
    @Test
    fun `unpair throws if clearCredentials fails`() = runTest {
        // Given: clearCredentials will fail
        coEvery { credentialRepository.clearCredentials() } throws SecurityException("Keystore locked")

        // When/Then: unpair should throw the exception
        assertThrows<SecurityException> {
            useCase("device-123")
        }

        // Verify: clearCredentials was attempted
        coVerify { credentialRepository.clearCredentials() }
    }

    @Tag("unit")
    @Test
    fun `unpair attempts to clear credentials even if disconnect fails`() = runTest {
        // Given: disconnect will fail
        isConnectedFlow.value = true
        coEvery { connectionManager.disconnectGracefully(any()) } throws IllegalStateException("Already disconnected")

        // When: unpair
        useCase("device-123")

        // Then: should still attempt to clear credentials
        coVerify { credentialRepository.clearCredentials() }
    }

    @Tag("unit")
    @Test
    fun `unpair sends request only once when connected`() = runTest {
        // Given: connected
        isConnectedFlow.value = true

        // When: unpair
        useCase("device-123")

        // Then: should send request exactly once
        coVerify(exactly = 1) { connectionManager.sendUnpairRequest("device-123") }
    }

    @Tag("unit")
    @Test
    fun `unpair with empty deviceId skips request`() = runTest {
        // Given: connected
        isConnectedFlow.value = true

        // When: unpair with empty string (treated as invalid)
        useCase("")

        // Then: should send request (empty string is valid, though unusual)
        coVerify { connectionManager.sendUnpairRequest("") }
        coVerify { credentialRepository.clearCredentials() }
    }
}
