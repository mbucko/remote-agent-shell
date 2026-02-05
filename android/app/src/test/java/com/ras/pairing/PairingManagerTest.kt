package com.ras.pairing

import com.ras.data.credentials.CredentialRepository
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceType
import com.ras.signaling.MockNtfyClient
import com.ras.signaling.NtfyClientInterface
import com.ras.signaling.PairExchangeResult
import com.ras.signaling.PairExchanger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
@OptIn(ExperimentalCoroutinesApi::class)
class PairingManagerTest {

    private lateinit var pairingManager: PairingManager
    private lateinit var keyManager: KeyManager
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var ntfyClient: NtfyClientInterface
    private lateinit var progressTracker: PairingProgressTracker
    private lateinit var mockExchanger: PairExchanger

    private val testDispatcher = UnconfinedTestDispatcher()

    private val phoneDeviceId = "phone-device-id-12345"
    private val daemonDeviceId = "daemon-device-abc123"

    @BeforeEach
    fun setUp() {
        keyManager = mockk(relaxed = true)
        credentialRepository = mockk(relaxed = true)
        ntfyClient = mockk(relaxed = true)
        progressTracker = mockk(relaxed = true)
        mockExchanger = mockk(relaxed = true)

        every { keyManager.getOrCreateDeviceId() } returns phoneDeviceId
        coEvery { credentialRepository.addDevice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { credentialRepository.setSelectedDevice(any()) } just Runs

        pairingManager = PairingManager(
            keyManager = keyManager,
            credentialRepository = credentialRepository,
            ntfyClient = ntfyClient,
            progressTracker = progressTracker
        )

        // Inject mock PairExchanger via factory
        pairingManager.pairExchangerFactory = { _ -> mockExchanger }

        // Override scope for synchronous testing
        pairingManager.scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
    }

    private fun createPayload(
        ip: String? = "192.168.1.100",
        port: Int? = 8080,
        tailscaleIp: String? = null,
        tailscalePort: Int? = null,
        vpnIp: String? = null,
        vpnPort: Int? = null
    ): ParsedQrPayload {
        return ParsedQrPayload(
            version = 1,
            ip = ip,
            port = port,
            masterSecret = ByteArray(32) { 0x42.toByte() },
            sessionId = "session-123",
            ntfyTopic = "topic-123",
            tailscaleIp = tailscaleIp,
            tailscalePort = tailscalePort,
            vpnIp = vpnIp,
            vpnPort = vpnPort
        )
    }

    @Test
    fun `startPairing transitions to QrParsed then ExchangingCredentials`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Success(daemonDeviceId, "my-laptop", DeviceType.LAPTOP)

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        // Should have passed through ExchangingCredentials and ended at Authenticated
        assertTrue(pairingManager.state.value is PairingState.Authenticated)
    }

    @Test
    fun `successful exchange stores device in credential repository`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Success(daemonDeviceId, "my-laptop", DeviceType.LAPTOP)

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        coVerify {
            credentialRepository.addDevice(
                deviceId = daemonDeviceId,
                masterSecret = any(),
                deviceName = "my-laptop",
                deviceType = DeviceType.LAPTOP,
                daemonHost = "192.168.1.100",
                daemonPort = 8080,
                daemonTailscaleIp = null,
                daemonTailscalePort = null,
                daemonVpnIp = null,
                daemonVpnPort = null,
                phoneDeviceId = phoneDeviceId
            )
        }
    }

    @Test
    fun `successful exchange sets device as selected`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Success(daemonDeviceId, "my-laptop", DeviceType.LAPTOP)

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        coVerify {
            credentialRepository.setSelectedDevice(daemonDeviceId)
        }
    }

    @Test
    fun `successful exchange stores phone device ID`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Success(daemonDeviceId, "my-laptop", DeviceType.LAPTOP)

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        coVerify {
            credentialRepository.addDevice(
                deviceId = daemonDeviceId,
                masterSecret = any(),
                deviceName = any(),
                deviceType = any(),
                daemonHost = any(),
                daemonPort = any(),
                daemonTailscaleIp = any(),
                daemonTailscalePort = any(),
                daemonVpnIp = any(),
                daemonVpnPort = any(),
                phoneDeviceId = phoneDeviceId
            )
        }
    }

    @Test
    fun `successful exchange transitions to Authenticated state`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Success(daemonDeviceId, "my-laptop", DeviceType.LAPTOP)

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        val state = pairingManager.state.value
        assertTrue(state is PairingState.Authenticated)
        assertEquals(daemonDeviceId, (state as PairingState.Authenticated).deviceId)
    }

    @Test
    fun `exchange timeout transitions to Failed with NTFY_TIMEOUT reason`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Timeout

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        val state = pairingManager.state.value
        assertTrue(state is PairingState.Failed)
        assertEquals(PairingState.FailureReason.NTFY_TIMEOUT, (state as PairingState.Failed).reason)
    }

    @Test
    fun `exchange auth failure transitions to Failed with AUTH_FAILED reason`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.AuthFailed

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        val state = pairingManager.state.value
        assertTrue(state is PairingState.Failed)
        assertEquals(PairingState.FailureReason.AUTH_FAILED, (state as PairingState.Failed).reason)
    }

    @Test
    fun `exchange error transitions to Failed with SIGNALING_FAILED reason`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Error("Connection refused")

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        val state = pairingManager.state.value
        assertTrue(state is PairingState.Failed)
        assertEquals(PairingState.FailureReason.SIGNALING_FAILED, (state as PairingState.Failed).reason)
    }

    @Test
    fun `reset clears state back to Idle`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Timeout

        pairingManager.startPairing(createPayload())
        advanceUntilIdle()

        pairingManager.reset()

        assertEquals(PairingState.Idle, pairingManager.state.value)
    }

    @Test
    fun `stores all connection hints from QR payload`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Success(daemonDeviceId, "my-laptop", DeviceType.LAPTOP)

        val payload = createPayload(
            ip = "192.168.1.100",
            port = 8080,
            tailscaleIp = "100.64.1.2",
            tailscalePort = 8081,
            vpnIp = "10.8.0.1",
            vpnPort = 8082
        )

        pairingManager.startPairing(payload)
        advanceUntilIdle()

        coVerify {
            credentialRepository.addDevice(
                deviceId = daemonDeviceId,
                masterSecret = any(),
                deviceName = "my-laptop",
                deviceType = DeviceType.LAPTOP,
                daemonHost = "192.168.1.100",
                daemonPort = 8080,
                daemonTailscaleIp = "100.64.1.2",
                daemonTailscalePort = 8081,
                daemonVpnIp = "10.8.0.1",
                daemonVpnPort = 8082,
                phoneDeviceId = phoneDeviceId
            )
        }
    }

    @Test
    fun `stores credentials with null optional fields`() = runTest {
        coEvery { mockExchanger.exchange(any(), any(), any(), any(), any()) } returns
            PairExchangeResult.Success(daemonDeviceId, "my-laptop", DeviceType.SERVER)

        val payload = createPayload(
            ip = null,
            port = null,
            tailscaleIp = null,
            tailscalePort = null,
            vpnIp = null,
            vpnPort = null
        )

        pairingManager.startPairing(payload)
        advanceUntilIdle()

        coVerify {
            credentialRepository.addDevice(
                deviceId = daemonDeviceId,
                masterSecret = any(),
                deviceName = "my-laptop",
                deviceType = DeviceType.SERVER,
                daemonHost = null,
                daemonPort = null,
                daemonTailscaleIp = null,
                daemonTailscalePort = null,
                daemonVpnIp = null,
                daemonVpnPort = null,
                phoneDeviceId = phoneDeviceId
            )
        }
    }
}
