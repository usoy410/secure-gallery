package com.sudo.securegallery.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** The real Android Keystore round trip on the emulator (issue #20). */
@RunWith(AndroidJUnit4::class)
class KeystoreVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "vault-instrumented")
    private val alias = "secure_gallery_test_hmac"

    @After
    fun tearDown() {
        dir.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun keystoreSecretIsStableAndKeyed() {
        val secret = KeystoreDeviceSecret(alias)
        val a = secret.mix("input".toByteArray())
        assertEquals(32, a.size)
        assertArrayEquals(a, KeystoreDeviceSecret(alias).mix("input".toByteArray()))
        assertFalse(a.contentEquals(secret.mix("other".toByteArray())))
    }

    @Test
    fun vaultRoundTripWithTheKeystore() {
        val pin = "482915".toCharArray()
        val vault = Vault(VaultStore(dir), KeystoreDeviceSecret(alias), iterations = 10_000)
        val code = vault.setUp(pin)
        val key = (vault.unlock(pin) as Vault.Unlock.Success).key
        val original = ByteArray(100_000) { (it % 251).toByte() }

        val result = vault.store(key, "item-1", original.inputStream(), Vault.sha256Hex(original))
        assertTrue(result is Vault.StoreResult.Verified)
        assertArrayEquals(original, vault.read(key, "item-1"))
        assertTrue(vault.unlock("000000".toCharArray()) is Vault.Unlock.WrongPin)
        assertArrayEquals(original, vault.read(vault.unlockWithRecoveryCode(code)!!, "item-1"))
    }
}
