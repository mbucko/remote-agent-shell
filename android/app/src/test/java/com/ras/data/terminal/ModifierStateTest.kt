package com.ras.data.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ModifierStateTest {

    @Test
    fun `bitmask with no modifiers is 0`() {
        val state = ModifierState()
        assertEquals(0, state.bitmask)
    }

    @Test
    fun `bitmask with shift is 1`() {
        // XTerm standard: Shift = 1
        val state = ModifierState(shift = ModifierMode.STICKY)
        assertEquals(1, state.bitmask)
    }

    @Test
    fun `bitmask with alt is 2`() {
        // XTerm standard: Alt = 2
        val state = ModifierState(alt = ModifierMode.STICKY)
        assertEquals(2, state.bitmask)
    }

    @Test
    fun `bitmask with ctrl is 4`() {
        // XTerm standard: Ctrl = 4
        val state = ModifierState(ctrl = ModifierMode.LOCKED)
        assertEquals(4, state.bitmask)
    }

    @Test
    fun `bitmask correctly combines modifiers`() {
        // XTerm standard: Shift=1, Ctrl=4
        val state = ModifierState(
            ctrl = ModifierMode.STICKY,
            shift = ModifierMode.LOCKED
        )
        assertEquals(5, state.bitmask)  // CTRL=4 + SHIFT=1
    }

    @Test
    fun `bitmask with all modifiers is 7`() {
        // XTerm standard: Shift=1, Alt=2, Ctrl=4 → 7
        val state = ModifierState(
            ctrl = ModifierMode.STICKY,
            alt = ModifierMode.STICKY,
            shift = ModifierMode.STICKY
        )
        assertEquals(7, state.bitmask)
    }

    @Test
    fun `toggle OFF becomes STICKY`() {
        assertEquals(ModifierMode.STICKY, ModifierMode.OFF.toggle())
    }

    @Test
    fun `toggle STICKY becomes OFF`() {
        assertEquals(ModifierMode.OFF, ModifierMode.STICKY.toggle())
    }

    @Test
    fun `toggle LOCKED becomes OFF`() {
        assertEquals(ModifierMode.OFF, ModifierMode.LOCKED.toggle())
    }

    @Test
    fun `hasActiveModifiers false when all OFF`() {
        val state = ModifierState()
        assertEquals(false, state.hasActiveModifiers)
    }

    @Test
    fun `hasActiveModifiers true when ctrl active`() {
        val state = ModifierState(ctrl = ModifierMode.STICKY)
        assertEquals(true, state.hasActiveModifiers)
    }

    @Test
    fun `hasActiveModifiers true when alt active`() {
        val state = ModifierState(alt = ModifierMode.LOCKED)
        assertEquals(true, state.hasActiveModifiers)
    }

    @Test
    fun `hasActiveModifiers true when shift active`() {
        val state = ModifierState(shift = ModifierMode.STICKY)
        assertEquals(true, state.hasActiveModifiers)
    }

    @Test
    fun `locked mode contributes to bitmask`() {
        // XTerm standard: Ctrl = 4
        val state = ModifierState(ctrl = ModifierMode.LOCKED)
        assertEquals(4, state.bitmask)
    }
}
