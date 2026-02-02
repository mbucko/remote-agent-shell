package com.ras.pairing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

class PairingConnectionStateTest {

    // Valid state transitions

    @Tag("unit")
    @Test
    fun `Creating can transition to Signaling`() {
        assertTrue(PairingConnectionState.Creating.canTransitionTo(PairingConnectionState.Signaling))
    }

    @Tag("unit")
    @Test
    fun `Creating can transition to Closed`() {
        assertTrue(PairingConnectionState.Creating.canTransitionTo(PairingConnectionState.Closed))
    }

    @Tag("unit")
    @Test
    fun `Signaling can transition to Connecting`() {
        assertTrue(PairingConnectionState.Signaling.canTransitionTo(PairingConnectionState.Connecting))
    }

    @Tag("unit")
    @Test
    fun `Signaling can transition to Closed`() {
        assertTrue(PairingConnectionState.Signaling.canTransitionTo(PairingConnectionState.Closed))
    }

    @Tag("unit")
    @Test
    fun `Connecting can transition to Authenticating`() {
        assertTrue(PairingConnectionState.Connecting.canTransitionTo(PairingConnectionState.Authenticating))
    }

    @Tag("unit")
    @Test
    fun `Connecting can transition to Closed`() {
        assertTrue(PairingConnectionState.Connecting.canTransitionTo(PairingConnectionState.Closed))
    }

    @Tag("unit")
    @Test
    fun `Authenticating can transition to HandedOff`() {
        assertTrue(PairingConnectionState.Authenticating.canTransitionTo(
            PairingConnectionState.HandedOff("ConnectionManager")
        ))
    }

    @Tag("unit")
    @Test
    fun `Authenticating can transition to Closed`() {
        assertTrue(PairingConnectionState.Authenticating.canTransitionTo(PairingConnectionState.Closed))
    }

    // Invalid state transitions

    @Tag("unit")
    @Test
    fun `Creating cannot skip to Connecting`() {
        assertFalse(PairingConnectionState.Creating.canTransitionTo(PairingConnectionState.Connecting))
    }

    @Tag("unit")
    @Test
    fun `Creating cannot skip to Authenticating`() {
        assertFalse(PairingConnectionState.Creating.canTransitionTo(PairingConnectionState.Authenticating))
    }

    @Tag("unit")
    @Test
    fun `Creating cannot skip to HandedOff`() {
        assertFalse(PairingConnectionState.Creating.canTransitionTo(
            PairingConnectionState.HandedOff("ConnectionManager")
        ))
    }

    @Tag("unit")
    @Test
    fun `Signaling cannot skip to Authenticating`() {
        assertFalse(PairingConnectionState.Signaling.canTransitionTo(PairingConnectionState.Authenticating))
    }

    @Tag("unit")
    @Test
    fun `HandedOff is terminal - cannot transition`() {
        val handedOff = PairingConnectionState.HandedOff("ConnectionManager")
        assertFalse(handedOff.canTransitionTo(PairingConnectionState.Closed))
        assertFalse(handedOff.canTransitionTo(PairingConnectionState.Creating))
    }

    @Tag("unit")
    @Test
    fun `Closed is terminal - cannot transition`() {
        assertFalse(PairingConnectionState.Closed.canTransitionTo(PairingConnectionState.Creating))
        assertFalse(PairingConnectionState.Closed.canTransitionTo(PairingConnectionState.Signaling))
    }

    // shouldCloseOnCleanup tests

    @Tag("unit")
    @Test
    fun `Creating should close on cleanup`() {
        assertTrue(PairingConnectionState.Creating.shouldCloseOnCleanup())
    }

    @Tag("unit")
    @Test
    fun `Signaling should close on cleanup`() {
        assertTrue(PairingConnectionState.Signaling.shouldCloseOnCleanup())
    }

    @Tag("unit")
    @Test
    fun `Connecting should close on cleanup`() {
        assertTrue(PairingConnectionState.Connecting.shouldCloseOnCleanup())
    }

    @Tag("unit")
    @Test
    fun `Authenticating should close on cleanup`() {
        assertTrue(PairingConnectionState.Authenticating.shouldCloseOnCleanup())
    }

    @Tag("unit")
    @Test
    fun `HandedOff should NOT close on cleanup - ConnectionManager owns it`() {
        val handedOff = PairingConnectionState.HandedOff("ConnectionManager")
        assertFalse(handedOff.shouldCloseOnCleanup())
    }

    @Tag("unit")
    @Test
    fun `Closed should NOT close on cleanup - already closed`() {
        assertFalse(PairingConnectionState.Closed.shouldCloseOnCleanup())
    }

    // toString tests

    @Tag("unit")
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
