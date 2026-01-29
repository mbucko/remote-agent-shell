package com.ras.domain.startup

import com.ras.data.reconnection.ReconnectionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AttemptReconnectionUseCaseImpl.
 */
class AttemptReconnectionUseCaseTest {

    private lateinit var reconnectionService: ReconnectionService

    @Before
    fun setup() {
        reconnectionService = mockk()
    }

    private fun createUseCase() = AttemptReconnectionUseCaseImpl(
        reconnectionService = reconnectionService
    )

    @Test
    fun `invoke calls reconnectionService`() = runTest {
        coEvery { reconnectionService.reconnect() } returns ReconnectionResult.Success

        val useCase = createUseCase()
        useCase()

        coVerify { reconnectionService.reconnect() }
    }

    @Test
    fun `invoke returns Success when reconnection succeeds`() = runTest {
        coEvery { reconnectionService.reconnect() } returns ReconnectionResult.Success

        val useCase = createUseCase()
        val result = useCase()

        assertEquals(ReconnectionResult.Success, result)
    }

    @Test
    fun `invoke returns NoCredentials when no stored credentials`() = runTest {
        coEvery { reconnectionService.reconnect() } returns ReconnectionResult.Failure.NoCredentials

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.NoCredentials)
    }

    @Test
    fun `invoke returns DaemonUnreachable when daemon is unreachable`() = runTest {
        coEvery { reconnectionService.reconnect() } returns ReconnectionResult.Failure.DaemonUnreachable

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.DaemonUnreachable)
    }

    @Test
    fun `invoke returns AuthenticationFailed when auth fails`() = runTest {
        coEvery { reconnectionService.reconnect() } returns ReconnectionResult.Failure.AuthenticationFailed

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.AuthenticationFailed)
    }

    @Test
    fun `invoke returns NetworkError on network issues`() = runTest {
        coEvery { reconnectionService.reconnect() } returns ReconnectionResult.Failure.NetworkError

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.NetworkError)
    }

    @Test
    fun `invoke returns Unknown for other errors`() = runTest {
        val errorMessage = "Some unexpected error"
        coEvery { reconnectionService.reconnect() } returns ReconnectionResult.Failure.Unknown(errorMessage)

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.Unknown)
        assertEquals(errorMessage, (result as ReconnectionResult.Failure.Unknown).message)
    }
}
