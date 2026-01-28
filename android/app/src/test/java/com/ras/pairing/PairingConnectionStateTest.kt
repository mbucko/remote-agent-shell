package com.ras.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingConnectionStateTest {

    // Valid state transitions

    @Test
    fun `Creating can transition to Signaling`() {
        assertTrue(PairingConnectionState.Creating.canTransitionTo(PairingConnectionState.Signaling))
    }

    @Test
    fun `Creating can transition to Closed`() {
        assertTrue(PairingConnectionState.Creating.canTransitionTo(PairingConnectionState.Closed))
    }

    @Test
    fun `Signaling can transition to Connecting`() {
        assertTrue(PairingConnectionState.Signaling.canTransitionTo(PairingConnectionState.Connecting))
    }

    @Test
    fun `Signaling can transition to Closed`() {
        assertTrue(PairingConnectionState.Signaling.canTransitionTo(PairingConnectionState.Closed))
    }

    @Test
    fun `Connecting can transition to Authenticating`() {
        assertTrue(PairingConnectionState.Connecting.canTransitionTo(PairingConnectionState.Authenticating))
    }

    @Test
    fun `Connecting can transition to Closed`() {
        assertTrue(PairingConnectionState.Connecting.canTransitionTo(PairingConnectionState.Closed))
    }

    @Test
    fun `Authenticating can transition to HandedOff`() {
        assertTrue(PairingConnectionState.Authenticating.canTransitionTo(
            PairingConnectionState.HandedOff("ConnectionManager")
        ))
    }

    @Test
    fun `Authenticating can transition to Closed`() {
        assertTrue(PairingConnectionState.Authenticating.canTransitionTo(PairingConnectionState.Closed))
    }

    // Invalid state transitions

    @Test
    fun `Creating cannot skip to Connecting`() {
        assertFalse(PairingConnectionState.Creating.canTransitionTo(PairingConnectionState.Connecting))
    }

    @Test
    fun `Creating cannot skip to Authenticating`() {
        assertFalse(PairingConnectionState.Creating.canTransitionTo(PairingConnectionState.Authenticating))
    }

    @Test
    fun `Creating cannot skip to HandedOff`() {
        assertFalse(PairingConnectionState.Creating.canTransitionTo(
            PairingConnectionState.HandedOff("ConnectionManager")
        ))
    }

    @Test
    fun `Signaling cannot skip to Authenticating`() {
        assertFalse(PairingConnectionState.Signaling.canTransitionTo(PairingConnectionState.Authenticating))
    }

    @Test
    fun `HandedOff is terminal - cannot transition`() {
        val handedOff = PairingConnectionState.HandedOff("ConnectionManager")
        assertFalse(handedOff.canTransitionTo(PairingConnectionState.Closed))
        assertFalse(handedOff.canTransitionTo(PairingConnectionState.Creating))
    }

    @Test
    fun `Closed is terminal - cannot transition`() {
        assertFalse(PairingConnectionState.Closed.canTransitionTo(PairingConnectionState.Creating))
        assertFalse(PairingConnectionState.Closed.canTransitionTo(PairingConnectionState.Signaling))
    }

    // shouldCloseOnCleanup tests

    @Test
    fun `Creating should close on cleanup`() {
        assertTrue(PairingConnectionState.Creating.shouldCloseOnCleanup())
    }

    @Test
    fun `Signaling should close on cleanup`() {
        assertTrue(PairingConnectionState.Signaling.shouldCloseOnCleanup())
    }

    @Test
    fun `Connecting should close on cleanup`() {
        assertTrue(PairingConnectionState.Connecting.shouldCloseOnCleanup())
    }

    @Test
    fun `Authenticating should close on cleanup`() {
        assertTrue(PairingConnectionState.Authenticating.shouldCloseOnCleanup())
    }

    @Test
    fun `HandedOff should NOT close on cleanup - ConnectionManager owns it`() {
        val handedOff = PairingConnectionState.HandedOff("ConnectionManager")
        assertFalse(handedOff.shouldCloseOnCleanup())
    }

    @Test
    fun `Closed should NOT close on cleanup - already closed`() {
        assertFalse(PairingConnectionState.Closed.shouldCloseOnCleanup())
    }

    // toString tests

    @Test
    fun `toString returns readable state names`() {
        assertEquals("Creating", PairingConnectionState.Creating.toString())
        assertEquals("Signaling", PairingConnectionState.Signaling.toString())
        assertEquals("Connecting", PairingConnectionState.Connecting.toString())
        assertEquals("Authenticating", PairingConnectionState.Authenticating.toString())
        assertEquals("HandedOff(to=ConnectionManager)",
            PairingConnectionState.HandedOff("ConnectionManager").toString())
        assertEquals("Closed", PairingConnectionState.Closed.toString())
    }
}
