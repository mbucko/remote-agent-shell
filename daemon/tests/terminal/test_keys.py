"""Tests for terminal key escape sequence mappings."""

import pytest

from ras.proto.ras import KeyType
from ras.terminal.keys import (
    KEY_SEQUENCES,
    MOD_ALT,
    MOD_CTRL,
    MOD_SHIFT,
    get_key_sequence,
)


class TestKeyConstants:
    """Test key-related constants."""

    def test_modifier_bits_match_xterm_standard(self):
        """Modifier bits should match XTerm standard encoding.

        XTerm modifier parameter = 1 + bits, where:
        - Shift = 1 → param 2
        - Alt = 2 → param 3
        - Ctrl = 4 → param 5
        """
        assert MOD_SHIFT == 1
        assert MOD_ALT == 2
        assert MOD_CTRL == 4

    def test_modifiers_can_be_combined(self):
        """Modifiers should be combinable with bitwise OR."""
        combined = MOD_CTRL | MOD_ALT | MOD_SHIFT
        assert combined == 7
        assert combined & MOD_CTRL == MOD_CTRL
        assert combined & MOD_ALT == MOD_ALT
        assert combined & MOD_SHIFT == MOD_SHIFT


class TestKeySequences:
    """Test the KEY_SEQUENCES dictionary."""

    def test_all_basic_keys_defined(self):
        """All basic keys should have sequences."""
        basic_keys = [
            KeyType.KEY_ENTER,
            KeyType.KEY_TAB,
            KeyType.KEY_BACKSPACE,
            KeyType.KEY_ESCAPE,
            KeyType.KEY_DELETE,
            KeyType.KEY_INSERT,
        ]
        for key in basic_keys:
            assert key in KEY_SEQUENCES, f"{key} should be defined"

    def test_all_arrow_keys_defined(self):
        """All arrow keys should have sequences."""
        arrow_keys = [
            KeyType.KEY_UP,
            KeyType.KEY_DOWN,
            KeyType.KEY_LEFT,
            KeyType.KEY_RIGHT,
        ]
        for key in arrow_keys:
            assert key in KEY_SEQUENCES, f"{key} should be defined"

    def test_all_navigation_keys_defined(self):
        """All navigation keys should have sequences."""
        nav_keys = [
            KeyType.KEY_HOME,
            KeyType.KEY_END,
            KeyType.KEY_PAGE_UP,
            KeyType.KEY_PAGE_DOWN,
        ]
        for key in nav_keys:
            assert key in KEY_SEQUENCES, f"{key} should be defined"

    def test_all_function_keys_defined(self):
        """All function keys F1-F12 should have sequences."""
        function_keys = [
            KeyType.KEY_F1,
            KeyType.KEY_F2,
            KeyType.KEY_F3,
            KeyType.KEY_F4,
            KeyType.KEY_F5,
            KeyType.KEY_F6,
            KeyType.KEY_F7,
            KeyType.KEY_F8,
            KeyType.KEY_F9,
            KeyType.KEY_F10,
            KeyType.KEY_F11,
            KeyType.KEY_F12,
        ]
        for key in function_keys:
            assert key in KEY_SEQUENCES, f"{key} should be defined"

    def test_all_control_keys_defined(self):
        """All control keys should have sequences."""
        ctrl_keys = [
            KeyType.KEY_CTRL_C,
            KeyType.KEY_CTRL_D,
            KeyType.KEY_CTRL_Z,
        ]
        for key in ctrl_keys:
            assert key in KEY_SEQUENCES, f"{key} should be defined"


class TestGetKeySequence:
    """Test the get_key_sequence function."""

    # Basic keys
    def test_key_enter_sequence(self):
        """Enter should produce carriage return."""
        assert get_key_sequence(KeyType.KEY_ENTER) == b"\r"

    def test_key_tab_sequence(self):
        """Tab should produce tab character."""
        assert get_key_sequence(KeyType.KEY_TAB) == b"\t"

    def test_key_backspace_sequence(self):
        """Backspace should produce DEL (0x7f)."""
        assert get_key_sequence(KeyType.KEY_BACKSPACE) == b"\x7f"

    def test_key_escape_sequence(self):
        """Escape should produce ESC (0x1b)."""
        assert get_key_sequence(KeyType.KEY_ESCAPE) == b"\x1b"

    def test_key_delete_sequence(self):
        """Delete should produce CSI 3 ~."""
        assert get_key_sequence(KeyType.KEY_DELETE) == b"\x1b[3~"

    def test_key_insert_sequence(self):
        """Insert should produce CSI 2 ~."""
        assert get_key_sequence(KeyType.KEY_INSERT) == b"\x1b[2~"

    # Arrow keys
    def test_key_up_sequence(self):
        """Up arrow should produce CSI A."""
        assert get_key_sequence(KeyType.KEY_UP) == b"\x1b[A"

    def test_key_down_sequence(self):
        """Down arrow should produce CSI B."""
        assert get_key_sequence(KeyType.KEY_DOWN) == b"\x1b[B"

    def test_key_right_sequence(self):
        """Right arrow should produce CSI C."""
        assert get_key_sequence(KeyType.KEY_RIGHT) == b"\x1b[C"

    def test_key_left_sequence(self):
        """Left arrow should produce CSI D."""
        assert get_key_sequence(KeyType.KEY_LEFT) == b"\x1b[D"

    # Navigation keys
    def test_key_home_sequence(self):
        """Home should produce CSI H."""
        assert get_key_sequence(KeyType.KEY_HOME) == b"\x1b[H"

    def test_key_end_sequence(self):
        """End should produce CSI F."""
        assert get_key_sequence(KeyType.KEY_END) == b"\x1b[F"

    def test_key_page_up_sequence(self):
        """Page Up should produce CSI 5 ~."""
        assert get_key_sequence(KeyType.KEY_PAGE_UP) == b"\x1b[5~"

    def test_key_page_down_sequence(self):
        """Page Down should produce CSI 6 ~."""
        assert get_key_sequence(KeyType.KEY_PAGE_DOWN) == b"\x1b[6~"

    # Function keys
    def test_key_f1_sequence(self):
        """F1 should produce SS3 P."""
        assert get_key_sequence(KeyType.KEY_F1) == b"\x1bOP"

    def test_key_f2_sequence(self):
        """F2 should produce SS3 Q."""
        assert get_key_sequence(KeyType.KEY_F2) == b"\x1bOQ"

    def test_key_f3_sequence(self):
        """F3 should produce SS3 R."""
        assert get_key_sequence(KeyType.KEY_F3) == b"\x1bOR"

    def test_key_f4_sequence(self):
        """F4 should produce SS3 S."""
        assert get_key_sequence(KeyType.KEY_F4) == b"\x1bOS"

    def test_key_f5_sequence(self):
        """F5 should produce CSI 15 ~."""
        assert get_key_sequence(KeyType.KEY_F5) == b"\x1b[15~"

    def test_key_f6_sequence(self):
        """F6 should produce CSI 17 ~."""
        assert get_key_sequence(KeyType.KEY_F6) == b"\x1b[17~"

    def test_key_f7_sequence(self):
        """F7 should produce CSI 18 ~."""
        assert get_key_sequence(KeyType.KEY_F7) == b"\x1b[18~"

    def test_key_f8_sequence(self):
        """F8 should produce CSI 19 ~."""
        assert get_key_sequence(KeyType.KEY_F8) == b"\x1b[19~"

    def test_key_f9_sequence(self):
        """F9 should produce CSI 20 ~."""
        assert get_key_sequence(KeyType.KEY_F9) == b"\x1b[20~"

    def test_key_f10_sequence(self):
        """F10 should produce CSI 21 ~."""
        assert get_key_sequence(KeyType.KEY_F10) == b"\x1b[21~"

    def test_key_f11_sequence(self):
        """F11 should produce CSI 23 ~."""
        assert get_key_sequence(KeyType.KEY_F11) == b"\x1b[23~"

    def test_key_f12_sequence(self):
        """F12 should produce CSI 24 ~."""
        assert get_key_sequence(KeyType.KEY_F12) == b"\x1b[24~"

    # Control characters
    def test_key_ctrl_c_sequence(self):
        """Ctrl+C should produce ETX (0x03)."""
        assert get_key_sequence(KeyType.KEY_CTRL_C) == b"\x03"

    def test_key_ctrl_d_sequence(self):
        """Ctrl+D should produce EOT (0x04)."""
        assert get_key_sequence(KeyType.KEY_CTRL_D) == b"\x04"

    def test_key_ctrl_z_sequence(self):
        """Ctrl+Z should produce SUB (0x1a)."""
        assert get_key_sequence(KeyType.KEY_CTRL_Z) == b"\x1a"

    # Unknown keys
    def test_unknown_key_returns_empty(self):
        """Unknown key type should return empty bytes."""
        assert get_key_sequence(KeyType.KEY_UNKNOWN) == b""



class TestKeySequenceFormats:
    """Test that key sequences follow expected formats."""

    def test_arrow_keys_use_csi_format(self):
        """Arrow keys should use CSI format (ESC [)."""
        arrow_keys = [
            KeyType.KEY_UP,
            KeyType.KEY_DOWN,
            KeyType.KEY_LEFT,
            KeyType.KEY_RIGHT,
        ]
        for key in arrow_keys:
            seq = get_key_sequence(key)
            assert seq.startswith(b"\x1b["), f"{key} should start with CSI"

    def test_control_chars_are_single_byte(self):
        """Control characters should be single bytes."""
        ctrl_keys = [
            KeyType.KEY_CTRL_C,
            KeyType.KEY_CTRL_D,
            KeyType.KEY_CTRL_Z,
        ]
        for key in ctrl_keys:
            seq = get_key_sequence(key)
            assert len(seq) == 1, f"{key} should be single byte"

    def test_f1_f4_use_ss3_format(self):
        """F1-F4 should use SS3 format (ESC O)."""
        f_keys = [
            KeyType.KEY_F1,
            KeyType.KEY_F2,
            KeyType.KEY_F3,
            KeyType.KEY_F4,
        ]
        for key in f_keys:
            seq = get_key_sequence(key)
            assert seq.startswith(b"\x1bO"), f"{key} should start with SS3"

    def test_f5_f12_use_csi_format(self):
        """F5-F12 should use CSI format."""
        f_keys = [
            KeyType.KEY_F5,
            KeyType.KEY_F6,
            KeyType.KEY_F7,
            KeyType.KEY_F8,
            KeyType.KEY_F9,
            KeyType.KEY_F10,
            KeyType.KEY_F11,
            KeyType.KEY_F12,
        ]
        for key in f_keys:
            seq = get_key_sequence(key)
            assert seq.startswith(b"\x1b["), f"{key} should start with CSI"


class TestModifierSequences:
    """Tests for modifier key escape sequences."""

    def test_no_modifier_returns_base(self):
        """Without modifiers, should return base sequence."""
        assert get_key_sequence(KeyType.KEY_UP) == b"\x1b[A"
        assert get_key_sequence(KeyType.KEY_TAB) == b"\t"

    def test_shift_tab_returns_backtab(self):
        """Shift+Tab should return ESC [ Z (backtab)."""
        assert get_key_sequence(KeyType.KEY_TAB, MOD_SHIFT) == b"\x1b[Z"

    def test_shift_up_arrow(self):
        """Shift+Up should return ESC [ 1 ; 2 A."""
        # Shift = 1, param = 1 + 1 = 2 (XTerm standard)
        assert get_key_sequence(KeyType.KEY_UP, MOD_SHIFT) == b"\x1b[1;2A"

    def test_ctrl_up_arrow(self):
        """Ctrl+Up should return ESC [ 1 ; 5 A."""
        # Ctrl = 4, param = 1 + 4 = 5 (XTerm standard)
        assert get_key_sequence(KeyType.KEY_UP, MOD_CTRL) == b"\x1b[1;5A"

    def test_ctrl_shift_up_arrow(self):
        """Ctrl+Shift+Up should return ESC [ 1 ; 6 A."""
        # Ctrl = 4, Shift = 1, param = 1 + 5 = 6 (XTerm standard)
        assert get_key_sequence(KeyType.KEY_UP, MOD_CTRL | MOD_SHIFT) == b"\x1b[1;6A"

    def test_shift_page_up(self):
        """Shift+PageUp should return ESC [ 5 ; 2 ~."""
        # Shift = 1, param = 1 + 1 = 2 (XTerm standard)
        assert get_key_sequence(KeyType.KEY_PAGE_UP, MOD_SHIFT) == b"\x1b[5;2~"

    def test_shift_f1_ss3_sequence(self):
        """Shift+F1 (SS3) should convert to CSI format with modifier."""
        # Shift = 1, param = 1 + 1 = 2 (XTerm standard)
        assert get_key_sequence(KeyType.KEY_F1, MOD_SHIFT) == b"\x1b[1;2P"

    def test_ctrl_on_ctrl_c_returns_base(self):
        """Ctrl modifier on KEY_CTRL_C should just return base (no double-ctrl)."""
        assert get_key_sequence(KeyType.KEY_CTRL_C, MOD_CTRL) == b"\x03"

    def test_alt_up_arrow(self):
        """Alt+Up should return ESC [ 1 ; 3 A."""
        # Alt = 2, param = 1 + 2 = 3 (XTerm standard)
        assert get_key_sequence(KeyType.KEY_UP, MOD_ALT) == b"\x1b[1;3A"

    def test_all_modifiers(self):
        """Ctrl+Alt+Shift+Up should return ESC [ 1 ; 8 A."""
        # All mods = 4 + 2 + 1 = 7, param = 1 + 7 = 8 (XTerm standard)
        all_mods = MOD_CTRL | MOD_ALT | MOD_SHIFT
        assert get_key_sequence(KeyType.KEY_UP, all_mods) == b"\x1b[1;8A"

    def test_modifier_on_enter_returns_base(self):
        """Modifiers on single-char keys like Enter return base (no CSI format)."""
        # Enter is \r, not a CSI sequence, so modifiers don't apply
        assert get_key_sequence(KeyType.KEY_ENTER, MOD_CTRL) == b"\r"

    def test_ctrl_d_ignores_ctrl_modifier(self):
        """KEY_CTRL_D ignores Ctrl modifier (already has Ctrl)."""
        assert get_key_sequence(KeyType.KEY_CTRL_D, MOD_CTRL) == b"\x04"

    def test_ctrl_z_ignores_ctrl_modifier(self):
        """KEY_CTRL_Z ignores Ctrl modifier (already has Ctrl)."""
        assert get_key_sequence(KeyType.KEY_CTRL_Z, MOD_CTRL) == b"\x1a"

    def test_delete_with_shift(self):
        """Shift+Delete should return ESC [ 3 ; 2 ~."""
        # Shift = 1, param = 1 + 1 = 2 (XTerm standard)
        assert get_key_sequence(KeyType.KEY_DELETE, MOD_SHIFT) == b"\x1b[3;2~"

    def test_f5_with_ctrl(self):
        """Ctrl+F5 should return ESC [ 15 ; 5 ~."""
        # Ctrl = 4, param = 1 + 4 = 5 (XTerm standard)
        assert get_key_sequence(KeyType.KEY_F5, MOD_CTRL) == b"\x1b[15;5~"
