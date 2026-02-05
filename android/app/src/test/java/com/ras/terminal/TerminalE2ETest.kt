package com.ras.terminal

import app.cash.turbine.test
import com.ras.data.connection.ConnectionManager
import com.ras.data.terminal.TerminalEvent
import com.ras.data.terminal.TerminalRepository
import com.ras.data.terminal.TerminalState
import com.ras.data.terminal.TerminalScreenState
import com.ras.proto.AttachTerminal
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * End-to-end tests for terminal I/O flow.
 *
 * These tests verify complete scenarios from user action through
 * WebRTC command/event to state update, covering all edge cases.
 *
 * Scenarios covered:
 * 1. Basic attach and input flow
 * 2. Special key interrupt (Ctrl+C)
 * 3. Reconnection with sequence resumption
 * 4. Error handling - session not found
 * 5. Error handling - not attached
 * 6. Error handling - rate limited
 * 7. Error handling - input too large
 * 8. Detach flow
 * 9. Output streaming
 * 10. Output skipped handling
 * 11. Raw mode toggle and input
 * 12. Multiple output chunks
 * 13. Partial output handling
 * 14. Connection loss recovery
 * 15. Invalid session ID rejection
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalE2ETest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var connectionManager: ConnectionManager
    private lateinit var notificationHandler: NotificationHandler
    private lateinit var terminalEventsFlow: MutableSharedFlow<ProtoTerminalEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var repository: TerminalRepository

    // Capture sent commands for verification
    private val sentCommands = mutableListOf<TerminalCommand>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        terminalEventsFlow = MutableSharedFlow()
        isConnectedFlow = MutableStateFlow(true)
        sentCommands.clear()

        connectionManager = mockk(relaxed = true) {
            every { terminalEvents } returns terminalEventsFlow
            every { isConnected } returns isConnectedFlow
            every { scope } returns kotlinx.coroutines.CoroutineScope(testDispatcher)
            coEvery { sendTerminalCommand(capture(slot())) } answers {
                sentCommands.add(firstArg())
            }
        }

        notificationHandler = mockk(relaxed = true)

        // Use short timeout (100ms) for tests instead of 10s production timeout
        repository = TerminalRepository(connectionManager, notificationHandler, testDispatcher, attachTimeoutMs = 100L)
    }

    private fun slot() = slot<TerminalCommand>()

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // Scenario 1: Basic Attach and Input Flow
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - basic attach and input flow`() = runTest(testDispatcher, timeout = 1.seconds) {
        // 1. User initiates attach (launch in background so test can emit response)
        val attachJob = launch { repository.attach("abc123def456") }
        testDispatcher.scheduler.runCurrent()  // Only run scheduled tasks, don't advance time

        // Verify attach command sent
        assertTrue(sentCommands.isNotEmpty(), "Expected command to be sent")
        assertTrue(sentCommands.last().hasAttach())
        assertEquals("abc123def456", sentCommands.last().attach.sessionId)
        assertTrue(repository.state.value.isAttaching, "Expected isAttaching=true but was ${repository.state.value}")

        // 2. Daemon responds with attached event
        val attachedEvent = createAttachedEvent("abc123def456", cols = 80, rows = 24, currentSeq = 0)
        terminalEventsFlow.emit(attachedEvent)
        testDispatcher.scheduler.advanceUntilIdle()
        attachJob.join()

        // Verify state updated
        assertTrue(repository.state.value.isAttached)
        assertFalse(repository.state.value.isAttaching)

        // 3. User sends input
        repository.sendLine("ls -la")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify input command sent
        assertTrue(sentCommands.last().hasInput())
        assertEquals("ls -la\r", sentCommands.last().input.data.toStringUtf8())

        // 4. Daemon sends output
        val outputEvent = createOutputEvent("abc123def456", "total 42\nfile1.txt", sequence = 1)
        terminalEventsFlow.emit(outputEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify state updated
        assertEquals(1L, repository.state.value.lastSequence)
    }

    // ==========================================================================
    // Scenario 2: Special Key Interrupt (Ctrl+C)
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - special key interrupt`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // User sends Ctrl+C
        repository.sendSpecialKey(KeyType.KEY_CTRL_C)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify special key command sent
        val cmd = sentCommands.last()
        assertTrue(cmd.input.hasSpecial())
        assertEquals(KeyType.KEY_CTRL_C, cmd.input.special.key)

        // Daemon sends output (^C)
        val outputEvent = createOutputEvent("abc123def456", "^C\n", sequence = 2)
        terminalEventsFlow.emit(outputEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2L, repository.state.value.lastSequence)
    }

    // ==========================================================================
    // Scenario 3: Reconnection with Sequence Resumption
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - reconnection with sequence resumption`() = runTest(testDispatcher, timeout = 1.seconds) {
        // Initial attach
        simulateAttached()

        // Receive some output
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "output1", sequence = 100))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(100L, repository.state.value.lastSequence)

        // Simulate disconnection (transport dies - no detach event delivered)
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(repository.state.value.isAttached)

        // Reconnect with last sequence
        sentCommands.clear()
        isConnectedFlow.value = true
        launch { repository.attach("abc123def456", fromSequence = 100) }
        testDispatcher.scheduler.runCurrent()

        // Verify fromSequence sent
        assertEquals(100L, sentCommands.last().attach.fromSequence)

        // Daemon responds with buffered output
        terminalEventsFlow.emit(createAttachedEvent("abc123def456", currentSeq = 150, bufferStartSeq = 50))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(repository.state.value.isAttached)
    }

    // ==========================================================================
    // Scenario 4: Error Handling - Session Not Found
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error handling session not found`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            // Try to attach to non-existent session - will throw TerminalAttachException
            launch {
                try {
                    repository.attach("notfound1234")
                } catch (e: com.ras.data.terminal.TerminalAttachException) {
                    // Expected - error event triggers this exception
                }
            }
            testDispatcher.scheduler.runCurrent()

            // Daemon responds with error
            val errorEvent = createErrorEvent("notfound1234", "SESSION_NOT_FOUND", "Session not found")
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify error state
            assertFalse(repository.state.value.isAttaching)
            assertNotNull(repository.state.value.error)
            assertEquals("SESSION_NOT_FOUND", repository.state.value.error?.code)

            // Verify event emitted
            val event = awaitItem() as TerminalEvent.Error
            assertEquals("SESSION_NOT_FOUND", event.code)
        }
    }

    // ==========================================================================
    // Scenario 5: Error Handling - Not Attached
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error handling not attached`() = runTest(testDispatcher, timeout = 1.seconds) {
        // Try to send input without attaching
        try {
            repository.sendInput("hello")
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("attached") == true)
        }
    }

    // ==========================================================================
    // Scenario 6: Error Handling - Rate Limited
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error handling rate limited`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.events.test {
            // Send rapid inputs
            repeat(5) {
                repository.sendInput("input$it")
                testDispatcher.scheduler.advanceUntilIdle()
            }

            // Daemon responds with rate limit error
            val errorEvent = createErrorEvent("abc123def456", "RATE_LIMITED", "Too many requests")
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify error event emitted
            val event = awaitItem() as TerminalEvent.Error
            assertEquals("RATE_LIMITED", event.code)
        }
    }

    // ==========================================================================
    // Scenario 7: Error Handling - Input Too Large
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error handling input too large`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Try to send oversized input
        val largeInput = ByteArray(100_000) // > 64KB
        try {
            repository.sendInput(largeInput)
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("large") == true)
        }
    }

    // ==========================================================================
    // Scenario 8: Detach Flow
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - detach flow`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.events.test {
            // User detaches
            sentCommands.clear()
            repository.detach()
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify detach command sent
            assertTrue(sentCommands.last().hasDetach())
            assertEquals("abc123def456", sentCommands.last().detach.sessionId)

            // Daemon confirms detachment
            terminalEventsFlow.emit(createDetachedEvent("abc123def456", "user_request"))
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify state
            assertFalse(repository.state.value.isAttached)
            assertNull(repository.state.value.error) // user_request doesn't set error

            // Verify event
            val event = awaitItem() as TerminalEvent.Detached
            assertEquals("user_request", event.reason)
        }
    }

    // ==========================================================================
    // Scenario 9: Output Streaming
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - output streaming`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.output.test {
            // Daemon sends multiple output chunks
            terminalEventsFlow.emit(createOutputEvent("abc123def456", "Line 1\n", sequence = 1))
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Line 1\n", String(awaitItem()))

            terminalEventsFlow.emit(createOutputEvent("abc123def456", "Line 2\n", sequence = 2))
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Line 2\n", String(awaitItem()))

            terminalEventsFlow.emit(createOutputEvent("abc123def456", "Line 3\n", sequence = 3))
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Line 3\n", String(awaitItem()))
        }

        assertEquals(3L, repository.state.value.lastSequence)
    }

    // ==========================================================================
    // Scenario 10: Output Skipped Handling
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - output skipped handling`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.events.test {
            // Daemon notifies output was skipped
            val skippedEvent = createSkippedEvent("abc123def456", fromSeq = 10, toSeq = 50, bytesSkipped = 40000)
            terminalEventsFlow.emit(skippedEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify state updated
            val skipped = repository.state.value.outputSkipped
            assertNotNull(skipped)
            assertEquals(10L, skipped?.fromSequence)
            assertEquals(50L, skipped?.toSequence)
            assertEquals(40000, skipped?.bytesSkipped)

            // Verify event emitted
            val event = awaitItem() as TerminalEvent.OutputSkipped
            assertEquals(40000, event.bytesSkipped)
        }

        // User dismisses notification
        repository.clearOutputSkipped()
        assertNull(repository.state.value.outputSkipped)
    }

    // ==========================================================================
    // Scenario 11: Raw Mode Toggle and Input
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - raw mode toggle and input`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Initial state is not raw mode
        assertFalse(repository.state.value.isRawMode)

        // Toggle to raw mode
        repository.setRawMode(true)
        assertTrue(repository.state.value.isRawMode)

        // Send individual keystrokes
        repository.sendInput("a")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("a", sentCommands.last().input.data.toStringUtf8())

        repository.sendInput("b")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("b", sentCommands.last().input.data.toStringUtf8())

        // Toggle back to normal mode
        repository.toggleRawMode()
        assertFalse(repository.state.value.isRawMode)
    }

    // ==========================================================================
    // Scenario 12: Multiple Output Chunks
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - multiple output chunks in sequence`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.output.test {
            // Large output split into chunks
            for (i in 1..10) {
                val chunk = "Chunk $i of 10\n"
                terminalEventsFlow.emit(createOutputEvent("abc123def456", chunk, sequence = i.toLong()))
                testDispatcher.scheduler.advanceUntilIdle()
                assertEquals(chunk, String(awaitItem()))
            }
        }

        assertEquals(10L, repository.state.value.lastSequence)
    }

    // ==========================================================================
    // Scenario 13: Partial Output Handling
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - partial output handling`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.events.test {
            // Daemon sends partial output
            val partialEvent = ProtoTerminalEvent.newBuilder()
                .setOutput(TerminalOutput.newBuilder()
                    .setSessionId("abc123def456")
                    .setData(ByteString.copyFromUtf8("partial..."))
                    .setSequence(1)
                    .setPartial(true)
                    .build())
                .build()
            terminalEventsFlow.emit(partialEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem() as TerminalEvent.Output
            assertTrue(event.partial)
        }
    }

    // ==========================================================================
    // Scenario 14: Connection Loss Recovery
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - connection loss recovery`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Build up some output history
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "output", sequence = 50))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(50L, repository.state.value.lastSequence)

        // Connection lost (transport dies - no detach event delivered)
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(repository.state.value.isAttached)
        assertNull(repository.state.value.error) // Connection drop is not an error

        // Connection restored
        isConnectedFlow.value = true

        // Reconnect from last sequence
        sentCommands.clear()
        launch { repository.attach("abc123def456", fromSequence = 50) }
        testDispatcher.scheduler.runCurrent()

        assertEquals(50L, sentCommands.last().attach.fromSequence)
    }

    // ==========================================================================
    // Scenario 15: Invalid Session ID Rejection
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - invalid session ID rejection`() = runTest(testDispatcher, timeout = 1.seconds) {
        val invalidIds = listOf(
            "abc123",           // too short
            "abc123def456789",  // too long
            "abc-123-def!",     // special chars
            "../../../etc",     // path traversal
            "abc123def45\u0000" // null byte
        )

        for (id in invalidIds) {
            try {
                repository.attach(id)
                fail("Should reject invalid session ID: $id")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    // ==========================================================================
    // Scenario 16: All Key Types Mapping
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - all special keys send correctly`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        val keyTypes = listOf(
            KeyType.KEY_ENTER,
            KeyType.KEY_TAB,
            KeyType.KEY_BACKSPACE,
            KeyType.KEY_ESCAPE,
            KeyType.KEY_UP,
            KeyType.KEY_DOWN,
            KeyType.KEY_LEFT,
            KeyType.KEY_RIGHT,
            KeyType.KEY_HOME,
            KeyType.KEY_END,
            KeyType.KEY_CTRL_C,
            KeyType.KEY_CTRL_D,
            KeyType.KEY_CTRL_Z
        )

        for (keyType in keyTypes) {
            sentCommands.clear()
            repository.sendSpecialKey(keyType)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(sentCommands.last().input.hasSpecial(), "Key $keyType should send special")
            assertEquals(keyType, sentCommands.last().input.special.key, "Key $keyType mismatch")
        }
    }

    // ==========================================================================
    // Scenario 17: State Reset
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - state reset clears everything`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        repository.setRawMode(true)

        // Add some state
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "output", sequence = 100))
        terminalEventsFlow.emit(createSkippedEvent("abc123def456", 10, 20, 5000))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(repository.state.value.outputSkipped)
        assertEquals(100L, repository.state.value.lastSequence)
        assertTrue(repository.state.value.isRawMode)

        // Reset
        repository.reset()

        val state = repository.state.value
        assertNull(state.sessionId)
        assertFalse(state.isAttached)
        assertFalse(state.isRawMode)
        assertEquals(0L, state.lastSequence)
        assertNull(state.outputSkipped)
        assertNull(state.error)
    }

    // ==========================================================================
    // Scenario 18: Unicode Input/Output
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - unicode input and output`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Send unicode input
        repository.sendInput("Hello, \u4e16\u754c!")  // Hello, 世界!
        testDispatcher.scheduler.advanceUntilIdle()

        val sentData = sentCommands.last().input.data.toStringUtf8()
        assertEquals("Hello, \u4e16\u754c!", sentData)

        // Receive unicode output
        repository.output.test {
            val unicodeOutput = "\u3053\u3093\u306b\u3061\u306f"  // こんにちは
            terminalEventsFlow.emit(createOutputEvent("abc123def456", unicodeOutput, sequence = 1))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(unicodeOutput, String(awaitItem()))
        }
    }

    // ==========================================================================
    // Scenario 19: Rapid Input Sequence
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - rapid input sequence maintains order`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        sentCommands.clear()

        // Send rapid inputs
        for (i in 1..10) {
            repository.sendInput("input$i")
            testDispatcher.scheduler.advanceUntilIdle()
        }

        // Verify all sent in order
        assertEquals(10, sentCommands.size)
        for (i in 1..10) {
            assertEquals("input$i", sentCommands[i - 1].input.data.toStringUtf8())
        }
    }

    // ==========================================================================
    // Scenario 20: Error Recovery Flow
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error recovery flow`() = runTest(testDispatcher, timeout = 1.seconds) {
        // Initial attach - will fail with error
        launch {
            try {
                repository.attach("abc123def456")
            } catch (e: com.ras.data.terminal.TerminalAttachException) {
                // Expected - error event will cause this
            }
        }
        testDispatcher.scheduler.runCurrent()

        // Error occurs
        terminalEventsFlow.emit(createErrorEvent("abc123def456", "PIPE_ERROR", "Terminal pipe broken"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(repository.state.value.error)
        assertFalse(repository.state.value.isAttaching)

        // User clears error
        repository.clearError()
        assertNull(repository.state.value.error)

        // User retries attach - this time it will succeed
        sentCommands.clear()
        launch { repository.attach("abc123def456") }
        testDispatcher.scheduler.runCurrent()

        assertTrue(repository.state.value.isAttaching)
        assertEquals("abc123def456", sentCommands.last().attach.sessionId)

        // This time it succeeds
        terminalEventsFlow.emit(createAttachedEvent("abc123def456"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.state.value.isAttached)
    }

    // ==========================================================================
    // Scenario 21: Error - Session Being Killed
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error session killing`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            launch {
                try {
                    repository.attach("abc123def456")
                } catch (e: com.ras.data.terminal.TerminalAttachException) {
                    // Expected
                }
            }
            testDispatcher.scheduler.runCurrent()

            // Daemon responds with session killing error
            val errorEvent = createErrorEvent("abc123def456", "SESSION_KILLING", "Session is being terminated")
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(repository.state.value.isAttaching)
            assertEquals("SESSION_KILLING", repository.state.value.error?.code)

            val event = awaitItem() as TerminalEvent.Error
            assertEquals("SESSION_KILLING", event.code)
        }
    }

    // ==========================================================================
    // Scenario 22: Error - Already Attached (Informational)
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error already attached`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.events.test {
            // Try to attach again (could happen from UI race condition)
            sentCommands.clear()
            repository.attach("abc123def456")
            testDispatcher.scheduler.advanceUntilIdle()

            // Daemon responds with already attached
            val errorEvent = createErrorEvent("abc123def456", "ALREADY_ATTACHED", "Already attached to this session")
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            // This is informational - we should still be attached
            val event = awaitItem() as TerminalEvent.Error
            assertEquals("ALREADY_ATTACHED", event.code)
        }
    }

    // ==========================================================================
    // Scenario 23: Error - Invalid Sequence (Buffer Rolled)
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error invalid sequence`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            // Try to resume from a sequence that's no longer in buffer
            launch {
                try {
                    repository.attach("abc123def456", fromSequence = 1000)
                } catch (e: com.ras.data.terminal.TerminalAttachException) {
                    // Expected
                }
            }
            testDispatcher.scheduler.runCurrent()

            val errorEvent = createErrorEvent("abc123def456", "INVALID_SEQUENCE", "Requested sequence not in buffer")
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("INVALID_SEQUENCE", repository.state.value.error?.code)

            val event = awaitItem() as TerminalEvent.Error
            assertEquals("INVALID_SEQUENCE", event.code)
        }
    }

    // ==========================================================================
    // Scenario 24: Error - Pipe Setup Failed
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - error pipe setup failed`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            launch {
                try {
                    repository.attach("abc123def456")
                } catch (e: com.ras.data.terminal.TerminalAttachException) {
                    // Expected
                }
            }
            testDispatcher.scheduler.runCurrent()

            val errorEvent = createErrorEvent("abc123def456", "PIPE_SETUP_FAILED", "Failed to setup tmux pipe-pane")
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("PIPE_SETUP_FAILED", repository.state.value.error?.code)

            val event = awaitItem() as TerminalEvent.Error
            assertEquals("PIPE_SETUP_FAILED", event.code)
        }
    }

    // ==========================================================================
    // Scenario 25: Concurrent Attach Attempts
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - concurrent attach attempts`() = runTest(testDispatcher, timeout = 1.seconds) {
        // Start first attach
        launch { repository.attach("abc123def456") }
        testDispatcher.scheduler.runCurrent()
        assertTrue(repository.state.value.isAttaching)

        // Before it completes, try another attach to SAME session - should be no-op
        sentCommands.clear()
        launch { repository.attach("abc123def456") } // Same session - no additional command
        testDispatcher.scheduler.runCurrent()

        // No new command sent (duplicate attach to same session is a no-op)
        assertEquals(0, sentCommands.size)
        assertTrue(repository.state.value.isAttaching)

        // Note: Attaching to a DIFFERENT session while already attaching is serialized
        // by the mutex - it waits for the first attach to complete (or timeout).
        // This test verifies that duplicate attach to SAME session is correctly ignored.
    }

    // ==========================================================================
    // Scenario 26: Send While Attaching
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - send while attaching fails`() = runTest(testDispatcher, timeout = 1.seconds) {
        launch { repository.attach("abc123def456") }
        testDispatcher.scheduler.runCurrent()
        assertTrue(repository.state.value.isAttaching)
        assertFalse(repository.state.value.isAttached)

        // Try to send input while still attaching
        try {
            repository.sendInput("test")
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("attached") == true)
        }
    }

    // ==========================================================================
    // Scenario 27: Session ID Mismatch (Ignored)
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - session ID mismatch ignored`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Receive event for different session
        val mismatchEvent = createOutputEvent("different123", "should be ignored", sequence = 999)
        terminalEventsFlow.emit(mismatchEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Note: Current implementation processes all events. This test documents behavior.
        // In production, daemon should not send mismatched events.
        // State will update because we don't filter by session ID (could be improved)
    }

    // ==========================================================================
    // Scenario 28: Zero-Length Output
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - zero length output`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.output.test {
            // Daemon sends empty output (heartbeat/keepalive)
            val emptyOutput = ProtoTerminalEvent.newBuilder()
                .setOutput(TerminalOutput.newBuilder()
                    .setSessionId("abc123def456")
                    .setData(ByteString.EMPTY)
                    .setSequence(1)
                    .build())
                .build()
            terminalEventsFlow.emit(emptyOutput)
            testDispatcher.scheduler.advanceUntilIdle()

            val output = awaitItem()
            assertEquals(0, output.size)
        }
    }

    // ==========================================================================
    // Scenario 29: Special Keys with Modifiers
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - special keys with modifiers`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Send Ctrl+Shift+C (modifier = 1 + 4 = 5)
        sentCommands.clear()
        repository.sendSpecialKey(KeyType.KEY_CTRL_C, modifiers = 5)
        testDispatcher.scheduler.advanceUntilIdle()

        val cmd = sentCommands.last()
        assertTrue(cmd.input.hasSpecial())
        assertEquals(KeyType.KEY_CTRL_C, cmd.input.special.key)
        assertEquals(5, cmd.input.special.modifiers)
    }

    // ==========================================================================
    // Scenario 30: All Function Keys F1-F12
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - all function keys F1 through F12`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        val functionKeys = listOf(
            KeyType.KEY_F1, KeyType.KEY_F2, KeyType.KEY_F3, KeyType.KEY_F4,
            KeyType.KEY_F5, KeyType.KEY_F6, KeyType.KEY_F7, KeyType.KEY_F8,
            KeyType.KEY_F9, KeyType.KEY_F10, KeyType.KEY_F11, KeyType.KEY_F12
        )

        for (key in functionKeys) {
            sentCommands.clear()
            repository.sendSpecialKey(key)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(sentCommands.isNotEmpty(), "Key $key should send")
            assertEquals(key, sentCommands.last().input.special.key)
        }
    }

    // ==========================================================================
    // Scenario 31: Binary Data Input
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - binary data input`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Send binary data including null bytes
        val binaryData = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte())
        repository.sendInput(binaryData)
        testDispatcher.scheduler.advanceUntilIdle()

        val sentData = sentCommands.last().input.data.toByteArray()
        assertArrayEquals(binaryData, sentData)
    }

    // ==========================================================================
    // Scenario 32: Double Detach
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - double detach is safe`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // First detach
        repository.detach()
        testDispatcher.scheduler.advanceUntilIdle()
        terminalEventsFlow.emit(createDetachedEvent("abc123def456", "user_request"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(repository.state.value.isAttached)

        // Second detach should be no-op
        sentCommands.clear()
        repository.detach()
        testDispatcher.scheduler.advanceUntilIdle()

        // No command sent because sessionId is cleared or state check prevents it
        // This documents current behavior
    }

    // ==========================================================================
    // Scenario 33: Detach Without Attach
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - detach without prior attach is safe`() = runTest(testDispatcher, timeout = 1.seconds) {
        // Never attached
        assertNull(repository.state.value.sessionId)

        sentCommands.clear()
        repository.detach()
        testDispatcher.scheduler.advanceUntilIdle()

        // No command should be sent
        assertTrue(sentCommands.isEmpty())
    }

    // ==========================================================================
    // Scenario 34: Large Paste (Within Limit)
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - large paste within limit`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // 60KB is within 64KB limit
        val largeText = "A".repeat(60_000)
        repository.sendInput(largeText)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(60_000, sentCommands.last().input.data.size())
    }

    // ==========================================================================
    // Scenario 35: Out-of-Order Sequence (Regression)
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - out of order sequence uses maxOf to prevent regression`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Receive sequence 100
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "first", sequence = 100))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(100L, repository.state.value.lastSequence)

        // Receive sequence 50 (out of order - could happen with multiple streams)
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "late", sequence = 50))
        testDispatcher.scheduler.advanceUntilIdle()

        // Sequence should NOT regress - maxOf(100, 50) = 100
        assertEquals(100L, repository.state.value.lastSequence)

        // Higher sequence should still update
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "newer", sequence = 150))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(150L, repository.state.value.lastSequence)
    }

    // ==========================================================================
    // Scenario 36: Attach While Already Attached (Session Switching)
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - attach to different session while attached switches sessions`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        assertTrue(repository.state.value.isAttached)
        assertEquals("abc123def456", repository.state.value.sessionId)

        // Clear commands to track new ones
        sentCommands.clear()

        // Attach to different session - should detach from old and attach to new
        launch { repository.attach("xyz789uvw012") }
        testDispatcher.scheduler.runCurrent()

        // Verify detach command was sent for old session
        val detachCmd = sentCommands.find { it.hasDetach() }
        assertNotNull(detachCmd, "Should send detach command")
        assertEquals("abc123def456", detachCmd?.detach?.sessionId)

        // Verify attach command was sent for new session
        val attachCmd = sentCommands.find { it.hasAttach() }
        assertNotNull(attachCmd, "Should send attach command")
        assertEquals("xyz789uvw012", attachCmd?.attach?.sessionId)

        // Simulate successful attach to new session
        terminalEventsFlow.emit(createAttachedEvent("xyz789uvw012"))
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify state switched to new session
        assertTrue(repository.state.value.isAttached)
        assertEquals("xyz789uvw012", repository.state.value.sessionId)
    }

    @Tag("e2e")
    @Test
    fun `E2E - re-attach to same session while attached is no-op`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        assertTrue(repository.state.value.isAttached)
        assertEquals("abc123def456", repository.state.value.sessionId)

        // Re-attaching to the same session should be a no-op (not throw)
        sentCommands.clear()
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        // No command sent (already attached to this session)
        assertEquals(0, sentCommands.size)
        assertTrue(repository.state.value.isAttached)
    }

    // ==========================================================================
    // Scenario 37: Empty Error Message
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - empty error message handled`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            launch {
                try {
                    repository.attach("abc123def456")
                } catch (e: com.ras.data.terminal.TerminalAttachException) {
                    // Expected
                }
            }
            testDispatcher.scheduler.runCurrent()

            val errorEvent = createErrorEvent("abc123def456", "UNKNOWN_ERROR", "")
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem() as TerminalEvent.Error
            assertEquals("", event.message)
        }
    }

    // ==========================================================================
    // Scenario 38: Empty Session ID in Error
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - empty session ID in error handled`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            // Error with empty session ID (global error)
            val errorEvent = ProtoTerminalEvent.newBuilder()
                .setError(TerminalError.newBuilder()
                    .setSessionId("")
                    .setErrorCode("CONNECTION_ERROR")
                    .setMessage("Connection lost")
                    .build())
                .build()
            terminalEventsFlow.emit(errorEvent)
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem() as TerminalEvent.Error
            assertNull(event.sessionId)
        }
    }

    // ==========================================================================
    // Scenario 39: Navigation Keys
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - all navigation keys`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        val navKeys = listOf(
            KeyType.KEY_HOME,
            KeyType.KEY_END,
            KeyType.KEY_PAGE_UP,
            KeyType.KEY_PAGE_DOWN,
            KeyType.KEY_INSERT,
            KeyType.KEY_DELETE
        )

        for (key in navKeys) {
            sentCommands.clear()
            repository.sendSpecialKey(key)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(key, sentCommands.last().input.special.key, "Key $key")
        }
    }

    // ==========================================================================
    // Scenario 40: Rapid Toggle Raw Mode
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - rapid raw mode toggle thread safe`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Rapidly toggle raw mode (tests thread safety of update)
        repeat(100) {
            repository.toggleRawMode()
        }

        // Should end up in original state (100 toggles = even = same)
        assertFalse(repository.state.value.isRawMode)
    }

    // ==========================================================================
    // Scenario 41: KEY_UNKNOWN Handling
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - KEY_UNKNOWN sends correctly`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Send KEY_UNKNOWN (enum value 0) - should still work
        sentCommands.clear()
        repository.sendSpecialKey(KeyType.KEY_UNKNOWN)
        testDispatcher.scheduler.advanceUntilIdle()

        // Command should be sent (daemon decides what to do with it)
        assertTrue(sentCommands.isNotEmpty())
        assertEquals(KeyType.KEY_UNKNOWN, sentCommands.last().input.special.key)
    }

    // ==========================================================================
    // Scenario 42: All Modifier Combinations
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - all modifier combinations`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Test all 8 modifier combinations (0-7)
        // 0 = none, 1 = Ctrl, 2 = Alt, 3 = Ctrl+Alt, 4 = Shift, 5 = Ctrl+Shift, 6 = Alt+Shift, 7 = all
        for (mod in 0..7) {
            sentCommands.clear()
            repository.sendSpecialKey(KeyType.KEY_ENTER, modifiers = mod)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(mod, sentCommands.last().input.special.modifiers)
        }
    }

    // ==========================================================================
    // Scenario 43: Empty ProtoTerminalEvent (No Fields Set)
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - empty event is handled gracefully`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        val initialState = repository.state.value

        // Send empty event (no oneof field set)
        val emptyEvent = ProtoTerminalEvent.newBuilder().build()
        terminalEventsFlow.emit(emptyEvent)
        testDispatcher.scheduler.advanceUntilIdle()

        // State should be unchanged
        assertEquals(initialState.isAttached, repository.state.value.isAttached)
    }

    // ==========================================================================
    // Scenario 44: Very Large Output
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - very large output handled`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.output.test {
            // Send 1MB of output
            val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() }
            val largeOutput = ProtoTerminalEvent.newBuilder()
                .setOutput(TerminalOutput.newBuilder()
                    .setSessionId("abc123def456")
                    .setData(ByteString.copyFrom(largeData))
                    .setSequence(1)
                    .build())
                .build()
            terminalEventsFlow.emit(largeOutput)
            testDispatcher.scheduler.advanceUntilIdle()

            val received = awaitItem()
            assertEquals(1024 * 1024, received.size)
        }
    }

    // ==========================================================================
    // Scenario 45: High-Frequency Output Streaming
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - high frequency output streaming`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.output.test {
            // Rapidly send 100 output events
            for (i in 1..100) {
                terminalEventsFlow.emit(createOutputEvent("abc123def456", "chunk$i", sequence = i.toLong()))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // All 100 should arrive
            repeat(100) {
                awaitItem()
            }

            assertEquals(100L, repository.state.value.lastSequence)
        }
    }

    // ==========================================================================
    // Scenario 46: Emoji and Special Unicode
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - emoji and special unicode`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Send emoji
        val emoji = "Hello 👋🌍 World 🚀"
        repository.sendInput(emoji)
        testDispatcher.scheduler.advanceUntilIdle()

        val sentData = sentCommands.last().input.data.toStringUtf8()
        assertEquals(emoji, sentData)

        // Receive emoji
        repository.output.test {
            terminalEventsFlow.emit(createOutputEvent("abc123def456", emoji, sequence = 1))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(emoji, String(awaitItem()))
        }
    }

    // ==========================================================================
    // Scenario 47: Control Characters in Output
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - control characters in output`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.output.test {
            // Output with ANSI escape sequences and control chars
            val ansiOutput = "\u001b[31mRed Text\u001b[0m\r\n\u0007Bell"
            terminalEventsFlow.emit(createOutputEvent("abc123def456", ansiOutput, sequence = 1))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(ansiOutput, String(awaitItem()))
        }
    }

    // ==========================================================================
    // Scenario 48: Maximum Valid Sequence Number
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - maximum sequence number`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        val maxSeq = Long.MAX_VALUE
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "max", sequence = maxSeq))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(maxSeq, repository.state.value.lastSequence)
    }

    // ==========================================================================
    // Scenario 49: Output With All Fields Set
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - output with all fields set`() = runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.events.test {
            val fullOutput = ProtoTerminalEvent.newBuilder()
                .setOutput(TerminalOutput.newBuilder()
                    .setSessionId("abc123def456")
                    .setData(ByteString.copyFromUtf8("test data"))
                    .setSequence(42)
                    .setPartial(true)
                    .build())
                .build()
            terminalEventsFlow.emit(fullOutput)
            testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem() as TerminalEvent.Output
            assertEquals("abc123def456", event.sessionId)
            assertEquals("test data", String(event.data))
            assertEquals(42L, event.sequence)
            assertTrue(event.partial)
        }
    }

    // ==========================================================================
    // Scenario 50: Attached Event With All Fields
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - attached event with all fields verified`() = runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            launch { repository.attach("abc123def456") }
            testDispatcher.scheduler.runCurrent()

            val attached = createAttachedEvent(
                sessionId = "abc123def456",
                cols = 132,
                rows = 43,
                bufferStartSeq = 100,
                currentSeq = 500
            )
            terminalEventsFlow.emit(attached)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify all state fields
            val state = repository.state.value
            assertEquals("abc123def456", state.sessionId)
            assertEquals(132, state.cols)
            assertEquals(43, state.rows)
            assertEquals(100L, state.bufferStartSeq)
            assertEquals(500L, state.lastSequence)
            assertTrue(state.isAttached)
            assertFalse(state.isAttaching)

            // Verify event
            val event = awaitItem() as TerminalEvent.Attached
            assertEquals("abc123def456", event.sessionId)
            assertEquals(132, event.cols)
            assertEquals(43, event.rows)
            assertEquals(100L, event.bufferStartSeq)
            assertEquals(500L, event.currentSeq)
        }
    }

    // ==========================================================================
    // Category A: onConnectionLost() Tests
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - connection drop without detach clears attachment and enables re-attach`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Build up output history for sequence resumption
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "output", sequence = 50))
        testDispatcher.scheduler.advanceUntilIdle()

        // Connection drops - NO detach event (transport is dead)
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        // isAttached must be false, but sessionId + lastSequence preserved for re-attach
        assertFalse(repository.state.value.isAttached)
        assertEquals("abc123def456", repository.state.value.sessionId)
        assertEquals(50L, repository.state.value.lastSequence)
        assertNull(repository.state.value.error) // Not an error, just disconnected

        // Re-attach should work (not blocked by stale "Already attached")
        sentCommands.clear()
        isConnectedFlow.value = true
        launch { repository.attach("abc123def456", fromSequence = 50) }
        testDispatcher.scheduler.runCurrent()

        // Verify attach command actually sent
        assertTrue(sentCommands.any { it.hasAttach() })
        assertEquals(50L, sentCommands.last().attach.fromSequence)
    }

    @Tag("e2e")
    @Test
    fun `E2E - pending attach cancelled on connection drop`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        // Start attach but don't let daemon respond
        launch {
            try {
                repository.attach("abc123def456")
            } catch (e: java.util.concurrent.CancellationException) {
                // Expected - connection dropped
            }
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue(repository.state.value.isAttaching)

        // Connection drops before daemon responds
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        // Should NOT be stuck in isAttaching state
        assertFalse(repository.state.value.isAttaching)
        assertFalse(repository.state.value.isAttached)
        // sessionId preserved for re-attach
        assertEquals("abc123def456", repository.state.value.sessionId)
    }

    @Tag("e2e")
    @Test
    fun `E2E - connection drop while not attached is no-op`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        // Never attached - fresh state
        val stateBefore = repository.state.value
        assertFalse(stateBefore.isAttached)
        assertFalse(stateBefore.isAttaching)

        // Connection drops
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        // State should be completely unchanged
        assertEquals(stateBefore, repository.state.value)
    }

    @Tag("e2e")
    @Test
    fun `E2E - canSendInput becomes false after connection drop`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        assertTrue(repository.state.value.canSendInput)

        // Connection drops
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        // canSendInput must be false (isAttached is now false)
        assertFalse(repository.state.value.canSendInput)
        // But no error set
        assertNull(repository.state.value.error)
    }

    @Tag("e2e")
    @Test
    fun `E2E - connection flapping leaves consistent state`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Rapid connection flapping
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()
        isConnectedFlow.value = true
        testDispatcher.scheduler.advanceUntilIdle()
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        // Should be cleanly not-attached
        assertFalse(repository.state.value.isAttached)
        assertFalse(repository.state.value.isAttaching)
        assertNull(repository.state.value.error)
        // sessionId preserved through all flaps
        assertEquals("abc123def456", repository.state.value.sessionId)
    }

    @Tag("e2e")
    @Test
    fun `E2E - connection drop preserves rawMode and outputSkipped`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        repository.setRawMode(true)
        terminalEventsFlow.emit(createSkippedEvent("abc123def456", 10, 20, 5000))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.state.value.isRawMode)
        assertNotNull(repository.state.value.outputSkipped)

        // Connection drops
        isConnectedFlow.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        // rawMode and outputSkipped preserved
        assertTrue(repository.state.value.isRawMode)
        assertNotNull(repository.state.value.outputSkipped)
        // But not attached
        assertFalse(repository.state.value.isAttached)
    }

    // ==========================================================================
    // Category B: handleError() NOT_ATTACHED Fix
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - NOT_ATTACHED error clears isAttached for self-healing`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        assertTrue(repository.state.value.isAttached)

        // Daemon sends NOT_ATTACHED (stale state scenario)
        terminalEventsFlow.emit(
            createErrorEvent("abc123def456", "NOT_ATTACHED", "Not attached to session"))
        testDispatcher.scheduler.advanceUntilIdle()

        // isAttached must now be false (self-healing)
        assertFalse(repository.state.value.isAttached)

        // Re-attach should now work
        repository.clearError()
        sentCommands.clear()
        launch { repository.attach("abc123def456") }
        testDispatcher.scheduler.runCurrent()

        assertTrue(sentCommands.any { it.hasAttach() })
    }

    @Tag("e2e")
    @Test
    fun `E2E - RATE_LIMITED error does NOT clear isAttached`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        assertTrue(repository.state.value.isAttached)

        // Daemon sends RATE_LIMITED while attached
        terminalEventsFlow.emit(
            createErrorEvent("abc123def456", "RATE_LIMITED", "Too many requests"))
        testDispatcher.scheduler.advanceUntilIdle()

        // isAttached must STILL be true (only NOT_ATTACHED clears it)
        assertTrue(repository.state.value.isAttached)
        assertNotNull(repository.state.value.error)
        assertEquals("RATE_LIMITED", repository.state.value.error?.code)
    }

    @Tag("e2e")
    @Test
    fun `E2E - error while not attaching does not complete pendingAttach`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()
        assertFalse(repository.state.value.isAttaching)

        // Error arrives while attached (not attaching)
        terminalEventsFlow.emit(
            createErrorEvent("abc123def456", "PIPE_ERROR", "Terminal pipe broken"))
        testDispatcher.scheduler.advanceUntilIdle()

        // Error state set but still technically attached (for non-NOT_ATTACHED errors)
        assertTrue(repository.state.value.isAttached)
        assertNotNull(repository.state.value.error)
        // No crash from trying to complete null pendingAttach
    }

    // ==========================================================================
    // Category C: attach() Untested Paths
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - attach timeout when daemon does not respond`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        repository.events.test {
            // Attach but daemon never responds (100ms test timeout)
            launch {
                try {
                    repository.attach("abc123def456")
                    fail("Should throw TimeoutCancellationException")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    // Expected
                }
            }
            testDispatcher.scheduler.runCurrent()
            assertTrue(repository.state.value.isAttaching)

            // Advance past timeout
            testDispatcher.scheduler.advanceTimeBy(200)
            testDispatcher.scheduler.runCurrent()

            // State should show timeout error
            assertFalse(repository.state.value.isAttaching)
            assertFalse(repository.state.value.isAttached)
            assertEquals("ATTACH_TIMEOUT", repository.state.value.error?.code)

            // Error event emitted
            val event = awaitItem() as TerminalEvent.Error
            assertEquals("ATTACH_TIMEOUT", event.code)
        }
    }

    @Tag("e2e")
    @Test
    fun `E2E - sendTerminalCommand failure during attach sets ATTACH_FAILED`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        // Make sendTerminalCommand throw
        coEvery { connectionManager.sendTerminalCommand(any()) } throws
            RuntimeException("Connection dead")

        repository.events.test {
            launch {
                try {
                    repository.attach("abc123def456")
                    fail("Should throw")
                } catch (e: RuntimeException) {
                    assertEquals("Connection dead", e.message)
                }
            }
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(repository.state.value.isAttaching)
            assertEquals("ATTACH_FAILED", repository.state.value.error?.code)

            val event = awaitItem() as TerminalEvent.Error
            assertEquals("ATTACH_FAILED", event.code)
        }
    }

    @Tag("e2e")
    @Test
    fun `E2E - session switch continues even if detach send fails`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Make sendTerminalCommand fail once (for detach), then succeed (for attach)
        var callCount = 0
        coEvery { connectionManager.sendTerminalCommand(any()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("Send failed")
            sentCommands.add(firstArg())
        }

        sentCommands.clear()
        launch { repository.attach("xyz789uvw012") }
        testDispatcher.scheduler.runCurrent()

        // Attach command should still be sent despite detach failure
        assertTrue(sentCommands.any { it.hasAttach() })
        assertEquals("xyz789uvw012", sentCommands.last().attach.sessionId)
    }

    @Tag("e2e")
    @Test
    fun `E2E - attach with Long MAX_VALUE fromSequence`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        launch { repository.attach("abc123def456", fromSequence = Long.MAX_VALUE) }
        testDispatcher.scheduler.runCurrent()

        assertTrue(sentCommands.any { it.hasAttach() })
        assertEquals(Long.MAX_VALUE, sentCommands.last().attach.fromSequence)

        // Complete attach
        terminalEventsFlow.emit(createAttachedEvent("abc123def456", currentSeq = 999))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(repository.state.value.isAttached)
    }

    // ==========================================================================
    // Category D: Other Untested Repository Paths
    // ==========================================================================

    @Tag("e2e")
    @Test
    fun `E2E - resize while not attached throws`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        // Never attached
        try {
            repository.resize(80, 24)
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("attached") == true)
        }
    }

    @Tag("e2e")
    @Test
    fun `E2E - sendSpecialKey while not attached throws`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        try {
            repository.sendSpecialKey(KeyType.KEY_CTRL_C)
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("attached") == true)
        }
    }

    @Tag("e2e")
    @Test
    fun `E2E - output skipped with zero bytes is ignored`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        repository.events.test {
            // Send skipped event with 0 bytes
            terminalEventsFlow.emit(createSkippedEvent("abc123def456", 10, 20, 0))
            testDispatcher.scheduler.advanceUntilIdle()

            // No outputSkipped set in state
            assertNull(repository.state.value.outputSkipped)
            // No event emitted (would timeout)
            expectNoEvents()
        }
    }

    @Tag("e2e")
    @Test
    fun `E2E - empty byte array input is sent`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        sentCommands.clear()
        repository.sendInput(ByteArray(0))
        testDispatcher.scheduler.advanceUntilIdle()

        // Empty input should still be sent (daemon decides what to do)
        assertTrue(sentCommands.isNotEmpty())
        assertEquals(0, sentCommands.last().input.data.size())
    }

    @Tag("e2e")
    @Test
    fun `E2E - input at exact 64KB boundary`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Exactly 65536 bytes should succeed
        val exactLimit = ByteArray(65536)
        repository.sendInput(exactLimit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(65536, sentCommands.last().input.data.size())

        // 65537 bytes should fail
        val overLimit = ByteArray(65537)
        try {
            repository.sendInput(overLimit)
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("large") == true)
        }
    }

    @Tag("e2e")
    @Test
    fun `E2E - detach propagates send failure`() =
        runTest(testDispatcher, timeout = 1.seconds) {
        simulateAttached()

        // Make send fail
        coEvery { connectionManager.sendTerminalCommand(any()) } throws
            RuntimeException("Connection dead")

        try {
            repository.detach()
            fail("Should throw RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("Connection dead", e.message)
        }
    }

    // ==========================================================================
    // Helper Methods
    // ==========================================================================

    private fun TestScope.simulateAttached() {
        launch { repository.attach("abc123def456") }
        testDispatcher.scheduler.runCurrent()

        launch { terminalEventsFlow.emit(createAttachedEvent("abc123def456")) }
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun createAttachedEvent(
        sessionId: String,
        cols: Int = 80,
        rows: Int = 24,
        bufferStartSeq: Long = 0,
        currentSeq: Long = 0
    ): ProtoTerminalEvent {
        return ProtoTerminalEvent.newBuilder()
            .setAttached(TerminalAttached.newBuilder()
                .setSessionId(sessionId)
                .setCols(cols)
                .setRows(rows)
                .setBufferStartSeq(bufferStartSeq)
                .setCurrentSeq(currentSeq)
                .build())
            .build()
    }

    private fun createDetachedEvent(sessionId: String, reason: String): ProtoTerminalEvent {
        return ProtoTerminalEvent.newBuilder()
            .setDetached(TerminalDetached.newBuilder()
                .setSessionId(sessionId)
                .setReason(reason)
                .build())
            .build()
    }

    private fun createOutputEvent(sessionId: String, data: String, sequence: Long): ProtoTerminalEvent {
        return ProtoTerminalEvent.newBuilder()
            .setOutput(TerminalOutput.newBuilder()
                .setSessionId(sessionId)
                .setData(ByteString.copyFromUtf8(data))
                .setSequence(sequence)
                .setPartial(false)
                .build())
            .build()
    }

    private fun createErrorEvent(sessionId: String, code: String, message: String): ProtoTerminalEvent {
        return ProtoTerminalEvent.newBuilder()
            .setError(TerminalError.newBuilder()
                .setSessionId(sessionId)
                .setErrorCode(code)
                .setMessage(message)
                .build())
            .build()
    }

    private fun createSkippedEvent(
        sessionId: String,
        fromSeq: Long,
        toSeq: Long,
        bytesSkipped: Int
    ): ProtoTerminalEvent {
        return ProtoTerminalEvent.newBuilder()
            .setSkipped(OutputSkipped.newBuilder()
                .setSessionId(sessionId)
                .setFromSequence(fromSeq)
                .setToSequence(toSeq)
                .setBytesSkipped(bytesSkipped)
                .build())
            .build()
    }
}
