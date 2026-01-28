package com.ras.terminal

import app.cash.turbine.test
import com.ras.data.connection.ConnectionManager
import com.ras.data.terminal.OutputSkippedInfo
import com.ras.data.terminal.TerminalEvent
import com.ras.data.terminal.TerminalRepository
import com.ras.data.terminal.TerminalState
import com.ras.proto.KeyType
import com.ras.proto.TerminalAttached
import com.ras.proto.TerminalCommand
import com.ras.proto.TerminalDetached
import com.ras.proto.TerminalError
import com.ras.proto.TerminalOutput
import com.ras.proto.OutputSkipped
import com.ras.proto.TerminalEvent as ProtoTerminalEvent
import com.google.protobuf.ByteString
import com.ras.notifications.NotificationHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Comprehensive tests for TerminalRepository.
 * Tests all event handling, command sending, and state management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var connectionManager: ConnectionManager
    private lateinit var notificationHandler: NotificationHandler
    private lateinit var terminalEventsFlow: MutableSharedFlow<ProtoTerminalEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var repository: TerminalRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        terminalEventsFlow = MutableSharedFlow()
        isConnectedFlow = MutableStateFlow(true)

        connectionManager = mockk(relaxed = true) {
            every { terminalEvents } returns terminalEventsFlow
            every { isConnected } returns isConnectedFlow
            every { scope } returns kotlinx.coroutines.CoroutineScope(testDispatcher)
        }

        notificationHandler = mockk(relaxed = true)

        repository = TerminalRepository(connectionManager, notificationHandler, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // Initial State Tests
    // ==========================================================================

    @Test
    fun `initial state is not attached`() {
        val state = repository.state.value
        assertFalse(state.isAttached)
        assertFalse(state.isAttaching)
        assertNull(state.sessionId)
        assertEquals(0L, state.lastSequence)
    }

    @Test
    fun `initial state is not raw mode`() {
        assertFalse(repository.state.value.isRawMode)
    }

    @Test
    fun `initial state has default dimensions`() {
        val state = repository.state.value
        assertEquals(80, state.cols)
        assertEquals(24, state.rows)
    }

    // ==========================================================================
    // Attach Command Tests
    // ==========================================================================

    @Test
    fun `attach sends correct command`() = runTest {
        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(commandSlot.captured.hasAttach())
        assertEquals("abc123def456", commandSlot.captured.attach.sessionId)
        assertEquals(0L, commandSlot.captured.attach.fromSequence)
    }

    @Test
    fun `attach with fromSequence sends correct command`() = runTest {
        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.attach("abc123def456", fromSequence = 100)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(100L, commandSlot.captured.attach.fromSequence)
    }

    @Test
    fun `attach updates state to attaching`() = runTest {
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = repository.state.value
        assertTrue(state.isAttaching)
        assertEquals("abc123def456", state.sessionId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attach throws for invalid session ID - too short`() = runTest {
        repository.attach("abc123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attach throws for invalid session ID - contains special chars`() = runTest {
        repository.attach("abc-123-def!")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attach throws for invalid session ID - path traversal`() = runTest {
        repository.attach("../../../etc")
    }

    // ==========================================================================
    // Detach Command Tests
    // ==========================================================================

    @Test
    fun `detach sends correct command when attached`() = runTest {
        // First attach
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate attached event
        val attachedEvent = ProtoTerminalEvent.newBuilder()
            .setAttached(TerminalAttached.newBuilder()
                .setSessionId("abc123def456")
                .setCols(80)
                .setRows(24)
                .setCurrentSeq(0)
                .build())
            .build()
        terminalEventsFlow.emit(attachedEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.detach()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(commandSlot.captured.hasDetach())
        assertEquals("abc123def456", commandSlot.captured.detach.sessionId)
    }

    @Test
    fun `detach does nothing when not attached`() = runTest {
        repository.detach()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { connectionManager.sendTerminalCommand(any()) }
    }

    // ==========================================================================
    // Send Input Tests
    // ==========================================================================

    @Test
    fun `sendInput sends data command when attached`() = runTest {
        // Attach first
        simulateAttached()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendInput("hello".toByteArray())
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(commandSlot.captured.hasInput())
        assertEquals("abc123def456", commandSlot.captured.input.sessionId)
        assertEquals("hello", commandSlot.captured.input.data.toStringUtf8())
    }

    @Test
    fun `sendInput string sends UTF-8 encoded bytes`() = runTest {
        simulateAttached()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendInput("hello")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("hello", commandSlot.captured.input.data.toStringUtf8())
    }

    @Test
    fun `sendLine appends carriage return`() = runTest {
        simulateAttached()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendLine("ls -la")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("ls -la\r", commandSlot.captured.input.data.toStringUtf8())
    }

    @Test(expected = IllegalStateException::class)
    fun `sendInput throws when not attached`() = runTest {
        repository.sendInput("hello".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sendInput throws for data too large`() = runTest {
        simulateAttached()
        repository.sendInput(ByteArray(100_000)) // > 64KB
    }

    // ==========================================================================
    // Send Special Key Tests
    // ==========================================================================

    @Test
    fun `sendSpecialKey sends correct command`() = runTest {
        simulateAttached()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendSpecialKey(KeyType.KEY_CTRL_C)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(commandSlot.captured.input.hasSpecial())
        assertEquals(KeyType.KEY_CTRL_C, commandSlot.captured.input.special.key)
        assertEquals(0, commandSlot.captured.input.special.modifiers)
    }

    @Test
    fun `sendSpecialKey with modifiers sends correct command`() = runTest {
        simulateAttached()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendSpecialKey(KeyType.KEY_ENTER, modifiers = 5)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(5, commandSlot.captured.input.special.modifiers)
    }

    @Test(expected = IllegalStateException::class)
    fun `sendSpecialKey throws when not attached`() = runTest {
        repository.sendSpecialKey(KeyType.KEY_CTRL_C)
    }

    // ==========================================================================
    // Event Handling - Attached
    // ==========================================================================

    @Test
    fun `handleAttached updates state correctly`() = runTest {
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        val event = ProtoTerminalEvent.newBuilder()
            .setAttached(TerminalAttached.newBuilder()
                .setSessionId("abc123def456")
                .setCols(120)
                .setRows(40)
                .setBufferStartSeq(10)
                .setCurrentSeq(50)
                .build())
            .build()

        terminalEventsFlow.emit(event)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = repository.state.value
        assertTrue(state.isAttached)
        assertFalse(state.isAttaching)
        assertEquals("abc123def456", state.sessionId)
        assertEquals(120, state.cols)
        assertEquals(40, state.rows)
        assertEquals(10L, state.bufferStartSeq)
        assertEquals(50L, state.lastSequence)
        assertNull(state.error)
    }

    @Test
    fun `handleAttached emits Attached event`() = runTest {
        repository.events.test {
            repository.attach("abc123def456")
            testDispatcher.scheduler.advanceUntilIdle()

            val event = ProtoTerminalEvent.newBuilder()
                .setAttached(TerminalAttached.newBuilder()
                    .setSessionId("abc123def456")
                    .setCols(80)
                    .setRows(24)
                    .setCurrentSeq(100)
                    .build())
                .build()

            terminalEventsFlow.emit(event)
            testDispatcher.scheduler.advanceUntilIdle()

            val emitted = awaitItem() as TerminalEvent.Attached
            assertEquals("abc123def456", emitted.sessionId)
            assertEquals(80, emitted.cols)
            assertEquals(24, emitted.rows)
            assertEquals(100L, emitted.currentSeq)
        }
    }

    // ==========================================================================
    // Event Handling - Detached
    // ==========================================================================

    @Test
    fun `handleDetached updates state correctly`() = runTest {
        simulateAttached()

        val event = ProtoTerminalEvent.newBuilder()
            .setDetached(TerminalDetached.newBuilder()
                .setSessionId("abc123def456")
                .setReason("user_request")
                .build())
            .build()

        terminalEventsFlow.emit(event)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = repository.state.value
        assertFalse(state.isAttached)
        assertNull(state.error) // user_request doesn't set error
    }

    @Test
    fun `handleDetached with non-user reason sets error`() = runTest {
        simulateAttached()

        val event = ProtoTerminalEvent.newBuilder()
            .setDetached(TerminalDetached.newBuilder()
                .setSessionId("abc123def456")
                .setReason("session_killed")
                .build())
            .build()

        terminalEventsFlow.emit(event)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(repository.state.value.error)
        assertEquals("session_killed", repository.state.value.error?.message)
    }

    @Test
    fun `handleDetached emits Detached event`() = runTest {
        simulateAttached()

        repository.events.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setDetached(TerminalDetached.newBuilder()
                    .setSessionId("abc123def456")
                    .setReason("user_request")
                    .build())
                .build()

            terminalEventsFlow.emit(event)
            testDispatcher.scheduler.advanceUntilIdle()

            val emitted = awaitItem() as TerminalEvent.Detached
            assertEquals("abc123def456", emitted.sessionId)
            assertEquals("user_request", emitted.reason)
        }
    }

    // ==========================================================================
    // Event Handling - Output
    // ==========================================================================

    @Test
    fun `handleOutput emits to output flow`() = runTest {
        simulateAttached()

        repository.output.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setOutput(TerminalOutput.newBuilder()
                    .setSessionId("abc123def456")
                    .setData(ByteString.copyFromUtf8("Hello World"))
                    .setSequence(1)
                    .setPartial(false)
                    .build())
                .build()

            terminalEventsFlow.emit(event)
            testDispatcher.scheduler.advanceUntilIdle()

            val bytes = awaitItem()
            assertEquals("Hello World", String(bytes))
        }
    }

    @Test
    fun `handleOutput updates lastSequence`() = runTest {
        simulateAttached()

        val event = ProtoTerminalEvent.newBuilder()
            .setOutput(TerminalOutput.newBuilder()
                .setSessionId("abc123def456")
                .setData(ByteString.copyFromUtf8("data"))
                .setSequence(42)
                .build())
            .build()

        terminalEventsFlow.emit(event)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(42L, repository.state.value.lastSequence)
    }

    @Test
    fun `handleOutput emits Output event`() = runTest {
        simulateAttached()

        repository.events.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setOutput(TerminalOutput.newBuilder()
                    .setSessionId("abc123def456")
                    .setData(ByteString.copyFromUtf8("test"))
                    .setSequence(5)
                    .setPartial(true)
                    .build())
                .build()

            terminalEventsFlow.emit(event)
            testDispatcher.scheduler.advanceUntilIdle()

            val emitted = awaitItem() as TerminalEvent.Output
            assertEquals("abc123def456", emitted.sessionId)
            assertEquals("test", String(emitted.data))
            assertEquals(5L, emitted.sequence)
            assertTrue(emitted.partial)
        }
    }

    // ==========================================================================
    // Event Handling - Error
    // ==========================================================================

    @Test
    fun `handleError updates state correctly`() = runTest {
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        val event = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setSessionId("abc123def456")
                .setErrorCode("SESSION_NOT_FOUND")
                .setMessage("Session not found")
                .build())
            .build()

        terminalEventsFlow.emit(event)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = repository.state.value
        assertFalse(state.isAttaching)
        assertNotNull(state.error)
        assertEquals("SESSION_NOT_FOUND", state.error?.code)
        assertEquals("Session not found", state.error?.message)
    }

    @Test
    fun `handleError emits Error event`() = runTest {
        repository.events.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setError(TerminalError.newBuilder()
                    .setSessionId("abc123def456")
                    .setErrorCode("RATE_LIMITED")
                    .setMessage("Too many requests")
                    .build())
                .build()

            terminalEventsFlow.emit(event)
            testDispatcher.scheduler.advanceUntilIdle()

            val emitted = awaitItem() as TerminalEvent.Error
            assertEquals("abc123def456", emitted.sessionId)
            assertEquals("RATE_LIMITED", emitted.code)
            assertEquals("Too many requests", emitted.message)
        }
    }

    // ==========================================================================
    // Event Handling - Output Skipped
    // ==========================================================================

    @Test
    fun `handleSkipped updates state correctly`() = runTest {
        simulateAttached()

        val event = ProtoTerminalEvent.newBuilder()
            .setSkipped(OutputSkipped.newBuilder()
                .setSessionId("abc123def456")
                .setFromSequence(10)
                .setToSequence(50)
                .setBytesSkipped(40000)
                .build())
            .build()

        terminalEventsFlow.emit(event)
        testDispatcher.scheduler.advanceUntilIdle()

        val skipped = repository.state.value.outputSkipped
        assertNotNull(skipped)
        assertEquals(10L, skipped?.fromSequence)
        assertEquals(50L, skipped?.toSequence)
        assertEquals(40000, skipped?.bytesSkipped)
    }

    @Test
    fun `handleSkipped emits OutputSkipped event`() = runTest {
        simulateAttached()

        repository.events.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setSkipped(OutputSkipped.newBuilder()
                    .setSessionId("abc123def456")
                    .setFromSequence(5)
                    .setToSequence(15)
                    .setBytesSkipped(10000)
                    .build())
                .build()

            terminalEventsFlow.emit(event)
            testDispatcher.scheduler.advanceUntilIdle()

            val emitted = awaitItem() as TerminalEvent.OutputSkipped
            assertEquals("abc123def456", emitted.sessionId)
            assertEquals(5L, emitted.fromSequence)
            assertEquals(15L, emitted.toSequence)
            assertEquals(10000, emitted.bytesSkipped)
        }
    }

    // ==========================================================================
    // State Management Tests
    // ==========================================================================

    @Test
    fun `setRawMode updates state`() {
        repository.setRawMode(true)
        assertTrue(repository.state.value.isRawMode)

        repository.setRawMode(false)
        assertFalse(repository.state.value.isRawMode)
    }

    @Test
    fun `toggleRawMode toggles state`() {
        assertFalse(repository.state.value.isRawMode)

        repository.toggleRawMode()
        assertTrue(repository.state.value.isRawMode)

        repository.toggleRawMode()
        assertFalse(repository.state.value.isRawMode)
    }

    @Test
    fun `clearError clears error state`() = runTest {
        // Set an error
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        val errorEvent = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setErrorCode("ERROR")
                .setMessage("message")
                .build())
            .build()
        terminalEventsFlow.emit(errorEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(repository.state.value.error)

        repository.clearError()
        assertNull(repository.state.value.error)
    }

    @Test
    fun `clearOutputSkipped clears skipped state`() = runTest {
        simulateAttached()

        val skippedEvent = ProtoTerminalEvent.newBuilder()
            .setSkipped(OutputSkipped.newBuilder()
                .setFromSequence(1)
                .setToSequence(10)
                .setBytesSkipped(1000)
                .build())
            .build()
        terminalEventsFlow.emit(skippedEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(repository.state.value.outputSkipped)

        repository.clearOutputSkipped()
        assertNull(repository.state.value.outputSkipped)
    }

    @Test
    fun `reset clears all state`() = runTest {
        simulateAttached()
        repository.setRawMode(true)

        repository.reset()

        val state = repository.state.value
        assertNull(state.sessionId)
        assertFalse(state.isAttached)
        assertFalse(state.isAttaching)
        assertFalse(state.isRawMode)
        assertEquals(0L, state.lastSequence)
    }

    // ==========================================================================
    // canSendInput Tests
    // ==========================================================================

    @Test
    fun `canSendInput returns false when not attached`() {
        assertFalse(repository.state.value.canSendInput)
    }

    @Test
    fun `canSendInput returns true when attached without error`() = runTest {
        simulateAttached()
        assertTrue(repository.state.value.canSendInput)
    }

    @Test
    fun `canSendInput returns false when has error`() = runTest {
        simulateAttached()

        val errorEvent = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setErrorCode("ERROR")
                .setMessage("message")
                .build())
            .build()
        terminalEventsFlow.emit(errorEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(repository.state.value.canSendInput)
    }

    // ==========================================================================
    // OutputSkippedInfo.displayText Tests
    // ==========================================================================

    @Test
    fun `OutputSkippedInfo displayText shows bytes for small values`() {
        val info = com.ras.data.terminal.OutputSkippedInfo(
            fromSequence = 0,
            toSequence = 10,
            bytesSkipped = 500
        )
        assertEquals("~500 bytes output skipped", info.displayText)
    }

    @Test
    fun `OutputSkippedInfo displayText shows KB for medium values`() {
        val info = com.ras.data.terminal.OutputSkippedInfo(
            fromSequence = 0,
            toSequence = 10,
            bytesSkipped = 5120 // 5KB
        )
        assertEquals("~5KB output skipped", info.displayText)
    }

    @Test
    fun `OutputSkippedInfo displayText shows MB for large values`() {
        val info = com.ras.data.terminal.OutputSkippedInfo(
            fromSequence = 0,
            toSequence = 10,
            bytesSkipped = 2 * 1024 * 1024 // 2MB
        )
        assertEquals("~2MB output skipped", info.displayText)
    }

    @Test
    fun `OutputSkippedInfo displayText boundary at 1024 bytes`() {
        // At exactly 1024, should show KB
        val info = com.ras.data.terminal.OutputSkippedInfo(
            fromSequence = 0,
            toSequence = 10,
            bytesSkipped = 1024
        )
        assertEquals("~1KB output skipped", info.displayText)
    }

    @Test
    fun `OutputSkippedInfo displayText boundary below 1024 bytes`() {
        val info = com.ras.data.terminal.OutputSkippedInfo(
            fromSequence = 0,
            toSequence = 10,
            bytesSkipped = 1023
        )
        assertEquals("~1023 bytes output skipped", info.displayText)
    }

    // ==========================================================================
    // TerminalErrorCodes Tests
    // ==========================================================================

    @Test
    fun `TerminalErrorCodes getDisplayMessage returns correct messages`() {
        assertEquals("Session not found",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("SESSION_NOT_FOUND", "default"))
        assertEquals("Session is being terminated",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("SESSION_KILLING", "default"))
        assertEquals("Not attached to terminal",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("NOT_ATTACHED", "default"))
        assertEquals("Already attached to this session",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("ALREADY_ATTACHED", "default"))
        assertEquals("Reconnection sequence not available",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("INVALID_SEQUENCE", "default"))
        assertEquals("Terminal communication error",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("PIPE_ERROR", "default"))
        assertEquals("Failed to setup terminal",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("PIPE_SETUP_FAILED", "default"))
        assertEquals("Too many inputs, please slow down",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("RATE_LIMITED", "default"))
        assertEquals("Input too large",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("INPUT_TOO_LARGE", "default"))
        assertEquals("Invalid session ID",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("INVALID_SESSION_ID", "default"))
    }

    @Test
    fun `TerminalErrorCodes getDisplayMessage returns default for unknown code`() {
        assertEquals("Custom error message",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("UNKNOWN_CODE", "Custom error message"))
    }

    // ==========================================================================
    // QuickButton Validation Tests
    // ==========================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `QuickButton requires keyType or character`() {
        com.ras.data.terminal.QuickButton(
            id = "test",
            label = "Test",
            keyType = null,
            character = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `QuickButton requires non-empty id`() {
        com.ras.data.terminal.QuickButton(
            id = "",
            label = "Test",
            character = "x"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `QuickButton requires non-empty label`() {
        com.ras.data.terminal.QuickButton(
            id = "test",
            label = "",
            character = "x"
        )
    }

    @Test
    fun `QuickButton accepts keyType only`() {
        val button = com.ras.data.terminal.QuickButton(
            id = "ctrl_c",
            label = "Ctrl+C",
            keyType = KeyType.KEY_CTRL_C
        )
        assertEquals(KeyType.KEY_CTRL_C, button.keyType)
        assertNull(button.character)
    }

    @Test
    fun `QuickButton accepts character only`() {
        val button = com.ras.data.terminal.QuickButton(
            id = "y",
            label = "Y",
            character = "y"
        )
        assertNull(button.keyType)
        assertEquals("y", button.character)
    }

    // ==========================================================================
    // TerminalState.canSendInput Tests
    // ==========================================================================

    @Test
    fun `canSendInput is false when sessionId is null`() {
        val state = com.ras.data.terminal.TerminalState(
            sessionId = null,
            isAttached = true,
            error = null
        )
        assertFalse(state.canSendInput)
    }

    @Test
    fun `canSendInput is false when not attached`() {
        val state = com.ras.data.terminal.TerminalState(
            sessionId = "abc123def456",
            isAttached = false,
            error = null
        )
        assertFalse(state.canSendInput)
    }

    @Test
    fun `canSendInput is false when error present`() {
        val state = com.ras.data.terminal.TerminalState(
            sessionId = "abc123def456",
            isAttached = true,
            error = com.ras.data.terminal.TerminalErrorInfo("ERROR", "msg", null)
        )
        assertFalse(state.canSendInput)
    }

    @Test
    fun `canSendInput is true when attached with sessionId and no error`() {
        val state = com.ras.data.terminal.TerminalState(
            sessionId = "abc123def456",
            isAttached = true,
            error = null
        )
        assertTrue(state.canSendInput)
    }

    // ==========================================================================
    // TerminalInputValidator Tests
    // ==========================================================================

    @Test
    fun `TerminalInputValidator accepts valid input size`() {
        val data = ByteArray(65536) // Exactly 64KB
        assertTrue(com.ras.data.terminal.TerminalInputValidator.isValidInputSize(data))
    }

    @Test
    fun `TerminalInputValidator rejects oversized input`() {
        val data = ByteArray(65537) // 64KB + 1
        assertFalse(com.ras.data.terminal.TerminalInputValidator.isValidInputSize(data))
    }

    @Test
    fun `TerminalInputValidator accepts empty input`() {
        val data = ByteArray(0)
        assertTrue(com.ras.data.terminal.TerminalInputValidator.isValidInputSize(data))
    }

    // ==========================================================================
    // Error Event with Empty Session ID Tests
    // ==========================================================================

    @Test
    fun `error event with empty sessionId converts to null`() = runTest {
        repository.events.test {
            val errorEvent = ProtoTerminalEvent.newBuilder()
                .setError(TerminalError.newBuilder()
                    .setSessionId("")
                    .setErrorCode("TEST_ERROR")
                    .setMessage("test")
                    .build())
                .build()
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem() as TerminalEvent.Error
            assertNull(event.sessionId)
        }
    }

    @Test
    fun `error state with empty sessionId stores null`() = runTest {
        val errorEvent = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setSessionId("")
                .setErrorCode("TEST_ERROR")
                .setMessage("test")
                .build())
            .build()
        terminalEventsFlow.emit(errorEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.state.value.error?.sessionId)
    }

    // ==========================================================================
    // Helper Methods
    // ==========================================================================

    private suspend fun simulateAttached() {
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        val attachedEvent = ProtoTerminalEvent.newBuilder()
            .setAttached(TerminalAttached.newBuilder()
                .setSessionId("abc123def456")
                .setCols(80)
                .setRows(24)
                .setCurrentSeq(0)
                .build())
            .build()
        terminalEventsFlow.emit(attachedEvent)
        testDispatcher.scheduler.advanceUntilIdle()
    }
}
