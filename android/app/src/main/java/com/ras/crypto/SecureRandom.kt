package com.ras.crypto

import java.security.SecureRandom

/**
 * CSPRNG wrapper - ALWAYS use this instead of kotlin.random.Random
 */
object CryptoRandom {
    private val secureRandom = SecureRandom()

    fun nextBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun nextHex(size: Int): String {
        return nextBytes(size).toHex()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
