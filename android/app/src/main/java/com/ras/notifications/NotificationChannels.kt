package com.ras.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * Notification channel setup for the app.
 *
 * Creates a high-priority notification channel for agent events.
 */
object NotificationChannels {

    const val CHANNEL_ID_AGENT = "agent_notifications"
    const val CHANNEL_NAME_AGENT = "Agent Notifications"
    const val CHANNEL_DESCRIPTION = "Notifications for agent events (approvals, completions, errors)"

    /**
     * Create notification channels.
     *
     * Should be called once at app startup (from Application.onCreate).
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_AGENT,
                CHANNEL_NAME_AGENT,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                enableLights(true)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Check if notifications are enabled for the agent channel.
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(CHANNEL_ID_AGENT)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }

        return true
    }
}
