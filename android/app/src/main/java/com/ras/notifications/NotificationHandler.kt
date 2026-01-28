package com.ras.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ras.MainActivity
import com.ras.R
import com.ras.proto.NotificationType
import com.ras.proto.TerminalNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles displaying system notifications for agent events.
 *
 * Features:
 * - Shows notifications for approval requests, task completions, and errors
 * - Notification tap navigates to the corresponding session
 * - Supports notification grouping when 5+ notifications are active
 * - Auto-dismisses notification when session is opened
 */
@Singleton
class NotificationHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NotificationHandler"
        private const val GROUP_KEY = "com.ras.AGENT_NOTIFICATIONS"
        private const val SUMMARY_NOTIFICATION_ID = 0
        private const val GROUP_THRESHOLD = 5

        // Intent extras
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }

    // Track active notifications for grouping
    private val activeNotifications = mutableSetOf<Int>()

    /**
     * Show a notification for a terminal event.
     *
     * @param notification The terminal notification from the daemon.
     * @return true if notification was shown, false if suppressed (e.g., permission denied)
     */
    fun showNotification(notification: TerminalNotification): Boolean {
        if (!hasNotificationPermission()) {
            Log.w(TAG, "Notification permission not granted, skipping")
            return false
        }

        val notificationId = notification.sessionId.hashCode()

        val pendingIntent = createPendingIntent(notification.sessionId)

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ID_AGENT)
            .setSmallIcon(getIconForType(notification.type))
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notification.body)
                    .setSummaryText(getTypeLabel(notification.type))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            activeNotifications.add(notificationId)

            // Update group summary if threshold reached
            if (activeNotifications.size >= GROUP_THRESHOLD) {
                showGroupSummary()
            }

            Log.d(TAG, "Notification shown: id=$notificationId, type=${notification.type}")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification: permission denied", e)
            return false
        }
    }

    /**
     * Dismiss notification for a session.
     *
     * Call this when the user opens a session to auto-dismiss its notification.
     *
     * @param sessionId The session ID whose notification should be dismissed.
     */
    fun dismissNotification(sessionId: String) {
        val notificationId = sessionId.hashCode()

        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
            activeNotifications.remove(notificationId)

            // Update or remove group summary
            if (activeNotifications.size < GROUP_THRESHOLD) {
                NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIFICATION_ID)
            } else if (activeNotifications.isNotEmpty()) {
                showGroupSummary()
            }

            Log.d(TAG, "Notification dismissed: sessionId=$sessionId")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to dismiss notification", e)
        }
    }

    /**
     * Dismiss all notifications.
     */
    fun dismissAll() {
        try {
            NotificationManagerCompat.from(context).cancelAll()
            activeNotifications.clear()
            Log.d(TAG, "All notifications dismissed")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to dismiss all notifications", e)
        }
    }

    /**
     * Check if notification permission is granted.
     *
     * On Android 13+ (API 33+), POST_NOTIFICATIONS permission is required.
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-Android 13: notifications enabled by default
            NotificationChannels.areNotificationsEnabled(context)
        }
    }

    private fun createPendingIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_FROM_NOTIFICATION, true)
        }

        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showGroupSummary() {
        val summaryBuilder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ID_AGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Agent Notifications")
            .setContentText("${activeNotifications.size} notifications")
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setSummaryText("${activeNotifications.size} agent events")
            )
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, summaryBuilder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show group summary", e)
        }
    }

    private fun getIconForType(type: NotificationType): Int {
        return when (type) {
            NotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED -> R.drawable.ic_notification_approval
            NotificationType.NOTIFICATION_TYPE_TASK_COMPLETED -> R.drawable.ic_notification_completed
            NotificationType.NOTIFICATION_TYPE_ERROR_DETECTED -> R.drawable.ic_notification_error
            else -> R.drawable.ic_notification
        }
    }

    private fun getTypeLabel(type: NotificationType): String {
        return when (type) {
            NotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED -> "Approval Needed"
            NotificationType.NOTIFICATION_TYPE_TASK_COMPLETED -> "Task Completed"
            NotificationType.NOTIFICATION_TYPE_ERROR_DETECTED -> "Error Detected"
            else -> "Notification"
        }
    }
}
