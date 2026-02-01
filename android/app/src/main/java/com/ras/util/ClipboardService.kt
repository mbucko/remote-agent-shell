package com.ras.util

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for clipboard operations.
 *
 * Abstracts clipboard access for dependency injection and testability.
 */
/**
 * Result of extracting image from clipboard.
 */
data class ClipboardImage(
    val data: ByteArray,
    val format: ImageFormat
) {
    enum class ImageFormat { JPEG, PNG }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardImage) return false
        return data.contentEquals(other.data) && format == other.format
    }

    override fun hashCode(): Int = data.contentHashCode() * 31 + format.hashCode()
}

interface ClipboardService {
    /**
     * Extract text from clipboard.
     *
     * @return Text content or null if clipboard is empty or contains non-text
     */
    fun extractText(): String?

    /**
     * Extract image from clipboard.
     *
     * @return Image data and format, or null if clipboard doesn't contain an image
     */
    fun extractImage(): ClipboardImage?

    /**
     * Check if clipboard contains an image.
     */
    fun hasImage(): Boolean

    /**
     * Prepare clipboard text for sending to terminal.
     *
     * Encodes to UTF-8 and enforces the 64KB size limit with UTF-8 safe truncation.
     *
     * @param text Raw clipboard text
     * @return UTF-8 encoded bytes, truncated if necessary, or null if empty
     */
    fun prepareForTerminal(text: String): ByteArray?

    /**
     * Check if text would be truncated when prepared for terminal.
     *
     * @param text Text to check
     * @return true if text exceeds 64KB when encoded as UTF-8
     */
    fun wouldTruncate(text: String): Boolean

    /**
     * Read an image from a content URI and convert to PNG.
     *
     * @param uri Content URI from photo picker or file provider
     * @return PNG image data, or null if read fails or image too large
     */
    fun readImageFromUri(uri: Uri): ClipboardImage?

    companion object {
        /** Maximum paste size in bytes (64KB). */
        const val MAX_PASTE_BYTES = 65536
        /** Maximum image size in bytes (5MB). */
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        /**
         * Chunk size for image transfer.
         *
         * RFC 8831 (WebRTC Data Channels) Section 6.6 recommends 16KB max message size
         * to avoid SCTP MTU/congestion issues. This is also the safe limit for
         * cross-implementation compatibility (libwebrtc ↔ aiortc).
         *
         * See: https://lgrahl.de/articles/demystifying-webrtc-dc-size-limit.html
         */
        const val IMAGE_CHUNK_SIZE = 16 * 1024
    }
}

/**
 * Android implementation of ClipboardService.
 *
 * Handles:
 * - Plain text (text/plain)
 * - HTML with text fallback (text/html)
 * - URI lists (text/uri-list)
 * - Ignores non-text content (images, etc.)
 */
@Singleton
class AndroidClipboardService @Inject constructor(
    @ApplicationContext private val context: Context
) : ClipboardService {

    override fun extractText(): String? {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null

        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        val item = clip.getItemAt(0)

        // coerceToText handles various MIME types gracefully
        val text = item.coerceToText(context)?.toString()

        return if (text.isNullOrEmpty()) null else text
    }

    override fun extractImage(): ClipboardImage? {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null

        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        val description = clip.description
        val item = clip.getItemAt(0)

        // Check for image MIME types
        val hasImageMime = (0 until description.mimeTypeCount).any { i ->
            description.getMimeType(i).startsWith("image/")
        }

        if (!hasImageMime) {
            // Try to get image from URI anyway (some apps use content:// URIs)
            val uri = item.uri ?: return null
            return tryExtractImageFromUri(uri)
        }

        // Try URI first (most common for images)
        item.uri?.let { uri ->
            tryExtractImageFromUri(uri)?.let { return it }
        }

        return null
    }

    private fun tryExtractImageFromUri(uri: Uri): ClipboardImage? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                if (bytes.size > ClipboardService.MAX_IMAGE_BYTES) {
                    return null // Image too large
                }

                // Determine format from bytes (magic numbers)
                val format = when {
                    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
                        ClipboardImage.ImageFormat.JPEG
                    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() ->
                        ClipboardImage.ImageFormat.PNG
                    else -> {
                        // Try to decode and re-encode as PNG
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: return null
                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        bitmap.recycle()
                        val pngBytes = output.toByteArray()
                        if (pngBytes.size > ClipboardService.MAX_IMAGE_BYTES) {
                            return null
                        }
                        return ClipboardImage(pngBytes, ClipboardImage.ImageFormat.PNG)
                    }
                }
                ClipboardImage(bytes, format)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun hasImage(): Boolean {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false

        val clip = clipboardManager.primaryClip ?: return false
        if (clip.itemCount == 0) return false

        val description = clip.description

        // Check for image MIME types
        return (0 until description.mimeTypeCount).any { i ->
            description.getMimeType(i).startsWith("image/")
        }
    }

    override fun prepareForTerminal(text: String): ByteArray? {
        if (text.isEmpty()) return null

        val bytes = text.toByteArray(Charsets.UTF_8)

        return if (bytes.size > ClipboardService.MAX_PASTE_BYTES) {
            truncateUtf8Safe(bytes, ClipboardService.MAX_PASTE_BYTES)
        } else {
            bytes
        }
    }

    override fun wouldTruncate(text: String): Boolean {
        return text.toByteArray(Charsets.UTF_8).size > ClipboardService.MAX_PASTE_BYTES
    }

    override fun readImageFromUri(uri: Uri): ClipboardImage? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                val pngBytes = output.toByteArray()

                if (pngBytes.size > ClipboardService.MAX_IMAGE_BYTES) {
                    return null // Image too large
                }

                ClipboardImage(pngBytes, ClipboardImage.ImageFormat.PNG)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Truncate UTF-8 bytes without splitting multi-byte characters.
     *
     * UTF-8 encoding:
     * - 1-byte: 0xxxxxxx (ASCII, 0x00-0x7F)
     * - 2-byte: 110xxxxx 10xxxxxx (lead 0xC0-0xDF)
     * - 3-byte: 1110xxxx 10xxxxxx 10xxxxxx (lead 0xE0-0xEF)
     * - 4-byte: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx (lead 0xF0-0xF7)
     *
     * Continuation bytes always match pattern 10xxxxxx (0x80-0xBF)
     *
     * @param bytes UTF-8 encoded byte array
     * @param maxBytes Maximum number of bytes to keep
     * @return Truncated byte array at valid UTF-8 boundary
     */
    internal fun truncateUtf8Safe(bytes: ByteArray, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (bytes.size <= maxBytes) return bytes

        var end = maxBytes

        // Scan backwards to find a valid UTF-8 boundary
        while (end > 0) {
            val byte = bytes[end - 1].toInt() and 0xFF

            // Check if this is a continuation byte (10xxxxxx)
            if ((byte and 0xC0) == 0x80) {
                // We're in the middle of a multi-byte character
                end--
                continue
            }

            // It's either ASCII or a lead byte
            if (byte < 0x80) {
                // ASCII - we're at a valid boundary
                break
            }

            // It's a lead byte - calculate expected character length
            val charLen = when {
                byte < 0xE0 -> 2  // 2-byte sequence (110xxxxx)
                byte < 0xF0 -> 3  // 3-byte sequence (1110xxxx)
                else -> 4        // 4-byte sequence (11110xxx)
            }

            // Check if the full character fits within maxBytes
            if ((end - 1) + charLen <= maxBytes) {
                // Character fits, we're at a valid boundary
                break
            } else {
                // Character would be truncated, exclude it
                end--
                break
            }
        }

        return bytes.copyOf(end)
    }
}
