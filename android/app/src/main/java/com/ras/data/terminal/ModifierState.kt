package com.ras.data.terminal

/**
 * Tracks sticky modifier key state.
 * - Tap: Activate for next key press (auto-clears)
 * - Long-press: Lock until tapped again
 */
data class ModifierState(
    val ctrl: ModifierMode = ModifierMode.OFF,
    val alt: ModifierMode = ModifierMode.OFF,
    val shift: ModifierMode = ModifierMode.OFF
) {
    /**
     * Get modifier bitmask for sending to daemon.
     * Uses XTerm standard encoding: SHIFT=1, ALT=2, CTRL=4
     * Parameter in escape sequence = 1 + bitmask
     */
    val bitmask: Int
        get() = (if (shift != ModifierMode.OFF) 1 else 0) or
                (if (alt != ModifierMode.OFF) 2 else 0) or
                (if (ctrl != ModifierMode.OFF) 4 else 0)

    /**
     * True if any modifier is active (sticky or locked).
     */
    val hasActiveModifiers: Boolean
        get() = ctrl != ModifierMode.OFF || alt != ModifierMode.OFF || shift != ModifierMode.OFF
}

/**
 * Modifier button state.
 */
enum class ModifierMode {
    OFF,      // Not active
    STICKY,   // Active for next key only (auto-clears after key press)
    LOCKED    // Active until toggled off
}

/**
 * Which modifier key (avoiding name collision with Compose Modifier).
 */
enum class ModifierKey {
    CTRL, ALT, SHIFT
}

/**
 * Toggle modifier mode: OFF -> STICKY, STICKY/LOCKED -> OFF
 */
fun ModifierMode.toggle(): ModifierMode = when (this) {
    ModifierMode.OFF -> ModifierMode.STICKY
    ModifierMode.STICKY -> ModifierMode.OFF
    ModifierMode.LOCKED -> ModifierMode.OFF
}
