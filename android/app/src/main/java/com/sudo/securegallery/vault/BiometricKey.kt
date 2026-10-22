package com.sudo.securegallery.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The third wrap of the data key (§8.3): an AES key in the Keystore that only works after a
 * strong biometric check through `BiometricPrompt`. The vault never sees the key, only a
 * [Cipher] that the prompt has unlocked for one operation.
 */
interface BiometricKey {
    /** A cipher to wrap the data key; null if the device cannot make the key. */
    fun encryptCipher(): Cipher?

    /**
     * A cipher to unwrap the data key, or throws [KeyInvalidated] when the user changed the
     * enrolled fingerprints or faces, which permanently kills the key.
     */
    fun decryptCipher(iv: ByteArray): Cipher

    fun delete()

    class KeyInvalidated : Exception("biometric key was invalidated by an enrollment change")
}

/** [BiometricKey] in the Android Keystore, invalidated when biometric enrollment changes. */
class KeystoreBiometricKey(private val alias: String = ALIAS) : BiometricKey {

    override fun encryptCipher(): Cipher? = try {
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, freshKey()) }
    } catch (_: java.security.GeneralSecurityException) {
        null
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: throw BiometricKey.KeyInvalidated()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw BiometricKey.KeyInvalidated()
        }
    }

    override fun delete() = keyStore().deleteEntry(alias)

    /** Always a new key when (re-)enabling, so an old wrap can never be reused. */
    private fun freshKey(): SecretKey {
        delete()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        const val ALIAS = "secure_gallery_biometric"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
