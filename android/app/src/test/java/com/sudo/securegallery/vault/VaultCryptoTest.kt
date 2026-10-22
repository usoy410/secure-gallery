package com.sudo.securegallery.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

/** AES-GCM wrapping and vault files (issue #20). */
class VaultCryptoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val kek = VaultCrypto.randomBytes(32)
    private val dek = VaultCrypto.randomBytes(32)
    private val aad = "aad".toByteArray()

    @Test
    fun wrapAndUnwrapRoundTrip() {
        assertArrayEquals(dek, VaultCrypto.open(kek, VaultCrypto.seal(kek, dek, aad), aad))
    }

    @Test
    fun wrongKeyOrTamperingIsDetected() {
        val sealed = VaultCrypto.seal(kek, dek, aad)
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(VaultCrypto.randomBytes(32), sealed, aad) }
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(kek, sealed, "other".toByteArray()) }
        val tampered = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(kek, tampered, aad) }
    }

    @Test
    fun vaultFileRoundTripAndTagCheck() {
        val original = ByteArray(200_000) { (it % 251).toByte() }
        val file = tmp.newFile("item-1.sgv")
        VaultCrypto.encryptFile(dek, "item-1", original.inputStream(), file)

        val out = ByteArrayOutputStream()
        VaultCrypto.decryptFile(dek, "item-1", file, out)
        assertArrayEquals(original, out.toByteArray())

        // The file is bound to its item: renamed to another item, it does not decrypt.
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.decryptFile(dek, "item-2", file, ByteArrayOutputStream()) }
        flipLastByte(file)
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.decryptFile(dek, "item-1", file, ByteArrayOutputStream()) }
    }
}
