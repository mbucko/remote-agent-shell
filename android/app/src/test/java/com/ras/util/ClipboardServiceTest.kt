package com.ras.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AndroidClipboardService.
 *
 * Tests cover:
 * - extractText with various clipboard states
 * - UTF-8 encoding of various content types
 * - Size limit enforcement (64KB)
 * - UTF-8 safe truncation at character boundaries
 * - Edge cases (empty, single char, emoji, etc.)
 */
class ClipboardServiceTest {

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var service: AndroidClipboardService

    @Before
    fun setup() {
        clipboardManager = mockk(relaxed = true)
        context = mockk {
            every { getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        }
        service = AndroidClipboardService(context)
    }

    // =========================================================================
    // extractText tests
    // =========================================================================

    @Test
    fun `extractText returns null when clipboard manager unavailable`() {
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns null
        val service = AndroidClipboardService(context)

        val result = service.extractText()

        assertNull(result)
    }

    @Test
    fun `extractText returns null when primaryClip is null`() {
        every { clipboardManager.primaryClip } returns null

        val result = service.extractText()

        assertNull(result)
    }

    @Test
    fun `extractText returns null when clip has no items`() {
        val emptyClip = mockk<ClipData> {
            every { itemCount } returns 0
        }
        every { clipboardManager.primaryClip } returns emptyClip

        val result = service.extractText()

        assertNull(result)
    }

    @Test
    fun `extractText returns text from clipboard`() {
        val item = mockk<ClipData.Item> {
            every { coerceToText(context) } returns "clipboard text"
        }
        val clip = mockk<ClipData> {
            every { itemCount } returns 1
            every { getItemAt(0) } returns item
        }
        every { clipboardManager.primaryClip } returns clip

        val result = service.extractText()

        assertEquals("clipboard text", result)
    }

    @Test
    fun `extractText returns null when coerceToText returns empty string`() {
        val item = mockk<ClipData.Item> {
            every { coerceToText(context) } returns ""
        }
        val clip = mockk<ClipData> {
            every { itemCount } returns 1
            every { getItemAt(0) } returns item
        }
        every { clipboardManager.primaryClip } returns clip

        val result = service.extractText()

        assertNull(result)
    }

    @Test
    fun `extractText returns null when coerceToText returns null`() {
        val item = mockk<ClipData.Item> {
            every { coerceToText(context) } returns null
        }
        val clip = mockk<ClipData> {
            every { itemCount } returns 1
            every { getItemAt(0) } returns item
        }
        every { clipboardManager.primaryClip } returns clip

        val result = service.extractText()

        assertNull(result)
    }

    // =========================================================================
    // prepareForTerminal tests
    // =========================================================================

    @Test
    fun `prepareForTerminal returns null for empty string`() {
        val result = service.prepareForTerminal("")
        assertNull(result)
    }

    @Test
    fun `prepareForTerminal encodes simple ASCII`() {
        val result = service.prepareForTerminal("hello world")
        assertNotNull(result)
        assertArrayEquals("hello world".toByteArray(), result)
    }

    @Test
    fun `prepareForTerminal encodes unicode CJK`() {
        val result = service.prepareForTerminal("Hello \u4e16\u754c")
        assertNotNull(result)
        // "世界" in UTF-8 is E4 B8 96 E7 95 8C
        val expected = "Hello \u4e16\u754c".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `prepareForTerminal encodes emoji`() {
        val result = service.prepareForTerminal("\uD83C\uDF89")  // 🎉
        assertNotNull(result)
        // 🎉 in UTF-8 is F0 9F 8E 89
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x8E.toByte(), 0x89.toByte()), result)
    }

    @Test
    fun `prepareForTerminal preserves newlines`() {
        val result = service.prepareForTerminal("line1\nline2\r\nline3")
        assertNotNull(result)
        assertArrayEquals("line1\nline2\r\nline3".toByteArray(), result)
    }

    @Test
    fun `prepareForTerminal handles exactly 64KB`() {
        val text = "x".repeat(65536)
        val result = service.prepareForTerminal(text)
        assertNotNull(result)
        assertEquals(65536, result!!.size)
    }

    @Test
    fun `prepareForTerminal truncates over 64KB`() {
        val text = "x".repeat(70000)
        val result = service.prepareForTerminal(text)
        assertNotNull(result)
        assertEquals(65536, result!!.size)
    }

    // =========================================================================
    // wouldTruncate tests
    // =========================================================================

    @Test
    fun `wouldTruncate returns false for small text`() {
        assertFalse(service.wouldTruncate("hello"))
    }

    @Test
    fun `wouldTruncate returns false for exactly 64KB`() {
        val text = "x".repeat(65536)
        assertFalse(service.wouldTruncate(text))
    }

    @Test
    fun `wouldTruncate returns true for over 64KB`() {
        val text = "x".repeat(65537)
        assertTrue(service.wouldTruncate(text))
    }

    @Test
    fun `wouldTruncate accounts for UTF-8 encoding size`() {
        // Each CJK character is 3 bytes in UTF-8
        // 21846 characters = 65538 bytes (over limit)
        val text = "\u4e16".repeat(21846)
        assertTrue(service.wouldTruncate(text))

        // 21845 characters = 65535 bytes (under limit)
        val text2 = "\u4e16".repeat(21845)
        assertFalse(service.wouldTruncate(text2))
    }

    // =========================================================================
    // truncateUtf8Safe tests
    // =========================================================================

    @Test
    fun `truncateUtf8Safe returns original if under limit`() {
        val bytes = "hello".toByteArray()
        val result = service.truncateUtf8Safe(bytes, 100)
        assertArrayEquals(bytes, result)
    }

    @Test
    fun `truncateUtf8Safe truncates ASCII at exact boundary`() {
        val bytes = "hello world".toByteArray()
        val result = service.truncateUtf8Safe(bytes, 5)
        assertArrayEquals("hello".toByteArray(), result)
    }

    @Test
    fun `truncateUtf8Safe does not split 2-byte UTF-8 character`() {
        // "xxxé" where é is C3 A9 (2 bytes)
        val text = "xxx\u00e9"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(5, bytes.size)  // 3 + 2

        // Truncate at 4 bytes - should not split the é
        val result = service.truncateUtf8Safe(bytes, 4)
        assertEquals(3, result.size)
        assertEquals("xxx", String(result, Charsets.UTF_8))
    }

    @Test
    fun `truncateUtf8Safe does not split 3-byte UTF-8 character`() {
        // "xx世" where 世 is E4 B8 96 (3 bytes)
        val text = "xx\u4e16"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(5, bytes.size)  // 2 + 3

        // Truncate at 4 bytes - should not split the 世
        val result = service.truncateUtf8Safe(bytes, 4)
        assertEquals(2, result.size)
        assertEquals("xx", String(result, Charsets.UTF_8))
    }

    @Test
    fun `truncateUtf8Safe does not split 4-byte UTF-8 character`() {
        // "x🎉" where 🎉 is F0 9F 8E 89 (4 bytes)
        val text = "x\uD83C\uDF89"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(5, bytes.size)  // 1 + 4

        // Truncate at 4 bytes - should not split the emoji
        val result = service.truncateUtf8Safe(bytes, 4)
        assertEquals(1, result.size)
        assertEquals("x", String(result, Charsets.UTF_8))
    }

    @Test
    fun `truncateUtf8Safe includes character that fits exactly`() {
        // "x🎉" is 5 bytes, truncate at 5 should include emoji
        val text = "x\uD83C\uDF89"
        val bytes = text.toByteArray(Charsets.UTF_8)

        val result = service.truncateUtf8Safe(bytes, 5)
        assertEquals(5, result.size)
        assertEquals(text, String(result, Charsets.UTF_8))
    }

    @Test
    fun `truncateUtf8Safe handles 64KB boundary with 2-byte char`() {
        // 65535 x's + é (2 bytes) = 65537 bytes
        val text = "x".repeat(65535) + "\u00e9"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(65537, bytes.size)

        val result = service.truncateUtf8Safe(bytes, 65536)
        assertEquals(65535, result.size)  // é excluded to avoid split

        // Should be valid UTF-8
        val decoded = String(result, Charsets.UTF_8)
        assertEquals(65535, decoded.length)
    }

    @Test
    fun `truncateUtf8Safe handles 64KB boundary with 3-byte char`() {
        // 65534 x's + 世 (3 bytes) = 65537 bytes
        val text = "x".repeat(65534) + "\u4e16"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(65537, bytes.size)

        val result = service.truncateUtf8Safe(bytes, 65536)
        assertEquals(65534, result.size)  // 世 excluded

        val decoded = String(result, Charsets.UTF_8)
        assertEquals(65534, decoded.length)
    }

    @Test
    fun `truncateUtf8Safe handles 64KB boundary with 4-byte char`() {
        // 65533 x's + 🎉 (4 bytes) = 65537 bytes
        val text = "x".repeat(65533) + "\uD83C\uDF89"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(65537, bytes.size)

        val result = service.truncateUtf8Safe(bytes, 65536)
        assertEquals(65533, result.size)  // emoji excluded

        val decoded = String(result, Charsets.UTF_8)
        assertEquals(65533, decoded.length)
    }

    @Test
    fun `truncateUtf8Safe includes char that fits exactly at boundary`() {
        // 65532 x's + 🎉 (4 bytes) = 65536 bytes exactly
        val text = "x".repeat(65532) + "\uD83C\uDF89"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(65536, bytes.size)

        val result = service.truncateUtf8Safe(bytes, 65536)
        assertEquals(65536, result.size)  // emoji fits

        val decoded = String(result, Charsets.UTF_8)
        assertTrue(decoded.endsWith("\uD83C\uDF89"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncateUtf8Safe throws for non-positive maxBytes`() {
        service.truncateUtf8Safe("hello".toByteArray(), 0)
    }

    // =========================================================================
    // Edge case tests
    // =========================================================================

    @Test
    fun `handles single character`() {
        val result = service.prepareForTerminal("x")
        assertNotNull(result)
        assertArrayEquals(byteArrayOf('x'.code.toByte()), result)
    }

    @Test
    fun `handles single emoji`() {
        val result = service.prepareForTerminal("\uD83C\uDF89")
        assertNotNull(result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `handles whitespace only`() {
        val result = service.prepareForTerminal("   ")
        assertNotNull(result)
        assertArrayEquals("   ".toByteArray(), result)
    }

    @Test
    fun `handles BOM marker`() {
        val result = service.prepareForTerminal("\uFEFFhello")
        assertNotNull(result)
        // BOM is EF BB BF in UTF-8
        val expected = "\uFEFFhello".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `handles shell metacharacters literally`() {
        val dangerous = "; rm -rf / && \$(whoami)"
        val result = service.prepareForTerminal(dangerous)
        assertNotNull(result)
        assertArrayEquals(dangerous.toByteArray(), result)
    }

    @Test
    fun `handles control characters`() {
        val control = "hello\u0007world\u001b[31m"
        val result = service.prepareForTerminal(control)
        assertNotNull(result)
        assertArrayEquals(control.toByteArray(), result)
    }

    @Test
    fun `handles null byte in string`() {
        val withNull = "hello\u0000world"
        val result = service.prepareForTerminal(withNull)
        assertNotNull(result)
        assertArrayEquals(withNull.toByteArray(), result)
    }
}
