package com.ras.pairing

import com.ras.ui.pairing.PairingStep
import com.ras.ui.pairing.StepStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)

class PairingProgressTrackerTest {

    private var currentTime = 0L
    private lateinit var tracker: PairingProgressTracker
    private val testClock = Clock { currentTime }

    @BeforeEach
    fun setup() {
        currentTime = 0L
        tracker = PairingProgressTracker(clock = testClock)
    }

    @Tag("unit")
    @Test
    fun `initial state has QR step in progress`() = runTest {
        tracker.start()

        val progress = tracker.progress.first()

        assertEquals("Scanning QR code...", progress.currentMessage)
        assertFalse(progress.isComplete)
        assertEquals(6, progress.steps.size)

        // First step should be QR scanned (in progress, not yet completed)
        assertEquals(StepStatus.IN_PROGRESS, progress.steps[0].status)
        assertEquals("QR code scanned", progress.steps[0].label)

        // Remaining steps should be pending
        assertEquals(StepStatus.PENDING, progress.steps[1].status)
        assertEquals(StepStatus.PENDING, progress.steps[2].status)
        assertEquals(StepStatus.PENDING, progress.steps[3].status)
        assertEquals(StepStatus.PENDING, progress.steps[4].status)
        assertEquals(StepStatus.PENDING, progress.steps[5].status)
    }

    @Tag("unit")
    @Test
    fun `onQrParsed completes QR step and starts creating connection`() = runTest {
        tracker.start()
        currentTime = 50 // 50ms elapsed
        tracker.onQrParsed()

        val progress = tracker.progress.first()

        assertEquals("Creating connection...", progress.currentMessage)

        // QR step should be completed with duration
        assertEquals(StepStatus.COMPLETED, progress.steps[0].status)
        assertEquals(50L, progress.steps[0].durationMs)

        // Creating connection should be in progress
        assertEquals(StepStatus.IN_PROGRESS, progress.steps[1].status)
    }

    @Tag("unit")
    @Test
    fun `onConnectionCreated transitions to reaching host step`() = runTest {
        tracker.start()
        tracker.onQrParsed()
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()
        
        val progress = tracker.progress.first()
        
        assertEquals("Reaching host...", progress.currentMessage)
        
        // Step 1 should be completed
        assertEquals(StepStatus.COMPLETED, progress.steps[1].status)
        assertNotNull(progress.steps[1].durationMs)
        
        // Step 2 should be in progress
        assertEquals(StepStatus.IN_PROGRESS, progress.steps[2].status)
        assertEquals("Reaching host", progress.steps[2].label)
    }

    @Tag("unit")
    @Test
    fun `direct connection path completes all steps`() = runTest {
        tracker.start()
        tracker.onQrParsed()
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()
        tracker.onSignalingComplete(usedNtfy = false)
        tracker.onDataChannelOpen()
        tracker.onAuthenticated()
        
        val progress = tracker.progress.first()
        
        assertTrue(progress.isComplete)
        assertEquals("Paired successfully!", progress.currentMessage)
        
        // All steps should be completed
        progress.steps.forEach { step ->
            assertEquals(StepStatus.COMPLETED, step.status)
        }
        
        // All steps except "Complete" should have duration
        progress.steps.filter { it.label != "Complete" }.forEach { step ->
            assertNotNull(step.durationMs, "Step '${step.label}' should have duration")
        }
    }

    @Tag("unit")
    @Test
    fun `direct failure shows gray dash and switches to relay path`() = runTest {
        tracker.start()
        tracker.onQrParsed()
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()
        tracker.onDirectFailed()
        
        val progress = tracker.progress.first()
        
        // Should now have 7 steps (relay path)
        assertEquals(7, progress.steps.size)
        
        // QR scanned still completed
        assertEquals(StepStatus.COMPLETED, progress.steps[0].status)
        
        // Creating connection completed
        assertEquals(StepStatus.COMPLETED, progress.steps[1].status)
        
        // Direct connection should be unavailable (gray dash)
        assertEquals(StepStatus.UNAVAILABLE, progress.steps[2].status)
        assertEquals("Direct connection", progress.steps[2].label)
        assertNotNull(progress.steps[2].durationMs)
        
        // Relay connection should be in progress
        assertEquals(StepStatus.IN_PROGRESS, progress.steps[3].status)
        assertEquals("Relay connection", progress.steps[3].label)
    }

    @Tag("unit")
    @Test
    fun `relay path completes successfully`() = runTest {
        tracker.start()
        tracker.onQrParsed()
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()
        tracker.onDirectFailed()
        tracker.onSignalingComplete(usedNtfy = true)
        tracker.onDataChannelOpen()
        tracker.onAuthenticated()
        
        val progress = tracker.progress.first()

        assertTrue(progress.isComplete, "Progress should be complete")
        assertEquals(7, progress.steps.size, "Should have 7 steps")

        // All steps except "Direct connection" should be completed
        progress.steps.forEach { step ->
            when (step.label) {
                "Direct connection" -> assertEquals(StepStatus.UNAVAILABLE, step.status, "Direct connection should be UNAVAILABLE")
                else -> assertEquals(StepStatus.COMPLETED, step.status, "${step.label} should be COMPLETED")
            }
        }
    }

    @Tag("unit")
    @Test
    fun `failure marks current step as unavailable`() = runTest {
        tracker.start()
        tracker.onQrParsed()
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()
        tracker.onFailed()
        
        val progress = tracker.progress.first()
        
        // Step 2 (reaching host) should be unavailable
        assertEquals(StepStatus.UNAVAILABLE, progress.steps[2].status)
        assertNotNull(progress.steps[2].durationMs)
        
        // Later steps should still be pending
        assertEquals(StepStatus.PENDING, progress.steps[3].status)
        assertEquals(StepStatus.PENDING, progress.steps[4].status)
    }

    @Tag("unit")
    @Test
    fun `reset clears all state`() = runTest {
        tracker.start()
        tracker.onQrParsed()
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()
        tracker.reset()

        val progress = tracker.progress.first()

        // Should be back to initial state with QR step in progress
        assertEquals(6, progress.steps.size)
        assertFalse(progress.isComplete)
        assertEquals(StepStatus.IN_PROGRESS, progress.steps[0].status)
        assertEquals(StepStatus.PENDING, progress.steps[1].status)
    }

    @Tag("unit")
    @Test
    fun `step durations are tracked with injectable clock`() = runTest {
        tracker.start()
        currentTime = 100
        tracker.onQrParsed()
        currentTime = 350
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()

        val progress = tracker.progress.first()

        // QR step should have 100ms duration
        assertEquals(100L, progress.steps[0].durationMs)
        // Creating connection step should have 250ms duration (350 - 100)
        assertEquals(250L, progress.steps[1].durationMs)
    }

    // ==========================================================================
    // Precondition Tests - Verify exceptions for out-of-order calls
    // ==========================================================================

    @Tag("unit")
    @Test
    fun `onQrParsed without start throws exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onQrParsed()
        }
    }

    @Tag("unit")
    @Test
    fun `onQrParsed called twice throws exception`() {
        tracker.start()
        tracker.onQrParsed()
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onQrParsed() // Should throw - can only be called once
        }
    }

    @Tag("unit")
    @Test
    fun `onConnectionCreated before onQrParsed throws exception`() {
        tracker.start()
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onConnectionCreated() // Should throw - onQrParsed not called yet
        }
    }

    @Tag("unit")
    @Test
    fun `onSignalingComplete before connection created throws exception`() {
        tracker.start()
        tracker.onQrParsed()
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onSignalingComplete(usedNtfy = false) // Should throw - not in reaching host phase
        }
    }

    @Tag("unit")
    @Test
    fun `onDataChannelOpen before signaling complete throws exception`() {
        tracker.start()
        tracker.onQrParsed()
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onDataChannelOpen() // Should throw - signaling not complete
        }
    }

    @Tag("unit")
    @Test
    fun `onAuthenticated before data channel open throws exception`() {
        tracker.start()
        tracker.onQrParsed()
        tracker.onCreatingConnection()
        tracker.onConnectionCreated()
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onAuthenticated() // Should throw - not in authenticating phase
        }
    }

    @Tag("unit")
    @Test
    fun `onFailed before start throws exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onFailed() // Should throw - pairing not started
        }
    }
}

class PairingStepTest {

    @Tag("unit")
    @Test
    fun `format duration less than 1 second shows milliseconds`() {
        assertEquals("12ms", PairingStep.formatDuration(12))
        assertEquals("999ms", PairingStep.formatDuration(999))
    }

    @Tag("unit")
    @Test
    fun `format duration 1-10 seconds shows one decimal`() {
        assertEquals("1.0s", PairingStep.formatDuration(1000))
        assertEquals("2.1s", PairingStep.formatDuration(2100))
        assertEquals("9.9s", PairingStep.formatDuration(9900))
    }

    @Tag("unit")
    @Test
    fun `format duration over 10 seconds shows whole seconds`() {
        assertEquals("10s", PairingStep.formatDuration(10000))
        assertEquals("30s", PairingStep.formatDuration(30000))
    }

    @Tag("unit")
    @Test
    fun `formattedDuration returns null when no duration`() {
        val step = PairingStep("Test", StepStatus.PENDING, null)
        assertNull(step.formattedDuration())
    }

    @Tag("unit")
    @Test
    fun `formattedDuration returns formatted string when duration exists`() {
        val step = PairingStep("Test", StepStatus.COMPLETED, 1234)
        assertEquals("1.2s", step.formattedDuration())
    }
}
