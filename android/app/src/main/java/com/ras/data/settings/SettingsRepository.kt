package com.ras.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SettingsRepository"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        SettingsKeys.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Observable state for reactive UI
    private val _notificationSettings = MutableStateFlow(loadNotificationSettings())
    val notificationSettings: StateFlow<NotificationSettings> = _notificationSettings.asStateFlow()

    private val _quickButtons = MutableStateFlow(loadQuickButtons())
    val quickButtons: StateFlow<List<SettingsQuickButton>> = _quickButtons.asStateFlow()

    private val _defaultAgent = MutableStateFlow(loadDefaultAgent())
    val defaultAgent: StateFlow<String?> = _defaultAgent.asStateFlow()

    init {
        migrateIfNeeded()
    }

    // ==========================================================================
    // Default Agent
    // ==========================================================================

    /**
     * Get the default agent for new sessions.
     * Returns null if "Always ask" is selected.
     */
    fun getDefaultAgent(): String? = _defaultAgent.value

    /**
     * Set the default agent for new sessions.
     * Pass null to select "Always ask".
     */
    fun setDefaultAgent(agent: String?) {
        if (agent != null) {
            prefs.edit().putString(SettingsKeys.DEFAULT_AGENT, agent).apply()
        } else {
            prefs.edit().remove(SettingsKeys.DEFAULT_AGENT).apply()
        }
        _defaultAgent.value = agent
        Log.d(TAG, "Default agent set to: $agent")
    }

    /**
     * Validate that the default agent is still in the installed agents list.
     * If not, clears the default and returns false.
     *
     * @param installedAgents List of currently installed agent names
     * @return true if default is valid (or not set), false if it was cleared
     */
    fun validateDefaultAgent(installedAgents: List<String>): Boolean {
        val current = getDefaultAgent() ?: return true  // No default is valid
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
    // Quick Buttons
    // ==========================================================================

    /**
     * Get the enabled quick buttons in order.
     */
    fun getEnabledQuickButtons(): List<SettingsQuickButton> = _quickButtons.value

    /**
     * Set the enabled quick buttons and their order.
     */
    fun setEnabledQuickButtons(buttons: List<SettingsQuickButton>) {
        val json = serializeQuickButtons(buttons)
        prefs.edit().putString(SettingsKeys.QUICK_BUTTONS, json).apply()
        _quickButtons.value = buttons
        Log.d(TAG, "Quick buttons set: ${buttons.map { it.id }}")
    }

    /**
     * Add a quick button to the enabled list.
     */
    fun enableQuickButton(button: SettingsQuickButton) {
        val current = _quickButtons.value.toMutableList()
        if (button !in current) {
            current.add(button)
            setEnabledQuickButtons(current)
        }
    }

    /**
     * Remove a quick button from the enabled list.
     */
    fun disableQuickButton(button: SettingsQuickButton) {
        val current = _quickButtons.value.toMutableList()
        current.remove(button)
        setEnabledQuickButtons(current)
    }

    /**
     * Reorder quick buttons by moving one from oldIndex to newIndex.
     */
    fun reorderQuickButtons(fromIndex: Int, toIndex: Int) {
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
        // Use simple comma-separated format for unit test compatibility
        // Format: ["id1","id2","id3"] (JSON-like but parsed manually)
        return buttons.joinToString(",", "[", "]") { "\"${it.id}\"" }
    }

    internal fun deserializeQuickButtons(serialized: String): List<SettingsQuickButton> {
        return try {
            // Parse simple JSON-like array format: ["id1","id2","id3"]
            val trimmed = serialized.trim()

            // Empty array
            if (trimmed.isBlank() || trimmed == "[]") {
                return emptyList()
            }

            // Must start with [ and end with ]
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                Log.w(TAG, "Invalid quick buttons format, using defaults: $serialized")
                return SettingsDefaults.QUICK_BUTTONS
            }

            val content = trimmed
                .removePrefix("[")
                .removeSuffix("]")
                .trim()

            if (content.isEmpty()) {
                return emptyList()
            }

            val buttons = content.split(",")
                .map { it.trim().removeSurrounding("\"") }
                .mapNotNull { id -> SettingsQuickButton.fromId(id) }

            // If we couldn't parse any valid buttons, return defaults
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

    /**
     * Get the current notification settings.
     */
    fun getNotificationSettings(): NotificationSettings = _notificationSettings.value

    /**
     * Set all notification settings at once.
     */
    fun setNotificationSettings(settings: NotificationSettings) {
        prefs.edit()
            .putBoolean(SettingsKeys.NOTIFY_APPROVAL, settings.approvalEnabled)
            .putBoolean(SettingsKeys.NOTIFY_COMPLETION, settings.completionEnabled)
            .putBoolean(SettingsKeys.NOTIFY_ERROR, settings.errorEnabled)
            .apply()
        _notificationSettings.value = settings
        Log.d(TAG, "Notification settings updated: $settings")
    }

    /**
     * Toggle a specific notification type.
     */
    fun setNotificationEnabled(type: NotificationType, enabled: Boolean) {
        val current = _notificationSettings.value
        val updated = when (type) {
            NotificationType.APPROVAL -> current.copy(approvalEnabled = enabled)
            NotificationType.COMPLETION -> current.copy(completionEnabled = enabled)
            NotificationType.ERROR -> current.copy(errorEnabled = enabled)
        }
        setNotificationSettings(updated)
    }

    /**
     * Check if a notification type is enabled.
     */
    fun isNotificationEnabled(type: NotificationType): Boolean {
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

    /**
     * Reset a specific section to defaults.
     */
    fun resetSection(section: SettingsSection) {
        when (section) {
            SettingsSection.SESSIONS -> {
                prefs.edit().remove(SettingsKeys.DEFAULT_AGENT).apply()
                _defaultAgent.value = SettingsDefaults.DEFAULT_AGENT
                Log.d(TAG, "Reset sessions section")
            }
            SettingsSection.TERMINAL -> {
                prefs.edit().remove(SettingsKeys.QUICK_BUTTONS).apply()
                _quickButtons.value = SettingsDefaults.QUICK_BUTTONS
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

    /**
     * Reset all settings to defaults.
     */
    fun resetAll() {
        prefs.edit().clear().apply()
        _defaultAgent.value = SettingsDefaults.DEFAULT_AGENT
        _quickButtons.value = SettingsDefaults.QUICK_BUTTONS
        _notificationSettings.value = SettingsDefaults.NOTIFICATIONS
        migrateIfNeeded() // Re-apply version
        Log.d(TAG, "Reset all settings")
    }

    // ==========================================================================
    // Migration
    // ==========================================================================

    /**
     * Get the current settings version.
     */
    fun getSettingsVersion(): Int = prefs.getInt(SettingsKeys.VERSION, 0)

    private fun migrateIfNeeded() {
        val currentVersion = getSettingsVersion()
        if (currentVersion < SETTINGS_VERSION) {
            // Future migrations go here
            // For now, just update version
            prefs.edit().putInt(SettingsKeys.VERSION, SETTINGS_VERSION).apply()
            Log.d(TAG, "Migrated settings from v$currentVersion to v$SETTINGS_VERSION")
        }
    }
}
