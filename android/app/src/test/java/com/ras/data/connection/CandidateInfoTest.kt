package com.ras.data.connection

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for CandidateInfo Tailscale IP detection.
 *
 * Verifies:
 * - IPv4 Tailscale IP detection (100.64.0.0/10 range)
 * - IPv6 Tailscale IP detection (fd7a:115c:a1e0::/48 range)
 */
class CandidateInfoTest {

    private fun candidateWithIp(ip: String) = CandidateInfo(
        type = "host",
        ip = ip,
        port = 12345,
        isLocal = false
    )

    // ==================== IPv4 Tailscale Detection Tests ====================

    @Tag("unit")
    @Test
    fun `isTailscaleIp returns true for IPv4 100_64 range`() {
        // 100.64.0.0/10 means 100.64.0.0 - 100.127.255.255
        assertTrue(candidateWithIp("100.64.0.1").isTailscaleIp())
        assertTrue(candidateWithIp("100.64.24.163").isTailscaleIp())
        assertTrue(candidateWithIp("100.100.100.100").isTailscaleIp())
        assertTrue(candidateWithIp("100.127.255.255").isTailscaleIp())
    }

    @Tag("unit")
    @Test
    fun `isTailscaleIp returns false for IPv4 outside range`() {
        // Below range
        assertFalse(candidateWithIp("100.63.255.255").isTailscaleIp())

        // Above range
        assertFalse(candidateWithIp("100.128.0.0").isTailscaleIp())

        // Different first octet
        assertFalse(candidateWithIp("192.168.1.1").isTailscaleIp())
        assertFalse(candidateWithIp("10.0.0.1").isTailscaleIp())

        // Public IPs
        assertFalse(candidateWithIp("8.8.8.8").isTailscaleIp())
        assertFalse(candidateWithIp("203.0.113.50").isTailscaleIp())
    }

    // ==================== IPv6 Tailscale Detection Tests ====================

    @Tag("unit")
    @Test
    fun `isTailscaleIp returns true for Tailscale IPv6 ULA range`() {
        // Tailscale's IPv6 ULA range: fd7a:115c:a1e0::/48
        assertTrue(candidateWithIp("fd7a:115c:a1e0::1").isTailscaleIp())
        assertTrue(candidateWithIp("fd7a:115c:a1e0:ab12:4843:cd96:6258:b240").isTailscaleIp())
        assertTrue(candidateWithIp("FD7A:115C:A1E0::1").isTailscaleIp()) // Case insensitive
    }

    @Tag("unit")
    @Test
    fun `isTailscaleIp returns false for non-Tailscale IPv6`() {
        assertFalse(candidateWithIp("::1").isTailscaleIp()) // Loopback
        assertFalse(candidateWithIp("fe80::1").isTailscaleIp()) // Link-local
        assertFalse(candidateWithIp("2001:db8::1").isTailscaleIp()) // Documentation range
        assertFalse(candidateWithIp("fd7a:115d:a1e0::1").isTailscaleIp()) // Different /48
        assertFalse(candidateWithIp("fd7b:115c:a1e0::1").isTailscaleIp()) // Different ULA
    }

    // ==================== Edge Cases ====================

    @Tag("unit")
    @Test
    fun `isTailscaleIp returns false for invalid IPs`() {
        assertFalse(candidateWithIp("not-an-ip").isTailscaleIp())
        assertFalse(candidateWithIp("").isTailscaleIp())
        assertFalse(candidateWithIp("100.64").isTailscaleIp())
    }
}
