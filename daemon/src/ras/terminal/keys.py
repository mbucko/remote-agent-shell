"""Key type to escape sequence mappings."""

from ras.proto.ras import KeyType

# Modifier bits
MOD_CTRL = 1
MOD_ALT = 2
MOD_SHIFT = 4

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

    # Handle Ctrl modifier for letter keys (future extension)
    # For now, just return the base sequence
    return base
