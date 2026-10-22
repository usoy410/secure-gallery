package com.sudo.securegallery.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * A secret that never leaves this device, mixed into the PIN-derived key (§8.3). Without it,
 * a copied vault cannot be brute-forced off the phone.
 */
interface DeviceSecret {
    /** HMAC-SHA256 of [input] under the device-held key: 32 bytes, same input gives same output. */
    fun mix(input: ByteArray): ByteArray
}

/** [DeviceSecret] backed by an HMAC key in the Android Keystore. The key bytes are never exposed. */
class KeystoreDeviceSecret(private val alias: String = ALIAS) : DeviceSecret {

    override fun mix(input: ByteArray): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key())
        return mac.doFinal(input)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        return generator.generateKey()
    }

    companion object {
        const val ALIAS = "secure_gallery_pin_hmac"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
