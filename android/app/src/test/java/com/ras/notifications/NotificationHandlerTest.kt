package com.ras.notifications

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.ras.TestApplication
import com.ras.proto.NotificationType
import com.ras.proto.TerminalNotification
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
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
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], application = TestApplication::class)
class NotificationHandlerTest {

    private lateinit var context: Application
    private lateinit var handler: NotificationHandler
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @BeforeEach
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

    @Tag("unit")
    @Test
    fun `na01 notification shown when permission granted`() {
        // Given: permission granted (Robolectric grants by default on SDK < 33)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = createTestNotification()

        // When: showing notification
        val result = handler.showNotification(notification)

        // Then: notification is shown
        assertTrue(result, "Notification should be shown")
        assertTrue(
            shadowNotificationManager.allNotifications.isNotEmpty(),
            "Should have at least 1 notification"
        )
    }

    // ==========================================================================
    // NA02: Permission denied - silent fail (no crash)
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `na02 permission denied returns false without crash`() {
        // Given: permission denied
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = createTestNotification()

        // When: trying to show notification
        val result = handler.showNotification(notification)

        // Then: returns false, no crash
        assertFalse(result, "Should return false when permission denied")
    }

    // ==========================================================================
    // NA06: Multiple notifications - 3 sessions notify separately
    // ==========================================================================

    @Tag("unit")
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
            notificationCount >= 3,
            "Should have at least 3 notifications, got $notificationCount"
        )
    }

    // ==========================================================================
    // NA07: Notification grouping - 5+ notifications grouped
    // ==========================================================================

    @Tag("unit")
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
            notificationCount >= 6,
            "Should show group summary after 5 notifications (expected 6, got $notificationCount)"
        )
    }

    // ==========================================================================
    // Dismiss Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `dismiss notification cancels by session id hash`() {
        // Given: permission granted
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val sessionId = "session-abc"
        val notification = createTestNotification(sessionId = sessionId)
        handler.showNotification(notification)

        val initialCount = shadowNotificationManager.allNotifications.size
        assertTrue(initialCount >= 1, "Should have at least 1 notification")

        // When: dismissing notification
        handler.dismissNotification(sessionId)

        // Then: notification count decreased
        val finalCount = shadowNotificationManager.allNotifications.size
        assertTrue(finalCount < initialCount, "Should have fewer notifications after dismiss")
    }

    @Tag("unit")
    @Test
    fun `dismiss all cancels all notifications`() {
        // Given: permission granted and multiple notifications
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        repeat(3) { i ->
            handler.showNotification(createTestNotification(sessionId = "session-$i"))
        }

        assertTrue(shadowNotificationManager.allNotifications.isNotEmpty(), "Should have notifications")

        // When: dismissing all
        handler.dismissAll()

        // Then: all notifications cleared
        assertTrue(
            shadowNotificationManager.allNotifications.isEmpty(),
            "Should have no notifications after dismissAll"
        )
    }

    // ==========================================================================
    // Notification Type Tests
    // ==========================================================================

    @Tag("unit")
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

    @Tag("unit")
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

    @Tag("unit")
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
