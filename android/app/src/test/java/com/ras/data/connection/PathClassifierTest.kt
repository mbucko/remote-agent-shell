package com.ras.data.connection

import org.junit.Test
import org.junit.Assert.*

class PathClassifierTest {

    @Test
    fun `classifyPath returns LAN_DIRECT when both host candidates on same subnet`() {
        val local = CandidateInfo("host", "192.168.1.45", 54321, true)
        val remote = CandidateInfo("host", "192.168.1.100", 54322, true)

        val result = PathClassifier.classifyPath(local, remote)

        assertEquals(PathType.LAN_DIRECT, result)
    }

    @Test
    fun `classifyPath returns WEBRTC_DIRECT when host candidates on different subnets`() {
        val local = CandidateInfo("host", "192.168.1.45", 54321, true)
        val remote = CandidateInfo("host", "10.0.0.100", 54322, true)

        val result = PathClassifier.classifyPath(local, remote)

        // Different subnets via host candidates should be WebRTC Direct (not true LAN)
        assertEquals(PathType.WEBRTC_DIRECT, result)
    }

    @Test
    fun `classifyPath returns WEBRTC_DIRECT when srflx candidates present`() {
        val local = CandidateInfo("srflx", "203.0.113.5", 54321, false)
        val remote = CandidateInfo("srflx", "198.51.100.10", 54322, false)

        val result = PathClassifier.classifyPath(local, remote)

        assertEquals(PathType.WEBRTC_DIRECT, result)
    }

    @Test
    fun `classifyPath returns TAILSCALE when Tailscale IP detected`() {
        val local = CandidateInfo("host", "100.64.5.1", 54321, true)
        val remote = CandidateInfo("host", "10.0.0.100", 54322, true)

        val result = PathClassifier.classifyPath(local, remote)

        assertEquals(PathType.TAILSCALE, result)
    }

    @Test
    fun `classifyPath returns TAILSCALE for any Tailscale IP`() {
        val local = CandidateInfo("host", "192.168.1.45", 54321, true)
        val remote = CandidateInfo("srflx", "100.127.255.255", 54322, false)

        val result = PathClassifier.classifyPath(local, remote)

        assertEquals(PathType.TAILSCALE, result)
    }

    @Test
    fun `classifyPath returns RELAY when local is relay`() {
        val local = CandidateInfo("relay", "203.0.113.10", 3478, false)
        val remote = CandidateInfo("srflx", "198.51.100.10", 54322, false)

        val result = PathClassifier.classifyPath(local, remote)

        assertEquals(PathType.RELAY, result)
    }

    @Test
    fun `classifyPath returns RELAY when remote is relay`() {
        val local = CandidateInfo("srflx", "203.0.113.5", 54321, false)
        val remote = CandidateInfo("relay", "203.0.113.10", 3478, false)

        val result = PathClassifier.classifyPath(local, remote)

        assertEquals(PathType.RELAY, result)
    }

    @Test
    fun `classifyPath prioritizes RELAY over other types`() {
        // Even with Tailscale IP, relay takes precedence
        val local = CandidateInfo("relay", "100.64.5.1", 3478, false)
        val remote = CandidateInfo("host", "10.0.0.100", 54322, true)

        val result = PathClassifier.classifyPath(local, remote)

        assertEquals(PathType.RELAY, result)
    }

    @Test
    fun `isTailscaleIp returns true for valid Tailscale range`() {
        val tailscaleIps = listOf(
            "100.64.0.1",
            "100.100.50.25",
            "100.127.255.255"
        )

        tailscaleIps.forEach { ip ->
            val candidate = CandidateInfo("host", ip, 54321, true)
            assertTrue("$ip should be Tailscale", candidate.isTailscaleIp())
        }
    }

    @Test
    fun `isTailscaleIp returns false for non-Tailscale IPs`() {
        val nonTailscaleIps = listOf(
            "100.63.255.255",  // Just below range
            "100.128.0.1",   // Just above range
            "192.168.1.1",
            "10.0.0.1",
            "203.0.113.5"
        )

        nonTailscaleIps.forEach { ip ->
            val candidate = CandidateInfo("host", ip, 54321, true)
            assertFalse("$ip should not be Tailscale", candidate.isTailscaleIp())
        }
    }

    @Test
    fun `isPrivateIp correctly identifies private ranges`() {
        assertTrue(CandidateInfo("host", "10.0.0.1", 54321, true).isPrivateIp())
        assertTrue(CandidateInfo("host", "192.168.1.1", 54321, true).isPrivateIp())
        assertTrue(CandidateInfo("host", "172.16.0.1", 54321, true).isPrivateIp())
        assertTrue(CandidateInfo("host", "172.31.255.255", 54321, true).isPrivateIp())

        assertFalse(CandidateInfo("host", "172.15.0.1", 54321, true).isPrivateIp())
        assertFalse(CandidateInfo("host", "172.32.0.1", 54321, true).isPrivateIp())
        assertFalse(CandidateInfo("host", "203.0.113.5", 54321, true).isPrivateIp())
        assertFalse(CandidateInfo("host", "100.64.0.1", 54321, true).isPrivateIp())
    }

    @Test
    fun `ConnectionPath label returns correct string for each type`() {
        val local = CandidateInfo("host", "192.168.1.45", 54321, true)
        val remote = CandidateInfo("host", "192.168.1.100", 54322, true)

        assertEquals("LAN Direct", ConnectionPath(local, remote, PathType.LAN_DIRECT).label)
        assertEquals("WebRTC Direct", ConnectionPath(local, remote, PathType.WEBRTC_DIRECT).label)
        assertEquals("Tailscale VPN", ConnectionPath(local, remote, PathType.TAILSCALE).label)
        assertEquals("WebRTC Relay", ConnectionPath(local, remote, PathType.RELAY).label)
    }

    @Test
    fun `showLocalIps returns true only for LAN_DIRECT and TAILSCALE`() {
        val local = CandidateInfo("host", "192.168.1.45", 54321, true)
        val remote = CandidateInfo("host", "192.168.1.100", 54322, true)

        assertTrue(ConnectionPath(local, remote, PathType.LAN_DIRECT).showLocalIps)
        assertTrue(ConnectionPath(local, remote, PathType.TAILSCALE).showLocalIps)
        assertFalse(ConnectionPath(local, remote, PathType.WEBRTC_DIRECT).showLocalIps)
        assertFalse(ConnectionPath(local, remote, PathType.RELAY).showLocalIps)
    }
}
