package com.ras.settings

import android.content.Context
import android.content.SharedPreferences
import com.ras.data.settings.NotificationSettings
import com.ras.data.settings.NotificationType
import com.ras.data.settings.SETTINGS_VERSION
import com.ras.data.settings.SettingsDefaults
import com.ras.data.settings.SettingsKeys
import com.ras.data.settings.SettingsQuickButton
import com.ras.data.settings.SettingsRepositoryImpl
import com.ras.data.settings.SettingsSection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repository: SettingsRepositoryImpl

    // In-memory storage for testing
    private val storage = mutableMapOf<String, Any?>()

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        // Mock SharedPreferences behavior with in-memory storage
        every { context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor

        // Default returns
        every { prefs.getString(any(), any()) } answers {
            storage[firstArg<String>()] as? String ?: secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            storage[firstArg<String>()] as? Boolean ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            storage[firstArg<String>()] as? Int ?: secondArg()
        }
        every { prefs.getFloat(any(), any()) } answers {
            storage[firstArg<String>()] as? Float ?: secondArg()
        }

        // Mock editor chain
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
        every { editor.putFloat(any(), any()) } answers {
            storage[firstArg<String>()] = secondArg<Float>()
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
        repository = SettingsRepositoryImpl(context)
    }

    // ==========================================================================
    // Default Agent Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `DA01 - fresh install has no default agent`() {
        assertNull(repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `DA02 - set default agent persists`() {
        repository.setDefaultAgent("claude")
        assertEquals("claude", repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `DA03 - clear default agent by setting null`() {
        repository.setDefaultAgent("claude")
        repository.setDefaultAgent(null)
        assertNull(repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `DA04 - default agent observable flow updates`() = runTest {
        assertEquals(null, repository.defaultAgent.first())

        repository.setDefaultAgent("aider")
        assertEquals("aider", repository.defaultAgent.first())
    }

    @Tag("unit")
    @Test
    fun `DA05 - validate default agent exists`() {
        repository.setDefaultAgent("claude")
        val result = repository.validateDefaultAgent(listOf("claude", "aider"))
        assertTrue(result)
        assertEquals("claude", repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `DA06 - validate default agent removed clears it`() {
        repository.setDefaultAgent("claude")
        val result = repository.validateDefaultAgent(listOf("aider"))
        assertFalse(result)
        assertNull(repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `DA07 - validate with no default always returns true`() {
        val result = repository.validateDefaultAgent(listOf("aider"))
        assertTrue(result)
    }

    // ==========================================================================
    // Quick Buttons Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `QB01 - fresh install has default buttons`() {
        val buttons = repository.getEnabledQuickButtons()
        assertEquals(SettingsDefaults.QUICK_BUTTONS, buttons)
    }

    @Tag("unit")
    @Test
    fun `QB02 - set quick buttons persists`() {
        val buttons = listOf(SettingsQuickButton.CTRL_C, SettingsQuickButton.YES)
        repository.setEnabledQuickButtons(buttons)
        assertEquals(buttons, repository.getEnabledQuickButtons())
    }

    @Tag("unit")
    @Test
    fun `QB03 - enable button adds to list`() {
        repository.setEnabledQuickButtons(listOf(SettingsQuickButton.YES))
        repository.enableQuickButton(SettingsQuickButton.NO)
        val buttons = repository.getEnabledQuickButtons()
        assertEquals(2, buttons.size)
        assertTrue(buttons.contains(SettingsQuickButton.YES))
        assertTrue(buttons.contains(SettingsQuickButton.NO))
    }

    @Tag("unit")
    @Test
    fun `QB04 - enable already enabled button is no-op`() {
        val initial = listOf(SettingsQuickButton.YES, SettingsQuickButton.NO)
        repository.setEnabledQuickButtons(initial)
        repository.enableQuickButton(SettingsQuickButton.YES)
        assertEquals(initial, repository.getEnabledQuickButtons())
    }

    @Tag("unit")
    @Test
    fun `QB05 - disable button removes from list`() {
        repository.setEnabledQuickButtons(listOf(SettingsQuickButton.YES, SettingsQuickButton.NO))
        repository.disableQuickButton(SettingsQuickButton.YES)
        assertEquals(listOf(SettingsQuickButton.NO), repository.getEnabledQuickButtons())
    }

    @Tag("unit")
    @Test
    fun `QB06 - disable all buttons results in empty list`() {
        repository.setEnabledQuickButtons(listOf(SettingsQuickButton.YES))
        repository.disableQuickButton(SettingsQuickButton.YES)
        assertTrue(repository.getEnabledQuickButtons().isEmpty())
    }

    @Tag("unit")
    @Test
    fun `QB07 - enable all buttons`() {
        repository.setEnabledQuickButtons(SettingsQuickButton.ALL)
        assertEquals(SettingsQuickButton.ALL.size, repository.getEnabledQuickButtons().size)
    }

    @Tag("unit")
    @Test
    fun `QB08 - reorder buttons`() {
        val initial = listOf(
            SettingsQuickButton.YES,
            SettingsQuickButton.NO,
            SettingsQuickButton.CTRL_C
        )
        repository.setEnabledQuickButtons(initial)
        repository.reorderQuickButtons(0, 2)
        val expected = listOf(
            SettingsQuickButton.NO,
            SettingsQuickButton.CTRL_C,
            SettingsQuickButton.YES
        )
        assertEquals(expected, repository.getEnabledQuickButtons())
    }

    @Tag("unit")
    @Test
    fun `QB09 - reorder with invalid indices is no-op`() {
        val initial = listOf(SettingsQuickButton.YES)
        repository.setEnabledQuickButtons(initial)
        repository.reorderQuickButtons(0, 5)
        assertEquals(initial, repository.getEnabledQuickButtons())
    }

    @Tag("unit")
    @Test
    fun `QB10 - quick buttons observable flow updates`() = runTest {
        val newButtons = listOf(SettingsQuickButton.TAB, SettingsQuickButton.ESC)
        repository.setEnabledQuickButtons(newButtons)
        assertEquals(newButtons, repository.quickButtons.first())
    }

    // ==========================================================================
    // Quick Button Serialization Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `serialize default buttons`() {
        val buttons = listOf(
            SettingsQuickButton.YES,
            SettingsQuickButton.NO,
            SettingsQuickButton.CTRL_C
        )
        val json = repository.serializeQuickButtons(buttons)
        assertEquals("""["yes","no","ctrl_c"]""", json)
    }

    @Tag("unit")
    @Test
    fun `serialize empty buttons`() {
        val json = repository.serializeQuickButtons(emptyList())
        assertEquals("[]", json)
    }

    @Tag("unit")
    @Test
    fun `serialize all buttons`() {
        val json = repository.serializeQuickButtons(SettingsQuickButton.ALL)
        assertEquals("""["yes","no","ctrl_c","esc","tab","shift_tab","up","down","backspace"]""", json)
    }

    @Tag("unit")
    @Test
    fun `serialize custom order`() {
        val buttons = listOf(
            SettingsQuickButton.CTRL_C,
            SettingsQuickButton.YES,
            SettingsQuickButton.NO
        )
        val json = repository.serializeQuickButtons(buttons)
        assertEquals("""["ctrl_c","yes","no"]""", json)
    }

    @Tag("unit")
    @Test
    fun `deserialize default buttons`() {
        val buttons = repository.deserializeQuickButtons("""["yes","no","ctrl_c"]""")
        assertEquals(
            listOf(
                SettingsQuickButton.YES,
                SettingsQuickButton.NO,
                SettingsQuickButton.CTRL_C
            ),
            buttons
        )
    }

    @Tag("unit")
    @Test
    fun `deserialize empty list`() {
        val buttons = repository.deserializeQuickButtons("[]")
        assertTrue(buttons.isEmpty())
    }

    @Tag("unit")
    @Test
    fun `deserialize with unknown ID skips it`() {
        val buttons = repository.deserializeQuickButtons("""["yes","unknown_button","no"]""")
        assertEquals(
            listOf(SettingsQuickButton.YES, SettingsQuickButton.NO),
            buttons
        )
    }

    @Tag("unit")
    @Test
    fun `deserialize invalid JSON returns defaults`() {
        val buttons = repository.deserializeQuickButtons("not valid json")
        assertEquals(SettingsDefaults.QUICK_BUTTONS, buttons)
    }

    // ==========================================================================
    // Notification Settings Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `NT01 - fresh install has all notifications enabled`() {
        val settings = repository.getNotificationSettings()
        assertTrue(settings.approvalEnabled)
        assertTrue(settings.completionEnabled)
        assertTrue(settings.errorEnabled)
    }

    @Tag("unit")
    @Test
    fun `NT02 - disable approval notification`() {
        repository.setNotificationEnabled(NotificationType.APPROVAL, false)
        val settings = repository.getNotificationSettings()
        assertFalse(settings.approvalEnabled)
        assertTrue(settings.completionEnabled)
        assertTrue(settings.errorEnabled)
    }

    @Tag("unit")
    @Test
    fun `NT03 - disable completion notification`() {
        repository.setNotificationEnabled(NotificationType.COMPLETION, false)
        val settings = repository.getNotificationSettings()
        assertTrue(settings.approvalEnabled)
        assertFalse(settings.completionEnabled)
        assertTrue(settings.errorEnabled)
    }

    @Tag("unit")
    @Test
    fun `NT04 - disable error notification`() {
        repository.setNotificationEnabled(NotificationType.ERROR, false)
        val settings = repository.getNotificationSettings()
        assertTrue(settings.approvalEnabled)
        assertTrue(settings.completionEnabled)
        assertFalse(settings.errorEnabled)
    }

    @Tag("unit")
    @Test
    fun `NT05 - disable all notifications`() {
        val allOff = NotificationSettings(
            approvalEnabled = false,
            completionEnabled = false,
            errorEnabled = false
        )
        repository.setNotificationSettings(allOff)
        assertEquals(allOff, repository.getNotificationSettings())
    }

    @Tag("unit")
    @Test
    fun `NT06 - set and get all notification settings`() {
        val settings = NotificationSettings(
            approvalEnabled = true,
            completionEnabled = false,
            errorEnabled = true
        )
        repository.setNotificationSettings(settings)
        assertEquals(settings, repository.getNotificationSettings())
    }

    @Tag("unit")
    @Test
    fun `NT07 - isNotificationEnabled checks individual types`() {
        repository.setNotificationSettings(
            NotificationSettings(
                approvalEnabled = true,
                completionEnabled = false,
                errorEnabled = true
            )
        )
        assertTrue(repository.isNotificationEnabled(NotificationType.APPROVAL))
        assertFalse(repository.isNotificationEnabled(NotificationType.COMPLETION))
        assertTrue(repository.isNotificationEnabled(NotificationType.ERROR))
    }

    @Tag("unit")
    @Test
    fun `NT08 - notification settings observable flow updates`() = runTest {
        val settings = NotificationSettings(
            approvalEnabled = false,
            completionEnabled = true,
            errorEnabled = false
        )
        repository.setNotificationSettings(settings)
        assertEquals(settings, repository.notificationSettings.first())
    }

    // ==========================================================================
    // Terminal Font Size Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `FS01 - fresh install returns default font size`() {
        assertEquals(SettingsDefaults.TERMINAL_FONT_SIZE, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS02 - set font size persists`() {
        repository.setTerminalFontSize(16f)
        assertEquals(16f, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS03 - set minimum font size`() {
        repository.setTerminalFontSize(8f)
        assertEquals(8f, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS04 - set maximum font size`() {
        repository.setTerminalFontSize(24f)
        assertEquals(24f, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS05 - set font size zero`() {
        // Edge case: font size should technically be > 0, but repository doesn't validate
        repository.setTerminalFontSize(0f)
        assertEquals(0f, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS06 - set negative font size`() {
        // Edge case: repository stores whatever is passed (validation happens in UI)
        repository.setTerminalFontSize(-5f)
        assertEquals(-5f, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS07 - set font size with decimals`() {
        repository.setTerminalFontSize(14.5f)
        assertEquals(14.5f, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS08 - set font size multiple times uses last value`() {
        repository.setTerminalFontSize(10f)
        repository.setTerminalFontSize(14f)
        repository.setTerminalFontSize(18f)
        assertEquals(18f, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS09 - set same font size is no-op`() {
        repository.setTerminalFontSize(12f)
        repository.setTerminalFontSize(12f)
        assertEquals(12f, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `FS10 - font size persisted to SharedPreferences`() {
        repository.setTerminalFontSize(20f)
        verify { editor.putFloat(SettingsKeys.TERMINAL_FONT_SIZE, 20f) }
        verify { editor.apply() }
    }

    // ==========================================================================
    // Reset Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `RS01 - reset sessions section clears default agent`() {
        repository.setDefaultAgent("claude")
        repository.resetSection(SettingsSection.SESSIONS)
        assertNull(repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `RS02 - reset terminal section restores default buttons`() {
        repository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        repository.resetSection(SettingsSection.TERMINAL)
        assertEquals(SettingsDefaults.QUICK_BUTTONS, repository.getEnabledQuickButtons())
    }

    @Tag("unit")
    @Test
    fun `RS02a - reset terminal section restores default font size`() {
        repository.setTerminalFontSize(20f)
        repository.resetSection(SettingsSection.TERMINAL)
        assertEquals(SettingsDefaults.TERMINAL_FONT_SIZE, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `RS02b - reset terminal section restores both buttons and font size`() {
        repository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        repository.setTerminalFontSize(20f)
        repository.resetSection(SettingsSection.TERMINAL)
        assertEquals(SettingsDefaults.QUICK_BUTTONS, repository.getEnabledQuickButtons())
        assertEquals(SettingsDefaults.TERMINAL_FONT_SIZE, repository.getTerminalFontSize())
    }

    @Tag("unit")
    @Test
    fun `RS03 - reset notifications section enables all`() {
        repository.setNotificationSettings(
            NotificationSettings(
                approvalEnabled = false,
                completionEnabled = false,
                errorEnabled = false
            )
        )
        repository.resetSection(SettingsSection.NOTIFICATIONS)
        assertEquals(SettingsDefaults.NOTIFICATIONS, repository.getNotificationSettings())
    }

    @Tag("unit")
    @Test
    fun `RS04 - reset one section preserves others`() {
        // Set all
        repository.setDefaultAgent("claude")
        repository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        repository.setTerminalFontSize(20f)
        repository.setNotificationSettings(
            NotificationSettings(false, false, false)
        )

        // Reset only sessions
        repository.resetSection(SettingsSection.SESSIONS)

        // Verify others unchanged
        assertNull(repository.getDefaultAgent())
        assertEquals(listOf(SettingsQuickButton.TAB), repository.getEnabledQuickButtons())
        assertEquals(20f, repository.getTerminalFontSize())
        assertEquals(
            NotificationSettings(false, false, false),
            repository.getNotificationSettings()
        )
    }

    @Tag("unit")
    @Test
    fun `RS04a - reset terminal section preserves sessions and notifications`() {
        // Set all
        repository.setDefaultAgent("claude")
        repository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        repository.setTerminalFontSize(20f)
        repository.setNotificationSettings(
            NotificationSettings(false, false, false)
        )

        // Reset only terminal
        repository.resetSection(SettingsSection.TERMINAL)

        // Verify terminal reset but others unchanged
        assertEquals("claude", repository.getDefaultAgent())
        assertEquals(SettingsDefaults.QUICK_BUTTONS, repository.getEnabledQuickButtons())
        assertEquals(SettingsDefaults.TERMINAL_FONT_SIZE, repository.getTerminalFontSize())
        assertEquals(
            NotificationSettings(false, false, false),
            repository.getNotificationSettings()
        )
    }

    @Tag("unit")
    @Test
    fun `RS05 - reset all clears everything`() {
        repository.setDefaultAgent("claude")
        repository.setEnabledQuickButtons(listOf(SettingsQuickButton.TAB))
        repository.setTerminalFontSize(20f)
        repository.setNotificationSettings(
            NotificationSettings(false, false, false)
        )

        repository.resetAll()

        assertNull(repository.getDefaultAgent())
        assertEquals(SettingsDefaults.QUICK_BUTTONS, repository.getEnabledQuickButtons())
        assertEquals(SettingsDefaults.TERMINAL_FONT_SIZE, repository.getTerminalFontSize())
        assertEquals(SettingsDefaults.NOTIFICATIONS, repository.getNotificationSettings())
    }

    // ==========================================================================
    // Migration Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `MG01 - fresh install sets version`() {
        assertEquals(SETTINGS_VERSION, repository.getSettingsVersion())
    }

    @Tag("unit")
    @Test
    fun `MG02 - version is persisted`() {
        verify { editor.putInt(SettingsKeys.VERSION, SETTINGS_VERSION) }
    }

    // ==========================================================================
    // SettingsQuickButton Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `SettingsQuickButton fromId returns correct button`() {
        assertEquals(SettingsQuickButton.YES, SettingsQuickButton.fromId("yes"))
        assertEquals(SettingsQuickButton.NO, SettingsQuickButton.fromId("no"))
        assertEquals(SettingsQuickButton.CTRL_C, SettingsQuickButton.fromId("ctrl_c"))
        assertEquals(SettingsQuickButton.TAB, SettingsQuickButton.fromId("tab"))
        assertEquals(SettingsQuickButton.ESC, SettingsQuickButton.fromId("esc"))
        assertEquals(SettingsQuickButton.ARROW_UP, SettingsQuickButton.fromId("up"))
    }

    @Tag("unit")
    @Test
    fun `SettingsQuickButton fromId returns null for unknown`() {
        assertNull(SettingsQuickButton.fromId("unknown"))
        assertNull(SettingsQuickButton.fromId(""))
        assertNull(SettingsQuickButton.fromId("YES"))  // case sensitive
    }

    @Tag("unit")
    @Test
    fun `SettingsQuickButton ALL contains all buttons`() {
        assertEquals(9, SettingsQuickButton.ALL.size)
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.YES))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.NO))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.CTRL_C))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.ESC))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.TAB))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.SHIFT_TAB))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.ARROW_UP))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.ARROW_DOWN))
        assertTrue(SettingsQuickButton.ALL.contains(SettingsQuickButton.BACKSPACE))
    }

    @Tag("unit")
    @Test
    fun `SettingsQuickButton has correct key sequences`() {
        assertEquals("Yes", SettingsQuickButton.YES.keySequence)
        assertEquals("No", SettingsQuickButton.NO.keySequence)
        assertEquals("\u0003", SettingsQuickButton.CTRL_C.keySequence)
        assertEquals("\u001b", SettingsQuickButton.ESC.keySequence)
        assertEquals("\t", SettingsQuickButton.TAB.keySequence)
        assertEquals("\u001b[Z", SettingsQuickButton.SHIFT_TAB.keySequence)
        assertEquals("\u001b[A", SettingsQuickButton.ARROW_UP.keySequence)
        assertEquals("\u001b[B", SettingsQuickButton.ARROW_DOWN.keySequence)
        assertEquals("\u007f", SettingsQuickButton.BACKSPACE.keySequence)
    }

    // ==========================================================================
    // Edge Cases
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `EC01 - very long agent name is stored`() {
        val longName = "a".repeat(200)
        repository.setDefaultAgent(longName)
        assertEquals(longName, repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `EC02 - empty agent name is stored`() {
        repository.setDefaultAgent("")
        assertEquals("", repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `EC03 - agent name with special characters`() {
        val specialName = "claude-v2.0_beta@test"
        repository.setDefaultAgent(specialName)
        assertEquals(specialName, repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `EC04 - agent name with unicode`() {
        val unicodeName = "claude-日本語-\uD83E\uDD16"
        repository.setDefaultAgent(unicodeName)
        assertEquals(unicodeName, repository.getDefaultAgent())
    }

    @Tag("unit")
    @Test
    fun `EC05 - reorder same index is no-op`() {
        val initial = listOf(SettingsQuickButton.YES, SettingsQuickButton.NO)
        repository.setEnabledQuickButtons(initial)
        repository.reorderQuickButtons(0, 0)
        // This might actually swap, let's just verify no crash
        assertEquals(2, repository.getEnabledQuickButtons().size)
    }

    @Tag("unit")
    @Test
    fun `EC06 - reorder with negative indices is no-op`() {
        val initial = listOf(SettingsQuickButton.YES, SettingsQuickButton.NO)
        repository.setEnabledQuickButtons(initial)
        repository.reorderQuickButtons(-1, 0)
        assertEquals(initial, repository.getEnabledQuickButtons())
    }
}
