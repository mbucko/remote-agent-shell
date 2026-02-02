package com.ras.data.webrtc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for VpnCandidateInjector.
 *
 * Verifies:
 * - Tailscale IP detection (100.64.0.0/10 range)
 * - Filtering of Tailscale candidates from SDP
 */
class VpnCandidateInjectorTest {

    // ==================== Tailscale IP Detection Tests ====================

    @Tag("unit")
    @Test
    fun `isTailscaleIp returns true for 100_64 range`() {
        // 100.64.0.0/10 means 100.64.0.0 - 100.127.255.255
        assertTrue(VpnCandidateInjector.isTailscaleIp("100.64.0.1"))
        assertTrue(VpnCandidateInjector.isTailscaleIp("100.64.24.163"))
        assertTrue(VpnCandidateInjector.isTailscaleIp("100.100.100.100"))
        assertTrue(VpnCandidateInjector.isTailscaleIp("100.127.255.255"))
    }

    @Tag("unit")
    @Test
    fun `isTailscaleIp returns false for IPs outside range`() {
        // Below range
        assertFalse(VpnCandidateInjector.isTailscaleIp("100.63.255.255"))

        // Above range
        assertFalse(VpnCandidateInjector.isTailscaleIp("100.128.0.0"))

        // Different first octet
        assertFalse(VpnCandidateInjector.isTailscaleIp("192.168.1.1"))
        assertFalse(VpnCandidateInjector.isTailscaleIp("10.0.0.1"))

        // Public IPs
        assertFalse(VpnCandidateInjector.isTailscaleIp("8.8.8.8"))
        assertFalse(VpnCandidateInjector.isTailscaleIp("203.0.113.50"))
    }

    @Tag("unit")
    @Test
    fun `isTailscaleIp returns false for invalid IPs`() {
        assertFalse(VpnCandidateInjector.isTailscaleIp("not-an-ip"))
        assertFalse(VpnCandidateInjector.isTailscaleIp(""))
        assertFalse(VpnCandidateInjector.isTailscaleIp("100.64"))
        assertFalse(VpnCandidateInjector.isTailscaleIp("::1"))
    }

    // ==================== SDP Filtering Tests ====================

    @Tag("unit")
    @Test
    fun `filterTailscaleCandidates removes Tailscale IP candidates`() {
        val sdp = """
            v=0
            o=- 1234567890 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            a=candidate:1 1 udp 2130706431 192.168.1.100 54321 typ host
            a=candidate:2 1 udp 2130706431 100.64.24.163 43098 typ host
            a=candidate:3 1 udp 1694498815 203.0.113.50 54321 typ srflx
            a=candidate:4 1 udp 2130706431 100.100.50.25 48467 typ host
        """.trimIndent()

        val filtered = VpnCandidateInjector.filterTailscaleCandidates(sdp)

        // Should keep non-Tailscale candidates
        assertTrue(filtered.contains("192.168.1.100"))
        assertTrue(filtered.contains("203.0.113.50"))

        // Should remove Tailscale candidates
        assertFalse(filtered.contains("100.64.24.163"))
        assertFalse(filtered.contains("100.100.50.25"))
    }

    @Tag("unit")
    @Test
    fun `filterTailscaleCandidates preserves non-candidate lines`() {
        val sdp = """
            v=0
            o=- 1234567890 2 IN IP4 127.0.0.1
            s=-
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            a=ice-ufrag:abcd
            a=ice-pwd:secretpassword
            a=candidate:1 1 udp 2130706431 100.64.24.163 43098 typ host
        """.trimIndent()

        val filtered = VpnCandidateInjector.filterTailscaleCandidates(sdp)

        // Should preserve all non-candidate lines
        assertTrue(filtered.contains("v=0"))
        assertTrue(filtered.contains("a=ice-ufrag:abcd"))
        assertTrue(filtered.contains("a=ice-pwd:secretpassword"))
        assertTrue(filtered.contains("m=application"))

        // Should remove Tailscale candidate
        assertFalse(filtered.contains("100.64.24.163"))
    }

    @Tag("unit")
    @Test
    fun `filterTailscaleCandidates returns unchanged SDP when no Tailscale candidates`() {
        val sdp = """
            v=0
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            a=candidate:1 1 udp 2130706431 192.168.1.100 54321 typ host
            a=candidate:2 1 udp 1694498815 203.0.113.50 54321 typ srflx
        """.trimIndent()

        val filtered = VpnCandidateInjector.filterTailscaleCandidates(sdp)

        // All original candidates should remain
        assertTrue(filtered.contains("192.168.1.100"))
        assertTrue(filtered.contains("203.0.113.50"))
    }
}
