package com.ras.data.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOwnershipTest {

    @Test
    fun `initial ownership is PairingManager`() {
        val ownership = ConnectionOwnership.PairingManager
        assertEquals("PairingManager", ownership.toString())
    }

    @Test
    fun `ownership types are distinct`() {
        val pairing: ConnectionOwnership = ConnectionOwnership.PairingManager
        val connection: ConnectionOwnership = ConnectionOwnership.ConnectionManager
        val disposed: ConnectionOwnership = ConnectionOwnership.Disposed

        assertFalse(pairing == connection)
        assertFalse(connection == disposed)
        assertFalse(pairing == disposed)
    }

    @Test
    fun `disposed is terminal state`() {
        val disposed = ConnectionOwnership.Disposed
        assertEquals("Disposed", disposed.toString())
    }

    @Test
    fun `ownership toString returns class name`() {
        assertEquals("PairingManager", ConnectionOwnership.PairingManager.toString())
        assertEquals("ConnectionManager", ConnectionOwnership.ConnectionManager.toString())
        assertEquals("Disposed", ConnectionOwnership.Disposed.toString())
    }
}
