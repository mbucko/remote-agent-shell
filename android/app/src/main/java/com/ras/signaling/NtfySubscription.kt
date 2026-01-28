package com.ras.signaling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

/**
 * Represents an active ntfy subscription.
 *
 * Provides:
 * - A flow of messages from the topic
 * - Ability to wait for the subscription to be ready (WebSocket connected)
 * - Clean shutdown via close()
 *
 * Usage:
 * ```kotlin
 * val subscription = ntfyClient.subscribe(topic)
 * subscription.awaitReady(5000)  // Wait for WebSocket to connect
 * ntfyClient.publish(topic, message)  // Now safe to publish
 * subscription.messages.collect { ... }  // Collect messages
 * subscription.close()  // Clean up when done
 * ```
 */
class NtfySubscription internal constructor(
    /**
     * Flow of messages from the subscribed topic.
     */
    val messages: Flow<NtfyMessage>,

    /**
     * Internal deferred that completes when WebSocket is connected.
     */
    private val readyDeferred: CompletableDeferred<Unit>,

    /**
     * Callback to close the subscription.
     */
    private val onClose: () -> Unit
) {
    /**
     * Wait for the subscription to be ready (WebSocket connected).
     *
     * @param timeoutMs Maximum time to wait for connection
     * @throws kotlinx.coroutines.TimeoutCancellationException if connection times out
     */
    suspend fun awaitReady(timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS) {
        withTimeout(timeoutMs) {
            readyDeferred.await()
        }
    }

    /**
     * Check if the subscription is ready without waiting.
     */
    val isReady: Boolean
        get() = readyDeferred.isCompleted && !readyDeferred.isCancelled

    /**
     * Close the subscription and release resources.
     */
    fun close() {
        onClose()
    }

    /**
     * Internal: mark the subscription as ready.
     * Called when WebSocket connects successfully.
     */
    internal fun markReady() {
        readyDeferred.complete(Unit)
    }

    /**
     * Internal: mark the subscription as failed.
     * Called when WebSocket fails to connect.
     */
    internal fun markFailed(error: Throwable) {
        readyDeferred.completeExceptionally(error)
    }

    companion object {
        const val DEFAULT_READY_TIMEOUT_MS = 5000L
    }
}
