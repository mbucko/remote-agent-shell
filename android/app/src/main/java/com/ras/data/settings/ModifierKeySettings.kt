package com.ras.data.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for modifier key visibility settings.
 * Extracted to enable easy testing/mocking.
 */
interface ModifierKeySettings {
    val showCtrlKey: StateFlow<Boolean>
    val showShiftKey: StateFlow<Boolean>
    val showAltKey: StateFlow<Boolean>
    val showMetaKey: StateFlow<Boolean>
}
