package com.ras.domain.startup

import com.ras.data.reconnection.ReconnectionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for AttemptReconnectionUseCaseImpl.
 */
class AttemptReconnectionUseCaseTest {

    private lateinit var reconnectionService: ReconnectionService

    @BeforeEach
    fun setup() {
        reconnectionService = mockk()
    }

    private fun createUseCase() = AttemptReconnectionUseCaseImpl(
        reconnectionService = reconnectionService
    )

    @Tag("unit")
    @Test
    fun `invoke calls reconnectionService`() = runTest {
        coEvery { reconnectionService.reconnect(any()) } returns ReconnectionResult.Success

        val useCase = createUseCase()
        useCase()

        coVerify { reconnectionService.reconnect(any()) }
    }

    @Tag("unit")
    @Test
    fun `invoke returns Success when reconnection succeeds`() = runTest {
        coEvery { reconnectionService.reconnect(any()) } returns ReconnectionResult.Success

        val useCase = createUseCase()
        val result = useCase()

        assertEquals(ReconnectionResult.Success, result)
    }

    @Tag("unit")
    @Test
    fun `invoke returns NoCredentials when no stored credentials`() = runTest {
        coEvery { reconnectionService.reconnect(any()) } returns ReconnectionResult.Failure.NoCredentials

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.NoCredentials)
    }

    @Tag("unit")
    @Test
    fun `invoke returns DaemonUnreachable when daemon is unreachable`() = runTest {
        coEvery { reconnectionService.reconnect(any()) } returns ReconnectionResult.Failure.DaemonUnreachable

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.DaemonUnreachable)
    }

    @Tag("unit")
    @Test
    fun `invoke returns AuthenticationFailed when auth fails`() = runTest {
        coEvery { reconnectionService.reconnect(any()) } returns ReconnectionResult.Failure.AuthenticationFailed

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.AuthenticationFailed)
    }

    @Tag("unit")
    @Test
    fun `invoke returns NetworkError on network issues`() = runTest {
        coEvery { reconnectionService.reconnect(any()) } returns ReconnectionResult.Failure.NetworkError

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.NetworkError)
    }

    @Tag("unit")
    @Test
    fun `invoke returns Unknown for other errors`() = runTest {
        val errorMessage = "Some unexpected error"
        coEvery { reconnectionService.reconnect(any()) } returns ReconnectionResult.Failure.Unknown(errorMessage)

        val useCase = createUseCase()
        val result = useCase()

        assertTrue(result is ReconnectionResult.Failure.Unknown)
        assertEquals(errorMessage, (result as ReconnectionResult.Failure.Unknown).message)
    }
}
