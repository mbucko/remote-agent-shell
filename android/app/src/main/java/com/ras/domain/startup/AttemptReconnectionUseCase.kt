package com.ras.domain.startup

import com.ras.data.reconnection.ReconnectionService
import javax.inject.Inject

/**
 * Use case to attempt reconnection using stored credentials.
 */
interface AttemptReconnectionUseCase {
    suspend operator fun invoke(): ReconnectionResult
}

/**
 * Implementation that delegates to ReconnectionService.
 */
class AttemptReconnectionUseCaseImpl @Inject constructor(
    private val reconnectionService: ReconnectionService
) : AttemptReconnectionUseCase {

    override suspend fun invoke(): ReconnectionResult {
        return reconnectionService.reconnect()
    }
}
