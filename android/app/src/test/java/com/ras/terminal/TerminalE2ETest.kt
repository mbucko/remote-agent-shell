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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

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
    private lateinit var terminalEventsFlow: MutableSharedFlow<ProtoTerminalEvent>
    private lateinit var isConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var repository: TerminalRepository

    // Capture sent commands for verification
    private val sentCommands = mutableListOf<TerminalCommand>()

    @Before
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

        repository = TerminalRepository(connectionManager, testDispatcher)
    }

    private fun slot() = slot<TerminalCommand>()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================================================
    // Scenario 1: Basic Attach and Input Flow
    // ==========================================================================

    @Test
    fun `E2E - basic attach and input flow`() = runTest {
        // 1. User initiates attach
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify attach command sent
        assertTrue(sentCommands.last().hasAttach())
        assertEquals("abc123def456", sentCommands.last().attach.sessionId)
        assertTrue(repository.state.value.isAttaching)

        // 2. Daemon responds with attached event
        val attachedEvent = createAttachedEvent("abc123def456", cols = 80, rows = 24, currentSeq = 0)
        terminalEventsFlow.emit(attachedEvent)
        testDispatcher.scheduler.advanceUntilIdle()

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

    @Test
    fun `E2E - special key interrupt`() = runTest {
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

    @Test
    fun `E2E - reconnection with sequence resumption`() = runTest {
        // Initial attach
        simulateAttached()

        // Receive some output
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "output1", sequence = 100))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(100L, repository.state.value.lastSequence)

        // Simulate disconnection
        terminalEventsFlow.emit(createDetachedEvent("abc123def456", "connection_lost"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(repository.state.value.isAttached)

        // Reconnect with last sequence
        sentCommands.clear()
        repository.attach("abc123def456", fromSequence = 100)
        testDispatcher.scheduler.advanceUntilIdle()

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

    @Test
    fun `E2E - error handling session not found`() = runTest {
        repository.events.test {
            // Try to attach to non-existent session
            repository.attach("notfound1234")
            testDispatcher.scheduler.advanceUntilIdle()

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

    @Test
    fun `E2E - error handling not attached`() = runTest {
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

    @Test
    fun `E2E - error handling rate limited`() = runTest {
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

    @Test
    fun `E2E - error handling input too large`() = runTest {
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

    @Test
    fun `E2E - detach flow`() = runTest {
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

    @Test
    fun `E2E - output streaming`() = runTest {
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

    @Test
    fun `E2E - output skipped handling`() = runTest {
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

    @Test
    fun `E2E - raw mode toggle and input`() = runTest {
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

    @Test
    fun `E2E - multiple output chunks in sequence`() = runTest {
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

    @Test
    fun `E2E - partial output handling`() = runTest {
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

    @Test
    fun `E2E - connection loss recovery`() = runTest {
        simulateAttached()

        // Build up some output history
        terminalEventsFlow.emit(createOutputEvent("abc123def456", "output", sequence = 50))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(50L, repository.state.value.lastSequence)

        // Connection lost
        isConnectedFlow.value = false
        terminalEventsFlow.emit(createDetachedEvent("abc123def456", "network_error"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(repository.state.value.isAttached)
        assertNotNull(repository.state.value.error)

        // Connection restored
        isConnectedFlow.value = true
        repository.clearError()

        // Reconnect from last sequence
        sentCommands.clear()
        repository.attach("abc123def456", fromSequence = 50)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(50L, sentCommands.last().attach.fromSequence)
    }

    // ==========================================================================
    // Scenario 15: Invalid Session ID Rejection
    // ==========================================================================

    @Test
    fun `E2E - invalid session ID rejection`() = runTest {
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

    @Test
    fun `E2E - all special keys send correctly`() = runTest {
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

            assertTrue("Key $keyType should send special", sentCommands.last().input.hasSpecial())
            assertEquals("Key $keyType mismatch", keyType, sentCommands.last().input.special.key)
        }
    }

    // ==========================================================================
    // Scenario 17: State Reset
    // ==========================================================================

    @Test
    fun `E2E - state reset clears everything`() = runTest {
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

    @Test
    fun `E2E - unicode input and output`() = runTest {
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

    @Test
    fun `E2E - rapid input sequence maintains order`() = runTest {
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

    @Test
    fun `E2E - error recovery flow`() = runTest {
        // Initial attach
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        // Error occurs
        terminalEventsFlow.emit(createErrorEvent("abc123def456", "PIPE_ERROR", "Terminal pipe broken"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(repository.state.value.error)
        assertFalse(repository.state.value.isAttaching)

        // User clears error
        repository.clearError()
        assertNull(repository.state.value.error)

        // User retries attach
        sentCommands.clear()
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.state.value.isAttaching)
        assertEquals("abc123def456", sentCommands.last().attach.sessionId)

        // This time it succeeds
        terminalEventsFlow.emit(createAttachedEvent("abc123def456"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.state.value.isAttached)
    }

    // ==========================================================================
    // Helper Methods
    // ==========================================================================

    private suspend fun simulateAttached() {
        repository.attach("abc123def456")
        testDispatcher.scheduler.advanceUntilIdle()

        terminalEventsFlow.emit(createAttachedEvent("abc123def456"))
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
