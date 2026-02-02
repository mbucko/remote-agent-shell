package com.ras.data.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for app settings.
 * Abstracts SharedPreferences for testability.
 */
interface SettingsRepository : ModifierKeySettings {
    // Observable state
    val notificationSettings: StateFlow<NotificationSettings>
    val quickButtons: StateFlow<List<SettingsQuickButton>>
    val defaultAgent: StateFlow<String?>
    val autoConnectEnabled: StateFlow<Boolean>

    // Default Agent
    fun getDefaultAgent(): String?
    fun setDefaultAgent(agent: String?)
    fun validateDefaultAgent(installedAgents: List<String>): Boolean

    // Terminal
    fun getTerminalFontSize(): Float
    fun setTerminalFontSize(size: Float)

    // Auto-Connect
    fun getAutoConnectEnabled(): Boolean
    fun setAutoConnectEnabled(enabled: Boolean)

    // Modifier Key Visibility
    fun getShowCtrlKey(): Boolean
    fun setShowCtrlKey(show: Boolean)
    fun getShowShiftKey(): Boolean
    fun setShowShiftKey(show: Boolean)
    fun getShowAltKey(): Boolean
    fun setShowAltKey(show: Boolean)
    fun getShowMetaKey(): Boolean
    fun setShowMetaKey(show: Boolean)

    // Quick Buttons
    fun getEnabledQuickButtons(): List<SettingsQuickButton>
    fun setEnabledQuickButtons(buttons: List<SettingsQuickButton>)
    fun enableQuickButton(button: SettingsQuickButton)
    fun disableQuickButton(button: SettingsQuickButton)
    fun reorderQuickButtons(fromIndex: Int, toIndex: Int)

    // Notifications
    fun getNotificationSettings(): NotificationSettings
    fun setNotificationSettings(settings: NotificationSettings)
    fun setNotificationEnabled(type: NotificationType, enabled: Boolean)
    fun isNotificationEnabled(type: NotificationType): Boolean

    // Reset
    fun resetSection(section: SettingsSection)
    fun resetAll()

    // Version
    fun getSettingsVersion(): Int
}
