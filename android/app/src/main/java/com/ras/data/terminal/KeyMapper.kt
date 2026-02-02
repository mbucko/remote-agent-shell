package com.ras.data.terminal

import android.view.KeyEvent
import com.ras.proto.KeyType
import com.ras.proto.SpecialKey

/**
 * Maps Android key events and KeyType enums to terminal escape sequences.
 *
 * This mapping must be consistent with the daemon's key sequence mapping
 * (see daemon/tests/terminal/test_vectors.py for cross-platform vectors).
 */
object KeyMapper {
    /**
     * Modifier bit flags (must match proto definition).
     */
    const val MOD_CTRL = 1
    const val MOD_ALT = 2
    const val MOD_SHIFT = 4

    /**
     * Key escape sequence mapping.
     * Keys are mapped to their ANSI/VT100 escape sequences.
     */
    private val KEY_SEQUENCES: Map<KeyType, ByteArray> = mapOf(
        // Basic keys
        KeyType.KEY_ENTER to byteArrayOf(0x0D),           // \r (carriage return)
        KeyType.KEY_TAB to byteArrayOf(0x09),             // \t
        KeyType.KEY_BACKSPACE to byteArrayOf(0x7F),       // DEL
        KeyType.KEY_ESCAPE to byteArrayOf(0x1B),          // ESC
        KeyType.KEY_DELETE to "\u001b[3~".toByteArray(),  // \x1b[3~
        KeyType.KEY_INSERT to "\u001b[2~".toByteArray(),  // \x1b[2~

        // Arrow keys
        KeyType.KEY_UP to "\u001b[A".toByteArray(),       // \x1b[A
        KeyType.KEY_DOWN to "\u001b[B".toByteArray(),     // \x1b[B
        KeyType.KEY_RIGHT to "\u001b[C".toByteArray(),    // \x1b[C
        KeyType.KEY_LEFT to "\u001b[D".toByteArray(),     // \x1b[D

        // Navigation
        KeyType.KEY_HOME to "\u001b[H".toByteArray(),     // \x1b[H
        KeyType.KEY_END to "\u001b[F".toByteArray(),      // \x1b[F
        KeyType.KEY_PAGE_UP to "\u001b[5~".toByteArray(), // \x1b[5~
        KeyType.KEY_PAGE_DOWN to "\u001b[6~".toByteArray(), // \x1b[6~

        // Function keys
        KeyType.KEY_F1 to "\u001bOP".toByteArray(),       // \x1bOP
        KeyType.KEY_F2 to "\u001bOQ".toByteArray(),       // \x1bOQ
        KeyType.KEY_F3 to "\u001bOR".toByteArray(),       // \x1bOR
        KeyType.KEY_F4 to "\u001bOS".toByteArray(),       // \x1bOS
        KeyType.KEY_F5 to "\u001b[15~".toByteArray(),     // \x1b[15~
        KeyType.KEY_F6 to "\u001b[17~".toByteArray(),     // \x1b[17~
        KeyType.KEY_F7 to "\u001b[18~".toByteArray(),     // \x1b[18~
        KeyType.KEY_F8 to "\u001b[19~".toByteArray(),     // \x1b[19~
        KeyType.KEY_F9 to "\u001b[20~".toByteArray(),     // \x1b[20~
        KeyType.KEY_F10 to "\u001b[21~".toByteArray(),    // \x1b[21~
        KeyType.KEY_F11 to "\u001b[23~".toByteArray(),    // \x1b[23~
        KeyType.KEY_F12 to "\u001b[24~".toByteArray(),    // \x1b[24~

        // Control characters
        KeyType.KEY_CTRL_C to byteArrayOf(0x03),          // ETX
        KeyType.KEY_CTRL_D to byteArrayOf(0x04),          // EOT
        KeyType.KEY_CTRL_Z to byteArrayOf(0x1A)           // SUB
    )

    /**
     * Get the escape sequence bytes for a KeyType.
     *
     * @param keyType The key type to map
     * @return The escape sequence bytes, or null if unknown
     */
    fun getKeySequence(keyType: KeyType): ByteArray? = KEY_SEQUENCES[keyType]

    /**
     * Get the escape sequence hex string for a KeyType (for testing/logging).
     *
     * @param keyType The key type to map
     * @return The escape sequence as hex string, or null if unknown
     */
    fun getKeySequenceHex(keyType: KeyType): String? =
        KEY_SEQUENCES[keyType]?.joinToString("") { "%02x".format(it) }

    /**
     * Map an Android KeyEvent to terminal bytes in raw mode.
     *
     * @param keyCode The Android keyCode
     * @param isCtrlPressed Whether Ctrl is held
     * @return The bytes to send, or null if key should not be sent
     */
    fun keyEventToBytes(
        keyCode: Int,
        isCtrlPressed: Boolean = false
    ): ByteArray? {
        // Handle Ctrl+letter combinations (A-Z map to 0x01-0x1A)
        if (isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val ctrlChar = (keyCode - KeyEvent.KEYCODE_A + 1).toByte()
            return byteArrayOf(ctrlChar)
        }

        // Map standard keys
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> byteArrayOf(0x0D)
            KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)  // Backspace
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~".toByteArray()

            // Arrow keys
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A".toByteArray()
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B".toByteArray()
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C".toByteArray()
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D".toByteArray()

            // Navigation
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H".toByteArray()
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F".toByteArray()
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~".toByteArray()
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~".toByteArray()
            KeyEvent.KEYCODE_INSERT -> "\u001b[2~".toByteArray()

            // Function keys
            KeyEvent.KEYCODE_F1 -> "\u001bOP".toByteArray()
            KeyEvent.KEYCODE_F2 -> "\u001bOQ".toByteArray()
            KeyEvent.KEYCODE_F3 -> "\u001bOR".toByteArray()
            KeyEvent.KEYCODE_F4 -> "\u001bOS".toByteArray()
            KeyEvent.KEYCODE_F5 -> "\u001b[15~".toByteArray()
            KeyEvent.KEYCODE_F6 -> "\u001b[17~".toByteArray()
            KeyEvent.KEYCODE_F7 -> "\u001b[18~".toByteArray()
            KeyEvent.KEYCODE_F8 -> "\u001b[19~".toByteArray()
            KeyEvent.KEYCODE_F9 -> "\u001b[20~".toByteArray()
            KeyEvent.KEYCODE_F10 -> "\u001b[21~".toByteArray()
            KeyEvent.KEYCODE_F11 -> "\u001b[23~".toByteArray()
            KeyEvent.KEYCODE_F12 -> "\u001b[24~".toByteArray()

            else -> null
        }
    }

    /**
     * Create a SpecialKey proto message.
     *
     * @param keyType The key type
     * @param modifiers Modifier bitmask (MOD_CTRL, MOD_ALT, MOD_SHIFT)
     * @return The SpecialKey message
     */
    fun createSpecialKey(keyType: KeyType, modifiers: Int = 0): SpecialKey {
        return SpecialKey.newBuilder()
            .setKey(keyType)
            .setModifiers(modifiers)
            .build()
    }

    /**
     * Calculate modifier bitmask from boolean flags.
     */
    fun calculateModifiers(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false
    ): Int {
        var modifiers = 0
        if (ctrl) modifiers = modifiers or MOD_CTRL
        if (alt) modifiers = modifiers or MOD_ALT
        if (shift) modifiers = modifiers or MOD_SHIFT
        return modifiers
    }

    /**
     * Get the byte value for Ctrl+letter combination.
     * Ctrl+A = 0x01, Ctrl+B = 0x02, ..., Ctrl+Z = 0x1A
     *
     * @param letter The letter (case insensitive)
     * @return The control character byte, or null if not A-Z
     */
    fun getCtrlLetterByte(letter: Char): Byte? {
        val upper = letter.uppercaseChar()
        return if (upper in 'A'..'Z') {
            (upper - 'A' + 1).toByte()
        } else {
            null
        }
    }
}
