package com.ras.data.webrtc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

class SdpValidatorTest {

    private val validSdpWithCandidates = """
        v=0
        o=- 123456 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE 0
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        c=IN IP4 0.0.0.0
        a=candidate:1 1 udp 2130706431 192.168.1.100 50000 typ host
        a=candidate:2 1 udp 1694498815 203.0.113.50 50000 typ srflx raddr 192.168.1.100 rport 50000
        a=end-of-candidates
    """.trimIndent()

    private val sdpWithoutCandidates = """
        v=0
        o=- 123456 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE 0
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        c=IN IP4 0.0.0.0
    """.trimIndent()

    private val sdpWithRelay = """
        v=0
        o=- 123456 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        a=candidate:1 1 udp 2130706431 192.168.1.100 50000 typ host
        a=candidate:2 1 udp 16777215 10.0.0.1 50000 typ relay raddr 192.168.1.100 rport 50000
    """.trimIndent()

    @Tag("unit")
    @Test
    fun `countCandidates returns correct count`() {
        assertEquals(2, SdpValidator.countCandidates(validSdpWithCandidates))
        assertEquals(0, SdpValidator.countCandidates(sdpWithoutCandidates))
    }

    @Tag("unit")
    @Test
    fun `hasHostCandidate detects host candidates`() {
        assertTrue(SdpValidator.hasHostCandidate(validSdpWithCandidates))
        assertFalse(SdpValidator.hasHostCandidate(sdpWithoutCandidates))
    }

    @Tag("unit")
    @Test
    fun `hasServerReflexiveCandidate detects srflx candidates`() {
        assertTrue(SdpValidator.hasServerReflexiveCandidate(validSdpWithCandidates))
        assertFalse(SdpValidator.hasServerReflexiveCandidate(sdpWithoutCandidates))
    }

    @Tag("unit")
    @Test
    fun `hasRelayCandidate detects relay candidates`() {
        assertTrue(SdpValidator.hasRelayCandidate(sdpWithRelay))
        assertFalse(SdpValidator.hasRelayCandidate(validSdpWithCandidates))
    }

    @Tag("unit")
    @Test
    fun `requireCandidates throws for SDP without candidates`() {
        assertThrows(IllegalStateException::class.java) {
            SdpValidator.requireCandidates(sdpWithoutCandidates, "Test SDP")
        }
    }

    @Tag("unit")
    @Test
    fun `requireCandidates passes for valid SDP`() {
        // Should not throw
        SdpValidator.requireCandidates(validSdpWithCandidates, "Test SDP")
    }

    @Tag("unit")
    @Test
    fun `requireCandidates error message is descriptive`() {
        val exception = assertThrows(IllegalStateException::class.java) {
            SdpValidator.requireCandidates(sdpWithoutCandidates, "Offer SDP")
        }
        assertTrue(exception.message!!.contains("Offer SDP"))
        assertTrue(exception.message!!.contains("no ICE candidates"))
    }

    @Tag("unit")
    @Test
    fun `extractCandidates returns all candidate lines`() {
        val candidates = SdpValidator.extractCandidates(validSdpWithCandidates)
        assertEquals(2, candidates.size)
        assertTrue(candidates[0].contains("host"))
        assertTrue(candidates[1].contains("srflx"))
    }

    @Tag("unit")
    @Test
    fun `extractCandidates returns empty list for no candidates`() {
        val candidates = SdpValidator.extractCandidates(sdpWithoutCandidates)
        assertTrue(candidates.isEmpty())
    }

    @Tag("unit")
    @Test
    fun `countCandidates handles empty string`() {
        assertEquals(0, SdpValidator.countCandidates(""))
    }

    @Tag("unit")
    @Test
    fun `MIN_EXPECTED_CANDIDATES is at least 1`() {
        assertTrue(SdpValidator.MIN_EXPECTED_CANDIDATES >= 1)
    }
}
