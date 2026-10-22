package com.sudo.securegallery.vault

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher

/**
 * The encrypted vault for originals (§8.3).
 *
 * A random AES-256 data key (DEK) encrypts every original. The DEK is stored only wrapped:
 * once under PBKDF2(PIN) mixed with a [DeviceSecret] held in the Keystore, once under
 * PBKDF2(recovery code), and optionally under a biometric-bound Keystore key ([BiometricKey]).
 *
 * Nothing decrypted is written to disk: originals decrypt into memory or into a digest.
 */
class Vault(
    private val store: VaultStore,
    private val deviceSecret: DeviceSecret,
    private val biometricKey: BiometricKey? = null,
    now: () -> Long = System::currentTimeMillis,
    private val iterations: Int = PBKDF2_ITERATIONS,
    /** Test hook: runs between writing a vault file and verifying it. */
    internal val afterWrite: (File) -> Unit = {},
) {
    private val limiter = PinAttemptLimiter(store, now)

    /** An unlocked data key, held in memory only. [close] wipes it. */
    class Key internal constructor(internal val bytes: ByteArray) : AutoCloseable {
        override fun close() = bytes.fill(0)
    }

    sealed interface Unlock {
        data class Success(val key: Key) : Unlock
        data class WrongPin(val waitMillis: Long) : Unlock
        data class TooSoon(val waitMillis: Long) : Unlock
    }

    sealed interface BiometricStart {
        /** Hand this cipher to `BiometricPrompt`, then pass it to [finishBiometricUnlock]. */
        data class Ready(val cipher: Cipher) : BiometricStart
        data object NotEnabled : BiometricStart
        /** Enrollment changed: biometric unlock was turned off; use the PIN, then enable it again. */
        data object Invalidated : BiometricStart
    }

    sealed interface StoreResult {
        /** Written, decrypted back and hash-checked. */
        data class Verified(val sha256: String) : StoreResult
        /** Removed again; the original must stay where it is. */
        data class Failed(val reason: String) : StoreResult
    }

    fun isSetUp(): Boolean = store.hasKeys()

    /**
     * First-time setup: makes the data key and returns the recovery code, which the caller shows
     * once and then wipes.
     */
    fun setUp(pin: CharArray): CharArray {
        check(!isSetUp()) { "vault is already set up" }
        require(pin.size >= MIN_PIN_LENGTH) { "PIN must have at least $MIN_PIN_LENGTH characters" }
        val dek = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES)
        val code = RecoveryCode.generate()
        try {
            store.writeKey(RECOVERY, wrap(dek, recoveryKek(code, existing = null), RECOVERY))
            store.writeKey(PIN, wrap(dek, pinKek(pin, existing = null), PIN))
        } finally {
            dek.fill(0)
        }
        return code
    }

    fun unlock(pin: CharArray): Unlock {
        val wait = limiter.waitMillis()
        if (wait > 0) return Unlock.TooSoon(wait)
        val wrapped = checkNotNull(store.readKey(PIN)) { "vault is not set up" }
        val dek = unwrap(wrapped, pinKek(pin, wrapped), PIN)
        if (dek == null) {
            limiter.recordFailure()
            return Unlock.WrongPin(limiter.waitMillis())
        }
        limiter.recordSuccess()
        return Unlock.Success(Key(dek))
    }

    /** Unlocks with the recovery code; null if it is wrong. */
    fun unlockWithRecoveryCode(typed: CharArray): Key? {
        val code = RecoveryCode.normalize(typed) ?: return null
        val wrapped = checkNotNull(store.readKey(RECOVERY)) { "vault is not set up" }
        return unwrap(wrapped, recoveryKek(code, wrapped), RECOVERY)?.let(::Key).also { code.fill('0') }
    }

    /** Sets a new PIN, for example after unlocking with the recovery code. */
    fun changePin(key: Key, newPin: CharArray) {
        require(newPin.size >= MIN_PIN_LENGTH) { "PIN must have at least $MIN_PIN_LENGTH characters" }
        store.writeKey(PIN, wrap(key.bytes, pinKek(newPin, existing = null), PIN))
        limiter.recordSuccess()
    }

    fun isBiometricEnabled(): Boolean = store.readKey(BIOMETRIC) != null

    /** A cipher for `BiometricPrompt` to unlock before [enableBiometric]; null if unsupported. */
    fun biometricEnrollCipher(): Cipher? = biometricKey?.encryptCipher()

    /** Wraps the data key with a cipher the user just unlocked with a biometric. */
    fun enableBiometric(key: Key, authenticatedCipher: Cipher) {
        // The Keystore picks the IV; keep it in front of the ciphertext.
        val sealed = authenticatedCipher.iv + authenticatedCipher.doFinal(key.bytes)
        store.writeKey(BIOMETRIC, WrappedKey(salt = ByteArray(0), iterations = 0, sealed = sealed))
    }

    fun startBiometricUnlock(): BiometricStart {
        val wrapped = store.readKey(BIOMETRIC) ?: return BiometricStart.NotEnabled
        val bio = biometricKey ?: return BiometricStart.NotEnabled
        return try {
            BiometricStart.Ready(bio.decryptCipher(wrapped.sealed.copyOfRange(0, GCM_IV_BYTES)))
        } catch (_: BiometricKey.KeyInvalidated) {
            disableBiometric()
            BiometricStart.Invalidated
        }
    }

    /** Unwraps the data key with the cipher `BiometricPrompt` authenticated; null on failure. */
    fun finishBiometricUnlock(authenticatedCipher: Cipher): Key? {
        val wrapped = store.readKey(BIOMETRIC) ?: return null
        return try {
            Key(authenticatedCipher.doFinal(wrapped.sealed, GCM_IV_BYTES, wrapped.sealed.size - GCM_IV_BYTES))
                .also { limiter.recordSuccess() }
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    fun disableBiometric() {
        store.removeKey(BIOMETRIC)
        biometricKey?.delete()
    }

    /**
     * Encrypts an original into the vault, then decrypts it back and compares SHA-256 hashes
     * (write-and-verify, §8.2 step 2). On any mismatch the vault file is deleted.
     *
     * @param expectedSha256 the hash the scan recorded for this file (lowercase hex), if known.
     */
    fun store(key: Key, itemId: String, original: InputStream, expectedSha256: String? = null): StoreResult {
        val target = store.itemFile(itemId)
        val tmp = File(target.path + ".tmp")
        store.dir.mkdirs()
        return try {
            val written = VaultCrypto.encryptFile(key.bytes, itemId, original, tmp).toHex()
            afterWrite(tmp)
            val readBack = VaultCrypto.sha256(tmp, key.bytes, itemId).toHex()
            when {
                readBack != written -> fail(tmp, "decrypted copy does not match")
                expectedSha256 != null && expectedSha256 != written -> fail(tmp, "original changed since the scan")
                !tmp.renameTo(target) -> fail(tmp, "could not save the vault file")
                else -> StoreResult.Verified(written)
            }
        } catch (e: GeneralSecurityException) {
            fail(tmp, "decryption check failed: ${e.javaClass.simpleName}")
        } catch (e: java.io.IOException) {
            fail(tmp, "could not write the vault file: ${e.javaClass.simpleName}")
        }
    }

    /** True once an original has been stored and verified. */
    fun contains(itemId: String): Boolean = store.itemFile(itemId).exists()

    /** Decrypts an original into memory. Throws if the file was changed. */
    fun read(key: Key, itemId: String): ByteArray =
        ByteArrayOutputStream().also { VaultCrypto.decryptFile(key.bytes, itemId, store.itemFile(itemId), it) }.toByteArray()

    fun delete(itemId: String): Boolean = store.itemFile(itemId).delete()

    private fun fail(tmp: File, reason: String): StoreResult {
        tmp.delete()
        return StoreResult.Failed(reason)
    }

    /** A key-encryption key with the salt and iteration count that made it. */
    private class Kek(val salt: ByteArray, val iterations: Int, val bytes: ByteArray)

    private fun wrap(dek: ByteArray, kek: Kek, method: String): WrappedKey {
        try {
            return WrappedKey(kek.salt, kek.iterations, VaultCrypto.seal(kek.bytes, dek, aad(method)))
        } finally {
            kek.bytes.fill(0)
        }
    }

    private fun unwrap(wrapped: WrappedKey, kek: Kek, method: String): ByteArray? = try {
        VaultCrypto.open(kek.bytes, wrapped.sealed, aad(method))
    } catch (_: GeneralSecurityException) {
        null
    } finally {
        kek.bytes.fill(0)
    }

    /** PBKDF2(PIN) mixed with the Keystore secret. A null [existing] makes a new salt. */
    private fun pinKek(pin: CharArray, existing: WrappedKey?): Kek {
        val salt = existing?.salt ?: VaultCrypto.randomBytes(SALT_BYTES)
        val rounds = existing?.iterations ?: iterations
        val stretched = VaultCrypto.pbkdf2(pin, salt, rounds)
        try {
            return Kek(salt, rounds, deviceSecret.mix(stretched))
        } finally {
            stretched.fill(0)
        }
    }

    private fun recoveryKek(code: CharArray, existing: WrappedKey?): Kek {
        val salt = existing?.salt ?: VaultCrypto.randomBytes(SALT_BYTES)
        val rounds = existing?.iterations ?: iterations
        return Kek(salt, rounds, VaultCrypto.pbkdf2(code, salt, rounds))
    }

    private fun aad(method: String) = "secure-gallery-dek-$method-v1".toByteArray()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256. */
        const val PBKDF2_ITERATIONS = 600_000
        const val MIN_PIN_LENGTH = 6
        private const val SALT_BYTES = 16
        private const val PIN = "pin"
        private const val RECOVERY = "recovery"
        private const val BIOMETRIC = "biometric"
        private const val GCM_IV_BYTES = 12

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
