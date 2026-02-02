package com.ras.data.connection

import com.ras.data.webrtc.SdpValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for VPN/NAT scenario detection.
 *
 * These tests verify:
 * - Detection of same public IP (hairpin NAT)
 * - Detection of symmetric NAT scenarios
 * - Proper handling when both devices are on VPN
 * - ICE candidate analysis utilities
 *
 * Background:
 * - Symmetric NAT: Port mappings change per destination, breaks P2P
 * - Hairpin NAT: Both devices behind same NAT, requires NAT to route internal traffic
 * - VPN scenarios: Often create symmetric NAT conditions
 */
class VpnScenarioTest {

    // ==================== Same Public IP Detection Tests ====================

    @Tag("unit")
    @Test
    fun `extractSrflxIp extracts server reflexive IP from candidate`() {
        /**
         * srflx candidates contain the public IP discovered via STUN.
         * Format: a=candidate:... srflx ... raddr <local-ip> rport <local-port>
         * The IP after "srflx" but before "raddr" is the public IP.
         */
        val candidate = "a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345"

        val ip = extractSrflxIp(candidate)

        assertEquals("203.0.113.50", ip)
    }

    @Tag("unit")
    @Test
    fun `extractSrflxIp returns null for non-srflx candidate`() {
        val hostCandidate = "a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host"

        val ip = extractSrflxIp(hostCandidate)

        assertNull(ip)
    }

    @Tag("unit")
    @Test
    fun `detectSamePublicIp returns true when offer and answer have same srflx IP`() {
        /**
         * When both devices are behind the same NAT (or same VPN server),
         * their srflx candidates will show the same public IP.
         * This is the hairpin NAT scenario.
         */
        val offerSdp = """
            v=0
            o=- 123 123 IN IP4 127.0.0.1
            s=-
            a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
            a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345
        """.trimIndent()

        val answerSdp = """
            v=0
            o=- 456 456 IN IP4 127.0.0.1
            s=-
            a=candidate:0 1 UDP 2122252543 192.168.1.200 23456 typ host
            a=candidate:1 1 UDP 1694498815 203.0.113.50 65432 typ srflx raddr 192.168.1.200 rport 23456
        """.trimIndent()

        val result = detectSamePublicIp(offerSdp, answerSdp)

        assertTrue(result.samePublicIp, "Should detect same public IP")
        assertEquals("203.0.113.50", result.offerPublicIp)
        assertEquals("203.0.113.50", result.answerPublicIp)
    }

    @Tag("unit")
    @Test
    fun `detectSamePublicIp returns false when offer and answer have different srflx IPs`() {
        /**
         * Normal case: devices on different networks have different public IPs.
         */
        val offerSdp = """
            v=0
            o=- 123 123 IN IP4 127.0.0.1
            s=-
            a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345
        """.trimIndent()

        val answerSdp = """
            v=0
            o=- 456 456 IN IP4 127.0.0.1
            s=-
            a=candidate:1 1 UDP 1694498815 198.51.100.75 65432 typ srflx raddr 10.0.0.50 rport 23456
        """.trimIndent()

        val result = detectSamePublicIp(offerSdp, answerSdp)

        assertFalse(result.samePublicIp, "Should not detect same public IP")
        assertEquals("203.0.113.50", result.offerPublicIp)
        assertEquals("198.51.100.75", result.answerPublicIp)
    }

    @Tag("unit")
    @Test
    fun `detectSamePublicIp handles missing srflx candidates`() {
        /**
         * If STUN fails, there won't be srflx candidates.
         * This might happen on restrictive networks.
         */
        val offerSdp = """
            v=0
            o=- 123 123 IN IP4 127.0.0.1
            s=-
            a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
        """.trimIndent()

        val answerSdp = """
            v=0
            o=- 456 456 IN IP4 127.0.0.1
            s=-
            a=candidate:0 1 UDP 2122252543 192.168.1.200 23456 typ host
        """.trimIndent()

        val result = detectSamePublicIp(offerSdp, answerSdp)

        assertFalse(result.samePublicIp, "Should not detect same public IP when no srflx")
        assertNull(result.offerPublicIp)
        assertNull(result.answerPublicIp)
    }

    // ==================== SdpValidator Tests ====================

    @Tag("unit")
    @Test
    fun `SdpValidator detects host candidates`() {
        val sdp = """
            v=0
            a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
        """.trimIndent()

        assertTrue(SdpValidator.hasHostCandidate(sdp))
        assertFalse(SdpValidator.hasServerReflexiveCandidate(sdp))
        assertFalse(SdpValidator.hasRelayCandidate(sdp))
    }

    @Tag("unit")
    @Test
    fun `SdpValidator detects srflx candidates`() {
        val sdp = """
            v=0
            a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345
        """.trimIndent()

        assertFalse(SdpValidator.hasHostCandidate(sdp))
        assertTrue(SdpValidator.hasServerReflexiveCandidate(sdp))
        assertFalse(SdpValidator.hasRelayCandidate(sdp))
    }

    @Tag("unit")
    @Test
    fun `SdpValidator detects relay candidates`() {
        val sdp = """
            v=0
            a=candidate:2 1 UDP 33562623 198.51.100.1 59000 typ relay raddr 203.0.113.50 rport 54321
        """.trimIndent()

        assertFalse(SdpValidator.hasHostCandidate(sdp))
        assertFalse(SdpValidator.hasServerReflexiveCandidate(sdp))
        assertTrue(SdpValidator.hasRelayCandidate(sdp))
    }

    @Tag("unit")
    @Test
    fun `SdpValidator counts all candidate types`() {
        val sdp = """
            v=0
            a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
            a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345
            a=candidate:2 1 UDP 33562623 198.51.100.1 59000 typ relay raddr 203.0.113.50 rport 54321
        """.trimIndent()

        assertEquals(3, SdpValidator.countCandidates(sdp))
        assertTrue(SdpValidator.hasHostCandidate(sdp))
        assertTrue(SdpValidator.hasServerReflexiveCandidate(sdp))
        assertTrue(SdpValidator.hasRelayCandidate(sdp))
    }

    // ==================== VPN Detection Scenario Tests ====================

    @Tag("unit")
    @Test
    fun `both devices on same VPN server scenario`() {
        /**
         * Scenario: Phone and laptop both connected to same VPN server.
         * Both will have same public IP (VPN exit node).
         * Direct P2P likely won't work (hairpin NAT not supported by most VPNs).
         *
         * Expected behavior:
         * - Detect same public IP
         * - Try host candidates first (might be on same LAN)
         * - If host fails, fall back to TURN relay (when implemented)
         */
        val phoneSdp = """
            v=0
            a=candidate:0 1 UDP 2122252543 10.8.0.5 12345 typ host
            a=candidate:1 1 UDP 1694498815 185.199.110.100 54321 typ srflx raddr 10.8.0.5 rport 12345
        """.trimIndent()

        val laptopSdp = """
            v=0
            a=candidate:0 1 UDP 2122252543 10.8.0.10 23456 typ host
            a=candidate:1 1 UDP 1694498815 185.199.110.100 65432 typ srflx raddr 10.8.0.10 rport 23456
        """.trimIndent()

        val result = detectSamePublicIp(phoneSdp, laptopSdp)

        assertTrue(result.samePublicIp, "Both on same VPN should have same public IP")
        assertEquals("185.199.110.100", result.offerPublicIp)

        // Both have host candidates on VPN subnet - might be able to connect directly
        assertTrue(SdpValidator.hasHostCandidate(phoneSdp))
        assertTrue(SdpValidator.hasHostCandidate(laptopSdp))
    }

    @Tag("unit")
    @Test
    fun `devices on different VPN servers scenario`() {
        /**
         * Scenario: Both devices on VPN but different servers/locations.
         * Different public IPs, but symmetric NAT may still block P2P.
         *
         * Expected behavior:
         * - Different public IPs
         * - ICE may still fail due to symmetric NAT
         * - Need TURN relay as fallback
         */
        val phoneSdp = """
            v=0
            a=candidate:0 1 UDP 2122252543 10.8.0.5 12345 typ host
            a=candidate:1 1 UDP 1694498815 185.199.110.100 54321 typ srflx raddr 10.8.0.5 rport 12345
        """.trimIndent()

        val laptopSdp = """
            v=0
            a=candidate:0 1 UDP 2122252543 10.9.0.10 23456 typ host
            a=candidate:1 1 UDP 1694498815 151.101.1.100 65432 typ srflx raddr 10.9.0.10 rport 23456
        """.trimIndent()

        val result = detectSamePublicIp(phoneSdp, laptopSdp)

        assertFalse(result.samePublicIp, "Different VPN servers should have different IPs")
        assertEquals("185.199.110.100", result.offerPublicIp)
        assertEquals("151.101.1.100", result.answerPublicIp)
    }

    @Tag("unit")
    @Test
    fun `no relay candidates means TURN not available`() {
        /**
         * When TURN server is not configured, relay candidates won't be present.
         * This limits connectivity options in restrictive NAT scenarios.
         */
        val sdp = """
            v=0
            a=candidate:0 1 UDP 2122252543 192.168.1.100 12345 typ host
            a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx raddr 192.168.1.100 rport 12345
        """.trimIndent()

        assertFalse(SdpValidator.hasRelayCandidate(sdp), "No TURN server configured")

        // This is a potential connectivity issue
        val candidates = SdpValidator.extractCandidates(sdp)
        assertEquals(2, candidates.size)
    }

    // ==================== Helper Functions ====================

    /**
     * Extract the public IP from an srflx (server reflexive) candidate.
     *
     * ICE candidate format:
     * a=candidate:<foundation> <component> <protocol> <priority> <ip> <port> typ <type> [extensions]
     *
     * For srflx: the <ip> is the public IP discovered via STUN
     */
    private fun extractSrflxIp(candidate: String): String? {
        if (!candidate.contains(" srflx")) return null

        // Split by spaces and find the IP (it's the 5th field, 0-indexed as 4)
        // a=candidate:1 1 UDP 1694498815 203.0.113.50 54321 typ srflx ...
        val parts = candidate.removePrefix("a=candidate:").split(" ")
        return if (parts.size >= 5) parts[4] else null
    }

    /**
     * Result of same public IP detection.
     */
    data class SamePublicIpResult(
        val samePublicIp: Boolean,
        val offerPublicIp: String?,
        val answerPublicIp: String?
    )

    /**
     * Detect if offer and answer have the same public IP (hairpin NAT scenario).
     */
    private fun detectSamePublicIp(offerSdp: String, answerSdp: String): SamePublicIpResult {
        val offerCandidates = SdpValidator.extractCandidates(offerSdp)
        val answerCandidates = SdpValidator.extractCandidates(answerSdp)

        val offerSrflxIp = offerCandidates.firstNotNullOfOrNull { extractSrflxIp(it) }
        val answerSrflxIp = answerCandidates.firstNotNullOfOrNull { extractSrflxIp(it) }

        val sameIp = offerSrflxIp != null && answerSrflxIp != null && offerSrflxIp == answerSrflxIp

        return SamePublicIpResult(
            samePublicIp = sameIp,
            offerPublicIp = offerSrflxIp,
            answerPublicIp = answerSrflxIp
        )
    }
}
