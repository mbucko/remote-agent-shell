package com.ras.data.webrtc

import android.util.Log

/**
 * Parses and formats ICE stats from RTCStatsReport for diagnostic logging.
 *
 * Extracted from WebRTCClient for testability — no native WebRTC dependencies.
 * Accepts pre-extracted stats maps and formats them for logging.
 */
class IceStatsLogger {

    companion object {
        private const val TAG = "WebRTCClient"
    }

    data class IceStats(
        val localType: String,
        val localIp: String,
        val remoteType: String,
        val remoteIp: String,
        val bytesSent: Long,
        val bytesReceived: Long,
        val lastPacketAgo: String,
        val rtt: String
    )

    /**
     * Extract ICE stats from a raw stats map (as returned by RTCStatsReport).
     *
     * @param statsMap All stats entries keyed by ID
     * @return Parsed IceStats if a nominated/succeeded candidate pair was found, null otherwise
     */
    fun extractStats(statsMap: Map<String, StatsEntry>): IceStats? {
        // Find nominated or succeeded candidate pair
        val pair = statsMap.values
            .filter { it.type == "candidate-pair" }
            .firstOrNull { it.members["nominated"] == true || it.members["state"] == "succeeded" }
            ?: return null

        val localId = pair.members["localCandidateId"] as? String
        val remoteId = pair.members["remoteCandidateId"] as? String
        val bytesSent = (pair.members["bytesSent"] as? Number)?.toLong() ?: 0L
        val bytesReceived = (pair.members["bytesReceived"] as? Number)?.toLong() ?: 0L
        val lastPacketReceived = pair.members["lastPacketReceivedTimestamp"] as? Number
        val rtt = pair.members["currentRoundTripTime"] as? Number

        val localCandidate = localId?.let { statsMap[it] }
        val remoteCandidate = remoteId?.let { statsMap[it] }
        val localType = localCandidate?.members?.get("candidateType") as? String ?: "?"
        val remoteType = remoteCandidate?.members?.get("candidateType") as? String ?: "?"
        val localIp = localCandidate?.members?.get("ip") as? String
            ?: localCandidate?.members?.get("address") as? String ?: "?"
        val remoteIp = remoteCandidate?.members?.get("ip") as? String
            ?: remoteCandidate?.members?.get("address") as? String ?: "?"

        val lastPktAgo = formatLastPacket(lastPacketReceived)
        val rttStr = formatRtt(rtt)

        return IceStats(
            localType = localType,
            localIp = localIp,
            remoteType = remoteType,
            remoteIp = remoteIp,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            lastPacketAgo = lastPktAgo,
            rtt = rttStr
        )
    }

    /**
     * Format a log line from IceStats.
     */
    fun formatLogLine(trigger: String, stats: IceStats): String {
        return "ICE $trigger stats — " +
            "local=${stats.localType}(${stats.localIp}), remote=${stats.remoteType}(${stats.remoteIp}), " +
            "sent=${stats.bytesSent}B, recv=${stats.bytesReceived}B, " +
            "lastPkt=${stats.lastPacketAgo}, rtt=${stats.rtt}"
    }

    private fun formatLastPacket(lastPacketReceived: Number?): String {
        if (lastPacketReceived == null) return "n/a"
        val lastPktMs = lastPacketReceived.toDouble()
        if (lastPktMs <= 0) return "n/a"
        val now = System.currentTimeMillis().toDouble()
        return "${((now - lastPktMs) / 1000).toLong()}s ago"
    }

    private fun formatRtt(rtt: Number?): String {
        if (rtt == null) return "n/a"
        return "${(rtt.toDouble() * 1000).toInt()}ms"
    }
}

/**
 * Simplified stats entry for testability — mirrors RTCStats structure.
 */
data class StatsEntry(
    val type: String,
    val id: String,
    val members: Map<String, Any?>
)
