package com.ras.data.webrtc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for IceStatsLogger — ICE stats extraction and formatting.
 *
 * No native WebRTC dependencies needed. Tests the parsing logic
 * that converts RTCStatsReport data into diagnostic log lines.
 */
@Tag("unit")
class IceStatsLoggerTest {

    private val logger = IceStatsLogger()

    private fun buildStatsMap(
        pairState: String = "succeeded",
        nominated: Boolean = false,
        localType: String = "host",
        localIp: String = "192.168.1.10",
        remoteType: String = "srflx",
        remoteIp: String = "203.0.113.5",
        bytesSent: Long = 12345L,
        bytesReceived: Long = 67890L,
        lastPacketReceivedTimestamp: Double? = null,
        currentRoundTripTime: Double? = 0.025
    ): Map<String, StatsEntry> {
        val pairMembers = mutableMapOf<String, Any?>(
            "state" to pairState,
            "localCandidateId" to "local-1",
            "remoteCandidateId" to "remote-1",
            "bytesSent" to bytesSent,
            "bytesReceived" to bytesReceived,
            "currentRoundTripTime" to currentRoundTripTime
        )
        if (nominated) pairMembers["nominated"] = true
        if (lastPacketReceivedTimestamp != null) {
            pairMembers["lastPacketReceivedTimestamp"] = lastPacketReceivedTimestamp
        }

        return mapOf(
            "pair-1" to StatsEntry(
                type = "candidate-pair",
                id = "pair-1",
                members = pairMembers
            ),
            "local-1" to StatsEntry(
                type = "local-candidate",
                id = "local-1",
                members = mapOf(
                    "candidateType" to localType,
                    "ip" to localIp
                )
            ),
            "remote-1" to StatsEntry(
                type = "remote-candidate",
                id = "remote-1",
                members = mapOf(
                    "candidateType" to remoteType,
                    "ip" to remoteIp
                )
            )
        )
    }

    @Test
    fun `extractStats returns stats for succeeded candidate pair`() {
        val statsMap = buildStatsMap()
        val stats = logger.extractStats(statsMap)

        assertNotNull(stats)
        assertEquals("host", stats!!.localType)
        assertEquals("192.168.1.10", stats.localIp)
        assertEquals("srflx", stats.remoteType)
        assertEquals("203.0.113.5", stats.remoteIp)
        assertEquals(12345L, stats.bytesSent)
        assertEquals(67890L, stats.bytesReceived)
        assertEquals("25ms", stats.rtt)
    }

    @Test
    fun `extractStats returns stats for nominated candidate pair`() {
        val statsMap = buildStatsMap(pairState = "in-progress", nominated = true)
        val stats = logger.extractStats(statsMap)

        assertNotNull(stats)
        assertEquals("host", stats!!.localType)
    }

    @Test
    fun `extractStats returns null when no candidate pair exists`() {
        val statsMap = mapOf(
            "local-1" to StatsEntry(
                type = "local-candidate",
                id = "local-1",
                members = mapOf("candidateType" to "host", "ip" to "192.168.1.10")
            )
        )
        val stats = logger.extractStats(statsMap)
        assertNull(stats)
    }

    @Test
    fun `extractStats returns null when no succeeded or nominated pair`() {
        val statsMap = mapOf(
            "pair-1" to StatsEntry(
                type = "candidate-pair",
                id = "pair-1",
                members = mapOf(
                    "state" to "failed",
                    "localCandidateId" to "local-1",
                    "remoteCandidateId" to "remote-1"
                )
            )
        )
        val stats = logger.extractStats(statsMap)
        assertNull(stats)
    }

    @Test
    fun `extractStats handles missing candidate objects gracefully`() {
        val statsMap = mapOf(
            "pair-1" to StatsEntry(
                type = "candidate-pair",
                id = "pair-1",
                members = mapOf(
                    "state" to "succeeded",
                    "localCandidateId" to "missing-local",
                    "remoteCandidateId" to "missing-remote",
                    "bytesSent" to 100L,
                    "bytesReceived" to 200L
                )
            )
        )
        val stats = logger.extractStats(statsMap)

        assertNotNull(stats)
        assertEquals("?", stats!!.localType)
        assertEquals("?", stats.localIp)
        assertEquals("?", stats.remoteType)
        assertEquals("?", stats.remoteIp)
    }

    @Test
    fun `extractStats uses address field when ip is missing`() {
        val statsMap = mapOf(
            "pair-1" to StatsEntry(
                type = "candidate-pair",
                id = "pair-1",
                members = mapOf(
                    "state" to "succeeded",
                    "localCandidateId" to "local-1",
                    "remoteCandidateId" to "remote-1",
                    "bytesSent" to 0L,
                    "bytesReceived" to 0L
                )
            ),
            "local-1" to StatsEntry(
                type = "local-candidate",
                id = "local-1",
                members = mapOf("candidateType" to "host", "address" to "10.0.0.1")
            ),
            "remote-1" to StatsEntry(
                type = "remote-candidate",
                id = "remote-1",
                members = mapOf("candidateType" to "relay", "address" to "turn.example.com")
            )
        )
        val stats = logger.extractStats(statsMap)

        assertNotNull(stats)
        assertEquals("10.0.0.1", stats!!.localIp)
        assertEquals("turn.example.com", stats.remoteIp)
    }

    @Test
    fun `extractStats handles null RTT`() {
        val statsMap = buildStatsMap(currentRoundTripTime = null)
        val stats = logger.extractStats(statsMap)

        assertNotNull(stats)
        assertEquals("n/a", stats!!.rtt)
    }

    @Test
    fun `extractStats handles null lastPacketReceived`() {
        val statsMap = buildStatsMap(lastPacketReceivedTimestamp = null)
        val stats = logger.extractStats(statsMap)

        assertNotNull(stats)
        assertEquals("n/a", stats!!.lastPacketAgo)
    }

    @Test
    fun `formatLogLine produces correct format`() {
        val stats = IceStatsLogger.IceStats(
            localType = "host",
            localIp = "192.168.1.10",
            remoteType = "srflx",
            remoteIp = "203.0.113.5",
            bytesSent = 12345L,
            bytesReceived = 67890L,
            lastPacketAgo = "3s ago",
            rtt = "25ms"
        )

        val line = logger.formatLogLine("DISCONNECTED", stats)

        assertEquals(
            "ICE DISCONNECTED stats — " +
                "local=host(192.168.1.10), remote=srflx(203.0.113.5), " +
                "sent=12345B, recv=67890B, " +
                "lastPkt=3s ago, rtt=25ms",
            line
        )
    }

    @Test
    fun `formatLogLine with FAILED trigger`() {
        val stats = IceStatsLogger.IceStats(
            localType = "relay",
            localIp = "turn.server.com",
            remoteType = "relay",
            remoteIp = "turn2.server.com",
            bytesSent = 0L,
            bytesReceived = 0L,
            lastPacketAgo = "n/a",
            rtt = "n/a"
        )

        val line = logger.formatLogLine("FAILED", stats)

        assertTrue(line.startsWith("ICE FAILED stats"))
        assertTrue(line.contains("relay(turn.server.com)"))
    }

    @Test
    fun `extractStats with zero bytes`() {
        val statsMap = buildStatsMap(bytesSent = 0L, bytesReceived = 0L)
        val stats = logger.extractStats(statsMap)

        assertNotNull(stats)
        assertEquals(0L, stats!!.bytesSent)
        assertEquals(0L, stats.bytesReceived)
    }

    @Test
    fun `extractStats with high RTT`() {
        val statsMap = buildStatsMap(currentRoundTripTime = 1.5)  // 1500ms
        val stats = logger.extractStats(statsMap)

        assertNotNull(stats)
        assertEquals("1500ms", stats!!.rtt)
    }
}
