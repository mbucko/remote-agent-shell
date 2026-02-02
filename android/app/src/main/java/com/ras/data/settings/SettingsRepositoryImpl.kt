package com.ras.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for app settings stored in SharedPreferences.
 *
 * Handles:
 * - Default agent selection
 * - Quick button configuration
 * - Notification preferences
 * - Settings versioning and migration
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository, ModifierKeySettings {
    companion object {
        private const val TAG = "SettingsRepository"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        SettingsKeys.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Observable state for reactive UI
    private val _notificationSettings = MutableStateFlow(loadNotificationSettings())
    override val notificationSettings: StateFlow<NotificationSettings> = _notificationSettings.asStateFlow()

    private val _quickButtons = MutableStateFlow(loadQuickButtons())
    override val quickButtons: StateFlow<List<SettingsQuickButton>> = _quickButtons.asStateFlow()

    private val _defaultAgent = MutableStateFlow(loadDefaultAgent())
    override val defaultAgent: StateFlow<String?> = _defaultAgent.asStateFlow()

    init {
        migrateIfNeeded()
    }

    // ==========================================================================
    // Default Agent
    // ==========================================================================

    override fun getDefaultAgent(): String? = _defaultAgent.value

    override fun setDefaultAgent(agent: String?) {
        if (agent != null) {
            prefs.edit().putString(SettingsKeys.DEFAULT_AGENT, agent).apply()
        } else {
            prefs.edit().remove(SettingsKeys.DEFAULT_AGENT).apply()
        }
        _defaultAgent.value = agent
        Log.d(TAG, "Default agent set to: $agent")
    }

    override fun validateDefaultAgent(installedAgents: List<String>): Boolean {
        val current = getDefaultAgent() ?: return true
        if (current !in installedAgents) {
            setDefaultAgent(null)
            Log.w(TAG, "Default agent '$current' no longer available, cleared")
            return false
        }
        return true
    }

    private fun loadDefaultAgent(): String? {
        return prefs.getString(SettingsKeys.DEFAULT_AGENT, null)
    }

    // ==========================================================================
    // Terminal Font Size
    // ==========================================================================

    override fun getTerminalFontSize(): Float {
        return prefs.getFloat(SettingsKeys.TERMINAL_FONT_SIZE, SettingsDefaults.TERMINAL_FONT_SIZE)
    }

    override fun setTerminalFontSize(size: Float) {
        prefs.edit().putFloat(SettingsKeys.TERMINAL_FONT_SIZE, size).apply()
        Log.d(TAG, "Terminal font size set to: $size")
    }

    // ==========================================================================
    // Auto-Connect
    // ==========================================================================

    private val _autoConnectEnabled = MutableStateFlow(loadAutoConnect())
    override val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled.asStateFlow()

    override fun getAutoConnectEnabled(): Boolean = _autoConnectEnabled.value

    override fun setAutoConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.AUTO_CONNECT, enabled).apply()
        _autoConnectEnabled.value = enabled
        Log.d(TAG, "Auto-connect set to: $enabled")
    }

    private fun loadAutoConnect(): Boolean {
        return prefs.getBoolean(SettingsKeys.AUTO_CONNECT, SettingsDefaults.AUTO_CONNECT)
    }

    // ==========================================================================
    // Modifier Key Visibility (via ModifierKeySettings interface)
    // ==========================================================================

    private val _showCtrlKey = MutableStateFlow(loadShowCtrlKey())
    override val showCtrlKey: StateFlow<Boolean> = _showCtrlKey.asStateFlow()

    private val _showShiftKey = MutableStateFlow(loadShowShiftKey())
    override val showShiftKey: StateFlow<Boolean> = _showShiftKey.asStateFlow()

    private val _showAltKey = MutableStateFlow(loadShowAltKey())
    override val showAltKey: StateFlow<Boolean> = _showAltKey.asStateFlow()

    private val _showMetaKey = MutableStateFlow(loadShowMetaKey())
    override val showMetaKey: StateFlow<Boolean> = _showMetaKey.asStateFlow()

    override fun getShowCtrlKey(): Boolean = _showCtrlKey.value
    override fun getShowShiftKey(): Boolean = _showShiftKey.value
    override fun getShowAltKey(): Boolean = _showAltKey.value
    override fun getShowMetaKey(): Boolean = _showMetaKey.value

    override fun setShowCtrlKey(show: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_CTRL_KEY, show).apply()
        _showCtrlKey.value = show
        Log.d(TAG, "Show Ctrl key set to: $show")
    }

    override fun setShowShiftKey(show: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_SHIFT_KEY, show).apply()
        _showShiftKey.value = show
        Log.d(TAG, "Show Shift key set to: $show")
    }

    override fun setShowAltKey(show: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_ALT_KEY, show).apply()
        _showAltKey.value = show
        Log.d(TAG, "Show Alt key set to: $show")
    }

    override fun setShowMetaKey(show: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.SHOW_META_KEY, show).apply()
        _showMetaKey.value = show
        Log.d(TAG, "Show Meta key set to: $show")
    }

    private fun loadShowCtrlKey(): Boolean =
        prefs.getBoolean(SettingsKeys.SHOW_CTRL_KEY, SettingsDefaults.SHOW_CTRL_KEY)

    private fun loadShowShiftKey(): Boolean =
        prefs.getBoolean(SettingsKeys.SHOW_SHIFT_KEY, SettingsDefaults.SHOW_SHIFT_KEY)

    private fun loadShowAltKey(): Boolean =
        prefs.getBoolean(SettingsKeys.SHOW_ALT_KEY, SettingsDefaults.SHOW_ALT_KEY)

    private fun loadShowMetaKey(): Boolean =
        prefs.getBoolean(SettingsKeys.SHOW_META_KEY, SettingsDefaults.SHOW_META_KEY)

    // ==========================================================================
    // Quick Buttons
    // ==========================================================================

    override fun getEnabledQuickButtons(): List<SettingsQuickButton> = _quickButtons.value

    override fun setEnabledQuickButtons(buttons: List<SettingsQuickButton>) {
        val json = serializeQuickButtons(buttons)
        prefs.edit().putString(SettingsKeys.QUICK_BUTTONS, json).apply()
        _quickButtons.value = buttons
        Log.d(TAG, "Quick buttons set: ${buttons.map { it.id }}")
    }

    override fun enableQuickButton(button: SettingsQuickButton) {
        val current = _quickButtons.value.toMutableList()
        if (button !in current) {
            current.add(button)
            setEnabledQuickButtons(current)
        }
    }

    override fun disableQuickButton(button: SettingsQuickButton) {
        val current = _quickButtons.value.toMutableList()
        current.remove(button)
        setEnabledQuickButtons(current)
    }

    override fun reorderQuickButtons(fromIndex: Int, toIndex: Int) {
        val current = _quickButtons.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            setEnabledQuickButtons(current)
        }
    }

    private fun loadQuickButtons(): List<SettingsQuickButton> {
        val json = prefs.getString(SettingsKeys.QUICK_BUTTONS, null)
            ?: return SettingsDefaults.QUICK_BUTTONS

        return deserializeQuickButtons(json)
    }

    internal fun serializeQuickButtons(buttons: List<SettingsQuickButton>): String {
        return buttons.joinToString(",", "[", "]") { "\"${it.id}\"" }
    }

    internal fun deserializeQuickButtons(serialized: String): List<SettingsQuickButton> {
        return try {
            val trimmed = serialized.trim()
            if (trimmed.isBlank() || trimmed == "[]") {
                return emptyList()
            }
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                Log.w(TAG, "Invalid quick buttons format, using defaults: $serialized")
                return SettingsDefaults.QUICK_BUTTONS
            }
            val content = trimmed.removePrefix("[").removeSuffix("]").trim()
            if (content.isEmpty()) {
                return emptyList()
            }
            val buttons = content.split(",")
                .map { it.trim().removeSurrounding("\"") }
                .mapNotNull { id -> SettingsQuickButton.fromId(id) }
            if (buttons.isEmpty() && content.isNotEmpty()) {
                Log.w(TAG, "No valid buttons found, using defaults: $serialized")
                return SettingsDefaults.QUICK_BUTTONS
            }
            buttons
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse quick buttons: $serialized", e)
            SettingsDefaults.QUICK_BUTTONS
        }
    }

    // ==========================================================================
    // Notifications
    // ==========================================================================

    override fun getNotificationSettings(): NotificationSettings = _notificationSettings.value

    override fun setNotificationSettings(settings: NotificationSettings) {
        prefs.edit()
            .putBoolean(SettingsKeys.NOTIFY_APPROVAL, settings.approvalEnabled)
            .putBoolean(SettingsKeys.NOTIFY_COMPLETION, settings.completionEnabled)
            .putBoolean(SettingsKeys.NOTIFY_ERROR, settings.errorEnabled)
            .apply()
        _notificationSettings.value = settings
        Log.d(TAG, "Notification settings updated: $settings")
    }

    override fun setNotificationEnabled(type: NotificationType, enabled: Boolean) {
        val current = _notificationSettings.value
        val updated = when (type) {
            NotificationType.APPROVAL -> current.copy(approvalEnabled = enabled)
            NotificationType.COMPLETION -> current.copy(completionEnabled = enabled)
            NotificationType.ERROR -> current.copy(errorEnabled = enabled)
        }
        setNotificationSettings(updated)
    }

    override fun isNotificationEnabled(type: NotificationType): Boolean {
        val settings = _notificationSettings.value
        return when (type) {
            NotificationType.APPROVAL -> settings.approvalEnabled
            NotificationType.COMPLETION -> settings.completionEnabled
            NotificationType.ERROR -> settings.errorEnabled
        }
    }

    private fun loadNotificationSettings(): NotificationSettings {
        return NotificationSettings(
            approvalEnabled = prefs.getBoolean(SettingsKeys.NOTIFY_APPROVAL, true),
            completionEnabled = prefs.getBoolean(SettingsKeys.NOTIFY_COMPLETION, true),
            errorEnabled = prefs.getBoolean(SettingsKeys.NOTIFY_ERROR, true)
        )
    }

    // ==========================================================================
    // Reset
    // ==========================================================================

    override fun resetSection(section: SettingsSection) {
        when (section) {
            SettingsSection.SESSIONS -> {
                prefs.edit().remove(SettingsKeys.DEFAULT_AGENT).apply()
                _defaultAgent.value = SettingsDefaults.DEFAULT_AGENT
                Log.d(TAG, "Reset sessions section")
            }
            SettingsSection.TERMINAL -> {
                prefs.edit()
                    .remove(SettingsKeys.QUICK_BUTTONS)
                    .remove(SettingsKeys.TERMINAL_FONT_SIZE)
                    .remove(SettingsKeys.SHOW_CTRL_KEY)
                    .remove(SettingsKeys.SHOW_SHIFT_KEY)
                    .remove(SettingsKeys.SHOW_ALT_KEY)
                    .remove(SettingsKeys.SHOW_META_KEY)
                    .apply()
                _quickButtons.value = SettingsDefaults.QUICK_BUTTONS
                _showCtrlKey.value = SettingsDefaults.SHOW_CTRL_KEY
                _showShiftKey.value = SettingsDefaults.SHOW_SHIFT_KEY
                _showAltKey.value = SettingsDefaults.SHOW_ALT_KEY
                _showMetaKey.value = SettingsDefaults.SHOW_META_KEY
                Log.d(TAG, "Reset terminal section")
            }
            SettingsSection.NOTIFICATIONS -> {
                prefs.edit()
                    .remove(SettingsKeys.NOTIFY_APPROVAL)
                    .remove(SettingsKeys.NOTIFY_COMPLETION)
                    .remove(SettingsKeys.NOTIFY_ERROR)
                    .apply()
                _notificationSettings.value = SettingsDefaults.NOTIFICATIONS
                Log.d(TAG, "Reset notifications section")
            }
        }
    }

    override fun resetAll() {
        prefs.edit().clear().apply()
        _defaultAgent.value = SettingsDefaults.DEFAULT_AGENT
        _quickButtons.value = SettingsDefaults.QUICK_BUTTONS
        _notificationSettings.value = SettingsDefaults.NOTIFICATIONS
        _autoConnectEnabled.value = SettingsDefaults.AUTO_CONNECT
        _showCtrlKey.value = SettingsDefaults.SHOW_CTRL_KEY
        _showShiftKey.value = SettingsDefaults.SHOW_SHIFT_KEY
        _showAltKey.value = SettingsDefaults.SHOW_ALT_KEY
        _showMetaKey.value = SettingsDefaults.SHOW_META_KEY
        migrateIfNeeded()
        Log.d(TAG, "Reset all settings")
    }

    // ==========================================================================
    // Migration
    // ==========================================================================

    override fun getSettingsVersion(): Int = prefs.getInt(SettingsKeys.VERSION, 0)

    private fun migrateIfNeeded() {
        val currentVersion = getSettingsVersion()
        if (currentVersion < SETTINGS_VERSION) {
            prefs.edit().putInt(SettingsKeys.VERSION, SETTINGS_VERSION).apply()
            Log.d(TAG, "Migrated settings from v$currentVersion to v$SETTINGS_VERSION")
        }
    }
}
