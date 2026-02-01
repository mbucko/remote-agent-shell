"""Key type to escape sequence mappings."""

from ras.proto.ras import KeyType

# Modifier bits (XTerm standard encoding)
# Parameter = 1 + bits, so Shift=2, Alt=3, Ctrl=5, Shift+Ctrl=6, etc.
MOD_SHIFT = 1
MOD_ALT = 2
MOD_CTRL = 4

# Key to escape sequence mapping
KEY_SEQUENCES: dict[KeyType, bytes] = {
    # Basic keys
    KeyType.KEY_ENTER: b"\r",
    KeyType.KEY_TAB: b"\t",
    KeyType.KEY_BACKSPACE: b"\x7f",
    KeyType.KEY_ESCAPE: b"\x1b",
    KeyType.KEY_DELETE: b"\x1b[3~",
    KeyType.KEY_INSERT: b"\x1b[2~",
    # Arrow keys
    KeyType.KEY_UP: b"\x1b[A",
    KeyType.KEY_DOWN: b"\x1b[B",
    KeyType.KEY_RIGHT: b"\x1b[C",
    KeyType.KEY_LEFT: b"\x1b[D",
    # Navigation
    KeyType.KEY_HOME: b"\x1b[H",
    KeyType.KEY_END: b"\x1b[F",
    KeyType.KEY_PAGE_UP: b"\x1b[5~",
    KeyType.KEY_PAGE_DOWN: b"\x1b[6~",
    # Function keys
    KeyType.KEY_F1: b"\x1bOP",
    KeyType.KEY_F2: b"\x1bOQ",
    KeyType.KEY_F3: b"\x1bOR",
    KeyType.KEY_F4: b"\x1bOS",
    KeyType.KEY_F5: b"\x1b[15~",
    KeyType.KEY_F6: b"\x1b[17~",
    KeyType.KEY_F7: b"\x1b[18~",
    KeyType.KEY_F8: b"\x1b[19~",
    KeyType.KEY_F9: b"\x1b[20~",
    KeyType.KEY_F10: b"\x1b[21~",
    KeyType.KEY_F11: b"\x1b[23~",
    KeyType.KEY_F12: b"\x1b[24~",
    # Control characters
    KeyType.KEY_CTRL_C: b"\x03",
    KeyType.KEY_CTRL_D: b"\x04",
    KeyType.KEY_CTRL_Z: b"\x1a",
}


def get_key_sequence(key: KeyType, modifiers: int = 0) -> bytes:
    """Get escape sequence for a key with optional modifiers.

    Args:
        key: The key type from the proto enum.
        modifiers: Modifier bits (MOD_CTRL, MOD_ALT, MOD_SHIFT).

    Returns:
        The escape sequence for the key, or empty bytes if unknown.
    """
    base = KEY_SEQUENCES.get(key)
    if base is None:
        return b""

    # Handle modified keys
    if modifiers:
        return apply_modifiers(key, base, modifiers)
    return base


def apply_modifiers(key: KeyType, base: bytes, modifiers: int) -> bytes:
    """Apply modifier keys to base escape sequence.

    XTerm modifier encoding: CSI <param> ; <modifier+1> <final>
    Modifier bits: Shift=4, Alt=2, Ctrl=1 → parameter = 1 + bits

    Args:
        key: The key type.
        base: The base escape sequence without modifiers.
        modifiers: Modifier bitmask.

    Returns:
        Modified escape sequence.
    """
    # Shift+Tab is a special case: ESC [ Z (backtab)
    if key == KeyType.KEY_TAB and (modifiers & MOD_SHIFT):
        return b"\x1b[Z"

    # For control chars with Ctrl already, just return base
    if key in (KeyType.KEY_CTRL_C, KeyType.KEY_CTRL_D, KeyType.KEY_CTRL_Z):
        return base

    # SS3 sequences (F1-F4): ESC O P → ESC [ 1 ; <mod> P
    if base.startswith(b"\x1bO") and len(base) == 3:
        final = base[-1:]
        mod_param = 1 + modifiers
        return b"\x1b[1;" + str(mod_param).encode() + final

    # CSI sequences: ESC [ ... → ESC [ <param> ; <mod> <final>
    if base.startswith(b"\x1b[") and len(base) >= 3:
        return _apply_csi_modifier(base, modifiers)

    return base


def _apply_csi_modifier(base: bytes, modifiers: int) -> bytes:
    """Apply modifiers to CSI sequences.

    Examples:
        Shift+Up (base=\\x1b[A) → \\x1b[1;2A
        Ctrl+Up → \\x1b[1;5A
        PageUp+Shift (base=\\x1b[5~) → \\x1b[5;2~

    Args:
        base: The base CSI escape sequence.
        modifiers: Modifier bitmask.

    Returns:
        Modified CSI escape sequence.
    """
    mod_param = 1 + modifiers

    # Parse: ESC [ <params> <final>
    final = base[-1:]
    params = base[2:-1]  # Between [ and final

    if params:
        # Has existing param (e.g., \x1b[5~ for PageUp)
        return b"\x1b[" + params + b";" + str(mod_param).encode() + final
    else:
        # No param (e.g., \x1b[A for Up)
        return b"\x1b[1;" + str(mod_param).encode() + final
