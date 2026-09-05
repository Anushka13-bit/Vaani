package com.vaanimitra.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KeystoreCrypto — Android Keystore-backed AES-256-GCM encryption for adapter files and DB (§7).
 *
 * Keys are generated in the Android Hardware Security Module (HSM) where available
 * and never leave the secure environment.
 *
 * Usage:
 *   val crypto = KeystoreCrypto("vaanimitra_adapter_key")
 *   val encrypted = crypto.encrypt(plainBytes)
 *   val decrypted = crypto.decrypt(encrypted)
 */
class KeystoreCrypto(private val keyAlias: String = "vaanimitra_master_key") {

    companion object {
        private const val TAG = "KeystoreCrypto"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    /**
     * Encrypt [plainBytes] using the Keystore-backed AES-256-GCM key.
     * Returns IV + ciphertext concatenated as a Base64 string.
     */
    fun encrypt(plainBytes: ByteArray): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainBytes)
        val combined = iv + cipherBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypt a Base64-encoded [encryptedBase64] string produced by [encrypt].
     */
    fun decrypt(encryptedBase64: String): ByteArray {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val cipherBytes = combined.copyOfRange(12, combined.size)
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(cipherBytes)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        return if (keyStore.containsAlias(keyAlias)) {
            (keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            Log.i(TAG, "Generating new Keystore key: $keyAlias")
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)  // background use; enable for high-security
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                .also { it.init(spec) }
                .generateKey()
        }
    }
}
