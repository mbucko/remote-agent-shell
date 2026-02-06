package com.ras.connection

import com.ras.data.webrtc.IceRecoveryHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for ICE recovery timeout logic.
 *
 * Uses [IceRecoveryHandler] directly - no WebRTC native library needed.
 * Uses TestCoroutineScheduler for deterministic time control.
 */
@Tag("unit")
@OptIn(ExperimentalCoroutinesApi::class)
class IceRecoveryTest {

    private var disconnectCount = 0

    @Test
    fun `ICE disconnected then reconnected within timeout - no teardown`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()

        handler.onIceRecovered()
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()

        assertEquals(0, disconnectCount, "onDisconnect should not be called when ICE recovers in time")
    }

    @Test
    fun `ICE disconnected timeout expires - triggers disconnect`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()

        assertEquals(1, disconnectCount, "onDisconnect should be called exactly once after timeout")
    }

    @Test
    fun `ICE failed - immediate disconnect no recovery window`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceFailed()

        assertEquals(1, disconnectCount, "onDisconnect should be called immediately on ICE FAILED")
    }

    @Test
    fun `ICE failed while recovery timer is pending - cancels timer and disconnects once`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()

        handler.onIceFailed()
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()

        assertEquals(1, disconnectCount, "onDisconnect should be called exactly once (from FAILED, not timer)")
    }

    @Test
    fun `rapid disconnect-reconnect cycles - no disconnect`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        repeat(5) {
            handler.onIceDisconnected()
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            handler.onIceRecovered()
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
        }

        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()

        assertEquals(0, disconnectCount, "onDisconnect should never fire during rapid recovery cycles")
    }

    @Test
    fun `recovery timeout cancelled when ICE reconnects at 10s mark`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()

        handler.onIceRecovered()
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()

        assertEquals(0, disconnectCount, "Timer cancelled at 10s should not fire at 15s")
    }

    @Test
    fun `cancel stops pending recovery timer`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()

        handler.cancel()
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()

        assertEquals(0, disconnectCount, "cancel() should prevent recovery timeout from firing")
    }

    @Test
    fun `disconnect then reconnect then disconnect again - only second timeout fires`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        handler.onIceRecovered()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()

        assertEquals(1, disconnectCount, "Only second disconnect's timeout should fire")
    }

    @Test
    fun `multiple disconnects before timeout - only one disconnect fires`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        handler.onIceDisconnected()

        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()

        assertEquals(1, disconnectCount, "Multiple disconnects should result in exactly one disconnect call")
    }

    @Test
    fun `COMPLETED state cancels recovery like CONNECTED`() = runTest {
        val handler = IceRecoveryHandler(scope = this, onRecoveryFailed = { disconnectCount++ })

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()

        handler.onIceRecovered()
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()

        assertEquals(0, disconnectCount, "onIceRecovered (COMPLETED) should cancel recovery timer")
    }

    @Test
    fun `custom timeout value is respected`() = runTest {
        val handler = IceRecoveryHandler(
            scope = this,
            recoveryTimeoutMs = 5_000,
            onRecoveryFailed = { disconnectCount++ }
        )

        handler.onIceDisconnected()
        testScheduler.advanceTimeBy(4_999)
        testScheduler.runCurrent()
        assertEquals(0, disconnectCount, "Should not fire before custom timeout")

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(1, disconnectCount, "Should fire at custom timeout boundary")
    }
}
