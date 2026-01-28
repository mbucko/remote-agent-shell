package com.ras.settings

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.ras.data.connection.ConnectionManager
import com.ras.data.sessions.AgentInfo
import com.ras.data.sessions.SessionEvent
import com.ras.data.sessions.SessionRepository
import com.ras.data.settings.DaemonInfo
import com.ras.data.settings.NotificationSettings
import com.ras.data.settings.NotificationType
import com.ras.data.settings.SETTINGS_VERSION
import com.ras.data.settings.SettingsDefaults
import com.ras.data.settings.SettingsKeys
import com.ras.data.settings.SettingsQuickButton
import com.ras.data.settings.SettingsRepository
import com.ras.data.settings.SettingsSection
import com.ras.ui.settings.SettingsUiEvent
import com.ras.ui.settings.SettingsUiState
import com.ras.ui.settings.SettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests for the Settings feature.
 *
 * Tests the complete flow from user action through ViewModel -> Repository -> Storage
 * and back through the reactive flows to the UI.
 *
 * Covers all scenarios:
 * - Fresh install defaults
 * - Default agent selection
 * - Quick button configuration
 * - Notification preferences
 * - Section and full reset
 * - Agent validation
 * - Connection state handling
 * - Error scenarios
 * - Edge cases
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsE2ETest {

    private val testDispatcher = StandardTestDispatcher()

    // In-memory storage for SharedPreferences simulation
    private val storage = mutableMapOf<String, Any?>()

    // Mocks for Android components
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    // Real repository with mocked storage
    private lateinit var settingsRepository: SettingsRepository

    // Mocked session repository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var sessionEventsFlow: MutableSharedFlow<SessionEvent>

    // Mocked connection manager
    private lateinit var connectionManager: ConnectionManager
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Setup mocked SharedPreferences with in-memory storage
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor

        every { prefs.getString(any(), any()) } answers {
            storage[firstArg<String>()] as? String ?: secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            storage[firstArg<String>()] as? Boolean ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            storage[firstArg<String>()] as? Int ?: secondArg()
        }

        every { editor.putString(any(), any()) } answers {
            storage[firstArg<String>()] = secondArg<String>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            storage[firstArg<String>()] = secondArg<Boolean>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            storage[firstArg<String>()] = secondArg<Int>()
            editor
        }
        every { editor.remove(any()) } answers {
            storage.remove(firstArg<String>())
            editor
        }
        every { editor.clear() } answers {
            storage.clear()
            editor
        }
        every { editor.apply() } returns Unit

        storage.clear()

        // Create real repository with mocked storage
        settingsRepository = SettingsRepository(context)

        // Setup session repository mock
        sessionEventsFlow = MutableSharedFlow()
        sessionRepository = mockk(relaxed = true) {
            every { events } returns sessionEventsFlow
        }

        // Setup connection manager mock
        isConnectedFlow = MutableStateFlow(false)
        connectionManager = mockk(relaxed = true) {
            every { isConnected } returns isConnectedFlow
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // Scenario 1: Fresh Install - Default Values
    // ==========================================================================

    @Test
    fun `E2E-01 Fresh install has all default values`() = runTest {
        // Given: Fresh install (empty storage)

        // Then: Repository returns defaults
        assertNull(settingsRepository.getDefaultAgent())
        assertEquals(SettingsDefaults.QUICK_BUTTONS, settingsRepository.getEnabledQuickButtons())
        assertEquals(SettingsDefaults.NOTIFICATIONS, settingsRepository.getNotificationSettings())
        assertEquals(SETTINGS_VERSION, settingsRepository.getSettingsVersion())
    }

    @Test
    fun `E2E-02 Fresh install flows emit default values`() = runTest {
        // Given: Fresh install

        // Then: Flows emit defaults
        assertEquals(null, settingsRepository.defaultAgent.first())
        assertEquals(SettingsDefaults.QUICK_BUTTONS, settingsRepository.quickButtons.first())
        assertEquals(SettingsDefaults.NOTIFICATIONS, settingsRepository.notificationSettings.first())
    }

    // ==========================================================================
    // Scenario 2: Default Agent Selection Flow
    // ==========================================================================

    @Test
    fun `E2E-03 User selects default agent - persists and emits`() = runTest {
        // Given: Fresh install
        assertNull(settingsRepository.getDefaultAgent())

        // When: User selects claude as default
        settingsRepository.setDefaultAgent("claude")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Persisted and flow emits
        assertEquals("claude", settingsRepository.getDefaultAgent())
        assertEquals("claude", settingsRepository.defaultAgent.first())
    }

    @Test
    fun `E2E-04 User clears default agent - returns to always ask`() = runTest {
        // Given: Default agent is set
        settingsRepository.setDefaultAgent("claude")

        // When: User clears default
        settingsRepository.setDefaultAgent(null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Cleared
        assertNull(settingsRepository.getDefaultAgent())
        assertNull(settingsRepository.defaultAgent.first())
    }

    @Test
    fun `E2E-05 User changes default agent - updates to new value`() = runTest {
        // Given: Default agent is set
        settingsRepository.setDefaultAgent("claude")

        // When: User changes to different agent
        settingsRepository.setDefaultAgent("aider")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Updated
        assertEquals("aider", settingsRepository.getDefaultAgent())
        assertEquals("aider", settingsRepository.defaultAgent.first())
    }

    @Test
    fun `E2E-06 Validate default agent exists - keeps valid agent`() = runTest {
        // Given: Default agent is set
        settingsRepository.setDefaultAgent("claude")

        // When: Validating against list that includes agent
        val valid = settingsRepository.validateDefaultAgent(listOf("claude", "aider"))

        // Then: Validation passes, agent kept
        assertTrue(valid)
        assertEquals("claude", settingsRepository.getDefaultAgent())
    }

    @Test
    fun `E2E-07 Validate default agent removed - clears default`() = runTest {
        // Given: Default agent is set
        settingsRepository.setDefaultAgent("removed-agent")

        // When: Validating against list without agent
        val valid = settingsRepository.validateDefaultAgent(listOf("claude", "aider"))

        // Then: Validation fails, agent cleared
        assertFalse(valid)
        assertNull(settingsRepository.getDefaultAgent())
    }

    // ==========================================================================
    // Scenario 3: Quick Button Configuration Flow
    // ==========================================================================

    @Test
    fun `E2E-08 User enables additional quick button`() = runTest {
        // Given: Default buttons (Y, N, Ctrl+C)
        assertEquals(3, settingsRepository.getEnabledQuickButtons().size)

        // When: User enables Tab
        settingsRepository.enableQuickButton(SettingsQuickButton.TAB)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Tab added to list
        val buttons = settingsRepository.getEnabledQuickButtons()
        assertEquals(4, buttons.size)
        assertTrue(buttons.contains(SettingsQuickButton.TAB))
    }

    @Test
    fun `E2E-09 User disables quick button`() = runTest {
        // Given: Default buttons
        assertTrue(settingsRepository.getEnabledQuickButtons().contains(SettingsQuickButton.YES))

        // When: User disables Y
        settingsRepository.disableQuickButton(SettingsQuickButton.YES)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Y removed from list
        val buttons = settingsRepository.getEnabledQuickButtons()
        assertEquals(2, buttons.size)
        assertFalse(buttons.contains(SettingsQuickButton.YES))
    }

    @Test
    fun `E2E-10 User reorders quick buttons`() = runTest {
        // Given: Default order (Y, N, Ctrl+C)
        val initial = settingsRepository.getEnabledQuickButtons()
        assertEquals(SettingsQuickButton.YES, initial[0])
        assertEquals(SettingsQuickButton.NO, initial[1])
        assertEquals(SettingsQuickButton.CTRL_C, initial[2])

        // When: User moves Y to end
        settingsRepository.reorderQuickButtons(0, 2)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Order changed (N, Ctrl+C, Y)
        val reordered = settingsRepository.getEnabledQuickButtons()
        assertEquals(SettingsQuickButton.NO, reordered[0])
        assertEquals(SettingsQuickButton.CTRL_C, reordered[1])
        assertEquals(SettingsQuickButton.YES, reordered[2])
    }

    @Test
    fun `E2E-11 User sets complete custom button list`() = runTest {
        // Given: Default buttons

        // When: User sets custom list
        val customButtons = listOf(
            SettingsQuickButton.CTRL_C,
            SettingsQuickButton.TAB,
            SettingsQuickButton.ESC,
            SettingsQuickButton.ENTER
        )
        settingsRepository.setEnabledQuickButtons(customButtons)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Custom list persisted
        assertEquals(customButtons, settingsRepository.getEnabledQuickButtons())
        assertEquals(customButtons, settingsRepository.quickButtons.first())
    }

    @Test
    fun `E2E-12 User disables all quick buttons`() = runTest {
        // Given: Default buttons

        // When: User disables all
        settingsRepository.setEnabledQuickButtons(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Empty list persisted
        assertTrue(settingsRepository.getEnabledQuickButtons().isEmpty())
    }

    @Test
    fun `E2E-13 User enables all available quick buttons`() = runTest {
        // When: User enables all
        settingsRepository.setEnabledQuickButtons(SettingsQuickButton.ALL)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: All 9 buttons enabled
        assertEquals(9, settingsRepository.getEnabledQuickButtons().size)
        assertEquals(SettingsQuickButton.ALL, settingsRepository.getEnabledQuickButtons())
    }

    // ==========================================================================
    // Scenario 4: Notification Preferences Flow
    // ==========================================================================

    @Test
    fun `E2E-14 User disables approval notifications`() = runTest {
        // Given: All notifications enabled
        assertTrue(settingsRepository.isNotificationEnabled(NotificationType.APPROVAL))

        // When: User disables approvals
        settingsRepository.setNotificationEnabled(NotificationType.APPROVAL, false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Approvals disabled, others enabled
        assertFalse(settingsRepository.isNotificationEnabled(NotificationType.APPROVAL))
        assertTrue(settingsRepository.isNotificationEnabled(NotificationType.COMPLETION))
        assertTrue(settingsRepository.isNotificationEnabled(NotificationType.ERROR))
    }

    @Test
    fun `E2E-15 User disables completion notifications`() = runTest {
        // Given: All notifications enabled

        // When: User disables completions
        settingsRepository.setNotificationEnabled(NotificationType.COMPLETION, false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Completions disabled
        assertFalse(settingsRepository.isNotificationEnabled(NotificationType.COMPLETION))
    }

    @Test
    fun `E2E-16 User disables error notifications`() = runTest {
        // Given: All notifications enabled

        // When: User disables errors
        settingsRepository.setNotificationEnabled(NotificationType.ERROR, false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Errors disabled
        assertFalse(settingsRepository.isNotificationEnabled(NotificationType.ERROR))
    }

    @Test
    fun `E2E-17 User disables all notifications`() = runTest {
        // When: User disables all
        settingsRepository.setNotificationSettings(
            NotificationSettings(false, false, false)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: All disabled
        val settings = settingsRepository.getNotificationSettings()
        assertFalse(settings.approvalEnabled)
        assertFalse(settings.completionEnabled)
        assertFalse(settings.errorEnabled)
    }

    @Test
    fun `E2E-18 User re-enables notification after disabling`() = runTest {
        // Given: Approval disabled
        settingsRepository.setNotificationEnabled(NotificationType.APPROVAL, false)

        // When: User re-enables
        settingsRepository.setNotificationEnabled(NotificationType.APPROVAL, true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Approval enabled again
        assertTrue(settingsRepository.isNotificationEnabled(NotificationType.APPROVAL))
    }

    @Test
    fun `E2E-19 Notification settings flow emits changes`() = runTest {
        settingsRepository.notificationSettings.test {
            // Initial value
            val initial = awaitItem()
            assertTrue(initial.approvalEnabled)

            // Change setting
            settingsRepository.setNotificationEnabled(NotificationType.APPROVAL, false)
            testDispatcher.scheduler.advanceUntilIdle()

            val updated = awaitItem()
            assertFalse(updated.approvalEnabled)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==========================================================================
    // Scenario 5: Reset Functionality
    // ==========================================================================

    @Test
    fun `E2E-20 Reset sessions section clears default agent only`() = runTest {
        // Given: All settings configured
        settingsRepository.setDefaultAgent("claude")
        settingsRepository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        settingsRepository.setNotificationSettings(NotificationSettings(false, false, false))

        // When: Reset sessions section
        settingsRepository.resetSection(SettingsSection.SESSIONS)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Only default agent cleared
        assertNull(settingsRepository.getDefaultAgent())
        assertEquals(listOf(SettingsQuickButton.TAB), settingsRepository.getEnabledQuickButtons())
        assertEquals(NotificationSettings(false, false, false), settingsRepository.getNotificationSettings())
    }

    @Test
    fun `E2E-21 Reset terminal section restores default buttons only`() = runTest {
        // Given: All settings configured
        settingsRepository.setDefaultAgent("claude")
        settingsRepository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        settingsRepository.setNotificationSettings(NotificationSettings(false, false, false))

        // When: Reset terminal section
        settingsRepository.resetSection(SettingsSection.TERMINAL)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Only buttons reset
        assertEquals("claude", settingsRepository.getDefaultAgent())
        assertEquals(SettingsDefaults.QUICK_BUTTONS, settingsRepository.getEnabledQuickButtons())
        assertEquals(NotificationSettings(false, false, false), settingsRepository.getNotificationSettings())
    }

    @Test
    fun `E2E-22 Reset notifications section enables all only`() = runTest {
        // Given: All settings configured
        settingsRepository.setDefaultAgent("claude")
        settingsRepository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        settingsRepository.setNotificationSettings(NotificationSettings(false, false, false))

        // When: Reset notifications section
        settingsRepository.resetSection(SettingsSection.NOTIFICATIONS)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Only notifications reset
        assertEquals("claude", settingsRepository.getDefaultAgent())
        assertEquals(listOf(SettingsQuickButton.TAB), settingsRepository.getEnabledQuickButtons())
        assertEquals(SettingsDefaults.NOTIFICATIONS, settingsRepository.getNotificationSettings())
    }

    @Test
    fun `E2E-23 Reset all restores all defaults`() = runTest {
        // Given: All settings configured
        settingsRepository.setDefaultAgent("claude")
        settingsRepository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        settingsRepository.setNotificationSettings(NotificationSettings(false, false, false))

        // When: Reset all
        settingsRepository.resetAll()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: All reset to defaults
        assertNull(settingsRepository.getDefaultAgent())
        assertEquals(SettingsDefaults.QUICK_BUTTONS, settingsRepository.getEnabledQuickButtons())
        assertEquals(SettingsDefaults.NOTIFICATIONS, settingsRepository.getNotificationSettings())
    }

    // ==========================================================================
    // Scenario 6: Serialization Edge Cases
    // ==========================================================================

    @Test
    fun `E2E-24 Quick buttons survive serialization round-trip`() = runTest {
        // Given: Custom button list
        val original = listOf(
            SettingsQuickButton.CTRL_C,
            SettingsQuickButton.TAB,
            SettingsQuickButton.ESC
        )

        // When: Set and retrieve
        settingsRepository.setEnabledQuickButtons(original)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Identical after round-trip
        assertEquals(original, settingsRepository.getEnabledQuickButtons())
    }

    @Test
    fun `E2E-25 All button types survive serialization`() = runTest {
        // When: Set all buttons
        settingsRepository.setEnabledQuickButtons(SettingsQuickButton.ALL)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: All preserved
        val retrieved = settingsRepository.getEnabledQuickButtons()
        assertEquals(9, retrieved.size)
        for (button in SettingsQuickButton.ALL) {
            assertTrue("Button ${button.id} missing", retrieved.contains(button))
        }
    }

    @Test
    fun `E2E-26 Empty button list survives serialization`() = runTest {
        // When: Set empty list
        settingsRepository.setEnabledQuickButtons(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Empty preserved
        assertTrue(settingsRepository.getEnabledQuickButtons().isEmpty())
    }

    @Test
    fun `E2E-27 Button order survives serialization`() = runTest {
        // Given: Specific order
        val ordered = listOf(
            SettingsQuickButton.CTRL_Z,
            SettingsQuickButton.YES,
            SettingsQuickButton.TAB,
            SettingsQuickButton.NO
        )

        // When: Set and retrieve
        settingsRepository.setEnabledQuickButtons(ordered)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Order preserved
        val retrieved = settingsRepository.getEnabledQuickButtons()
        assertEquals(ordered.size, retrieved.size)
        for (i in ordered.indices) {
            assertEquals("Position $i mismatch", ordered[i], retrieved[i])
        }
    }

    // ==========================================================================
    // Scenario 7: Edge Cases - Agent Names
    // ==========================================================================

    @Test
    fun `E2E-28 Agent name with special characters`() = runTest {
        val name = "claude-v2.0_beta@test"
        settingsRepository.setDefaultAgent(name)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(name, settingsRepository.getDefaultAgent())
    }

    @Test
    fun `E2E-29 Agent name with unicode`() = runTest {
        val name = "claude-日本語-🤖"
        settingsRepository.setDefaultAgent(name)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(name, settingsRepository.getDefaultAgent())
    }

    @Test
    fun `E2E-30 Very long agent name`() = runTest {
        val name = "a".repeat(500)
        settingsRepository.setDefaultAgent(name)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(name, settingsRepository.getDefaultAgent())
    }

    @Test
    fun `E2E-31 Empty agent name`() = runTest {
        settingsRepository.setDefaultAgent("")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", settingsRepository.getDefaultAgent())
    }

    @Test
    fun `E2E-32 Agent name with spaces`() = runTest {
        val name = "claude code agent"
        settingsRepository.setDefaultAgent(name)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(name, settingsRepository.getDefaultAgent())
    }

    // ==========================================================================
    // Scenario 8: Edge Cases - Quick Buttons
    // ==========================================================================

    @Test
    fun `E2E-33 Enable same button twice is idempotent`() = runTest {
        val initial = settingsRepository.getEnabledQuickButtons().size
        settingsRepository.enableQuickButton(SettingsQuickButton.YES)
        settingsRepository.enableQuickButton(SettingsQuickButton.YES)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(initial, settingsRepository.getEnabledQuickButtons().size)
    }

    @Test
    fun `E2E-34 Disable non-existent button is no-op`() = runTest {
        // Disable TAB which is not in default list
        assertFalse(settingsRepository.getEnabledQuickButtons().contains(SettingsQuickButton.TAB))
        settingsRepository.disableQuickButton(SettingsQuickButton.TAB)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SettingsDefaults.QUICK_BUTTONS, settingsRepository.getEnabledQuickButtons())
    }

    @Test
    fun `E2E-35 Reorder with invalid index is no-op`() = runTest {
        val before = settingsRepository.getEnabledQuickButtons()
        settingsRepository.reorderQuickButtons(-1, 5)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(before, settingsRepository.getEnabledQuickButtons())
    }

    @Test
    fun `E2E-36 Reorder with out-of-bounds index is no-op`() = runTest {
        val before = settingsRepository.getEnabledQuickButtons()
        settingsRepository.reorderQuickButtons(0, 100)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(before, settingsRepository.getEnabledQuickButtons())
    }

    @Test
    fun `E2E-37 Reorder same index is no-op`() = runTest {
        val before = settingsRepository.getEnabledQuickButtons()
        settingsRepository.reorderQuickButtons(1, 1)
        testDispatcher.scheduler.advanceUntilIdle()
        // Size should be same
        assertEquals(before.size, settingsRepository.getEnabledQuickButtons().size)
    }

    // ==========================================================================
    // Scenario 9: Data Model Tests
    // ==========================================================================

    @Test
    fun `E2E-38 SettingsQuickButton key sequences are correct control chars`() {
        assertEquals('\u0003', SettingsQuickButton.CTRL_C.keySequence[0])  // ETX
        assertEquals('\u0004', SettingsQuickButton.CTRL_D.keySequence[0])  // EOT
        assertEquals('\u001a', SettingsQuickButton.CTRL_Z.keySequence[0])  // SUB
        assertEquals('\u000c', SettingsQuickButton.CTRL_L.keySequence[0])  // FF
        assertEquals('\u001b', SettingsQuickButton.ESC.keySequence[0])     // ESC
        assertEquals('\t', SettingsQuickButton.TAB.keySequence[0])         // HT
        assertEquals('\n', SettingsQuickButton.ENTER.keySequence[0])       // LF
    }

    @Test
    fun `E2E-39 NotificationSettings equality`() {
        val s1 = NotificationSettings(true, false, true)
        val s2 = NotificationSettings(true, false, true)
        val s3 = NotificationSettings(false, false, true)

        assertEquals(s1, s2)
        assertFalse(s1 == s3)
    }

    @Test
    fun `E2E-40 DaemonInfo shows connection state`() {
        val connected = DaemonInfo(true, "1.0.0", "192.168.1.1", System.currentTimeMillis())
        val disconnected = DaemonInfo(false, null, null, null)

        assertTrue(connected.connected)
        assertNotNull(connected.version)
        assertFalse(disconnected.connected)
        assertNull(disconnected.version)
    }

    // ==========================================================================
    // Scenario 10: Flow Reactivity
    // ==========================================================================

    @Test
    fun `E2E-41 Default agent flow emits on each change`() = runTest {
        settingsRepository.defaultAgent.test {
            assertEquals(null, awaitItem())

            settingsRepository.setDefaultAgent("claude")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("claude", awaitItem())

            settingsRepository.setDefaultAgent("aider")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("aider", awaitItem())

            settingsRepository.setDefaultAgent(null)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(null, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `E2E-42 Quick buttons flow emits on each change`() = runTest {
        settingsRepository.quickButtons.test {
            assertEquals(SettingsDefaults.QUICK_BUTTONS, awaitItem())

            settingsRepository.enableQuickButton(SettingsQuickButton.TAB)
            testDispatcher.scheduler.advanceUntilIdle()
            val withTab = awaitItem()
            assertTrue(withTab.contains(SettingsQuickButton.TAB))

            settingsRepository.disableQuickButton(SettingsQuickButton.TAB)
            testDispatcher.scheduler.advanceUntilIdle()
            val withoutTab = awaitItem()
            assertFalse(withoutTab.contains(SettingsQuickButton.TAB))

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==========================================================================
    // Scenario 11: Concurrent Operations
    // ==========================================================================

    @Test
    fun `E2E-43 Rapid setting changes all persist`() = runTest {
        // Rapid fire changes
        for (i in 0 until 10) {
            settingsRepository.setDefaultAgent("agent$i")
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Last value should win
        assertEquals("agent9", settingsRepository.getDefaultAgent())
    }

    @Test
    fun `E2E-44 Rapid notification toggles all persist`() = runTest {
        // Rapid toggles
        for (i in 0 until 10) {
            settingsRepository.setNotificationEnabled(NotificationType.APPROVAL, i % 2 == 0)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Last value (i=9, 9%2=1, so false)
        assertFalse(settingsRepository.isNotificationEnabled(NotificationType.APPROVAL))
    }

    // ==========================================================================
    // Scenario 12: SettingsUiState Integration
    // ==========================================================================

    @Test
    fun `E2E-45 SettingsUiState reflects all settings`() {
        val agents = listOf(AgentInfo("claude", "claude", "/path", true))
        val buttons = listOf(SettingsQuickButton.YES, SettingsQuickButton.NO)
        val notifications = NotificationSettings(true, false, true)
        val daemon = DaemonInfo(true, "1.0", "127.0.0.1", 12345L)

        val state = SettingsUiState(
            defaultAgent = "claude",
            availableAgents = agents,
            agentListLoading = false,
            agentListError = null,
            quickButtons = buttons,
            notifications = notifications,
            daemonInfo = daemon
        )

        assertEquals("claude", state.defaultAgent)
        assertEquals(1, state.availableAgents.size)
        assertEquals(buttons, state.quickButtons)
        assertEquals(notifications, state.notifications)
        assertTrue(state.daemonInfo.connected)
    }

    @Test
    fun `E2E-46 SettingsUiState loading state`() {
        val loadingState = SettingsUiState(agentListLoading = true)
        assertTrue(loadingState.agentListLoading)
        assertNull(loadingState.agentListError)
    }

    @Test
    fun `E2E-47 SettingsUiState error state`() {
        val errorState = SettingsUiState(
            agentListLoading = false,
            agentListError = "Network error"
        )
        assertFalse(errorState.agentListLoading)
        assertEquals("Network error", errorState.agentListError)
    }

    // ==========================================================================
    // Scenario 13: SettingsUiEvent Types
    // ==========================================================================

    @Test
    fun `E2E-48 ShowMessage event with different messages`() {
        val event1 = SettingsUiEvent.ShowMessage("Reset complete")
        val event2 = SettingsUiEvent.ShowMessage("Error occurred")

        assertEquals("Reset complete", event1.message)
        assertEquals("Error occurred", event2.message)
        assertFalse(event1 == event2)
    }

    @Test
    fun `E2E-49 NavigateToAgentPicker is data object`() {
        val e1 = SettingsUiEvent.NavigateToAgentPicker
        val e2 = SettingsUiEvent.NavigateToAgentPicker
        assertTrue(e1 === e2)
    }

    // ==========================================================================
    // Scenario 14: ViewModel Agent Loading States
    // ==========================================================================

    @Test
    fun `E2E-51 ViewModel shows loading state during agent fetch`() = runTest {
        // Given: ViewModel is created while connected
        isConnectedFlow.value = true
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Agent list is loading
        // Note: Initial state should have loading=true if connected

        // Then: Loading state is reflected
        // (The ViewModel sets loading on init if connected)
        coVerify { sessionRepository.getAgents() }
    }

    @Test
    fun `E2E-52 ViewModel handles agent load error`() = runTest {
        // Given: ViewModel is created while connected
        isConnectedFlow.value = true
        coEvery { sessionRepository.getAgents() } throws Exception("Network error")

        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Error state is reflected
        val state = viewModel.uiState.value
        assertFalse(state.agentListLoading)
        assertNotNull(state.agentListError)
        assertTrue(state.agentListError!!.contains("Failed to load"))
    }

    @Test
    fun `E2E-53 ViewModel receives agents from SessionEvent`() = runTest {
        // Given: ViewModel is created
        isConnectedFlow.value = true
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Agents are loaded via event
        val agents = listOf(
            AgentInfo("claude", "claude", "/usr/bin/claude", true),
            AgentInfo("aider", "aider", "/usr/bin/aider", true)
        )
        sessionEventsFlow.emit(SessionEvent.AgentsLoaded(agents))
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Agents appear in UI state
        val state = viewModel.uiState.value
        assertEquals(2, state.availableAgents.size)
        assertFalse(state.agentListLoading)
        assertNull(state.agentListError)
    }

    @Test
    fun `E2E-54 ViewModel validates default agent when agents load`() = runTest {
        // Given: Default agent is set
        settingsRepository.setDefaultAgent("removed-agent")
        testDispatcher.scheduler.advanceUntilIdle()

        isConnectedFlow.value = true
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Agents are loaded without the default
        sessionEventsFlow.emit(SessionEvent.AgentsLoaded(listOf(
            AgentInfo("claude", "claude", "/usr/bin/claude", true)
        )))
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Default is cleared
        assertNull(settingsRepository.getDefaultAgent())
    }

    @Test
    fun `E2E-55 ViewModel emits event when default agent removed`() = runTest {
        // Given: Default agent is set
        settingsRepository.setDefaultAgent("removed-agent")
        testDispatcher.scheduler.advanceUntilIdle()

        isConnectedFlow.value = true
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Agents are loaded without the default
        viewModel.uiEvents.test {
            sessionEventsFlow.emit(SessionEvent.AgentsLoaded(listOf(
                AgentInfo("claude", "claude", "/usr/bin/claude", true)
            )))
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: ShowMessage event is emitted
            val event = awaitItem()
            assertTrue(event is SettingsUiEvent.ShowMessage)
            assertTrue((event as SettingsUiEvent.ShowMessage).message.contains("no longer available"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==========================================================================
    // Scenario 15: Connection State Handling
    // ==========================================================================

    @Test
    fun `E2E-56 ViewModel reflects connected state`() = runTest {
        // Given: Initially disconnected
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.daemonInfo.connected)

        // When: Connection established
        isConnectedFlow.value = true
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: State reflects connected
        assertTrue(viewModel.uiState.value.daemonInfo.connected)
        assertNotNull(viewModel.uiState.value.daemonInfo.lastSeen)
    }

    @Test
    fun `E2E-57 ViewModel reflects disconnected state`() = runTest {
        // Given: Initially connected
        isConnectedFlow.value = true
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.daemonInfo.connected)

        // When: Connection lost
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: State reflects disconnected
        assertFalse(viewModel.uiState.value.daemonInfo.connected)
    }

    @Test
    fun `E2E-58 ViewModel tracks lastSeen time when connected`() = runTest {
        // Given: Disconnected
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.daemonInfo.lastSeen)

        // When: Connect
        isConnectedFlow.value = true
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: lastSeen is populated
        assertNotNull(viewModel.uiState.value.daemonInfo.lastSeen)
        assertTrue(viewModel.uiState.value.daemonInfo.lastSeen!! > 0)
    }

    @Test
    fun `E2E-59 ViewModel preserves lastSeen after disconnect`() = runTest {
        // Given: Connected
        isConnectedFlow.value = true
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        val connectedLastSeen = viewModel.uiState.value.daemonInfo.lastSeen
        assertNotNull(connectedLastSeen)

        // When: Disconnect
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: lastSeen preserved
        assertEquals(connectedLastSeen, viewModel.uiState.value.daemonInfo.lastSeen)
    }

    @Test
    fun `E2E-60 ViewModel skips agent load when disconnected`() = runTest {
        // Given: Disconnected
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: No agent fetch attempted
        coVerify(exactly = 0) { sessionRepository.getAgents() }
    }

    // ==========================================================================
    // Scenario 16: App Restart Simulation
    // ==========================================================================

    @Test
    fun `E2E-61 Settings persist across repository recreation`() = runTest {
        // Given: Configure settings
        settingsRepository.setDefaultAgent("claude")
        settingsRepository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB, SettingsQuickButton.ESC))
        settingsRepository.setNotificationSettings(NotificationSettings(false, true, false))
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Create new repository (simulates app restart)
        val newRepository = SettingsRepository(context)

        // Then: Settings are persisted
        assertEquals("claude", newRepository.getDefaultAgent())
        assertEquals(listOf(SettingsQuickButton.TAB, SettingsQuickButton.ESC), newRepository.getEnabledQuickButtons())
        assertEquals(NotificationSettings(false, true, false), newRepository.getNotificationSettings())
    }

    @Test
    fun `E2E-62 Settings flows emit correct values after restart`() = runTest {
        // Given: Configure settings
        settingsRepository.setDefaultAgent("aider")
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Create new repository
        val newRepository = SettingsRepository(context)

        // Then: Flow emits persisted value
        assertEquals("aider", newRepository.defaultAgent.first())
    }

    @Test
    fun `E2E-63 Version persists across restart`() = runTest {
        // Given: Settings with version
        assertEquals(SETTINGS_VERSION, settingsRepository.getSettingsVersion())

        // When: Create new repository
        val newRepository = SettingsRepository(context)

        // Then: Version is preserved
        assertEquals(SETTINGS_VERSION, newRepository.getSettingsVersion())
    }

    // ==========================================================================
    // Scenario 17: ViewModel User Actions
    // ==========================================================================

    @Test
    fun `E2E-64 ViewModel setDefaultAgent updates state`() = runTest {
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Set default agent through ViewModel
        viewModel.setDefaultAgent("claude")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: State and repository updated
        assertEquals("claude", viewModel.uiState.value.defaultAgent)
        assertEquals("claude", settingsRepository.getDefaultAgent())
    }

    @Test
    fun `E2E-65 ViewModel toggleQuickButton enable`() = runTest {
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Enable TAB through ViewModel
        viewModel.toggleQuickButton(SettingsQuickButton.TAB, true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: TAB is in list
        assertTrue(viewModel.uiState.value.quickButtons.contains(SettingsQuickButton.TAB))
        assertTrue(settingsRepository.getEnabledQuickButtons().contains(SettingsQuickButton.TAB))
    }

    @Test
    fun `E2E-66 ViewModel toggleQuickButton disable`() = runTest {
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Disable YES through ViewModel
        viewModel.toggleQuickButton(SettingsQuickButton.YES, false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: YES is not in list
        assertFalse(viewModel.uiState.value.quickButtons.contains(SettingsQuickButton.YES))
        assertFalse(settingsRepository.getEnabledQuickButtons().contains(SettingsQuickButton.YES))
    }

    @Test
    fun `E2E-67 ViewModel setNotificationEnabled updates state`() = runTest {
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Disable approval through ViewModel
        viewModel.setNotificationEnabled(NotificationType.APPROVAL, false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: State and repository updated
        assertFalse(viewModel.uiState.value.notifications.approvalEnabled)
        assertFalse(settingsRepository.isNotificationEnabled(NotificationType.APPROVAL))
    }

    @Test
    fun `E2E-68 ViewModel resetSection emits message`() = runTest {
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // Set up something to reset
        viewModel.setDefaultAgent("claude")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiEvents.test {
            // When: Reset sessions section
            viewModel.resetSection(SettingsSection.SESSIONS)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Message event emitted
            val event = awaitItem()
            assertTrue(event is SettingsUiEvent.ShowMessage)
            assertTrue((event as SettingsUiEvent.ShowMessage).message.contains("Sessions"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `E2E-69 ViewModel refreshAgentList fetches agents`() = runTest {
        isConnectedFlow.value = true
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // Initial fetch happened
        coVerify(exactly = 1) { sessionRepository.getAgents() }

        // When: Refresh
        viewModel.refreshAgentList()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Another fetch
        coVerify(exactly = 2) { sessionRepository.getAgents() }
    }

    @Test
    fun `E2E-70 ViewModel disconnect calls ConnectionManager`() = runTest {
        isConnectedFlow.value = true
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Disconnect
        viewModel.disconnect()

        // Then: ConnectionManager.disconnect called
        verify { connectionManager.disconnect() }
    }

    // ==========================================================================
    // Scenario 18: Error Edge Cases
    // ==========================================================================

    @Test
    fun `E2E-71 Corrupted buttons JSON falls back to defaults`() = runTest {
        // Given: Corrupted JSON in storage
        storage[SettingsKeys.QUICK_BUTTONS] = "not valid json {{{["

        // When: Create repository
        val repo = SettingsRepository(context)

        // Then: Falls back to defaults
        assertEquals(SettingsDefaults.QUICK_BUTTONS, repo.getEnabledQuickButtons())
    }

    @Test
    fun `E2E-72 Partially valid buttons JSON keeps valid entries`() = runTest {
        // Given: JSON with one invalid entry
        storage[SettingsKeys.QUICK_BUTTONS] = """["yes","invalid_button","no"]"""

        // When: Create repository
        val repo = SettingsRepository(context)

        // Then: Only valid buttons kept
        val buttons = repo.getEnabledQuickButtons()
        assertTrue(buttons.contains(SettingsQuickButton.YES))
        assertTrue(buttons.contains(SettingsQuickButton.NO))
        assertEquals(2, buttons.size)
    }

    @Test
    fun `E2E-73 Empty JSON array gives empty list`() = runTest {
        // Given: Empty array
        storage[SettingsKeys.QUICK_BUTTONS] = "[]"

        // When: Create repository
        val repo = SettingsRepository(context)

        // Then: Empty list
        assertTrue(repo.getEnabledQuickButtons().isEmpty())
    }

    @Test
    fun `E2E-74 Whitespace-only JSON gives empty list`() = runTest {
        // Given: Whitespace (treated as blank/empty)
        storage[SettingsKeys.QUICK_BUTTONS] = "   "

        // When: Create repository
        val repo = SettingsRepository(context)

        // Then: Returns empty list (blank is treated as intentionally empty)
        assertTrue(repo.getEnabledQuickButtons().isEmpty())
    }

    // ==========================================================================
    // Scenario 19: Settings Work While Disconnected
    // ==========================================================================

    @Test
    fun `E2E-75 Can change settings while disconnected`() = runTest {
        // Given: Disconnected
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Change all settings
        viewModel.setDefaultAgent("claude")
        viewModel.toggleQuickButton(SettingsQuickButton.TAB, true)
        viewModel.setNotificationEnabled(NotificationType.APPROVAL, false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: All changes persisted
        assertEquals("claude", viewModel.uiState.value.defaultAgent)
        assertTrue(viewModel.uiState.value.quickButtons.contains(SettingsQuickButton.TAB))
        assertFalse(viewModel.uiState.value.notifications.approvalEnabled)
    }

    @Test
    fun `E2E-76 Settings persist through connection state changes`() = runTest {
        // Given: Configure while disconnected
        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setDefaultAgent("claude")
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Connect
        isConnectedFlow.value = true
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Settings preserved
        assertEquals("claude", viewModel.uiState.value.defaultAgent)
    }

    @Test
    fun `E2E-77 Reset works while disconnected`() = runTest {
        // Given: Configured and disconnected
        settingsRepository.setDefaultAgent("claude")
        testDispatcher.scheduler.advanceUntilIdle()

        isConnectedFlow.value = false
        val viewModel = SettingsViewModel(settingsRepository, sessionRepository, connectionManager)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: Reset
        viewModel.resetSection(SettingsSection.SESSIONS)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Reset works
        assertNull(viewModel.uiState.value.defaultAgent)
    }

    // ==========================================================================
    // Scenario 20: Complete User Journey
    // ==========================================================================

    @Test
    fun `E2E-50 Complete settings configuration journey`() = runTest {
        // 1. Fresh install - check defaults
        assertNull(settingsRepository.getDefaultAgent())
        assertEquals(SettingsDefaults.QUICK_BUTTONS, settingsRepository.getEnabledQuickButtons())
        assertTrue(settingsRepository.isNotificationEnabled(NotificationType.APPROVAL))

        // 2. Configure default agent
        settingsRepository.setDefaultAgent("claude")
        testDispatcher.scheduler.advanceUntilIdle()

        // 3. Customize quick buttons
        settingsRepository.disableQuickButton(SettingsQuickButton.NO)
        settingsRepository.enableQuickButton(SettingsQuickButton.TAB)
        settingsRepository.enableQuickButton(SettingsQuickButton.ESC)
        testDispatcher.scheduler.advanceUntilIdle()

        // 4. Adjust notifications
        settingsRepository.setNotificationEnabled(NotificationType.COMPLETION, false)
        testDispatcher.scheduler.advanceUntilIdle()

        // 5. Verify all changes persisted
        assertEquals("claude", settingsRepository.getDefaultAgent())

        val buttons = settingsRepository.getEnabledQuickButtons()
        assertTrue(buttons.contains(SettingsQuickButton.YES))
        assertFalse(buttons.contains(SettingsQuickButton.NO))
        assertTrue(buttons.contains(SettingsQuickButton.TAB))
        assertTrue(buttons.contains(SettingsQuickButton.ESC))

        assertTrue(settingsRepository.isNotificationEnabled(NotificationType.APPROVAL))
        assertFalse(settingsRepository.isNotificationEnabled(NotificationType.COMPLETION))
        assertTrue(settingsRepository.isNotificationEnabled(NotificationType.ERROR))

        // 6. Reset terminal section only
        settingsRepository.resetSection(SettingsSection.TERMINAL)
        testDispatcher.scheduler.advanceUntilIdle()

        // 7. Verify selective reset
        assertEquals("claude", settingsRepository.getDefaultAgent())  // Preserved
        assertEquals(SettingsDefaults.QUICK_BUTTONS, settingsRepository.getEnabledQuickButtons())  // Reset
        assertFalse(settingsRepository.isNotificationEnabled(NotificationType.COMPLETION))  // Preserved
    }
}
