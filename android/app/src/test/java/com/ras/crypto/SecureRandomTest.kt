package com.ras.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class SecureRandomTest {

    @Tag("unit")
    @Test
    fun `nextBytes returns correct size`() {
        val result = CryptoRandom.nextBytes(32)
        assertEquals(32, result.size)
    }

    @Tag("unit")
    @Test
    fun `nextBytes returns different values each time`() {
        val a = CryptoRandom.nextBytes(32)
        val b = CryptoRandom.nextBytes(32)

        // Statistically almost impossible to be equal
        assertNotEquals(a.toHex(), b.toHex())
    }

    @Tag("unit")
    @Test
    fun `nextHex returns correct length`() {
        val result = CryptoRandom.nextHex(16)
        assertEquals(32, result.length) // 16 bytes = 32 hex chars
    }

    @Tag("unit")
    @Test
    fun `toHex converts bytes correctly`() {
        val bytes = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())
        assertEquals("0123456789abcdef", bytes.toHex())
    }

    @Tag("unit")
    @Test
    fun `toHex handles zeros`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x00)
        assertEquals("000000", bytes.toHex())
    }

    @Tag("unit")
    @Test
    fun `hexToBytes converts correctly`() {
        val hex = "0123456789abcdef"
        val expected = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())

        assertTrue(expected.contentEquals(hex.hexToBytes()))
    }

    @Tag("unit")
    @Test
    fun `hexToBytes handles uppercase`() {
        val hex = "ABCDEF"
        val expected = byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte())

        assertTrue(expected.contentEquals(hex.hexToBytes()))
    }

    @Tag("unit")
    @Test
    fun `hexToBytes rejects odd length`() {
        assertThrows(IllegalStateException::class.java) {
            "abc".hexToBytes()
        }
    }

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
