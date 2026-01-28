package com.ras.data.settings

/**
 * Settings schema version for migrations.
 */
const val SETTINGS_VERSION = 1

/**
 * SharedPreferences keys for settings storage.
 */
object SettingsKeys {
    const val PREFS_NAME = "ras_settings"
    const val VERSION = "settings_version"

    // Sessions
    const val DEFAULT_AGENT = "default_agent"

    // Terminal
    const val QUICK_BUTTONS = "quick_buttons"

    // Notifications
    const val NOTIFY_APPROVAL = "notify_approval"
    const val NOTIFY_COMPLETION = "notify_completion"
    const val NOTIFY_ERROR = "notify_error"
}

/**
 * Notification type for toggle settings.
 */
enum class NotificationType {
    APPROVAL,
    COMPLETION,
    ERROR
}

/**
 * Settings section for reset functionality.
 */
enum class SettingsSection {
    SESSIONS,
    TERMINAL,
    NOTIFICATIONS
}

/**
 * Notification settings configuration.
 */
data class NotificationSettings(
    val approvalEnabled: Boolean = true,
    val completionEnabled: Boolean = true,
    val errorEnabled: Boolean = true
)

/**
 * Connection/daemon information (read-only).
 */
data class DaemonInfo(
    val connected: Boolean,
    val version: String?,
    val ipAddress: String?,
    val lastSeen: Long?
)

/**
 * Quick button definition for settings.
 * Note: This is a simplified version for settings UI.
 * The actual QuickButton with keyType is in TerminalState.kt
 */
data class SettingsQuickButton(
    val id: String,
    val label: String,
    val keySequence: String
) {
    companion object {
        // Predefined quick buttons
        val YES = SettingsQuickButton("yes", "Y", "y")
        val NO = SettingsQuickButton("no", "N", "n")
        val ENTER = SettingsQuickButton("enter", "Enter", "\n")
        val CTRL_C = SettingsQuickButton("ctrl_c", "Ctrl+C", "\u0003")
        val CTRL_D = SettingsQuickButton("ctrl_d", "Ctrl+D", "\u0004")
        val CTRL_Z = SettingsQuickButton("ctrl_z", "Ctrl+Z", "\u001a")
        val CTRL_L = SettingsQuickButton("ctrl_l", "Ctrl+L", "\u000c")
        val ESC = SettingsQuickButton("esc", "Esc", "\u001b")
        val TAB = SettingsQuickButton("tab", "Tab", "\t")

        /**
         * All available quick buttons.
         */
        val ALL = listOf(YES, NO, ENTER, CTRL_C, CTRL_D, CTRL_Z, CTRL_L, ESC, TAB)

        /**
         * Find button by ID.
         */
        fun fromId(id: String): SettingsQuickButton? = ALL.find { it.id == id }
    }
}

/**
 * Default values for settings.
 */
object SettingsDefaults {
    val DEFAULT_AGENT: String? = null  // "Always ask"
    val QUICK_BUTTONS = listOf(
        SettingsQuickButton.YES,
        SettingsQuickButton.NO,
        SettingsQuickButton.CTRL_C
    )
    val NOTIFICATIONS = NotificationSettings(
        approvalEnabled = true,
        completionEnabled = true,
        errorEnabled = true
    )
}
