package com.ras.data.webrtc

/**
 * Validates SDP content for required fields.
 *
 * Used to catch configuration errors early rather than mysterious timeouts.
 */
object SdpValidator {

    /**
     * Minimum number of ICE candidates expected in a valid offer/answer.
     * At least one host candidate should always be present.
     */
    const val MIN_EXPECTED_CANDIDATES = 1

    /**
     * Validate that SDP contains ICE candidates.
     *
     * @param sdp The SDP string to validate
     * @param description Human-readable description for error messages
     * @throws IllegalStateException if no candidates found
     */
    fun requireCandidates(sdp: String, description: String = "SDP") {
        val candidateCount = countCandidates(sdp)
        if (candidateCount < MIN_EXPECTED_CANDIDATES) {
            throw IllegalStateException(
                "$description contains no ICE candidates. " +
                "This usually means ICE gathering didn't complete before SDP was sent. " +
                "Candidates found: $candidateCount"
            )
        }
    }

    /**
     * Count ICE candidates in SDP.
     */
    fun countCandidates(sdp: String): Int {
        return sdp.lines().count { it.startsWith("a=candidate:") }
    }

    /**
     * Check if SDP contains at least one host candidate (local network).
     * Looks for "typ host" pattern which may be at end of line.
     */
    fun hasHostCandidate(sdp: String): Boolean {
        return sdp.lines().any {
            it.startsWith("a=candidate:") && it.contains(" host")
        }
    }

    /**
     * Check if SDP contains server-reflexive candidate (STUN).
     * Looks for "typ srflx" pattern.
     */
    fun hasServerReflexiveCandidate(sdp: String): Boolean {
        return sdp.lines().any {
            it.startsWith("a=candidate:") && it.contains(" srflx")
        }
    }

    /**
     * Check if SDP contains relay candidate (TURN).
     * Looks for "typ relay" pattern.
     */
    fun hasRelayCandidate(sdp: String): Boolean {
        return sdp.lines().any {
            it.startsWith("a=candidate:") && it.contains(" relay")
        }
    }

    /**
     * Extract all candidates from SDP for debugging.
     */
    fun extractCandidates(sdp: String): List<String> {
        return sdp.lines().filter { it.startsWith("a=candidate:") }
    }
}
