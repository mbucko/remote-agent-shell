package com.ras.settings

import com.ras.data.sessions.AgentInfo
import com.ras.data.settings.NotificationSettings
import com.ras.data.settings.SettingsDefaults
import com.ras.data.settings.SettingsQuickButton
import com.ras.ui.settings.SettingsUiEvent
import com.ras.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Settings UI state classes.
 *
 * Note: Full ViewModel tests are deferred pending MockK configuration
 * for mocking classes with Android dependencies.
 */
class SettingsViewModelTest {

    // ==========================================================================
    // SettingsUiState Tests
    // ==========================================================================

    @Test
    fun `SettingsUiState has correct defaults`() {
        val state = SettingsUiState()

        assertNull(state.defaultAgent)
        assertTrue(state.availableAgents.isEmpty())
        assertFalse(state.agentListLoading)
        assertNull(state.agentListError)
        assertEquals(SettingsDefaults.QUICK_BUTTONS, state.quickButtons)
        assertEquals(SettingsDefaults.NOTIFICATIONS, state.notifications)
        assertFalse(state.daemonInfo.connected)
    }

    @Test
    fun `SettingsUiState copy preserves fields`() {
        val agents = listOf(AgentInfo("test", "test", "/test", true))
        val state = SettingsUiState(
            defaultAgent = "agent",
            availableAgents = agents,
            agentListLoading = true,
            agentListError = "error"
        )

        val copy = state.copy(defaultAgent = "new-agent")

        assertEquals("new-agent", copy.defaultAgent)
        assertEquals(agents, copy.availableAgents)
        assertTrue(copy.agentListLoading)
        assertEquals("error", copy.agentListError)
    }

    @Test
    fun `SettingsUiState with all fields set`() {
        val agents = listOf(
            AgentInfo("claude", "claude", "/usr/bin/claude", true),
            AgentInfo("aider", "aider", "/usr/bin/aider", true)
        )
        val buttons = listOf(SettingsQuickButton.YES, SettingsQuickButton.NO)
        val notifications = NotificationSettings(false, true, false)

        val state = SettingsUiState(
            defaultAgent = "claude",
            availableAgents = agents,
            agentListLoading = false,
            agentListError = null,
            quickButtons = buttons,
            notifications = notifications
        )

        assertEquals("claude", state.defaultAgent)
        assertEquals(2, state.availableAgents.size)
        assertFalse(state.agentListLoading)
        assertNull(state.agentListError)
        assertEquals(buttons, state.quickButtons)
        assertEquals(notifications, state.notifications)
    }

    @Test
    fun `SettingsUiState with loading state`() {
        val state = SettingsUiState(agentListLoading = true)

        assertTrue(state.agentListLoading)
        assertNull(state.agentListError)
        assertTrue(state.availableAgents.isEmpty())
    }

    @Test
    fun `SettingsUiState with error state`() {
        val state = SettingsUiState(
            agentListLoading = false,
            agentListError = "Network connection failed"
        )

        assertFalse(state.agentListLoading)
        assertEquals("Network connection failed", state.agentListError)
    }

    // ==========================================================================
    // SettingsUiEvent Tests
    // ==========================================================================

    @Test
    fun `ShowMessage event contains message`() {
        val event = SettingsUiEvent.ShowMessage("Test message")
        assertEquals("Test message", event.message)
    }

    @Test
    fun `ShowMessage events with same message are equal`() {
        val event1 = SettingsUiEvent.ShowMessage("Test")
        val event2 = SettingsUiEvent.ShowMessage("Test")
        assertEquals(event1, event2)
    }

    @Test
    fun `ShowMessage events with different messages are not equal`() {
        val event1 = SettingsUiEvent.ShowMessage("Test 1")
        val event2 = SettingsUiEvent.ShowMessage("Test 2")
        assertFalse(event1 == event2)
    }

    @Test
    fun `NavigateToAgentPicker is singleton`() {
        val event1 = SettingsUiEvent.NavigateToAgentPicker
        val event2 = SettingsUiEvent.NavigateToAgentPicker
        assertTrue(event1 === event2)
    }

    // ==========================================================================
    // NotificationSettings Tests
    // ==========================================================================

    @Test
    fun `NotificationSettings default has all enabled`() {
        val settings = SettingsDefaults.NOTIFICATIONS

        assertTrue(settings.approvalEnabled)
        assertTrue(settings.completionEnabled)
        assertTrue(settings.errorEnabled)
    }

    @Test
    fun `NotificationSettings copy works correctly`() {
        val settings = NotificationSettings(true, true, true)
        val modified = settings.copy(approvalEnabled = false)

        assertFalse(modified.approvalEnabled)
        assertTrue(modified.completionEnabled)
        assertTrue(modified.errorEnabled)
    }

    // ==========================================================================
    // SettingsQuickButton Tests
    // ==========================================================================

    @Test
    fun `default quick buttons list`() {
        val defaults = SettingsDefaults.QUICK_BUTTONS

        assertEquals(3, defaults.size)
        assertEquals(SettingsQuickButton.YES, defaults[0])
        assertEquals(SettingsQuickButton.NO, defaults[1])
        assertEquals(SettingsQuickButton.CTRL_C, defaults[2])
    }

    @Test
    fun `SettingsQuickButton fromId returns correct button`() {
        assertEquals(SettingsQuickButton.YES, SettingsQuickButton.fromId("yes"))
        assertEquals(SettingsQuickButton.NO, SettingsQuickButton.fromId("no"))
        assertEquals(SettingsQuickButton.CTRL_C, SettingsQuickButton.fromId("ctrl_c"))
        assertEquals(SettingsQuickButton.TAB, SettingsQuickButton.fromId("tab"))
        assertEquals(SettingsQuickButton.ESC, SettingsQuickButton.fromId("esc"))
        assertEquals(SettingsQuickButton.ENTER, SettingsQuickButton.fromId("enter"))
        assertEquals(SettingsQuickButton.CTRL_D, SettingsQuickButton.fromId("ctrl_d"))
        assertEquals(SettingsQuickButton.CTRL_Z, SettingsQuickButton.fromId("ctrl_z"))
        assertEquals(SettingsQuickButton.CTRL_L, SettingsQuickButton.fromId("ctrl_l"))
    }

    @Test
    fun `SettingsQuickButton fromId returns null for unknown`() {
        assertNull(SettingsQuickButton.fromId("unknown"))
        assertNull(SettingsQuickButton.fromId(""))
        assertNull(SettingsQuickButton.fromId("YES"))  // case sensitive
        assertNull(SettingsQuickButton.fromId(" yes")) // no trimming
    }

    @Test
    fun `SettingsQuickButton ALL contains all buttons`() {
        assertEquals(9, SettingsQuickButton.ALL.size)
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.YES))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.NO))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.ENTER))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.CTRL_C))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.CTRL_D))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.CTRL_Z))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.CTRL_L))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.ESC))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.TAB))
    }

    @Test
    fun `SettingsQuickButton has correct key sequences`() {
        assertEquals("y", SettingsQuickButton.YES.keySequence)
        assertEquals("n", SettingsQuickButton.NO.keySequence)
        assertEquals("\n", SettingsQuickButton.ENTER.keySequence)
        assertEquals("\u0003", SettingsQuickButton.CTRL_C.keySequence)
        assertEquals("\u0004", SettingsQuickButton.CTRL_D.keySequence)
        assertEquals("\u001a", SettingsQuickButton.CTRL_Z.keySequence)
        assertEquals("\u000c", SettingsQuickButton.CTRL_L.keySequence)
        assertEquals("\u001b", SettingsQuickButton.ESC.keySequence)
        assertEquals("\t", SettingsQuickButton.TAB.keySequence)
    }

    @Test
    fun `SettingsQuickButton has unique IDs`() {
        val ids = SettingsQuickButton.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `SettingsQuickButton has unique labels`() {
        val labels = SettingsQuickButton.ALL.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }
}
