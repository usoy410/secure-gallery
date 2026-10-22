package com.sudo.securegallery.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Biometric wrap of the data key and the fall-back when enrollment changes (issue #21). */
class VaultBiometricTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pin = "482915".toCharArray()
    private val original = "synthetic original".toByteArray()

    /** A plain AES key standing in for the biometric-bound Keystore key. */
    private class FakeBiometricKey : BiometricKey {
        var key: SecretKey? = null
        var invalidated = false
        var deleted = false

        override fun encryptCipher(): Cipher {
            key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            invalidated = false
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }

        override fun decryptCipher(iv: ByteArray): Cipher {
            if (invalidated || key == null) throw BiometricKey.KeyInvalidated()
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        }

        override fun delete() {
            deleted = true
            key = null
        }
    }

    private fun setUpVault(bio: BiometricKey): Pair<Vault, Vault.Key> {
        val vault = testVault(tmp.root, biometric = bio)
        vault.setUp(pin)
        val key = (vault.unlock(pin) as Vault.Unlock.Success).key
        vault.store(key, "item-1", original.inputStream())
        return vault to key
    }

    @Test
    fun biometricIsOffUntilEnabled() {
        val (vault, _) = setUpVault(FakeBiometricKey())
        assertFalse(vault.isBiometricEnabled())
        assertEquals(Vault.BiometricStart.NotEnabled, vault.startBiometricUnlock())
    }

    @Test
    fun enabledBiometricUnlocksTheSameDataKey() {
        val bio = FakeBiometricKey()
        val (vault, key) = setUpVault(bio)
        vault.enableBiometric(key, vault.biometricEnrollCipher()!!)
        assertTrue(vault.isBiometricEnabled())

        val start = vault.startBiometricUnlock() as Vault.BiometricStart.Ready
        val unlocked = vault.finishBiometricUnlock(start.cipher)!!
        assertArrayEquals(original, vault.read(unlocked, "item-1"))
    }

    @Test
    fun enrollmentChangeTurnsBiometricOffAndThePinStillWorks() {
        val bio = FakeBiometricKey()
        val (vault, key) = setUpVault(bio)
        vault.enableBiometric(key, vault.biometricEnrollCipher()!!)

        bio.invalidated = true // the user added a fingerprint
        assertEquals(Vault.BiometricStart.Invalidated, vault.startBiometricUnlock())
        assertFalse(vault.isBiometricEnabled())
        assertTrue(bio.deleted)
        assertTrue(vault.unlock(pin) is Vault.Unlock.Success)

        // Re-enrolling after a PIN unlock works with a fresh key.
        val again = (vault.unlock(pin) as Vault.Unlock.Success).key
        vault.enableBiometric(again, vault.biometricEnrollCipher()!!)
        val start = vault.startBiometricUnlock() as Vault.BiometricStart.Ready
        assertArrayEquals(original, vault.read(vault.finishBiometricUnlock(start.cipher)!!, "item-1"))
    }

    @Test
    fun aCipherThatDoesNotMatchIsRejected() {
        val bio = FakeBiometricKey()
        val (vault, key) = setUpVault(bio)
        vault.enableBiometric(key, vault.biometricEnrollCipher()!!)
        val wrong = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(), GCMParameterSpec(128, ByteArray(12)))
        }
        assertNull(vault.finishBiometricUnlock(wrong))
    }

    @Test
    fun disablingRemovesTheWrapAndTheKey() {
        val bio = FakeBiometricKey()
        val (vault, key) = setUpVault(bio)
        vault.enableBiometric(key, vault.biometricEnrollCipher()!!)
        vault.disableBiometric()
        assertFalse(vault.isBiometricEnabled())
        assertTrue(bio.deleted)
        // PIN and recovery wraps are untouched.
        assertTrue(vault.unlock(pin) is Vault.Unlock.Success)
    }
}
