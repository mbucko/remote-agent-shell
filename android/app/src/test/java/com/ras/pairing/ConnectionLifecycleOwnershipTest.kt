package com.ras.pairing

import com.ras.data.webrtc.ConnectionOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for connection lifecycle ownership that verify:
 * 1. Ownership transfer is atomic and thread-safe
 * 2. Only the current owner can close a connection
 * 3. Concurrent cleanup and handoff don't cause double-close
 */
class ConnectionLifecycleOwnershipTest {

    /**
     * Simulates a WebRTC client with ownership tracking for testing.
     * Mirrors the ownership semantics of WebRTCClient without WebRTC dependencies.
     */
    class MockOwnedConnection(
        initialOwner: ConnectionOwnership = ConnectionOwnership.PairingManager
    ) {
        private val lock = Any()

        @Volatile
        private var owner: ConnectionOwnership = initialOwner

        @Volatile
        private var closed = false

        val closeCount = AtomicInteger(0)

        fun getOwner(): ConnectionOwnership = owner

        fun isClosed(): Boolean = closed

        fun transferOwnership(newOwner: ConnectionOwnership): Boolean {
            synchronized(lock) {
                if (owner == ConnectionOwnership.Disposed) return false
                if (closed) return false
                owner = newOwner
                return true
            }
        }

        fun closeByOwner(caller: ConnectionOwnership): Boolean {
            synchronized(lock) {
                if (owner != caller) return false
                owner = ConnectionOwnership.Disposed
            }
            doClose()
            return true
        }

        fun close() {
            synchronized(lock) {
                owner = ConnectionOwnership.Disposed
            }
            doClose()
        }

        private fun doClose() {
            synchronized(lock) {
                if (closed) return
                closed = true
                closeCount.incrementAndGet()
            }
        }
    }

    // Test 1: Basic ownership transfer

    @Tag("unit")
    @Test
    fun `ownership transfer changes owner`() {
        val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)
        assertEquals(ConnectionOwnership.PairingManager, connection.getOwner())

        val transferred = connection.transferOwnership(ConnectionOwnership.ConnectionManager)

        assertTrue(transferred)
        assertEquals(ConnectionOwnership.ConnectionManager, connection.getOwner())
    }

    @Tag("unit")
    @Test
    fun `cannot transfer from disposed connection`() {
        val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)
        connection.close() // Disposes the connection

        val transferred = connection.transferOwnership(ConnectionOwnership.ConnectionManager)

        assertFalse(transferred)
        assertEquals(ConnectionOwnership.Disposed, connection.getOwner())
    }

    // Test 2: closeByOwner respects ownership

    @Tag("unit")
    @Test
    fun `closeByOwner succeeds when caller is owner`() {
        val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)

        val closed = connection.closeByOwner(ConnectionOwnership.PairingManager)

        assertTrue(closed)
        assertTrue(connection.isClosed())
        assertEquals(1, connection.closeCount.get())
    }

    @Tag("unit")
    @Test
    fun `closeByOwner fails when caller is not owner`() {
        val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)

        val closed = connection.closeByOwner(ConnectionOwnership.ConnectionManager)

        assertFalse(closed)
        assertFalse(connection.isClosed())
        assertEquals(0, connection.closeCount.get())
    }

    @Tag("unit")
    @Test
    fun `closeByOwner fails after ownership transfer`() {
        val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)
        connection.transferOwnership(ConnectionOwnership.ConnectionManager)

        // PairingManager tries to close after transfer
        val closed = connection.closeByOwner(ConnectionOwnership.PairingManager)

        assertFalse(closed)
        assertFalse(connection.isClosed())
    }

    // Test 3: Cleanup after handoff doesn't close connection

    @Tag("unit")
    @Test
    fun `cleanup after successful handoff does not close connection`() {
        val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)
        val connectionState: PairingConnectionState

        // Simulate successful handoff
        connection.transferOwnership(ConnectionOwnership.ConnectionManager)
        connectionState = PairingConnectionState.HandedOff("ConnectionManager")

        // Simulate cleanup being called
        if (connectionState.shouldCloseOnCleanup()) {
            connection.closeByOwner(ConnectionOwnership.PairingManager)
        }

        // Connection should NOT be closed
        assertFalse(connection.isClosed())
        assertEquals(0, connection.closeCount.get())
    }

    // Test 4: Double close is safe

    @Tag("unit")
    @Test
    fun `double close is idempotent`() {
        val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)

        connection.close()
        connection.close()

        assertTrue(connection.isClosed())
        assertEquals(1, connection.closeCount.get())
    }

    @Tag("unit")
    @Test
    fun `closeByOwner then close is idempotent`() {
        val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)

        connection.closeByOwner(ConnectionOwnership.PairingManager)
        connection.close()

        assertTrue(connection.isClosed())
        assertEquals(1, connection.closeCount.get())
    }

    // Test 5: Concurrent access safety

    @Tag("unit")
    @Test
    fun `concurrent transfer and close is safe`() = runBlocking {
        repeat(100) {
            val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)
            val transferSucceeded = AtomicBoolean(false)
            val closeSucceeded = AtomicBoolean(false)

            // Race transfer and close
            val jobs = listOf(
                async(Dispatchers.Default) {
                    transferSucceeded.set(connection.transferOwnership(ConnectionOwnership.ConnectionManager))
                },
                async(Dispatchers.Default) {
                    closeSucceeded.set(connection.closeByOwner(ConnectionOwnership.PairingManager))
                }
            )
            jobs.awaitAll()

            // Exactly one should succeed, or both could fail if timed just right
            // But connection should never be closed AND transferred
            if (transferSucceeded.get()) {
                // If transfer succeeded, close by PairingManager should have failed
                assertFalse(closeSucceeded.get(), "Close should fail after transfer")
                assertEquals(ConnectionOwnership.ConnectionManager, connection.getOwner())
            }

            if (closeSucceeded.get()) {
                // If close succeeded, transfer should have failed (connection disposed)
                assertEquals(ConnectionOwnership.Disposed, connection.getOwner())
                assertTrue(connection.isClosed())
            }

            // Close should have happened at most once
            assertTrue(
                connection.closeCount.get() <= 1,
                "Connection should be closed at most once"
            )
        }
    }

    @Tag("unit")
    @Test
    fun `concurrent cleanup from multiple sources is safe`() = runBlocking {
        repeat(100) {
            val connection = MockOwnedConnection(ConnectionOwnership.PairingManager)

            // Simulate multiple cleanup calls racing
            val jobs = (1..10).map {
                async(Dispatchers.Default) {
                    delay((0..5).random().toLong()) // Random small delay
                    connection.closeByOwner(ConnectionOwnership.PairingManager)
                }
            }
            jobs.awaitAll()

            // Only one close should have succeeded
            assertEquals(
                1,
                connection.closeCount.get(),
                "Connection should be closed exactly once"
            )
        }
    }

    // Test 6: State machine integration

    @Tag("unit")
    @Test
    fun `state machine tracks valid handoff flow`() {
        val states = mutableListOf<PairingConnectionState>()
        var state: PairingConnectionState = PairingConnectionState.Creating
        states.add(state)

        // Valid progression
        state = PairingConnectionState.Signaling
        assertTrue(states.last().canTransitionTo(state))
        states.add(state)

        state = PairingConnectionState.Connecting
        assertTrue(states.last().canTransitionTo(state))
        states.add(state)

        state = PairingConnectionState.Authenticating
        assertTrue(states.last().canTransitionTo(state))
        states.add(state)

        state = PairingConnectionState.HandedOff("ConnectionManager")
        assertTrue(states.last().canTransitionTo(state))
        states.add(state)

        // Verify final state doesn't close on cleanup
        assertFalse(state.shouldCloseOnCleanup())
    }

    @Tag("unit")
    @Test
    fun `state machine tracks error flow`() {
        val states = mutableListOf<PairingConnectionState>()
        var state: PairingConnectionState = PairingConnectionState.Creating
        states.add(state)

        state = PairingConnectionState.Signaling
        states.add(state)

        // Error during signaling - go to closed
        state = PairingConnectionState.Closed
        assertTrue(states.last().canTransitionTo(state))
        states.add(state)

        // Closed state should NOT close on cleanup (already closed)
        assertFalse(state.shouldCloseOnCleanup())
    }
}
