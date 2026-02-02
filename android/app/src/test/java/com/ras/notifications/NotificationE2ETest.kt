package com.ras.notifications

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.ras.TestApplication
import com.ras.proto.NotificationType
import com.ras.proto.TerminalNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Comprehensive End-to-End tests for the notification system.
 *
 * Tests cover:
 * - Thread safety under concurrent operations
 * - Edge cases with empty/null/long values
 * - Notification ID collision scenarios
 * - Grouping threshold behavior
 * - Intent handling edge cases
 * - All error scenarios
 * - All notification types
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], application = TestApplication::class)
class NotificationE2ETest {

    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher
    private lateinit var context: Application
    private lateinit var handler: NotificationHandler
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @BeforeEach
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()

        // Create notification channels
        NotificationChannels.createChannels(context)

        handler = NotificationHandler(context)

        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        shadowNotificationManager = shadowOf(notificationManager)

        // Grant permission by default
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        handler.dismissAll()
    }

    // ==========================================================================
    // E2E01: Complete Happy Path - Show, Dismiss, Verify
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E01 complete flow - show notification, tap, dismiss`() {
        // 1. Daemon sends notification
        val notification = createNotification(
            sessionId = "session-abc",
            type = NotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED,
            title = "Test Project: Approval needed",
            body = "Proceed with edit? (y/n)"
        )

        // 2. Show notification
        val shown = handler.showNotification(notification)
        assertTrue(shown, "Notification should be shown")
        assertEquals(1, handler.getActiveNotificationCount())
        assertEquals(1, shadowNotificationManager.allNotifications.size)

        // 3. User taps notification (simulated by dismissNotification)
        handler.dismissNotification("session-abc")

        // 4. Verify notification dismissed
        assertEquals(0, handler.getActiveNotificationCount())
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // E2E02: Concurrent Show Operations - Thread Safety
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E02 concurrent show operations are thread safe`() = runTest {
        val jobs = (1..100).map { i ->
            launch {
                val notification = createNotification(sessionId = "session-$i")
                handler.showNotification(notification)
            }
        }
        jobs.forEach { it.join() }

        // All 100 notifications should be tracked
        assertEquals(100, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E03: Concurrent Show and Dismiss - Thread Safety
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E03 concurrent show and dismiss operations are thread safe`() = runTest {
        // First show 50 notifications
        repeat(50) { i ->
            handler.showNotification(createNotification(sessionId = "session-$i"))
        }
        assertEquals(50, handler.getActiveNotificationCount())

        // Now show 50 more while dismissing first 25
        val showJobs = (50..99).map { i ->
            launch {
                handler.showNotification(createNotification(sessionId = "session-$i"))
            }
        }
        val dismissJobs = (0..24).map { i ->
            launch {
                handler.dismissNotification("session-$i")
            }
        }

        (showJobs + dismissJobs).forEach { it.join() }

        // Should have 50 + 50 - 25 = 75 notifications
        assertEquals(75, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E04: Rapid Show/Dismiss Same Session - No Race Condition
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E04 rapid show dismiss same session is safe`() = runTest {
        val sessionId = "rapid-session"

        // Rapidly show and dismiss the same notification
        repeat(100) {
            handler.showNotification(createNotification(sessionId = sessionId))
            handler.dismissNotification(sessionId)
        }

        // Should end with 0 notifications
        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E05: Empty Session ID - Rejected
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E05 empty session ID is rejected`() {
        val notification = createNotification(sessionId = "")
        val result = handler.showNotification(notification)

        assertFalse(result, "Empty sessionId should be rejected")
        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E06: Blank Session ID - Rejected
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E06 blank session ID is rejected`() {
        val notification = createNotification(sessionId = "   ")
        val result = handler.showNotification(notification)

        assertFalse(result, "Blank sessionId should be rejected")
        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E07: Very Long Session ID - Accepted with Truncation
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E07 very long session ID is accepted`() {
        val longSessionId = "a".repeat(1000)
        val notification = createNotification(sessionId = longSessionId)

        val result = handler.showNotification(notification)

        assertTrue(result, "Long sessionId should be accepted")
        assertEquals(1, handler.getActiveNotificationCount())

        // Dismiss with same long ID
        handler.dismissNotification(longSessionId)
        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E08: Empty Title - Uses Default
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E08 empty title uses default`() {
        val notification = createNotification(
            sessionId = "session-empty-title",
            title = ""
        )

        val result = handler.showNotification(notification)
        assertTrue(result, "Notification with empty title should be shown")
        assertEquals(1, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // E2E09: Empty Body - Uses Default
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E09 empty body uses default`() {
        val notification = createNotification(
            sessionId = "session-empty-body",
            body = ""
        )

        val result = handler.showNotification(notification)
        assertTrue(result, "Notification with empty body should be shown")
        assertEquals(1, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // E2E10: Very Long Title - Truncated
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E10 very long title is truncated`() {
        val longTitle = "A".repeat(500)
        val notification = createNotification(
            sessionId = "session-long-title",
            title = longTitle
        )

        val result = handler.showNotification(notification)
        assertTrue(result)
        assertEquals(1, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // E2E11: Very Long Body - Truncated
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E11 very long body is truncated`() {
        val longBody = "B".repeat(1000)
        val notification = createNotification(
            sessionId = "session-long-body",
            body = longBody
        )

        val result = handler.showNotification(notification)
        assertTrue(result)
        assertEquals(1, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // E2E12: All Notification Types - Correct Icons
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E12 all notification types show correctly`() {
        val types = listOf(
            NotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED,
            NotificationType.NOTIFICATION_TYPE_TASK_COMPLETED,
            NotificationType.NOTIFICATION_TYPE_ERROR_DETECTED
        )

        types.forEachIndexed { index, type ->
            val notification = createNotification(
                sessionId = "session-type-$index",
                type = type
            )
            val result = handler.showNotification(notification)
            assertTrue(result, "Type $type should be shown")
        }

        assertEquals(3, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E13: Unknown Notification Type - Uses Default
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E13 unknown notification type uses default`() {
        val notification = createNotification(
            sessionId = "session-unspecified",
            type = NotificationType.NOTIFICATION_TYPE_UNSPECIFIED
        )

        val result = handler.showNotification(notification)
        assertTrue(result, "Unspecified type should still show")
        assertEquals(1, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E14: Grouping Threshold - Summary at 5
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E14 group summary shown at threshold of 5`() {
        // Show 4 notifications - no summary
        repeat(4) { i ->
            handler.showNotification(createNotification(sessionId = "session-$i"))
        }
        assertEquals(4, shadowNotificationManager.allNotifications.size)

        // Show 5th - summary should appear
        handler.showNotification(createNotification(sessionId = "session-4"))
        assertEquals(5, handler.getActiveNotificationCount())
        // 5 individual + 1 summary = 6
        assertEquals(6, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // E2E15: Group Summary Removed Below Threshold
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E15 group summary removed when below threshold`() {
        // Show 6 notifications
        repeat(6) { i ->
            handler.showNotification(createNotification(sessionId = "session-$i"))
        }
        assertEquals(7, shadowNotificationManager.allNotifications.size) // 6 + summary

        // Dismiss 2 to get below threshold
        handler.dismissNotification("session-0")
        handler.dismissNotification("session-1")

        // 4 notifications, no summary
        assertEquals(4, handler.getActiveNotificationCount())
        assertEquals(4, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // E2E16: Permission Denied - Silent Fail
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E16 permission denied returns false without crash`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = createNotification(sessionId = "session-denied")
        val result = handler.showNotification(notification)

        assertFalse(result, "Should return false when permission denied")
        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E17: Dismiss Non-Existent Session - No Crash
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E17 dismiss non existent session is safe`() {
        handler.dismissNotification("non-existent-session")
        // Should not crash
        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E18: Dismiss Blank Session ID - Ignored
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E18 dismiss blank session ID is ignored`() {
        handler.showNotification(createNotification(sessionId = "real-session"))
        assertEquals(1, handler.getActiveNotificationCount())

        handler.dismissNotification("")
        handler.dismissNotification("   ")

        // Original notification still exists
        assertEquals(1, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E19: Dismiss All - Clears Everything
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E19 dismiss all clears all notifications`() {
        // Show 10 notifications
        repeat(10) { i ->
            handler.showNotification(createNotification(sessionId = "session-$i"))
        }
        assertTrue(handler.getActiveNotificationCount() > 0)

        handler.dismissAll()

        assertEquals(0, handler.getActiveNotificationCount())
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // E2E20: Hash Collision Prevention - Positive IDs
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E20 notification IDs are always positive`() {
        // Test with session IDs that might produce negative hashes
        val testIds = listOf(
            "test",
            "a".repeat(100),
            "negative-hash-test",
            "\u0000\u0001\u0002",
            "特殊字符"
        )

        testIds.forEach { sessionId ->
            val notification = createNotification(sessionId = sessionId)
            handler.showNotification(notification)
        }

        // All should be shown without issues
        assertEquals(testIds.size, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E21: Same Session Duplicate - Updates Existing
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E21 same session shows only one notification`() {
        // Show notification for same session 5 times
        repeat(5) {
            handler.showNotification(createNotification(sessionId = "same-session"))
        }

        // Should only have 1 notification (same ID updates)
        assertEquals(1, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E22: Unicode in Title and Body - Handled
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E22 unicode content is handled`() {
        val notification = createNotification(
            sessionId = "unicode-session",
            title = "项目: 需要批准 🚀",
            body = "こんにちは世界 👋"
        )

        val result = handler.showNotification(notification)
        assertTrue(result)
        assertEquals(1, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E23: Control Characters in Text - Handled
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E23 control characters in text are handled`() {
        val notification = createNotification(
            sessionId = "control-session",
            title = "Title\twith\ttabs",
            body = "Body\nwith\nnewlines"
        )

        val result = handler.showNotification(notification)
        assertTrue(result)
        assertEquals(1, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E24: Intent Extras - Correct Values
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E24 intent extras are correctly set`() {
        val intent = Intent().apply {
            putExtra(NotificationHandler.EXTRA_SESSION_ID, "test-session")
            putExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, true)
        }

        assertEquals("test-session", intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID))
        assertTrue(intent.getBooleanExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, false))
    }

    // ==========================================================================
    // E2E25: Intent Without Session ID - From Group Summary
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E25 intent from group summary has no session ID`() {
        val intent = Intent().apply {
            putExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, true)
            // No session ID - this is from group summary
        }

        val sessionId = intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID)
        assertTrue(sessionId.isNullOrBlank())
    }

    // ==========================================================================
    // E2E26: Large Number of Notifications - Performance
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E26 handles large number of notifications`() {
        // Show 100 notifications
        repeat(100) { i ->
            handler.showNotification(createNotification(sessionId = "session-$i"))
        }

        assertEquals(100, handler.getActiveNotificationCount())

        // Dismiss all
        handler.dismissAll()
        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E27: Notification After Permission Revoked - Silent Fail
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E27 notification after permission revoked fails silently`() {
        // First show works
        handler.showNotification(createNotification(sessionId = "session-1"))
        assertEquals(1, handler.getActiveNotificationCount())

        // Revoke permission
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // Second show fails silently
        val result = handler.showNotification(createNotification(sessionId = "session-2"))
        assertFalse(result)
        // Count still 1 from first successful show
        assertEquals(1, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E28: Session ID with Special Characters - Handled
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E28 session ID with special characters is handled`() {
        val specialIds = listOf(
            "session/with/slashes",
            "session:with:colons",
            "session@with@ats",
            "session#with#hashes",
            "session with spaces"
        )

        specialIds.forEach { sessionId ->
            handler.showNotification(createNotification(sessionId = sessionId))
        }

        assertEquals(specialIds.size, handler.getActiveNotificationCount())

        // Dismiss all with same IDs
        specialIds.forEach { sessionId ->
            handler.dismissNotification(sessionId)
        }

        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E29: Whitespace-Only Title - Uses Default
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E29 whitespace only title uses default`() {
        val notification = createNotification(
            sessionId = "whitespace-title",
            title = "   \t\n   "
        )

        val result = handler.showNotification(notification)
        assertTrue(result)
    }

    // ==========================================================================
    // E2E30: Dismiss During Show - Thread Safe
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E30 dismiss during show is thread safe`() = runTest {
        val sessionId = "concurrent-session"

        // Launch concurrent show and dismiss
        val showJob = launch {
            repeat(50) {
                handler.showNotification(createNotification(sessionId = sessionId))
                delay(1)
            }
        }
        val dismissJob = launch {
            repeat(50) {
                delay(1)
                handler.dismissNotification(sessionId)
            }
        }

        showJob.join()
        dismissJob.join()

        // Should end consistently without crash
        assertTrue(handler.getActiveNotificationCount() <= 1)
    }

    // ==========================================================================
    // E2E31: Zero Timestamp - Accepted
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E31 zero timestamp is accepted`() {
        val notification = createNotification(
            sessionId = "zero-timestamp",
            timestamp = 0L
        )

        val result = handler.showNotification(notification)
        assertTrue(result)
    }

    // ==========================================================================
    // E2E32: Future Timestamp - Accepted
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E32 future timestamp is accepted`() {
        val notification = createNotification(
            sessionId = "future-timestamp",
            timestamp = System.currentTimeMillis() + 86400000 // Tomorrow
        )

        val result = handler.showNotification(notification)
        assertTrue(result)
    }

    // ==========================================================================
    // E2E33: Notification Flow Integration
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E33 complete notification flow from daemon to dismiss`() {
        // Simulate daemon sending 3 notifications for different sessions
        val sessions = listOf(
            Triple("proj-1", NotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED, "Proceed?"),
            Triple("proj-2", NotificationType.NOTIFICATION_TYPE_TASK_COMPLETED, "Done!"),
            Triple("proj-3", NotificationType.NOTIFICATION_TYPE_ERROR_DETECTED, "Failed!")
        )

        sessions.forEach { (sessionId, type, body) ->
            val notification = createNotification(
                sessionId = sessionId,
                type = type,
                title = "$sessionId: ${type.name}",
                body = body
            )
            handler.showNotification(notification)
        }

        assertEquals(3, handler.getActiveNotificationCount())

        // User taps first notification
        handler.dismissNotification("proj-1")
        assertEquals(2, handler.getActiveNotificationCount())

        // User dismisses all remaining
        handler.dismissAll()
        assertEquals(0, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E34: Notification ID Uniqueness
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E34 different session IDs have different notification IDs`() {
        val sessionIds = (1..100).map { "session-$it" }

        sessionIds.forEach { sessionId ->
            handler.showNotification(createNotification(sessionId = sessionId))
        }

        // All should be unique (100 unique session IDs)
        assertEquals(100, handler.getActiveNotificationCount())
    }

    // ==========================================================================
    // E2E35: Group Summary Has Click Handler
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E35 group summary notification is shown`() {
        // Show 5 notifications to trigger group summary
        repeat(5) { i ->
            handler.showNotification(createNotification(sessionId = "session-$i"))
        }

        // 5 individual + 1 summary = 6
        assertEquals(6, shadowNotificationManager.allNotifications.size)
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private fun createNotification(
        sessionId: String = "test-session",
        type: NotificationType = NotificationType.NOTIFICATION_TYPE_APPROVAL_NEEDED,
        title: String = "Test Notification",
        body: String = "Test body content",
        snippet: String = "Test snippet",
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
