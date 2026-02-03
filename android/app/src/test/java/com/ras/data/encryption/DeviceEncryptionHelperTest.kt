package com.ras.data.encryption

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Tag("unit")
@ExtendWith(RobolectricExtension::class)
class DeviceEncryptionHelperTest {

    private lateinit var helper: DeviceEncryptionHelper

    // Test key provider that returns a fixed key for testing
    private class TestKeyProvider : KeyProvider {
        private val key: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        override fun getKey(): SecretKey = key
    }

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        helper = DeviceEncryptionHelper(context, TestKeyProvider())
    }

    @Test
    fun `encrypt and decrypt returns original data`() = runTest {
        val plaintext = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val (encrypted, iv) = helper.encrypt(plaintext)
        val decrypted = helper.decrypt(encrypted, iv)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertext for same plaintext`() = runTest {
        val plaintext = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val (encrypted1, iv1) = helper.encrypt(plaintext)
        val (encrypted2, iv2) = helper.encrypt(plaintext)

        // Different IVs produce different ciphertexts
        assertFalse(encrypted1.contentEquals(encrypted2))
        assertFalse(iv1.contentEquals(iv2))
    }

    @Test
    fun `encrypt produces valid IV length`() = runTest {
        val plaintext = byteArrayOf(1, 2, 3, 4, 5)

        val (_, iv) = helper.encrypt(plaintext)

        assertEquals(12, iv.size) // GCM IV is 12 bytes
    }

    @Test
    fun `decrypt with wrong IV fails`() = runTest {
        val plaintext = byteArrayOf(1, 2, 3, 4, 5)
        val (encrypted, _) = helper.encrypt(plaintext)
        val wrongIv = ByteArray(12) { 0 }

        assertThrows(Exception::class.java) {
            helper.decrypt(encrypted, wrongIv)
        }
    }

    @Test
    fun `decrypt with corrupted ciphertext fails`() = runTest {
        val plaintext = byteArrayOf(1, 2, 3, 4, 5)
        val (encrypted, iv) = helper.encrypt(plaintext)

        // Corrupt the ciphertext
        val corrupted = encrypted.clone()
        corrupted[0] = (corrupted[0] + 1).toByte()

        assertThrows(Exception::class.java) {
            helper.decrypt(corrupted, iv)
        }
    }

    @Test
    fun `can encrypt large data`() = runTest {
        val plaintext = ByteArray(1024) { it.toByte() }

        val (encrypted, iv) = helper.encrypt(plaintext)
        val decrypted = helper.decrypt(encrypted, iv)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `can encrypt 32-byte master secret`() = runTest {
        val masterSecret = ByteArray(32) { it.toByte() }

        val (encrypted, iv) = helper.encrypt(masterSecret)
        val decrypted = helper.decrypt(encrypted, iv)

        assertArrayEquals(masterSecret, decrypted)
    }

    @Test
    fun `empty data can be encrypted and decrypted`() = runTest {
        val plaintext = byteArrayOf()

        val (encrypted, iv) = helper.encrypt(plaintext)
        val decrypted = helper.decrypt(encrypted, iv)

        assertArrayEquals(plaintext, decrypted)
    }
}
