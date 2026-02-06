package com.ras.terminal

import app.cash.turbine.test
import com.ras.data.connection.ConnectionManager
import com.ras.data.terminal.OutputSkippedInfo
import com.ras.data.terminal.TerminalAttachException
import com.ras.data.terminal.TerminalEvent
import com.ras.data.terminal.TerminalRepository
import com.ras.data.terminal.TerminalState
import com.ras.proto.KeyType
import com.ras.proto.TerminalAttached
import com.ras.proto.TerminalCommand
import com.ras.proto.TerminalDetached
import com.ras.proto.TerminalError
import com.ras.proto.TerminalOutput
import com.ras.proto.TerminalSnapshot
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for TerminalRepository.
 * Tests all event handling, command sending, and state management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var connectionManager: ConnectionManager
    private lateinit var notificationHandler: NotificationHandler
    private lateinit var terminalEventsFlow: MutableSharedFlow<ProtoTerminalEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var repository: TerminalRepository

    @BeforeEach
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

        // Use short timeout (100ms) for tests instead of 10s production timeout
        repository = TerminalRepository(connectionManager, notificationHandler, testDispatcher, attachTimeoutMs = 100L)
    }

    @AfterEach
    fun tearDown() {
        repository.close()  // Cancel background coroutines
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // Initial State Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `initial state is not attached`() {
        val state = repository.state.value
        assertFalse(state.isAttached)
        assertFalse(state.isAttaching)
        assertNull(state.sessionId)
        assertEquals(0L, state.lastSequence)
    }

    @Tag("unit")
    @Test
    fun `initial state is not raw mode`() {
        assertFalse(repository.state.value.isRawMode)
    }

    @Tag("unit")
    @Test
    fun `initial state has default dimensions`() {
        val state = repository.state.value
        assertEquals(80, state.cols)
        assertEquals(24, state.rows)
    }

    // ==========================================================================
    // Attach Command Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `attach sends correct command`() = runTest(testDispatcher, timeout = 1.seconds) {
        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        // Launch attach - use runCurrent() to start it (runs until it suspends)
        val job = launch {
            repository.attach("abc123def456")
        }


        assertTrue(commandSlot.captured.hasAttach())
        assertEquals("abc123def456", commandSlot.captured.attach.sessionId)
        assertEquals(0L, commandSlot.captured.attach.fromSequence)

        // Complete the attach by emitting response
        terminalEventsFlow.emit(
            ProtoTerminalEvent.newBuilder()
                .setAttached(TerminalAttached.newBuilder().setSessionId("abc123def456").build())
                .build()
        )

        job.join()

        // Close repository to cancel background coroutines before test ends
        repository.close()
    }

    @Tag("unit")
    @Test
    fun `attach with fromSequence sends correct command`() = runTest(testDispatcher, timeout = 1.seconds) {
        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        val job = launch {
            repository.attach("abc123def456", fromSequence = 100)
        }


        assertEquals(100L, commandSlot.captured.attach.fromSequence)

        // Complete the attach
        val event = ProtoTerminalEvent.newBuilder()
            .setAttached(TerminalAttached.newBuilder().setSessionId("abc123def456").build())
            .build()
        terminalEventsFlow.emit(event)

        job.join()
    }

    @Tag("unit")
    @Test
    fun `attach updates state to attaching`() = runTest(testDispatcher, timeout = 1.seconds) {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        val job = launch {
            repository.attach("abc123def456")
        }
        // With UnconfinedTestDispatcher, coroutine runs until suspension point

        // Check attaching state while waiting for response
        val state = repository.state.value
        assertTrue(state.isAttaching)
        assertEquals("abc123def456", state.sessionId)

        // Complete the attach by emitting response
        terminalEventsFlow.emit(
            ProtoTerminalEvent.newBuilder()
                .setAttached(TerminalAttached.newBuilder().setSessionId("abc123def456").build())
                .build()
        )
        job.join()
    }

    @Tag("unit")
    @Test
    fun `attach throws for invalid session ID - too short`() = runTest(testDispatcher, timeout = 1.seconds) {
        assertFailsWith<IllegalArgumentException> {
            repository.attach("abc123")
        }
    }

    @Tag("unit")
    @Test
    fun `attach throws for invalid session ID - contains special chars`() = runTest(testDispatcher, timeout = 1.seconds) {
        assertFailsWith<IllegalArgumentException> {
            repository.attach("abc-123-def!")
        }
    }

    @Tag("unit")
    @Test
    fun `attach throws for invalid session ID - path traversal`() = runTest(testDispatcher, timeout = 1.seconds) {
        assertFailsWith<IllegalArgumentException> {
            repository.attach("../../../etc")
        }
    }

    // ==========================================================================
    // Attach Request-Response Correlation Tests (CompletableDeferred pattern)
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `attach completes when daemon responds with TerminalAttached`() = runTest(testDispatcher, timeout = 1.seconds) {
        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        // Launch attach in background and immediately respond
        val attachJob = async {
            repository.attach("abc123def456")
        }

        // Wait for command to be sent


        // Simulate daemon response
        val attachedEvent = ProtoTerminalEvent.newBuilder()
            .setAttached(TerminalAttached.newBuilder()
                .setSessionId("abc123def456")
                .setCols(80)
                .setRows(24)
                .setCurrentSeq(0)
                .build())
            .build()
        terminalEventsFlow.emit(attachedEvent)


        // Verify attach completes without exception
        attachJob.await()

        // Verify final state
        val state = repository.state.value
        assertTrue(state.isAttached)
        assertFalse(state.isAttaching)
        assertEquals("abc123def456", state.sessionId)
    }

    @Tag("unit")
    @Test
    fun `attach throws TerminalAttachException when daemon responds with error`() = runTest(testDispatcher, timeout = 1.seconds) {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        // Launch attach in background
        val attachJob = async {
            try {
                repository.attach("abc123def456")
                fail("Expected TerminalAttachException")
            } catch (e: TerminalAttachException) {
                assertEquals("SESSION_NOT_FOUND", e.code)
                assertEquals("Session not found", e.message)
            }
        }

        // Wait for command to be sent


        // Simulate daemon error response
        val errorEvent = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setSessionId("abc123def456")
                .setErrorCode("SESSION_NOT_FOUND")
                .setMessage("Session not found")
                .build())
            .build()
        terminalEventsFlow.emit(errorEvent)


        // Wait for exception to propagate
        attachJob.await()

        // Verify state reflects error
        val state = repository.state.value
        assertFalse(state.isAttached)
        assertFalse(state.isAttaching)
        assertNotNull(state.error)
    }

    @Tag("unit")
    @Test
    fun `attach throws TimeoutCancellationException when daemon does not respond`() = runTest(testDispatcher, timeout = 1.seconds) {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        // Launch attach in background
        val attachJob = async {
            try {
                repository.attach("abc123def456")
                fail("Expected TimeoutCancellationException")
            } catch (e: TimeoutCancellationException) {
                // Expected
            }
        }

        // Advance past the timeout (10 seconds)
        advanceTimeBy(11_000)


        // Wait for timeout to propagate
        attachJob.await()

        // Verify state reflects timeout
        val state = repository.state.value
        assertFalse(state.isAttached)
        assertFalse(state.isAttaching)
        assertNotNull(state.error)
        assertEquals("ATTACH_TIMEOUT", state.error?.code)
    }

    @Tag("unit")
    @Test
    fun `attach resets state when sendTerminalCommand throws unexpected exception`() = runTest(testDispatcher, timeout = 1.seconds) {
        // Simulate connection lost - sendTerminalCommand throws
        coEvery { connectionManager.sendTerminalCommand(any()) } throws
            IllegalStateException("No encryption codec available")

        try {
            repository.attach("abc123def456")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("No encryption codec available", e.message)
        }

        // Verify state is properly reset, not stuck in isAttaching
        val state = repository.state.value
        assertFalse(state.isAttached)
        assertFalse(state.isAttaching)  // Key assertion - must not be stuck
        assertNotNull(state.error)
        assertEquals("ATTACH_FAILED", state.error?.code)
    }

    @Tag("unit")
    @Test
    fun `attach allows retry after sendTerminalCommand throws`() = runTest(testDispatcher, timeout = 1.seconds) {
        // First attempt - connection lost
        coEvery { connectionManager.sendTerminalCommand(any()) } throws
            IllegalStateException("No encryption codec available")

        try {
            repository.attach("abc123def456")
        } catch (e: IllegalStateException) {
            // Expected
        }

        // Verify we can retry (not stuck in isAttaching)
        assertFalse(repository.state.value.isAttaching)

        // Second attempt - succeeds
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        val attachJob = launch {
            repository.attach("abc123def456")
        }

        // Simulate daemon response
        val attachedEvent = ProtoTerminalEvent.newBuilder()
            .setAttached(TerminalAttached.newBuilder()
                .setSessionId("abc123def456")
                .setCols(80)
                .setRows(24)
                .build())
            .build()
        terminalEventsFlow.emit(attachedEvent)

        attachJob.join()

        // Verify successful attach after retry
        assertTrue(repository.state.value.isAttached)
    }

    @Tag("unit")
    @Test
    fun `concurrent attach calls to same session are no-op`() = runTest(testDispatcher, timeout = 1.seconds) {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        // First attach - will be pending
        val firstAttachJob = launch {
            repository.attach("abc123def456")
        }


        // Second attach to same session - should return immediately (no-op)
        val secondAttachJob = launch {
            repository.attach("abc123def456")
        }


        // Complete the first attach
        val attachedEvent = ProtoTerminalEvent.newBuilder()
            .setAttached(TerminalAttached.newBuilder()
                .setSessionId("abc123def456")
                .setCols(80)
                .setRows(24)
                .build())
            .build()
        terminalEventsFlow.emit(attachedEvent)


        firstAttachJob.join()
        secondAttachJob.join()

        // Only one command should have been sent
        coVerify(exactly = 1) { connectionManager.sendTerminalCommand(any()) }
    }

    // Test removed: Implementation uses Mutex which serializes attach calls
    // rather than throwing on concurrent calls. The original test assumption was wrong.

    // ==========================================================================
    // Detach Command Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `detach sends correct command when attached`() = runTest(testDispatcher, timeout = 1.seconds) {
        // First attach - use launch so we can emit response
        val attachJob = launch { repository.attach("abc123def456") }

        // Simulate attached event
        terminalEventsFlow.emit(
            ProtoTerminalEvent.newBuilder()
                .setAttached(TerminalAttached.newBuilder()
                    .setSessionId("abc123def456")
                    .setCols(80)
                    .setRows(24)
                    .setCurrentSeq(0)
                    .build())
                .build()
        )
        attachJob.join()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.detach()

        assertTrue(commandSlot.captured.hasDetach())
        assertEquals("abc123def456", commandSlot.captured.detach.sessionId)
    }

    @Tag("unit")
    @Test
    fun `detach does nothing when not attached`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.detach()


        coVerify(exactly = 0) { connectionManager.sendTerminalCommand(any()) }
    }

    // ==========================================================================
    // Send Input Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `sendInput sends data command when attached`() = runTest(testDispatcher, timeout = 1.seconds) {
        // Attach first
        simulateAttachedState()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendInput("hello".toByteArray())


        assertTrue(commandSlot.captured.hasInput())
        assertEquals("abc123def456", commandSlot.captured.input.sessionId)
        assertEquals("hello", commandSlot.captured.input.data.toStringUtf8())
    }

    @Tag("unit")
    @Test
    fun `sendInput string sends UTF-8 encoded bytes`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendInput("hello")


        assertEquals("hello", commandSlot.captured.input.data.toStringUtf8())
    }

    @Tag("unit")
    @Test
    fun `sendLine appends carriage return`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendLine("ls -la")


        assertEquals("ls -la\r", commandSlot.captured.input.data.toStringUtf8())
    }

    @Tag("unit")
    @Test
    fun `sendInput throws when not attached`() = runTest(testDispatcher, timeout = 1.seconds) {
        assertFailsWith<IllegalStateException> {
            repository.sendInput("hello".toByteArray())
        }
    }

    @Tag("unit")
    @Test
    fun `sendInput throws for data too large`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()
        assertFailsWith<IllegalArgumentException> {
            repository.sendInput(ByteArray(100_000)) // > 64KB
        }
    }

    // ==========================================================================
    // Send Special Key Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `sendSpecialKey sends correct command`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendSpecialKey(KeyType.KEY_CTRL_C)


        assertTrue(commandSlot.captured.input.hasSpecial())
        assertEquals(KeyType.KEY_CTRL_C, commandSlot.captured.input.special.key)
        assertEquals(0, commandSlot.captured.input.special.modifiers)
    }

    @Tag("unit")
    @Test
    fun `sendSpecialKey with modifiers sends correct command`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val commandSlot = slot<TerminalCommand>()
        coEvery { connectionManager.sendTerminalCommand(capture(commandSlot)) } returns Unit

        repository.sendSpecialKey(KeyType.KEY_ENTER, modifiers = 5)


        assertEquals(5, commandSlot.captured.input.special.modifiers)
    }

    @Tag("unit")
    @Test
    fun `sendSpecialKey throws when not attached`() = runTest(testDispatcher, timeout = 1.seconds) {
        assertFailsWith<IllegalStateException> {
            repository.sendSpecialKey(KeyType.KEY_CTRL_C)
        }
    }

    // ==========================================================================
    // Event Handling - Attached
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `handleAttached updates state correctly`() = runTest(testDispatcher, timeout = 1.seconds) {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        val attachJob = launch {
            repository.attach("abc123def456")
        }


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

        attachJob.join()

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

    @Tag("unit")
    @Test
    fun `handleAttached emits Attached event`() = runTest(testDispatcher, timeout = 1.seconds) {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        repository.events.test {
            val attachJob = launch {
                repository.attach("abc123def456")
            }
    

            val event = ProtoTerminalEvent.newBuilder()
                .setAttached(TerminalAttached.newBuilder()
                    .setSessionId("abc123def456")
                    .setCols(80)
                    .setRows(24)
                    .setCurrentSeq(100)
                    .build())
                .build()

            terminalEventsFlow.emit(event)
    
            attachJob.join()

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

    @Tag("unit")
    @Test
    fun `handleDetached updates state correctly`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val event = ProtoTerminalEvent.newBuilder()
            .setDetached(TerminalDetached.newBuilder()
                .setSessionId("abc123def456")
                .setReason("user_request")
                .build())
            .build()

        terminalEventsFlow.emit(event)


        val state = repository.state.value
        assertFalse(state.isAttached)
        assertNull(state.error) // user_request doesn't set error
    }

    @Tag("unit")
    @Test
    fun `handleDetached with non-user reason sets error`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val event = ProtoTerminalEvent.newBuilder()
            .setDetached(TerminalDetached.newBuilder()
                .setSessionId("abc123def456")
                .setReason("session_killed")
                .build())
            .build()

        terminalEventsFlow.emit(event)


        assertNotNull(repository.state.value.error)
        assertEquals("session_killed", repository.state.value.error?.message)
    }

    @Tag("unit")
    @Test
    fun `handleDetached emits Detached event`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        repository.events.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setDetached(TerminalDetached.newBuilder()
                    .setSessionId("abc123def456")
                    .setReason("user_request")
                    .build())
                .build()

            terminalEventsFlow.emit(event)
    

            val emitted = awaitItem() as TerminalEvent.Detached
            assertEquals("abc123def456", emitted.sessionId)
            assertEquals("user_request", emitted.reason)
        }
    }

    // ==========================================================================
    // Event Handling - Output
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `handleOutput emits to output flow`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

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
    

            val bytes = awaitItem()
            assertEquals("Hello World", String(bytes))
        }
    }

    @Tag("unit")
    @Test
    fun `handleOutput updates lastSequence`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val event = ProtoTerminalEvent.newBuilder()
            .setOutput(TerminalOutput.newBuilder()
                .setSessionId("abc123def456")
                .setData(ByteString.copyFromUtf8("data"))
                .setSequence(42)
                .build())
            .build()

        terminalEventsFlow.emit(event)


        assertEquals(42L, repository.state.value.lastSequence)
    }

    @Tag("unit")
    @Test
    fun `handleOutput emits Output event`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

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

    @Tag("unit")
    @Test
    fun `handleError updates state correctly`() = runTest(testDispatcher, timeout = 1.seconds) {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        val attachJob = async {
            try {
                repository.attach("abc123def456")
            } catch (e: TerminalAttachException) {
                // Expected
            }
        }


        val event = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setSessionId("abc123def456")
                .setErrorCode("SESSION_NOT_FOUND")
                .setMessage("Session not found")
                .build())
            .build()

        terminalEventsFlow.emit(event)

        attachJob.await()

        val state = repository.state.value
        assertFalse(state.isAttaching)
        assertNotNull(state.error)
        assertEquals("SESSION_NOT_FOUND", state.error?.code)
        assertEquals("Session not found", state.error?.message)
    }

    @Tag("unit")
    @Test
    fun `handleError emits Error event`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setError(TerminalError.newBuilder()
                    .setSessionId("abc123def456")
                    .setErrorCode("RATE_LIMITED")
                    .setMessage("Too many requests")
                    .build())
                .build()

            terminalEventsFlow.emit(event)
    

            val emitted = awaitItem() as TerminalEvent.Error
            assertEquals("abc123def456", emitted.sessionId)
            assertEquals("RATE_LIMITED", emitted.code)
            assertEquals("Too many requests", emitted.message)
        }
    }

    // ==========================================================================
    // Event Handling - Output Skipped
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `handleSkipped updates state correctly`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val event = ProtoTerminalEvent.newBuilder()
            .setSkipped(OutputSkipped.newBuilder()
                .setSessionId("abc123def456")
                .setFromSequence(10)
                .setToSequence(50)
                .setBytesSkipped(40000)
                .build())
            .build()

        terminalEventsFlow.emit(event)


        val skipped = repository.state.value.outputSkipped
        assertNotNull(skipped)
        assertEquals(10L, skipped?.fromSequence)
        assertEquals(50L, skipped?.toSequence)
        assertEquals(40000, skipped?.bytesSkipped)
    }

    @Tag("unit")
    @Test
    fun `handleSkipped emits OutputSkipped event`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

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

    @Tag("unit")
    @Test
    fun `setRawMode updates state`() {
        repository.setRawMode(true)
        assertTrue(repository.state.value.isRawMode)

        repository.setRawMode(false)
        assertFalse(repository.state.value.isRawMode)
    }

    @Tag("unit")
    @Test
    fun `toggleRawMode toggles state`() {
        assertFalse(repository.state.value.isRawMode)

        repository.toggleRawMode()
        assertTrue(repository.state.value.isRawMode)

        repository.toggleRawMode()
        assertFalse(repository.state.value.isRawMode)
    }

    @Tag("unit")
    @Test
    fun `clearError clears error state`() = runTest(testDispatcher, timeout = 1.seconds) {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        // Set an error by attempting attach and receiving error response
        val attachJob = async {
            try {
                repository.attach("abc123def456")
            } catch (e: TerminalAttachException) {
                // Expected
            }
        }


        val errorEvent = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setErrorCode("ERROR")
                .setMessage("message")
                .build())
            .build()
        terminalEventsFlow.emit(errorEvent)

        attachJob.await()

        assertNotNull(repository.state.value.error)

        repository.clearError()
        assertNull(repository.state.value.error)
    }

    @Tag("unit")
    @Test
    fun `clearOutputSkipped clears skipped state`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val skippedEvent = ProtoTerminalEvent.newBuilder()
            .setSkipped(OutputSkipped.newBuilder()
                .setFromSequence(1)
                .setToSequence(10)
                .setBytesSkipped(1000)
                .build())
            .build()
        terminalEventsFlow.emit(skippedEvent)


        assertNotNull(repository.state.value.outputSkipped)

        repository.clearOutputSkipped()
        assertNull(repository.state.value.outputSkipped)
    }

    @Tag("unit")
    @Test
    fun `reset clears all state`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()
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

    @Tag("unit")
    @Test
    fun `canSendInput returns false when not attached`() {
        assertFalse(repository.state.value.canSendInput)
    }

    @Tag("unit")
    @Test
    fun `canSendInput returns true when attached without error`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()
        assertTrue(repository.state.value.canSendInput)
    }

    @Tag("unit")
    @Test
    fun `canSendInput returns false when has error`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val errorEvent = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setErrorCode("ERROR")
                .setMessage("message")
                .build())
            .build()
        terminalEventsFlow.emit(errorEvent)


        assertFalse(repository.state.value.canSendInput)
    }

    // ==========================================================================
    // OutputSkippedInfo.displayText Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `OutputSkippedInfo displayText shows bytes for small values`() {
        val info = com.ras.data.terminal.OutputSkippedInfo(
            fromSequence = 0,
            toSequence = 10,
            bytesSkipped = 500
        )
        assertEquals("~500 bytes output skipped", info.displayText)
    }

    @Tag("unit")
    @Test
    fun `OutputSkippedInfo displayText shows KB for medium values`() {
        val info = com.ras.data.terminal.OutputSkippedInfo(
            fromSequence = 0,
            toSequence = 10,
            bytesSkipped = 5120 // 5KB
        )
        assertEquals("~5KB output skipped", info.displayText)
    }

    @Tag("unit")
    @Test
    fun `OutputSkippedInfo displayText shows MB for large values`() {
        val info = com.ras.data.terminal.OutputSkippedInfo(
            fromSequence = 0,
            toSequence = 10,
            bytesSkipped = 2 * 1024 * 1024 // 2MB
        )
        assertEquals("~2MB output skipped", info.displayText)
    }

    @Tag("unit")
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

    @Tag("unit")
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

    @Tag("unit")
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

    @Tag("unit")
    @Test
    fun `TerminalErrorCodes getDisplayMessage returns default for unknown code`() {
        assertEquals("Custom error message",
            com.ras.data.terminal.TerminalErrorCodes.getDisplayMessage("UNKNOWN_CODE", "Custom error message"))
    }

    // ==========================================================================
    // QuickButton Validation Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `QuickButton requires keyType or character`() {
        assertThrows(IllegalArgumentException::class.java) {
            com.ras.data.terminal.QuickButton(
                id = "test",
                label = "Test",
                keyType = null,
                character = null
            )
        }
    }

    @Tag("unit")
    @Test
    fun `QuickButton requires non-empty id`() {
        assertThrows(IllegalArgumentException::class.java) {
            com.ras.data.terminal.QuickButton(
                id = "",
                label = "Test",
                character = "x"
            )
        }
    }

    @Tag("unit")
    @Test
    fun `QuickButton requires non-empty label`() {
        assertThrows(IllegalArgumentException::class.java) {
            com.ras.data.terminal.QuickButton(
                id = "test",
                label = "",
                character = "x"
            )
        }
    }

    @Tag("unit")
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

    @Tag("unit")
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

    @Tag("unit")
    @Test
    fun `canSendInput is false when sessionId is null`() {
        val state = com.ras.data.terminal.TerminalState(
            sessionId = null,
            isAttached = true,
            error = null
        )
        assertFalse(state.canSendInput)
    }

    @Tag("unit")
    @Test
    fun `canSendInput is false when not attached`() {
        val state = com.ras.data.terminal.TerminalState(
            sessionId = "abc123def456",
            isAttached = false,
            error = null
        )
        assertFalse(state.canSendInput)
    }

    @Tag("unit")
    @Test
    fun `canSendInput is false when error present`() {
        val state = com.ras.data.terminal.TerminalState(
            sessionId = "abc123def456",
            isAttached = true,
            error = com.ras.data.terminal.TerminalErrorInfo("ERROR", "msg", null)
        )
        assertFalse(state.canSendInput)
    }

    @Tag("unit")
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

    @Tag("unit")
    @Test
    fun `TerminalInputValidator accepts valid input size`() {
        val data = ByteArray(65536) // Exactly 64KB
        assertTrue(com.ras.data.terminal.TerminalInputValidator.isValidInputSize(data))
    }

    @Tag("unit")
    @Test
    fun `TerminalInputValidator rejects oversized input`() {
        val data = ByteArray(65537) // 64KB + 1
        assertFalse(com.ras.data.terminal.TerminalInputValidator.isValidInputSize(data))
    }

    @Tag("unit")
    @Test
    fun `TerminalInputValidator accepts empty input`() {
        val data = ByteArray(0)
        assertTrue(com.ras.data.terminal.TerminalInputValidator.isValidInputSize(data))
    }

    // ==========================================================================
    // Error Event with Empty Session ID Tests
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `error event with empty sessionId converts to null`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            val errorEvent = ProtoTerminalEvent.newBuilder()
                .setError(TerminalError.newBuilder()
                    .setSessionId("")
                    .setErrorCode("TEST_ERROR")
                    .setMessage("test")
                    .build())
                .build()
            terminalEventsFlow.emit(errorEvent)
    

            val event = awaitItem() as TerminalEvent.Error
            assertNull(event.sessionId)
        }
    }

    @Tag("unit")
    @Test
    fun `error state with empty sessionId stores null`() = runTest(testDispatcher, timeout = 1.seconds) {
        val errorEvent = ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setSessionId("")
                .setErrorCode("TEST_ERROR")
                .setMessage("test")
                .build())
            .build()
        terminalEventsFlow.emit(errorEvent)


        assertNull(repository.state.value.error?.sessionId)
    }

    // ==========================================================================
    // Event Handling - Snapshot
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `handleSnapshot emits to output flow`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        repository.output.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setSnapshot(TerminalSnapshot.newBuilder()
                    .setSessionId("abc123def456")
                    .setData(ByteString.copyFromUtf8("$ echo hello\nhello\n$ "))
                    .setStreamSeq(42)
                    .build())
                .build()

            terminalEventsFlow.emit(event)

            val bytes = awaitItem()
            assertEquals("$ echo hello\nhello\n$ ", String(bytes))
        }
    }

    @Tag("unit")
    @Test
    fun `handleSnapshot updates lastSequence`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        val event = ProtoTerminalEvent.newBuilder()
            .setSnapshot(TerminalSnapshot.newBuilder()
                .setSessionId("abc123def456")
                .setData(ByteString.copyFromUtf8("data"))
                .setStreamSeq(99)
                .build())
            .build()

        terminalEventsFlow.emit(event)

        assertEquals(99L, repository.state.value.lastSequence)
    }

    @Tag("unit")
    @Test
    fun `handleSnapshot emits Snapshot event`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        repository.events.test {
            val event = ProtoTerminalEvent.newBuilder()
                .setSnapshot(TerminalSnapshot.newBuilder()
                    .setSessionId("abc123def456")
                    .setData(ByteString.copyFromUtf8("screen content"))
                    .setStreamSeq(50)
                    .build())
                .build()

            terminalEventsFlow.emit(event)

            val emitted = awaitItem() as TerminalEvent.Snapshot
            assertEquals("abc123def456", emitted.sessionId)
            assertEquals("screen content", String(emitted.data))
            assertEquals(50L, emitted.streamSeq)
        }
    }

    @Tag("unit")
    @Test
    fun `handleSnapshot does not regress lastSequence`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttachedState()

        // First set a high sequence via output
        val outputEvent = ProtoTerminalEvent.newBuilder()
            .setOutput(TerminalOutput.newBuilder()
                .setSessionId("abc123def456")
                .setData(ByteString.copyFromUtf8("data"))
                .setSequence(100)
                .build())
            .build()
        terminalEventsFlow.emit(outputEvent)

        assertEquals(100L, repository.state.value.lastSequence)

        // Now snapshot with lower sequence should not regress
        val snapshotEvent = ProtoTerminalEvent.newBuilder()
            .setSnapshot(TerminalSnapshot.newBuilder()
                .setSessionId("abc123def456")
                .setData(ByteString.copyFromUtf8("snapshot"))
                .setStreamSeq(50)
                .build())
            .build()
        terminalEventsFlow.emit(snapshotEvent)

        assertEquals(100L, repository.state.value.lastSequence)
    }

    // ==========================================================================
    // Helper Methods
    // ==========================================================================

    /**
     * Helper to simulate a full attach sequence by directly setting state.
     * This bypasses the request-response flow for tests that just need
     * the repository to be in an attached state.
     *
     * For testing the actual attach() request-response flow, use the
     * dedicated tests in "Attach Request-Response Correlation Tests" section.
     */
    private fun simulateAttachedState() {
        coEvery { connectionManager.sendTerminalCommand(any()) } returns Unit

        // Directly emit the attached event to simulate daemon response
        // This sets the state without going through the full attach() flow
        kotlinx.coroutines.runBlocking {
            val attachedEvent = ProtoTerminalEvent.newBuilder()
                .setAttached(TerminalAttached.newBuilder()
                    .setSessionId("abc123def456")
                    .setCols(80)
                    .setRows(24)
                    .setCurrentSeq(0)
                    .build())
                .build()
            terminalEventsFlow.emit(attachedEvent)
        }

        // Advance to process the event

    }
}
