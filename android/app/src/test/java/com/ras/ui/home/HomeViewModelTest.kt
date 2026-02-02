package com.ras.ui.home

import com.ras.data.connection.ConnectionManager
import com.ras.data.credentials.CredentialRepository
import com.ras.data.model.DeviceType
import com.ras.data.sessions.SessionInfo
import com.ras.data.sessions.SessionRepository
import com.ras.data.sessions.SessionStatus
import com.ras.data.settings.NotificationSettings
import com.ras.data.settings.SettingsQuickButton
import com.ras.data.settings.SettingsRepository
import com.ras.data.settings.SettingsSection
import com.ras.data.settings.ModifierKeySettings
import com.ras.data.settings.NotificationType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for HomeViewModel.
 *
 * Uses manual mock implementation for SettingsRepository to avoid MockK issues
 * with interface default methods and Kotlin suspend functions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var settingsRepository: TestSettingsRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var sessionsFlow: MutableStateFlow<List<SessionInfo>>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        sessionsFlow = MutableStateFlow(emptyList())
        isConnectedFlow = MutableStateFlow(false)

        // Create mocks with explicit type
        credentialRepository = mockk<CredentialRepository>(relaxed = true)
        connectionManager = mockk<ConnectionManager>(relaxed = true)
        sessionRepository = mockk<SessionRepository>(relaxed = true)
        
        // Use manual mock for SettingsRepository to avoid MockK interface issues
        settingsRepository = TestSettingsRepository()

        // Setup suspend function stubs with coEvery
        coEvery { credentialRepository.hasCredentials() } returns true
        coEvery { credentialRepository.getDeviceName() } returns "Test Device"
        coEvery { credentialRepository.getDeviceType() } returns DeviceType.DESKTOP

        // Setup property stubs with every
        every { connectionManager.isConnected } returns isConnectedFlow
        every { sessionRepository.sessions } returns sessionsFlow
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Tag("unit")
    @Test
    fun `session count starts at 0`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevice)
        assertEquals(0, (state as HomeState.HasDevice).sessionCount)
    }

    @Tag("unit")
    @Test
    fun `session count updates when sessions change while connected`() = runTest {
        // Start connected
        isConnectedFlow.value = true

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Update sessions
        val sessions = listOf(
            createSessionInfo("1"),
            createSessionInfo("2"),
            createSessionInfo("3")
        )
        sessionsFlow.value = sessions
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevice)
        assertEquals(3, (state as HomeState.HasDevice).sessionCount)
    }

    @Tag("unit")
    @Test
    fun `session count shows 0 when connected with no sessions`() = runTest {
        // Start connected
        isConnectedFlow.value = true

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Sessions flow is empty
        val state = viewModel.state.value
        assertTrue(state is HomeState.HasDevice)
        assertEquals(0, (state as HomeState.HasDevice).sessionCount)
    }

    @Tag("unit")
    @Test
    fun `session count resets to 0 when disconnected`() = runTest {
        // Start connected with sessions
        isConnectedFlow.value = true
        sessionsFlow.value = listOf(createSessionInfo("1"), createSessionInfo("2"))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify initial state has sessions
        var state = viewModel.state.value
        assertTrue(state is HomeState.HasDevice)
        assertEquals(2, (state as HomeState.HasDevice).sessionCount)

        // Disconnect - count should reset to 0
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        state = viewModel.state.value
        assertTrue(state is HomeState.HasDevice)
        assertEquals(0, (state as HomeState.HasDevice).sessionCount)
    }

    @Tag("unit")
    @Test
    fun `session count updates to exact number including 0`() = runTest {
        // Start connected
        isConnectedFlow.value = true

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Test 5 sessions
        sessionsFlow.value = listOf(
            createSessionInfo("1"),
            createSessionInfo("2"),
            createSessionInfo("3"),
            createSessionInfo("4"),
            createSessionInfo("5")
        )
        testDispatcher.scheduler.advanceUntilIdle()

        var state = viewModel.state.value
        assertTrue(state is HomeState.HasDevice)
        assertEquals(5, (state as HomeState.HasDevice).sessionCount)

        // Test 12 sessions
        sessionsFlow.value = (1..12).map { createSessionInfo(it.toString()) }
        testDispatcher.scheduler.advanceUntilIdle()

        state = viewModel.state.value
        assertTrue(state is HomeState.HasDevice)
        assertEquals(12, (state as HomeState.HasDevice).sessionCount)

        // Test back to 0
        sessionsFlow.value = emptyList()
        testDispatcher.scheduler.advanceUntilIdle()

        state = viewModel.state.value
        assertTrue(state is HomeState.HasDevice)
        assertEquals(0, (state as HomeState.HasDevice).sessionCount)
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            credentialRepository = credentialRepository,
            connectionManager = connectionManager,
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepository
        )
    }

    private fun createSessionInfo(id: String): SessionInfo {
        return SessionInfo(
            id = id,
            tmuxName = "tmux_$id",
            displayName = "Session $id",
            agent = "test-agent",
            directory = "/test",
            status = SessionStatus.ACTIVE,
            createdAt = Instant.now(),
            lastActivityAt = Instant.now()
        )
    }

    /**
     * Manual test implementation of SettingsRepository to avoid MockK issues.
     * Only implements the methods needed for HomeViewModel tests.
     */
    private class TestSettingsRepository : SettingsRepository {
        private val _autoConnectEnabled = MutableStateFlow(false)
        private val _notificationSettings = MutableStateFlow(NotificationSettings())
        private val _quickButtons = MutableStateFlow<List<SettingsQuickButton>>(emptyList())
        private val _defaultAgent = MutableStateFlow<String?>(null)
        private val _showCtrlKey = MutableStateFlow(true)
        private val _showShiftKey = MutableStateFlow(true)
        private val _showAltKey = MutableStateFlow(true)
        private val _showMetaKey = MutableStateFlow(true)

        override val notificationSettings: StateFlow<NotificationSettings> = _notificationSettings
        override val showCtrlKey: StateFlow<Boolean> = _showCtrlKey
        override val showShiftKey: StateFlow<Boolean> = _showShiftKey
        override val showAltKey: StateFlow<Boolean> = _showAltKey
        override val showMetaKey: StateFlow<Boolean> = _showMetaKey
        override val quickButtons: StateFlow<List<SettingsQuickButton>> = _quickButtons
        override val defaultAgent: StateFlow<String?> = _defaultAgent
        override val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled

        override fun getDefaultAgent(): String? = _defaultAgent.value
        override fun setDefaultAgent(agent: String?) { _defaultAgent.value = agent }
        override fun validateDefaultAgent(installedAgents: List<String>): Boolean = true

        override fun getTerminalFontSize(): Float = 14f
        override fun setTerminalFontSize(size: Float) {}

        override fun getAutoConnectEnabled(): Boolean = _autoConnectEnabled.value
        override fun setAutoConnectEnabled(enabled: Boolean) { _autoConnectEnabled.value = enabled }

        override fun getShowCtrlKey(): Boolean = true
        override fun setShowCtrlKey(show: Boolean) {}
        override fun getShowShiftKey(): Boolean = true
        override fun setShowShiftKey(show: Boolean) {}
        override fun getShowAltKey(): Boolean = true
        override fun setShowAltKey(show: Boolean) {}
        override fun getShowMetaKey(): Boolean = true
        override fun setShowMetaKey(show: Boolean) {}

        override fun getEnabledQuickButtons(): List<SettingsQuickButton> = _quickButtons.value
        override fun setEnabledQuickButtons(buttons: List<SettingsQuickButton>) { _quickButtons.value = buttons }
        override fun enableQuickButton(button: SettingsQuickButton) {}
        override fun disableQuickButton(button: SettingsQuickButton) {}
        override fun reorderQuickButtons(fromIndex: Int, toIndex: Int) {}

        override fun getNotificationSettings(): NotificationSettings = _notificationSettings.value
        override fun setNotificationSettings(settings: NotificationSettings) { _notificationSettings.value = settings }
        override fun setNotificationEnabled(type: NotificationType, enabled: Boolean) {}
        override fun isNotificationEnabled(type: NotificationType): Boolean = true

        override fun resetSection(section: SettingsSection) {}
        override fun resetAll() {}
        override fun getSettingsVersion(): Int = 1
    }
}
