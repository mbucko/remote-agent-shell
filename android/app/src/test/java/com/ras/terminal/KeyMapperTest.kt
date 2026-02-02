package com.ras.terminal

import android.view.KeyEvent
import com.ras.data.terminal.KeyMapper
import com.ras.proto.KeyType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for KeyMapper.
 *
 * These tests verify escape sequence generation matches the cross-platform
 * test vectors defined in daemon/tests/terminal/test_vectors.py.
 */
class KeyMapperTest {

    // ==========================================================================
    // Basic Key Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `KEY_ENTER maps to carriage return`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_ENTER)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x0D), bytes)
    }

    @Tag("unit")
    @Test
    fun `KEY_TAB maps to tab character`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_TAB)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x09), bytes)
    }

    @Tag("unit")
    @Test
    fun `KEY_BACKSPACE maps to DEL`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_BACKSPACE)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x7F), bytes)
    }

    @Tag("unit")
    @Test
    fun `KEY_ESCAPE maps to ESC`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_ESCAPE)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x1B), bytes)
    }

    @Tag("unit")
    @Test
    fun `KEY_DELETE maps to ESC 3 tilde sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_DELETE)
        assertNotNull(bytes)
        assertEquals("1b5b337e", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_INSERT maps to ESC 2 tilde sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_INSERT)
        assertNotNull(bytes)
        assertEquals("1b5b327e", bytes!!.toHexString())
    }

    // ==========================================================================
    // Arrow Key Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `KEY_UP maps to ESC A sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_UP)
        assertNotNull(bytes)
        assertEquals("1b5b41", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_DOWN maps to ESC B sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_DOWN)
        assertNotNull(bytes)
        assertEquals("1b5b42", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_RIGHT maps to ESC C sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_RIGHT)
        assertNotNull(bytes)
        assertEquals("1b5b43", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_LEFT maps to ESC D sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_LEFT)
        assertNotNull(bytes)
        assertEquals("1b5b44", bytes!!.toHexString())
    }

    // ==========================================================================
    // Navigation Key Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `KEY_HOME maps to ESC H sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_HOME)
        assertNotNull(bytes)
        assertEquals("1b5b48", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_END maps to ESC F sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_END)
        assertNotNull(bytes)
        assertEquals("1b5b46", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_PAGE_UP maps to ESC 5 tilde sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_PAGE_UP)
        assertNotNull(bytes)
        assertEquals("1b5b357e", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_PAGE_DOWN maps to ESC 6 tilde sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_PAGE_DOWN)
        assertNotNull(bytes)
        assertEquals("1b5b367e", bytes!!.toHexString())
    }

    // ==========================================================================
    // Function Key Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `KEY_F1 maps to ESC OP`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_F1)
        assertNotNull(bytes)
        assertEquals("1b4f50", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_F2 maps to ESC OQ`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_F2)
        assertNotNull(bytes)
        assertEquals("1b4f51", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `KEY_F12 maps to correct sequence`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_F12)
        assertNotNull(bytes)
        assertEquals("1b5b32347e", bytes!!.toHexString())
    }

    // ==========================================================================
    // Control Character Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `KEY_CTRL_C maps to ETX`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_CTRL_C)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x03), bytes)
    }

    @Tag("unit")
    @Test
    fun `KEY_CTRL_D maps to EOT`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_CTRL_D)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x04), bytes)
    }

    @Tag("unit")
    @Test
    fun `KEY_CTRL_Z maps to SUB`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_CTRL_Z)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x1A), bytes)
    }

    // ==========================================================================
    // Unknown Key Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `KEY_UNKNOWN returns null`() {
        val bytes = KeyMapper.getKeySequence(KeyType.KEY_UNKNOWN)
        assertNull(bytes)
    }

    // ==========================================================================
    // Android KeyEvent Mapping Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `Ctrl+C keyEvent maps to ETX`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_C, isCtrlPressed = true)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x03), bytes)
    }

    @Tag("unit")
    @Test
    fun `Ctrl+D keyEvent maps to EOT`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_D, isCtrlPressed = true)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x04), bytes)
    }

    @Tag("unit")
    @Test
    fun `Ctrl+Z keyEvent maps to SUB`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_Z, isCtrlPressed = true)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x1A), bytes)
    }

    @Tag("unit")
    @Test
    fun `Ctrl+A keyEvent maps to SOH`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_A, isCtrlPressed = true)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x01), bytes)
    }

    @Tag("unit")
    @Test
    fun `ENTER keyEvent maps to carriage return`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_ENTER)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x0D), bytes)
    }

    @Tag("unit")
    @Test
    fun `TAB keyEvent maps to tab`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_TAB)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x09), bytes)
    }

    @Tag("unit")
    @Test
    fun `DEL keyEvent maps to backspace`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_DEL)
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x7F), bytes)
    }

    @Tag("unit")
    @Test
    fun `arrow up keyEvent maps to ESC A`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_DPAD_UP)
        assertNotNull(bytes)
        assertEquals("1b5b41", bytes!!.toHexString())
    }

    @Tag("unit")
    @Test
    fun `unknown keyEvent returns null`() {
        val bytes = KeyMapper.keyEventToBytes(KeyEvent.KEYCODE_CAMERA)
        assertNull(bytes)
    }

    // ==========================================================================
    // Ctrl+Letter Calculation Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `getCtrlLetterByte A returns 1`() {
        assertEquals(0x01.toByte(), KeyMapper.getCtrlLetterByte('A'))
        assertEquals(0x01.toByte(), KeyMapper.getCtrlLetterByte('a'))
    }

    @Tag("unit")
    @Test
    fun `getCtrlLetterByte Z returns 26`() {
        assertEquals(0x1A.toByte(), KeyMapper.getCtrlLetterByte('Z'))
        assertEquals(0x1A.toByte(), KeyMapper.getCtrlLetterByte('z'))
    }

    @Tag("unit")
    @Test
    fun `getCtrlLetterByte non-letter returns null`() {
        assertNull(KeyMapper.getCtrlLetterByte('1'))
        assertNull(KeyMapper.getCtrlLetterByte('@'))
    }

    // ==========================================================================
    // Modifier Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `calculateModifiers with no modifiers returns 0`() {
        assertEquals(0, KeyMapper.calculateModifiers())
    }

    @Tag("unit")
    @Test
    fun `calculateModifiers with ctrl returns 1`() {
        assertEquals(1, KeyMapper.calculateModifiers(ctrl = true))
    }

    @Tag("unit")
    @Test
    fun `calculateModifiers with alt returns 2`() {
        assertEquals(2, KeyMapper.calculateModifiers(alt = true))
    }

    @Tag("unit")
    @Test
    fun `calculateModifiers with shift returns 4`() {
        assertEquals(4, KeyMapper.calculateModifiers(shift = true))
    }

    @Tag("unit")
    @Test
    fun `calculateModifiers with ctrl+alt returns 3`() {
        assertEquals(3, KeyMapper.calculateModifiers(ctrl = true, alt = true))
    }

    @Tag("unit")
    @Test
    fun `calculateModifiers with all modifiers returns 7`() {
        assertEquals(7, KeyMapper.calculateModifiers(ctrl = true, alt = true, shift = true))
    }

    // ==========================================================================
    // SpecialKey Proto Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `createSpecialKey creates correct proto`() {
        val key = KeyMapper.createSpecialKey(KeyType.KEY_CTRL_C)
        assertEquals(KeyType.KEY_CTRL_C, key.key)
        assertEquals(0, key.modifiers)
    }

    @Tag("unit")
    @Test
    fun `createSpecialKey with modifiers creates correct proto`() {
        val key = KeyMapper.createSpecialKey(KeyType.KEY_ENTER, 5) // Ctrl+Shift
        assertEquals(KeyType.KEY_ENTER, key.key)
        assertEquals(5, key.modifiers)
    }

    // ==========================================================================
    // Hex String Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `getKeySequenceHex returns correct format`() {
        val hex = KeyMapper.getKeySequenceHex(KeyType.KEY_UP)
        assertEquals("1b5b41", hex)
    }

    @Tag("unit")
    @Test
    fun `getKeySequenceHex for unknown returns null`() {
        val hex = KeyMapper.getKeySequenceHex(KeyType.KEY_UNKNOWN)
        assertNull(hex)
    }

    // Helper extension
    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
