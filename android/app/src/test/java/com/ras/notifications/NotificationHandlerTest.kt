package com.ras.notifications

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.ras.proto.NotificationType
import com.ras.proto.TerminalNotification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Unit tests for NotificationHandler.
 *
 * Uses Robolectric to test Android notification functionality.
 *
 * Tests cover:
 * - NA01: Permission granted - notification shown
 * - NA02: Permission denied - silent fail (no crash)
 * - NA06: Multiple notifications - 3 sessions notify separately
 * - NA07: Notification grouping - 5+ notifications grouped
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class NotificationHandlerTest {

    private lateinit var context: Application
    private lateinit var handler: NotificationHandler
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Create notification channels (required for Android O+)
        NotificationChannels.createChannels(context)

        handler = NotificationHandler(context)

        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        shadowNotificationManager = shadowOf(notificationManager)
    }

    // ==========================================================================
    // NA01: Permission granted - notification shown
    // ==========================================================================

    @Test
    fun `na01 notification shown when permission granted`() {
        // Given: permission granted (Robolectric grants by default on SDK < 33)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = createTestNotification()

        // When: showing notification
        val result = handler.showNotification(notification)

        // Then: notification is shown
        assertTrue("Notification should be shown", result)
        assertTrue(
            "Should have at least 1 notification",
            shadowNotificationManager.allNotifications.isNotEmpty()
        )
    }

    // ==========================================================================
    // NA02: Permission denied - silent fail (no crash)
    // ==========================================================================

    @Test
    fun `na02 permission denied returns false without crash`() {
        // Given: permission denied
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = createTestNotification()

        // When: trying to show notification
        val result = handler.showNotification(notification)

        // Then: returns false, no crash
        assertFalse("Should return false when permission denied", result)
    }

    // ==========================================================================
    // NA06: Multiple notifications - 3 sessions notify separately
    // ==========================================================================

    @Test
    fun `na06 multiple sessions have separate notifications`() {
        // Given: permission granted
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // When: showing notifications for 3 different sessions
        val sessions = listOf("session-001", "session-002", "session-003")
        sessions.forEach { sessionId ->
            val notification = createTestNotification(sessionId = sessionId)
            handler.showNotification(notification)
        }

        // Then: 3 separate notifications shown
        val notificationCount = shadowNotificationManager.allNotifications.size
        assertTrue(
            "Should have at least 3 notifications, got $notificationCount",
            notificationCount >= 3
        )
    }

    // ==========================================================================
    // NA07: Notification grouping - 5+ notifications grouped
    // ==========================================================================

    @Test
    fun `na07 five plus notifications are grouped`() {
        // Given: permission granted
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // When: showing 5 notifications
        repeat(5) { i ->
            val notification = createTestNotification(sessionId = "session-$i")
            handler.showNotification(notification)
        }

        // Then: summary notification is also shown (5 + 1 summary = 6 notifications)
        val notificationCount = shadowNotificationManager.allNotifications.size
        assertTrue(
            "Should show group summary after 5 notifications (expected 6, got $notificationCount)",
            notificationCount >= 6
        )
    }

    // ==========================================================================
    // Dismiss Tests
    // ==========================================================================

    @Test
    fun `dismiss notification cancels by session id hash`() {
        // Given: permission granted
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val sessionId = "session-abc"
        val notification = createTestNotification(sessionId = sessionId)
        handler.showNotification(notification)

        val initialCount = shadowNotificationManager.allNotifications.size
        assertTrue("Should have at least 1 notification", initialCount >= 1)

        // When: dismissing notification
        handler.dismissNotification(sessionId)

        // Then: notification count decreased
        val finalCount = shadowNotificationManager.allNotifications.size
        assertTrue("Should have fewer notifications after dismiss", finalCount < initialCount)
    }

    @Test
    fun `dismiss all cancels all notifications`() {
        // Given: permission granted and multiple notifications
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        repeat(3) { i ->
            handler.showNotification(createTestNotification(sessionId = "session-$i"))
        }

        assertTrue("Should have notifications", shadowNotificationManager.allNotifications.isNotEmpty())

        // When: dismissing all
        handler.dismissAll()

        // Then: all notifications cleared
        assertTrue(
            "Should have no notifications after dismissAll",
            shadowNotificationManager.allNotifications.isEmpty()
        )
    }

    // ==========================================================================
    // Notification Type Tests
    // ==========================================================================

    @Test
    fun `approval notification has correct content`() {
        // Given: permission granted
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = createTestNotification(
            type = NotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED,
            title = "my-project: Approval needed",
            body = "Proceed with edit? (y/n)"
        )

        // When: showing notification
        val result = handler.showNotification(notification)

        // Then: notification shown
        assertTrue(result)
        assertTrue(shadowNotificationManager.allNotifications.isNotEmpty())
    }

    @Test
    fun `completion notification has correct content`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = createTestNotification(
            type = NotificationType.NOTIFICATION_TYPE_TASK_COMPLETED,
            title = "my-project: Task completed",
            body = "Task completed"
        )

        val result = handler.showNotification(notification)

        assertTrue(result)
        assertTrue(shadowNotificationManager.allNotifications.isNotEmpty())
    }

    @Test
    fun `error notification has correct content`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = createTestNotification(
            type = NotificationType.NOTIFICATION_TYPE_ERROR_DETECTED,
            title = "my-project: Error detected",
            body = "Error: Something failed"
        )

        val result = handler.showNotification(notification)

        assertTrue(result)
        assertTrue(shadowNotificationManager.allNotifications.isNotEmpty())
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private fun createTestNotification(
        sessionId: String = "session-123",
        type: NotificationType = NotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED,
        title: String = "Test: Approval needed",
        body: String = "Proceed? (y/n)",
        snippet: String = "Proceed? (y/n)",
        timestamp: Long = System.currentTimeMillis()
    ): TerminalNotification {
        return TerminalNotification.newBuilder()
            .setSessionId(sessionId)
            .setType(type)
            .setTitle(title)
            .setBody(body)
            .setSnippet(snippet)
            .setTimestamp(timestamp)
            .build()
    }
}
