package com.sudo.securegallery.vault

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The export file (§8.3, #26): one zip holding
 * - `manifest.properties`: format, version, item count and IDs;
 * - `recovery.properties`: the data key wrapped by the recovery code (salt, rounds, ciphertext);
 * - `items/<id>.sgv`: the encrypted originals, byte for byte as stored.
 *
 * Nothing in it is decrypted, and it holds no PIN wrap: the PIN and Keystore secret belong to
 * the old phone. Without the recovery code the file is useless.
 */
internal object VaultArchive {
    const val FORMAT = "secure-gallery-vault"
    const val VERSION = 1
    private const val MANIFEST = "manifest.properties"
    private const val RECOVERY = "recovery.properties"
    private const val ITEMS = "items/"
    private const val MAX_ITEMS = 100_000

    fun write(out: OutputStream, recovery: WrappedKey, items: List<Pair<String, File>>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            Properties().apply {
                setProperty("format", FORMAT)
                setProperty("version", VERSION.toString())
                setProperty("items", items.size.toString())
                items.forEachIndexed { i, (id, _) -> setProperty("item.$i", id) }
            }.store(zip, null)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(RECOVERY))
            Properties().apply {
                setProperty("salt", b64(recovery.salt))
                setProperty("iterations", recovery.iterations.toString())
                setProperty("sealed", b64(recovery.sealed))
            }.store(zip, null)
            zip.closeEntry()

            for ((id, file) in items) {
                zip.putNextEntry(ZipEntry("$ITEMS$id.sgv"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** What an archive holds once unpacked into a staging folder. */
    class Unpacked(val recovery: WrappedKey, val items: Map<String, File>)

    class Invalid(reason: String) : Exception(reason)

    /**
     * Unpacks into [staging] (in app-private storage) and checks the structure: known format and
     * version, a recovery key, and exactly the items the manifest lists. Entry names are checked
     * before anything is written, so a crafted name cannot escape [staging].
     */
    fun read(input: InputStream, staging: File): Unpacked {
        staging.mkdirs()
        var manifest: Properties? = null
        var recovery: Properties? = null
        val files = mutableMapOf<String, File>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == MANIFEST -> manifest = Properties().apply { load(zip) }
                    name == RECOVERY -> recovery = Properties().apply { load(zip) }
                    name.startsWith(ITEMS) && name.endsWith(".sgv") -> {
                        val id = name.removePrefix(ITEMS).removeSuffix(".sgv")
                        if (!VaultStore.isItemId(id)) throw Invalid("bad item name")
                        if (id in files) throw Invalid("duplicate item")
                        if (files.size >= MAX_ITEMS) throw Invalid("too many items")
                        val target = File(staging, "$id.sgv")
                        target.outputStream().use { zip.copyTo(it) }
                        files[id] = target
                    }
                    else -> throw Invalid("unexpected entry")
                }
            }
        }
        val m = manifest ?: throw Invalid("missing manifest")
        if (m.getProperty("format") != FORMAT) throw Invalid("not a Secure Gallery vault export")
        if (m.getProperty("version")?.toIntOrNull() != VERSION) throw Invalid("unsupported export version")
        val count = m.getProperty("items")?.toIntOrNull() ?: throw Invalid("bad manifest")
        val listed = (0 until count).map { m.getProperty("item.$it") ?: throw Invalid("bad manifest") }.toSet()
        if (listed != files.keys) throw Invalid("items do not match the manifest")
        val r = recovery ?: throw Invalid("missing recovery key")
        val key = try {
            WrappedKey(
                salt = unb64(r.getProperty("salt")),
                iterations = r.getProperty("iterations").toInt(),
                sealed = unb64(r.getProperty("sealed")),
            )
        } catch (_: RuntimeException) {
            throw Invalid("bad recovery key")
        }
        if (key.iterations !in 1..10_000_000) throw Invalid("bad recovery key")
        return Unpacked(key, files)
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)
}
