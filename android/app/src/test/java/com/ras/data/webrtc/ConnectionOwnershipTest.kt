package com.ras.data.webrtc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

class ConnectionOwnershipTest {

    @Tag("unit")
    @Test
    fun `initial ownership is PairingManager`() {
        val ownership = ConnectionOwnership.PairingManager
        assertEquals("PairingManager", ownership.toString())
    }

    @Tag("unit")
    @Test
    fun `ownership types are distinct`() {
        val pairing: ConnectionOwnership = ConnectionOwnership.PairingManager
        val connection: ConnectionOwnership = ConnectionOwnership.ConnectionManager
        val disposed: ConnectionOwnership = ConnectionOwnership.Disposed

        assertFalse(pairing == connection)
        assertFalse(connection == disposed)
        assertFalse(pairing == disposed)
    }

    @Tag("unit")
    @Test
    fun `disposed is terminal state`() {
        val disposed = ConnectionOwnership.Disposed
        assertEquals("Disposed", disposed.toString())
    }

    @Tag("unit")
    @Test
    fun `ownership toString returns class name`() {
        assertEquals("PairingManager", ConnectionOwnership.PairingManager.toString())
        assertEquals("ConnectionManager", ConnectionOwnership.ConnectionManager.toString())
        assertEquals("Disposed", ConnectionOwnership.Disposed.toString())
    }
}
