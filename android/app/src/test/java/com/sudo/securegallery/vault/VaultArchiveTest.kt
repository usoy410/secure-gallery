package com.sudo.securegallery.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Export and import of the vault with the recovery code (issue #26). */
class VaultArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val oldPin = "482915".toCharArray()
    private val newPin = "735102".toCharArray()
    private val originals = mapOf(
        "item-1" to ByteArray(120_000) { (it % 251).toByte() },
        "item-2" to "SG-PLAINTEXT-MARKER second original".toByteArray(),
        "item-7" to ByteArray(10) { 7 },
    )

    /** Phone A: a vault with three originals, exported. Returns the export and its recovery code. */
    private fun exportFromOldPhone(): Pair<ByteArray, CharArray> {
        val dir = tmp.newFolder("old-phone")
        val vault = testVault(dir, secret = FakeDeviceSecret(seed = 1))
        val code = vault.setUp(oldPin)
        val key = (vault.unlock(oldPin) as Vault.Unlock.Success).key
        originals.forEach { (id, bytes) -> vault.store(key, id, bytes.inputStream()) }
        val out = ByteArrayOutputStream()
        assertEquals(3, vault.export(out))
        dir.deleteRecursively() // the phone is wiped or the app uninstalled
        return out.toByteArray() to code
    }

    /** Phone B: fresh install, first-run setup made an empty vault with a new PIN. */
    private fun newPhone(name: String = "new-phone"): Pair<File, Vault> {
        val dir = tmp.newFolder(name)
        val vault = testVault(dir, secret = FakeDeviceSecret(seed = 2))
        vault.setUp(newPin)
        return dir to vault
    }

    @Test
    fun exportWipeImportRestoresByteIdenticalOriginals() {
        val (archive, code) = exportFromOldPhone()
        val (dir, vault) = newPhone()

        assertEquals(Vault.ImportResult.Imported(3), vault.importArchive(archive.inputStream(), code, newPin))

        val key = (vault.unlock(newPin) as Vault.Unlock.Success).key
        originals.forEach { (id, bytes) -> assertArrayEquals(id, bytes, vault.read(key, id)) }
        // The old phone's PIN means nothing here; the recovery code still opens the vault.
        assertTrue(vault.unlock(oldPin) is Vault.Unlock.WrongPin)
        assertNotNull(vault.unlockWithRecoveryCode(code))
        assertFalse(File(dir, "import-staging").exists())
    }

    @Test
    fun exportNeedsNoUnlockAndHoldsNoPlaintextOrPinWrap() {
        val (archive, _) = exportFromOldPhone()
        val names = mutableListOf<String>()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(listOf("manifest.properties", "recovery.properties", "items/item-1.sgv", "items/item-2.sgv", "items/item-7.sgv"), names)
        val text = String(archive, Charsets.ISO_8859_1)
        assertFalse(text.contains("SG-PLAINTEXT-MARKER"))
        assertFalse(text.contains("pin."))
    }

    @Test
    fun wrongRecoveryCodeChangesNothing() {
        val (archive, _) = exportFromOldPhone()
        val (dir, vault) = newPhone()
        val keysBefore = File(dir, "keys.properties").readBytes()

        assertEquals(Vault.ImportResult.WrongRecoveryCode, vault.importArchive(archive.inputStream(), RecoveryCode.generate(), newPin))
        assertEquals(Vault.ImportResult.WrongRecoveryCode, vault.importArchive(archive.inputStream(), "not a code".toCharArray(), newPin))
        assertArrayEquals(keysBefore, File(dir, "keys.properties").readBytes())
        assertEquals(emptyList<String>(), VaultStore(dir).itemIds())
        assertTrue(vault.unlock(newPin) is Vault.Unlock.Success)
    }

    @Test
    fun damagedOriginalInTheExportIsRejected() {
        val (archive, code) = exportFromOldPhone()
        val (dir, vault) = newPhone()
        val damaged = rewrite(archive) { name, bytes ->
            if (name == "items/item-2.sgv") bytes.also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() } else bytes
        }
        val result = vault.importArchive(damaged.inputStream(), code, newPin)
        assertTrue(result is Vault.ImportResult.Invalid)
        assertEquals(emptyList<String>(), VaultStore(dir).itemIds())
        assertTrue(vault.unlock(newPin) is Vault.Unlock.Success) // keys untouched
    }

    @Test
    fun entryNamesCannotEscapeTheVaultFolder() {
        val (archive, code) = exportFromOldPhone()
        val (dir, vault) = newPhone()
        val evil = rewrite(archive, extra = "items/../../evil.sgv" to ByteArray(4))
        assertTrue(vault.importArchive(evil.inputStream(), code, newPin) is Vault.ImportResult.Invalid)
        assertFalse(File(dir.parentFile, "evil.sgv").exists())
        assertFalse(File(dir, "evil.sgv").exists())
    }

    @Test
    fun itemsMustMatchTheManifest() {
        val (archive, code) = exportFromOldPhone()
        val (_, vault) = newPhone()
        val missing = rewrite(archive) { name, bytes -> if (name == "items/item-7.sgv") null else bytes }
        assertTrue(vault.importArchive(missing.inputStream(), code, newPin) is Vault.ImportResult.Invalid)
        assertTrue(vault.importArchive(ByteArrayInputStream("not a zip".toByteArray()), code, newPin) is Vault.ImportResult.Invalid)
    }

    @Test
    fun importOnlyGoesIntoAnEmptyVault() {
        val (archive, code) = exportFromOldPhone()
        val (_, vault) = newPhone()
        val key = (vault.unlock(newPin) as Vault.Unlock.Success).key
        vault.store(key, "item-99", "already here".toByteArray().inputStream())
        assertEquals(Vault.ImportResult.VaultNotEmpty, vault.importArchive(archive.inputStream(), code, newPin))
    }

    /** Copies a zip, changing, dropping (null) or adding entries. */
    private fun rewrite(
        archive: ByteArray,
        extra: Pair<String, ByteArray>? = null,
        change: (String, ByteArray) -> ByteArray? = { _, b -> b },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zipOut ->
            ZipInputStream(archive.inputStream()).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    val bytes = change(entry.name, zipIn.readBytes()) ?: continue
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                }
            }
            extra?.let { (name, bytes) ->
                zipOut.putNextEntry(ZipEntry(name))
                zipOut.write(bytes)
                zipOut.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
