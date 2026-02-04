package com.ras.data.connection

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for LanDirectTransport.
 *
 * Note: Full integration tests require a running daemon. These unit tests
 * verify basic properties and exception behavior.
 */
class LanDirectTransportTest {

    // ==================== Type Tests ====================

    @Tag("unit")
    @Test
    fun `TransportType LAN_DIRECT exists`() {
        // Verify the enum value exists
        val type = TransportType.LAN_DIRECT
        assertEquals("LAN Direct", type.displayName)
    }

    // ==================== Exception Tests ====================

    @Tag("unit")
    @Test
    fun `LanDirectAuthException can be created with message`() {
        val exception = LanDirectAuthException("Invalid signature")
        assertEquals("Invalid signature", exception.message)
        assertNull(exception.cause)
    }

    @Tag("unit")
    @Test
    fun `LanDirectAuthException can be created with message and cause`() {
        val cause = RuntimeException("Underlying error")
        val exception = LanDirectAuthException("Auth failed", cause)
        assertEquals("Auth failed", exception.message)
        assertEquals(cause, exception.cause)
    }

    @Tag("unit")
    @Test
    fun `TransportException isRecoverable defaults to false`() {
        val exception = TransportException("Error")
        assertFalse(exception.isRecoverable)
    }

    @Tag("unit")
    @Test
    fun `TransportException can set isRecoverable`() {
        val exception = TransportException("Timeout", isRecoverable = true)
        assertTrue(exception.isRecoverable)
    }
}
