package com.ras.fakes

import com.ras.data.settings.ModifierKeySettings
import com.ras.data.settings.NotificationSettings
import com.ras.data.settings.NotificationType
import com.ras.data.settings.SettingsQuickButton
import com.ras.data.settings.SettingsRepository
import com.ras.data.settings.SettingsSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake SettingsRepository for testing.
 * Provides in-memory implementation for tests.
 */
class FakeSettingsRepository : SettingsRepository {
    // Observable state
    private val _notificationSettings = MutableStateFlow(NotificationSettings())
    override val notificationSettings: StateFlow<NotificationSettings> = _notificationSettings

    private val _quickButtons = MutableStateFlow<List<SettingsQuickButton>>(emptyList())
    override val quickButtons: StateFlow<List<SettingsQuickButton>> = _quickButtons

    private val _defaultAgent = MutableStateFlow<String?>(null)
    override val defaultAgent: StateFlow<String?> = _defaultAgent

    private val _autoConnectEnabled = MutableStateFlow(false)
    override val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled

    // Modifier keys
    private val _showCtrlKey = MutableStateFlow(true)
    override val showCtrlKey: StateFlow<Boolean> = _showCtrlKey

    private val _showShiftKey = MutableStateFlow(true)
    override val showShiftKey: StateFlow<Boolean> = _showShiftKey

    private val _showAltKey = MutableStateFlow(true)
    override val showAltKey: StateFlow<Boolean> = _showAltKey

    private val _showMetaKey = MutableStateFlow(true)
    override val showMetaKey: StateFlow<Boolean> = _showMetaKey

    // Storage
    private var terminalFontSize: Float = 14f
    private var enabledButtons = mutableListOf<SettingsQuickButton>()

    // Default Agent
    override fun getDefaultAgent(): String? = _defaultAgent.value
    override fun setDefaultAgent(agent: String?) {
        _defaultAgent.value = agent
    }

    override fun validateDefaultAgent(installedAgents: List<String>): Boolean {
        val current = getDefaultAgent() ?: return true
        if (current !in installedAgents) {
            setDefaultAgent(null)
            return false
        }
        return true
    }

    // Terminal
    override fun getTerminalFontSize(): Float = terminalFontSize
    override fun setTerminalFontSize(size: Float) {
        terminalFontSize = size
    }

    // Auto-Connect
    override fun getAutoConnectEnabled(): Boolean = _autoConnectEnabled.value
    override fun setAutoConnectEnabled(enabled: Boolean) {
        _autoConnectEnabled.value = enabled
    }

    // Modifier Keys
    override fun getShowCtrlKey(): Boolean = _showCtrlKey.value
    override fun setShowCtrlKey(show: Boolean) {
        _showCtrlKey.value = show
    }

    override fun getShowShiftKey(): Boolean = _showShiftKey.value
    override fun setShowShiftKey(show: Boolean) {
        _showShiftKey.value = show
    }

    override fun getShowAltKey(): Boolean = _showAltKey.value
    override fun setShowAltKey(show: Boolean) {
        _showAltKey.value = show
    }

    override fun getShowMetaKey(): Boolean = _showMetaKey.value
    override fun setShowMetaKey(show: Boolean) {
        _showMetaKey.value = show
    }

    // Quick Buttons
    override fun getEnabledQuickButtons(): List<SettingsQuickButton> = enabledButtons.toList()
    override fun setEnabledQuickButtons(buttons: List<SettingsQuickButton>) {
        enabledButtons.clear()
        enabledButtons.addAll(buttons)
        _quickButtons.value = buttons
    }

    override fun enableQuickButton(button: SettingsQuickButton) {
        if (button !in enabledButtons) {
            enabledButtons.add(button)
            _quickButtons.value = enabledButtons.toList()
        }
    }

    override fun disableQuickButton(button: SettingsQuickButton) {
        enabledButtons.remove(button)
        _quickButtons.value = enabledButtons.toList()
    }

    override fun reorderQuickButtons(fromIndex: Int, toIndex: Int) {
        if (fromIndex in enabledButtons.indices && toIndex in enabledButtons.indices) {
            val button = enabledButtons.removeAt(fromIndex)
            enabledButtons.add(toIndex, button)
            _quickButtons.value = enabledButtons.toList()
        }
    }

    // Notifications
    override fun getNotificationSettings(): NotificationSettings = _notificationSettings.value
    override fun setNotificationSettings(settings: NotificationSettings) {
        _notificationSettings.value = settings
    }

    override fun setNotificationEnabled(type: NotificationType, enabled: Boolean) {
        val current = _notificationSettings.value
        _notificationSettings.value = when (type) {
            NotificationType.APPROVAL -> current.copy(approvalEnabled = enabled)
            NotificationType.COMPLETION -> current.copy(completionEnabled = enabled)
            NotificationType.ERROR -> current.copy(errorEnabled = enabled)
        }
    }

    override fun isNotificationEnabled(type: NotificationType): Boolean {
        return when (type) {
            NotificationType.APPROVAL -> _notificationSettings.value.approvalEnabled
            NotificationType.COMPLETION -> _notificationSettings.value.completionEnabled
            NotificationType.ERROR -> _notificationSettings.value.errorEnabled
        }
    }

    // Reset
    override fun resetSection(section: SettingsSection) {
        // No-op for tests
    }

    override fun resetAll() {
        // Reset to defaults
        _autoConnectEnabled.value = false
        _defaultAgent.value = null
        terminalFontSize = 14f
        enabledButtons.clear()
        _quickButtons.value = emptyList()
        _notificationSettings.value = NotificationSettings()
    }

    // Version
    override fun getSettingsVersion(): Int = 1
}
