package com.ras.pairing

import com.ras.ui.pairing.PairingStep
import com.ras.ui.pairing.StepStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
    fun `initial state has 3 steps with QR step in progress`() = runTest {
        tracker.start()

        val progress = tracker.progress.first()

        assertEquals("Scanning QR code...", progress.currentMessage)
        assertFalse(progress.isComplete)
        assertEquals(3, progress.steps.size)

        assertEquals(StepStatus.IN_PROGRESS, progress.steps[0].status)
        assertEquals("QR code scanned", progress.steps[0].label)
        assertEquals(StepStatus.PENDING, progress.steps[1].status)
        assertEquals("Exchanging credentials", progress.steps[1].label)
        assertEquals(StepStatus.PENDING, progress.steps[2].status)
        assertEquals("Complete", progress.steps[2].label)
    }

    @Tag("unit")
    @Test
    fun `onQrParsed completes step 0 and starts exchanging`() = runTest {
        tracker.start()
        currentTime = 50
        tracker.onQrParsed()

        val progress = tracker.progress.first()

        assertEquals("Exchanging credentials...", progress.currentMessage)
        assertEquals(StepStatus.COMPLETED, progress.steps[0].status)
        assertEquals(50L, progress.steps[0].durationMs)
        assertEquals(StepStatus.IN_PROGRESS, progress.steps[1].status)
    }

    @Tag("unit")
    @Test
    fun `onExchanging marks step 1 in progress`() = runTest {
        tracker.start()
        tracker.onQrParsed()
        tracker.onExchanging()

        val progress = tracker.progress.first()

        assertEquals(StepStatus.IN_PROGRESS, progress.steps[1].status)
    }

    @Tag("unit")
    @Test
    fun `onComplete marks all steps completed`() = runTest {
        tracker.start()
        currentTime = 100
        tracker.onQrParsed()
        currentTime = 350
        tracker.onComplete()

        val progress = tracker.progress.first()

        assertTrue(progress.isComplete)
        assertEquals("Paired successfully!", progress.currentMessage)
        assertEquals(StepStatus.COMPLETED, progress.steps[0].status)
        assertEquals(StepStatus.COMPLETED, progress.steps[1].status)
        assertEquals(StepStatus.COMPLETED, progress.steps[2].status)

        // QR step should have 100ms duration
        assertEquals(100L, progress.steps[0].durationMs)
        // Exchanging step should have 250ms duration
        assertEquals(250L, progress.steps[1].durationMs)
    }

    @Tag("unit")
    @Test
    fun `onFailed marks current step unavailable`() = runTest {
        tracker.start()
        currentTime = 100
        tracker.onQrParsed()
        currentTime = 400
        tracker.onFailed()

        val progress = tracker.progress.first()

        assertEquals(StepStatus.COMPLETED, progress.steps[0].status)
        assertEquals(StepStatus.UNAVAILABLE, progress.steps[1].status)
        assertEquals(300L, progress.steps[1].durationMs)
        assertEquals(StepStatus.PENDING, progress.steps[2].status)
    }

    @Tag("unit")
    @Test
    fun `step durations are tracked with injectable clock`() = runTest {
        tracker.start()
        currentTime = 100
        tracker.onQrParsed()
        currentTime = 350
        tracker.onComplete()

        val progress = tracker.progress.first()

        assertEquals(100L, progress.steps[0].durationMs)
        assertEquals(250L, progress.steps[1].durationMs)
    }

    @Tag("unit")
    @Test
    fun `reset clears all state`() = runTest {
        tracker.start()
        tracker.onQrParsed()
        tracker.onComplete()
        tracker.reset()

        val progress = tracker.progress.first()

        assertEquals(3, progress.steps.size)
        assertFalse(progress.isComplete)
        assertEquals(StepStatus.IN_PROGRESS, progress.steps[0].status)
        assertEquals(StepStatus.PENDING, progress.steps[1].status)
        assertEquals(StepStatus.PENDING, progress.steps[2].status)
    }

    // Precondition tests

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
            tracker.onQrParsed()
        }
    }

    @Tag("unit")
    @Test
    fun `onComplete before onQrParsed throws exception`() {
        tracker.start()
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onComplete()
        }
    }

    @Tag("unit")
    @Test
    fun `onFailed before start throws exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            tracker.onFailed()
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
