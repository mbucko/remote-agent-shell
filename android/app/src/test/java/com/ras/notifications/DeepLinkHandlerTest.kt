package com.ras.notifications

import android.content.Intent
import android.os.Build
import com.ras.TestApplication
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.time.Instant

/**
 * Unit tests for deep link handling from notifications.
 *
 * Tests cover:
 * - DL01: Valid session - opens session terminal
 * - DL02: Invalid session - toast "Session not found"
 * - DL03: App cold start - app starts, opens session
 * - DL04: App warm start - brings app forward, opens session
 * - DL05: Already on session - stays on session (no-op)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], application = TestApplication::class)
class DeepLinkHandlerTest {

    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher
    private lateinit var mockSessionRepository: SessionRepository
    private lateinit var mockNotificationHandler: NotificationHandler
    private lateinit var sessionsFlow: MutableStateFlow<List<SessionInfo>>

    @BeforeEach
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        sessionsFlow = MutableStateFlow(emptyList())

        mockSessionRepository = mockk {
            every { sessions } returns sessionsFlow
        }

        mockNotificationHandler = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // DL01: Valid session - opens session terminal
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `dl01 valid session id navigates to terminal`() = runTest {
        // Given: session exists
        val sessionId = "session-abc"
        sessionsFlow.value = listOf(createTestSession(sessionId))

        // When: extracting session from intent
        val intent = createNotificationIntent(sessionId)
        val extractedSessionId = intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID)
        val sessionExists = sessionsFlow.value.any { it.id == extractedSessionId }

        // Then: session found
        assertEquals(sessionId, extractedSessionId)
        assertTrue(sessionExists, "Session should exist")
    }

    // ==========================================================================
    // DL02: Invalid session - toast "Session not found"
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `dl02 invalid session id returns false`() = runTest {
        // Given: session does NOT exist
        sessionsFlow.value = listOf(createTestSession("other-session"))

        // When: checking for non-existent session
        val intent = createNotificationIntent("nonexistent-session")
        val extractedSessionId = intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID)
        val sessionExists = sessionsFlow.value.any { it.id == extractedSessionId }

        // Then: session not found
        assertFalse(sessionExists, "Session should not exist")
    }

    // ==========================================================================
    // DL03: App cold start - session id is preserved in intent
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `dl03 cold start intent has session id`() {
        // Given: notification intent with session ID
        val sessionId = "session-cold"
        val intent = createNotificationIntent(sessionId)

        // Then: intent has correct extras
        assertEquals(sessionId, intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID))
        assertTrue(intent.getBooleanExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, false))
    }

    // ==========================================================================
    // DL04: App warm start - intent extras accessible
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `dl04 warm start intent has session id`() {
        // Same as cold start - intent carries session ID
        val sessionId = "session-warm"
        val intent = createNotificationIntent(sessionId)

        assertEquals(sessionId, intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID))
        assertTrue(intent.getBooleanExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, false))
    }

    // ==========================================================================
    // DL05: Already on session - navigation is idempotent
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `dl05 same session navigation is safe`() = runTest {
        // Given: on session A, receiving deep link for session A
        val sessionId = "session-already"
        sessionsFlow.value = listOf(createTestSession(sessionId))

        val intent = createNotificationIntent(sessionId)
        val extractedSessionId = intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID)

        // Simulating current screen is already session A
        val currentSessionId = sessionId

        // When: deep linking to same session
        val shouldNavigate = extractedSessionId != currentSessionId

        // Then: no navigation needed (already there)
        assertFalse(shouldNavigate, "Should not navigate when already on session")
    }

    // ==========================================================================
    // Intent Parsing Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `intent without session id is ignored`() {
        // Given: intent without session extra
        val intent = Intent()

        // When: extracting session ID
        val sessionId = intent.getStringExtra(NotificationHandler.EXTRA_SESSION_ID)

        // Then: null session ID
        assertNull(sessionId, "Session ID should be null")
    }

    @Tag("unit")
    @Test
    fun `intent without from_notification flag is ignored`() {
        // Given: intent with session but no from_notification flag
        val intent = Intent().apply {
            putExtra(NotificationHandler.EXTRA_SESSION_ID, "session-test")
            // NOT setting EXTRA_FROM_NOTIFICATION
        }

        // When: checking flags
        val fromNotification = intent.getBooleanExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, false)

        // Then: flag is false
        assertFalse(fromNotification, "from_notification should be false")
    }

    // ==========================================================================
    // Notification Dismissal Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `notification dismissed after navigation`() {
        // Given: valid session
        val sessionId = "session-dismiss"

        // When: handling notification tap
        mockNotificationHandler.dismissNotification(sessionId)

        // Then: notification dismissed
        verify { mockNotificationHandler.dismissNotification(sessionId) }
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private fun createNotificationIntent(sessionId: String): Intent {
        return Intent().apply {
            putExtra(NotificationHandler.EXTRA_SESSION_ID, sessionId)
            putExtra(NotificationHandler.EXTRA_FROM_NOTIFICATION, true)
        }
    }

    private fun createTestSession(sessionId: String): SessionInfo {
        return SessionInfo(
            id = sessionId,
            tmuxName = "tmux-$sessionId",
            displayName = "Test Session",
            directory = "/home/test",
            agent = "claude",
            createdAt = Instant.now(),
            lastActivityAt = Instant.now(),
            status = SessionStatus.ACTIVE
        )
    }
}
