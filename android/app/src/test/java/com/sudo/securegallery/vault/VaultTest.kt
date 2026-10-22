package com.sudo.securegallery.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Vault setup, unlock, recovery and write-and-verify (issue #20). */
class VaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pin = "482915".toCharArray()
    private val original = "synthetic original bytes".toByteArray()

    private fun unlocked(vault: Vault): Vault.Key = (vault.unlock(pin) as Vault.Unlock.Success).key

    @Test
    fun setUpGivesAOneTimeRecoveryCodeAndRefusesASecondSetUp() {
        val vault = testVault(tmp.root)
        val code = vault.setUp(pin)
        assertEquals(RecoveryCode.LENGTH, code.size)
        assertTrue(vault.isSetUp())
        assertThrows(IllegalStateException::class.java) { vault.setUp(pin) }
    }

    @Test
    fun shortPinIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { testVault(tmp.root).setUp("12345".toCharArray()) }
    }

    @Test
    fun storeVerifiesAndReadsBack() {
        val vault = testVault(tmp.root)
        vault.setUp(pin)
        val key = unlocked(vault)
        val result = vault.store(key, "item-1", original.inputStream(), Vault.sha256Hex(original))
        assertEquals(Vault.StoreResult.Verified(Vault.sha256Hex(original)), result)
        assertTrue(vault.contains("item-1"))
        assertArrayEquals(original, vault.read(key, "item-1"))
        // Another session with the same PIN reads the same file.
        assertArrayEquals(original, vault.read(unlocked(testVault(tmp.root)), "item-1"))
    }

    @Test
    fun corruptedWriteIsDeletedAndReported() {
        val vault = testVault(tmp.root, afterWrite = ::flipLastByte)
        vault.setUp(pin)
        val result = vault.store(unlocked(vault), "item-1", original.inputStream())
        assertTrue(result is Vault.StoreResult.Failed)
        assertFalse(vault.contains("item-1"))
        assertEquals(emptyList<String>(), tmp.root.list()!!.filter { it.startsWith("item-1") })
    }

    @Test
    fun originalChangedSinceTheScanIsRejected() {
        val vault = testVault(tmp.root)
        vault.setUp(pin)
        val result = vault.store(unlocked(vault), "item-1", original.inputStream(), expectedSha256 = "0".repeat(64))
        assertTrue(result is Vault.StoreResult.Failed)
        assertFalse(vault.contains("item-1"))
    }

    @Test
    fun wrongPinIsRefusedAndThenSlowedDown() {
        val clock = FakeClock()
        val vault = testVault(tmp.root, clock = clock)
        vault.setUp(pin)
        val wrong = "000000".toCharArray()
        repeat(PinAttemptLimiter.FREE_ATTEMPTS) {
            assertEquals(Vault.Unlock.WrongPin(0), vault.unlock(wrong))
        }
        assertEquals(Vault.Unlock.WrongPin(30_000), vault.unlock(wrong))
        // Even the right PIN must wait now.
        assertEquals(Vault.Unlock.TooSoon(30_000), vault.unlock(pin))
        clock.millis += 30_000
        assertTrue(vault.unlock(pin) is Vault.Unlock.Success)
        // Success resets the count.
        assertEquals(Vault.Unlock.WrongPin(0), vault.unlock(wrong))
    }

    @Test
    fun attemptCountSurvivesARestart() {
        val clock = FakeClock()
        testVault(tmp.root, clock = clock).apply { setUp(pin) }
        repeat(PinAttemptLimiter.FREE_ATTEMPTS + 1) { testVault(tmp.root, clock = clock).unlock("000000".toCharArray()) }
        assertTrue(testVault(tmp.root, clock = clock).unlock(pin) is Vault.Unlock.TooSoon)
    }

    @Test
    fun recoveryCodeUnlocksAndSetsANewPin() {
        val vault = testVault(tmp.root)
        val code = vault.setUp(pin)
        vault.store(unlocked(vault), "item-1", original.inputStream())

        val typed = RecoveryCode.format(code).lowercase().toCharArray()
        val key = vault.unlockWithRecoveryCode(typed)
        assertNotNull(key)
        key!!
        assertArrayEquals(original, vault.read(key, "item-1"))
        assertNull(vault.unlockWithRecoveryCode(RecoveryCode.generate()))

        val newPin = "735102".toCharArray()
        vault.changePin(key, newPin)
        assertTrue(vault.unlock(pin) is Vault.Unlock.WrongPin)
        assertTrue(vault.unlock(newPin) is Vault.Unlock.Success)
    }

    @Test
    fun pinNeedsTheDeviceSecretButTheRecoveryCodeDoesNot() {
        val code = testVault(tmp.root).setUp(pin)
        // The same vault files on another phone: the Keystore key is not there.
        val elsewhere = testVault(tmp.root, secret = FakeDeviceSecret(seed = 9))
        assertTrue(elsewhere.unlock(pin) is Vault.Unlock.WrongPin)
        assertNotNull(elsewhere.unlockWithRecoveryCode(code))
    }

    @Test
    fun keyFileHoldsNoPinNoCodeAndNoDataKey() {
        val vault = testVault(tmp.root)
        val code = vault.setUp(pin)
        val key = unlocked(vault)
        val stored = File(tmp.root, "keys.properties").readBytes()
        val text = String(stored)
        assertFalse(text.contains(String(pin)))
        assertFalse(text.contains(String(code)))
        assertFalse(stored.containsSequence(key.bytes))
        assertFalse(text.contains(java.util.Base64.getEncoder().encodeToString(key.bytes)))
    }

    @Test
    fun closingAKeyWipesIt() {
        val vault = testVault(tmp.root)
        vault.setUp(pin)
        val key = unlocked(vault)
        key.close()
        assertTrue(key.bytes.all { it == 0.toByte() })
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }
}
