package com.ras.data.reconnection

import com.ras.domain.startup.ReconnectionResult

/**
 * Service for reconnecting to daemon using stored credentials.
 * Handles HTTP signaling, WebRTC connection, and authentication.
 */
interface ReconnectionService {
    /**
     * Attempt to reconnect using stored credentials.
     * @return Success if connected, or specific failure reason
     */
    suspend fun reconnect(): ReconnectionResult
}
