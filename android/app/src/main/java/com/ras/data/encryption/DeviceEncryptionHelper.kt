package com.ras.data.encryption

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for providing encryption keys.
 * Allows for testing with mock keys.
 */
interface KeyProvider {
    fun getKey(): SecretKey
}

/**
 * Production key provider using Android Keystore.
 */
class KeystoreKeyProvider(private val alias: String) : KeyProvider {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    override fun getKey(): SecretKey {
        val existingKey = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }

        // Generate new key in Android Keystore
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val keySpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
}

/**
 * Helper for encrypting and decrypting device credentials.
 *
 * Uses Android Keystore AES-GCM encryption for secure storage of master secrets.
 * Each device's master_secret is encrypted separately before storing in Room database.
 */
@Singleton
class DeviceEncryptionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Internal constructor for testing
    internal constructor(context: Context, keyProvider: KeyProvider) : this(context) {
        this.testKeyProvider = keyProvider
    }

    private var testKeyProvider: KeyProvider? = null
    companion object {
        private const val DEVICE_ENCRYPTION_KEY_ALIAS = "ras_device_encryption_key"
        private const val GCM_TAG_LENGTH = 128
    }

    private val actualKeyProvider: KeyProvider by lazy {
        testKeyProvider ?: KeystoreKeyProvider(DEVICE_ENCRYPTION_KEY_ALIAS)
    }

    /**
     * Encrypt plaintext data using AES-GCM.
     *
     * @param plaintext The data to encrypt
     * @return Pair of (ciphertext, IV)
     */
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = actualKeyProvider.getKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        return Pair(ciphertext, iv)
    }

    /**
     * Decrypt ciphertext using AES-GCM.
     *
     * @param ciphertext The encrypted data
     * @param iv The initialization vector used during encryption
     * @return The decrypted plaintext
     * @throws Exception if decryption fails (wrong key, corrupted data, etc.)
     */
    fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val key = actualKeyProvider.getKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return cipher.doFinal(ciphertext)
    }
}
