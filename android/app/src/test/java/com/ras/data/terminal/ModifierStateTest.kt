package com.ras.data.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `bitmask with ctrl, alt, shift is 7`() {
        // XTerm standard: Shift=1, Alt=2, Ctrl=4 → 7
        val state = ModifierState(
            ctrl = ModifierMode.STICKY,
            alt = ModifierMode.STICKY,
            shift = ModifierMode.STICKY
        )
        assertEquals(7, state.bitmask)
    }

    @Test
    fun `bitmask with meta is 8`() {
        // XTerm standard: Meta=8
        val state = ModifierState(meta = ModifierMode.STICKY)
        assertEquals(8, state.bitmask)
    }

    @Test
    fun `bitmask with all modifiers is 15`() {
        // XTerm standard: Shift=1, Alt=2, Ctrl=4, Meta=8 → 15
        val state = ModifierState(
            ctrl = ModifierMode.STICKY,
            alt = ModifierMode.STICKY,
            shift = ModifierMode.STICKY,
            meta = ModifierMode.STICKY
        )
        assertEquals(15, state.bitmask)
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
    fun `hasActiveModifiers true when meta active`() {
        val state = ModifierState(meta = ModifierMode.LOCKED)
        assertEquals(true, state.hasActiveModifiers)
    }

    @Test
    fun `locked mode contributes to bitmask`() {
        // XTerm standard: Ctrl = 4
        val state = ModifierState(ctrl = ModifierMode.LOCKED)
        assertEquals(4, state.bitmask)
    }

    // ==========================================================================
    // getAppShortcut() Tests - Paste Shortcuts
    // ==========================================================================

    @Test
    fun `getAppShortcut returns PASTE for Meta+v`() {
        val state = ModifierState(meta = ModifierMode.STICKY)
        assertEquals(AppAction.PASTE_HOST, state.getAppShortcut('v'))
    }

    @Test
    fun `getAppShortcut returns PASTE for Meta+V uppercase`() {
        val state = ModifierState(meta = ModifierMode.STICKY)
        assertEquals(AppAction.PASTE_HOST, state.getAppShortcut('V'))
    }

    @Test
    fun `getAppShortcut returns PASTE for Ctrl+Shift+v`() {
        val state = ModifierState(ctrl = ModifierMode.STICKY, shift = ModifierMode.STICKY)
        assertEquals(AppAction.PASTE_HOST, state.getAppShortcut('v'))
    }

    @Test
    fun `getAppShortcut returns PASTE for Ctrl+Shift+V uppercase`() {
        val state = ModifierState(ctrl = ModifierMode.STICKY, shift = ModifierMode.STICKY)
        assertEquals(AppAction.PASTE_HOST, state.getAppShortcut('V'))
    }

    @Test
    fun `getAppShortcut handles LOCKED mode same as STICKY for Meta+V`() {
        val state = ModifierState(meta = ModifierMode.LOCKED)
        assertEquals(AppAction.PASTE_HOST, state.getAppShortcut('v'))
    }

    @Test
    fun `getAppShortcut handles LOCKED mode same as STICKY for Ctrl+Shift+V`() {
        val state = ModifierState(ctrl = ModifierMode.LOCKED, shift = ModifierMode.LOCKED)
        assertEquals(AppAction.PASTE_HOST, state.getAppShortcut('v'))
    }

    // ==========================================================================
    // getAppShortcut() Tests - Non-Shortcuts (should return null)
    // ==========================================================================

    @Test
    fun `getAppShortcut returns null for Ctrl+V alone`() {
        val state = ModifierState(ctrl = ModifierMode.STICKY)
        assertNull(state.getAppShortcut('v'))
    }

    @Test
    fun `getAppShortcut returns null for Shift+V alone`() {
        val state = ModifierState(shift = ModifierMode.STICKY)
        assertNull(state.getAppShortcut('v'))
    }

    @Test
    fun `getAppShortcut returns null for no modifiers`() {
        val state = ModifierState()
        assertNull(state.getAppShortcut('v'))
    }

    @Test
    fun `getAppShortcut returns null for Meta with other characters`() {
        val state = ModifierState(meta = ModifierMode.STICKY)
        assertNull(state.getAppShortcut('c')) // Copy not implemented yet
        assertNull(state.getAppShortcut('x'))
        assertNull(state.getAppShortcut('a'))
        assertNull(state.getAppShortcut('z'))
    }

    @Test
    fun `getAppShortcut returns null for Ctrl+Shift with other characters`() {
        val state = ModifierState(ctrl = ModifierMode.STICKY, shift = ModifierMode.STICKY)
        assertNull(state.getAppShortcut('c')) // Copy not implemented yet
        assertNull(state.getAppShortcut('x'))
    }

    @Test
    fun `getAppShortcut returns null for Alt+V`() {
        val state = ModifierState(alt = ModifierMode.STICKY)
        assertNull(state.getAppShortcut('v'))
    }
}
