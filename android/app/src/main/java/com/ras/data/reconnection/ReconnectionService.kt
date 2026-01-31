package com.ras.data.reconnection

import com.ras.data.connection.ConnectionProgress
import com.ras.domain.startup.ReconnectionResult

/**
 * Progress update during reconnection.
 * @deprecated Use ConnectionProgress instead for full connection details
 */
data class ReconnectionProgress(
    val step: String,
    val detail: String? = null
)

/**
 * Service for reconnecting to daemon using stored credentials.
 * Handles HTTP signaling, WebRTC connection, and authentication.
 */
interface ReconnectionService {
    /**
     * Attempt to reconnect using stored credentials.
     * @param onProgress Callback for connection progress updates
     * @return Success if connected, or specific failure reason
     */
    suspend fun reconnect(onProgress: (ConnectionProgress) -> Unit = {}): ReconnectionResult
}
