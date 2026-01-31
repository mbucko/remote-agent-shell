package com.ras.domain.startup

import com.ras.data.connection.ConnectionProgress
import com.ras.data.reconnection.ReconnectionService
import javax.inject.Inject

/**
 * Use case to attempt reconnection using stored credentials.
 */
interface AttemptReconnectionUseCase {
    suspend operator fun invoke(onProgress: (ConnectionProgress) -> Unit = {}): ReconnectionResult
}

/**
 * Implementation that delegates to ReconnectionService.
 */
class AttemptReconnectionUseCaseImpl @Inject constructor(
    private val reconnectionService: ReconnectionService
) : AttemptReconnectionUseCase {

    override suspend fun invoke(onProgress: (ConnectionProgress) -> Unit): ReconnectionResult {
        return reconnectionService.reconnect(onProgress)
    }
}
