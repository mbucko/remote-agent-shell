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
import java.util.Collections
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
 *
 * Thread Safety:
 * - This class is thread-safe. Multiple coroutines can call methods concurrently.
 * - The activeNotifications set is synchronized for concurrent access.
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
        private const val MAX_TITLE_LENGTH = 100
        private const val MAX_BODY_LENGTH = 500
        private const val DEFAULT_TITLE = "Agent Notification"
        private const val DEFAULT_BODY = "Tap to view"

        // Intent extras
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }

    // Track active notifications for grouping - thread-safe
    private val activeNotifications: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Show a notification for a terminal event.
     *
     * @param notification The terminal notification from the daemon.
     *        Must have non-null, non-empty sessionId.
     * @return true if notification was shown, false if suppressed (e.g., permission denied,
     *         invalid notification, or error)
     */
    fun showNotification(notification: TerminalNotification): Boolean {
        // Validate notification fields
        if (!validateNotification(notification)) {
            return false
        }

        if (!hasNotificationPermission()) {
            Log.w(TAG, "Notification permission not granted, skipping")
            return false
        }

        // Use positive int to avoid issues with negative hash codes
        val notificationId = getNotificationId(notification.sessionId)

        val pendingIntent = createPendingIntent(notification.sessionId)

        // Sanitize title and body
        val title = sanitizeText(notification.title, MAX_TITLE_LENGTH, DEFAULT_TITLE)
        val body = sanitizeText(notification.body, MAX_BODY_LENGTH, DEFAULT_BODY)

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ID_AGENT)
            .setSmallIcon(getIconForType(notification.type))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body)
                    .setSummaryText(getTypeLabel(notification.type))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())

            // Thread-safe operations on synchronized set
            synchronized(activeNotifications) {
                activeNotifications.add(notificationId)
                if (activeNotifications.size >= GROUP_THRESHOLD) {
                    showGroupSummary()
                }
            }

            Log.d(TAG, "Notification shown: id=$notificationId, type=${notification.type}, " +
                    "sessionId=${notification.sessionId}")
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
     *        Empty or blank session IDs are ignored.
     */
    fun dismissNotification(sessionId: String) {
        if (sessionId.isBlank()) {
            Log.w(TAG, "Ignoring dismissNotification with blank sessionId")
            return
        }

        val notificationId = getNotificationId(sessionId)

        try {
            NotificationManagerCompat.from(context).cancel(notificationId)

            // Thread-safe operations on synchronized set
            synchronized(activeNotifications) {
                activeNotifications.remove(notificationId)

                // Update or remove group summary
                if (activeNotifications.size < GROUP_THRESHOLD) {
                    NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIFICATION_ID)
                } else if (activeNotifications.isNotEmpty()) {
                    showGroupSummary()
                }
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

            synchronized(activeNotifications) {
                activeNotifications.clear()
            }

            Log.d(TAG, "All notifications dismissed")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to dismiss all notifications", e)
        }
    }

    /**
     * Check if notification permission is granted.
     *
     * On Android 13+ (API 33+), POST_NOTIFICATIONS permission is required.
     * On earlier versions, notifications are enabled by default unless
     * the user explicitly disables them in settings.
     *
     * @return true if notifications can be shown, false otherwise
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

    /**
     * Get the count of active notifications.
     * Useful for testing and debugging.
     */
    fun getActiveNotificationCount(): Int {
        synchronized(activeNotifications) {
            return activeNotifications.size
        }
    }

    /**
     * Validate notification fields.
     *
     * @return true if notification is valid, false otherwise
     */
    private fun validateNotification(notification: TerminalNotification): Boolean {
        if (notification.sessionId.isNullOrBlank()) {
            Log.e(TAG, "Rejecting notification with null/blank sessionId")
            return false
        }

        // Log warning for unexpected notification types but still allow
        if (notification.type == NotificationType.NOTIFICATION_TYPE_UNSPECIFIED ||
            notification.type == NotificationType.UNRECOGNIZED) {
            Log.w(TAG, "Notification has unexpected type: ${notification.type}, " +
                    "sessionId=${notification.sessionId}")
        }

        return true
    }

    /**
     * Sanitize text for display in notification.
     * Trims, truncates, and provides default if empty.
     */
    private fun sanitizeText(text: String?, maxLength: Int, default: String): String {
        if (text.isNullOrBlank()) {
            return default
        }
        val trimmed = text.trim()
        return if (trimmed.length > maxLength) {
            trimmed.take(maxLength - 3) + "..."
        } else {
            trimmed
        }
    }

    /**
     * Generate a unique notification ID from session ID.
     * Uses positive int to avoid issues with Android notification IDs.
     */
    private fun getNotificationId(sessionId: String): Int {
        // Ensure positive int by masking with 0x7FFFFFFF
        // Also avoid 0 which is reserved for summary notification
        val hash = sessionId.hashCode() and 0x7FFFFFFF
        return if (hash == 0) 1 else hash
    }

    private fun createPendingIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_FROM_NOTIFICATION, true)
        }

        return PendingIntent.getActivity(
            context,
            getNotificationId(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Create a PendingIntent for the group summary notification.
     * Opens the app to the sessions list.
     */
    private fun createSummaryPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // No session ID - will navigate to sessions list
            putExtra(EXTRA_FROM_NOTIFICATION, true)
        }

        return PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Show the group summary notification.
     * Must be called within synchronized(activeNotifications) block.
     */
    private fun showGroupSummary() {
        val count = activeNotifications.size
        val summaryBuilder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ID_AGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Agent Notifications")
            .setContentText("$count notifications")
            .setContentIntent(createSummaryPendingIntent())
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setSummaryText("$count agent events")
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
            else -> {
                Log.w(TAG, "Unknown notification type: $type, using default icon")
                R.drawable.ic_notification
            }
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
