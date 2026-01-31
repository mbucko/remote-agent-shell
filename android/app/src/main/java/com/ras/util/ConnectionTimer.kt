package com.ras.util

import android.util.Log

/**
 * Utility for tracking connection timing phases with millisecond precision.
 *
 * Usage:
 * ```
 * val timer = ConnectionTimer("webrtc")
 * timer.mark("offer_created")
 * // ... do work ...
 * timer.mark("ice_connected")
 * timer.logSummary()
 * ```
 *
 * All timing logs use the [TIMING] prefix for easy filtering in logcat:
 * ```
 * adb logcat | grep TIMING
 * ```
 */
class ConnectionTimer(private val label: String) {
    companion object {
        private const val TAG = "TIMING"
    }

    private val startTime = System.nanoTime()
    private val marks = mutableListOf<Pair<String, Double>>()

    /**
     * Record a timing mark and return elapsed ms since timer creation.
     */
    fun mark(phase: String): Double {
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        synchronized(marks) {
            marks.add(phase to elapsedMs)
        }
        return elapsedMs
    }

    /**
     * Record and log a timing mark.
     */
    fun logMark(phase: String) {
        val ms = mark(phase)
        Log.i(TAG, "[$label] $phase @ ${formatMs(ms)}")
    }

    /**
     * Log a summary of all timing marks.
     */
    fun logSummary() {
        synchronized(marks) {
            if (marks.isEmpty()) return
            val summary = marks.joinToString(" | ") { "${it.first}=${it.second.toInt()}ms" }
            val total = marks.last().second.toInt()
            Log.i(TAG, "[$label] summary: $summary (total=${total}ms)")
        }
    }

    /**
     * Get elapsed time since timer creation in milliseconds.
     */
    fun elapsedMs(): Double {
        return (System.nanoTime() - startTime) / 1_000_000.0
    }

    /**
     * Get all marks as a list of (phase, elapsedMs) pairs.
     */
    fun getMarks(): List<Pair<String, Double>> {
        synchronized(marks) {
            return marks.toList()
        }
    }

    /**
     * Reset the timer (clear marks and reset start time).
     */
    fun reset() {
        synchronized(marks) {
            marks.clear()
        }
    }

    private fun formatMs(ms: Double): String {
        return if (ms < 1000) {
            "${String.format("%.1f", ms)}ms"
        } else {
            "${String.format("%.2f", ms / 1000)}s"
        }
    }
}

/**
 * Global connection timer for tracking end-to-end connection timing.
 *
 * This singleton allows different components (Orchestrator, Strategy, WebRTCClient)
 * to log timing marks to the same timer for a complete picture.
 *
 * Usage:
 * ```
 * GlobalConnectionTimer.start("session-123")
 * GlobalConnectionTimer.logMark("offer_created")
 * GlobalConnectionTimer.logMark("answer_received")
 * GlobalConnectionTimer.logSummary()
 * ```
 */
object GlobalConnectionTimer {
    @Volatile
    private var timer: ConnectionTimer? = null

    /**
     * Start a new global timer, replacing any existing one.
     */
    fun start(label: String = "connection") {
        timer = ConnectionTimer(label)
        Log.i("TIMING", "[$label] started")
    }

    /**
     * Log a timing mark to the global timer.
     * No-op if timer not started.
     */
    fun logMark(phase: String) {
        timer?.logMark(phase)
    }

    /**
     * Log a summary of the global timer.
     * No-op if timer not started.
     */
    fun logSummary() {
        timer?.logSummary()
    }

    /**
     * Get elapsed time since timer start.
     * Returns 0 if timer not started.
     */
    fun elapsedMs(): Double {
        return timer?.elapsedMs() ?: 0.0
    }

    /**
     * Clear the global timer.
     */
    fun clear() {
        timer = null
    }
}
