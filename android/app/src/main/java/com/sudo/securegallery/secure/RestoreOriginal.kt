package com.sudo.securegallery.secure

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.scan.ImageQuery
import com.sudo.securegallery.vault.Vault

/**
 * "Not sensitive" (§8.4, #25): undoes a false positive. The original goes back to its album, the
 * redacted copy and the vault file are removed, and the item becomes RESTORED, which no step of
 * the pipeline ever leaves, so the image is never flagged again automatically.
 *
 * Safe order: the original is decrypted and hash-checked against the scan, written back and
 * read back to check the hash again, and only then are the copy and the vault file deleted. If
 * any step fails, nothing is deleted. The restored file belongs to the app, so the scan skips it.
 */
class RestoreOriginal(
    private val dao: ItemDao,
    private val vault: Vault,
    private val gallery: Gallery,
    private val vaultId: (Item) -> String,
) {
    /** The few MediaStore operations a restore needs; a fake in unit tests. */
    interface Gallery {
        /** Album (`RELATIVE_PATH`) and display name of a MediaStore image, or null if gone. */
        fun locate(id: Long): Pair<String?, String>?
        fun exists(id: Long): Boolean
        /** Writes a new image and returns its ID, or null on failure. */
        fun insert(relativePath: String?, displayName: String, mimeType: String, bytes: ByteArray): Long?
        fun read(id: Long): ByteArray?
        fun delete(id: Long)
    }

    sealed interface Result {
        /** Done; [mediaStoreId] is the original's ID in the gallery now. */
        data class Restored(val mediaStoreId: Long) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun restore(key: Vault.Key, item: Item): Result {
        val copyId = item.redactedMediaStoreId
        val originalInGallery = item.state != ItemState.VAULTED && gallery.exists(item.mediaStoreId)
        var mediaStoreId = item.mediaStoreId
        if (!originalInGallery) {
            if (!vault.contains(vaultId(item))) return Result.Failed("no vault copy")
            val bytes = vault.read(key, vaultId(item))
            if (Vault.sha256Hex(bytes) != item.contentHash) return Result.Failed("vault copy does not match the scan")
            val (path, copyName) = copyId?.let(gallery::locate) ?: return Result.Failed("redacted copy not found")
            val (mime, ext) = imageType(bytes) ?: return Result.Failed("unknown image type")
            val name = copyName.substringBefore(ImageQuery.REDACTED_NAME_MARKER) + ext
            val newId = gallery.insert(path, name, mime, bytes) ?: return Result.Failed("could not write the original")
            if (gallery.read(newId)?.let(Vault::sha256Hex) != item.contentHash) {
                gallery.delete(newId)
                return Result.Failed("restored file does not match")
            }
            mediaStoreId = newId
        }
        // The original is back and checked: now the copy and the vault file can go.
        copyId?.let(gallery::delete)
        vault.delete(vaultId(item))
        val restored = item.moveTo(ItemState.RESTORED)
            .copy(mediaStoreId = mediaStoreId, redactedMediaStoreId = null, corrections = item.corrections + 1)
        dao.update(restored)
        return Result.Restored(mediaStoreId)
    }

    companion object {
        /** MIME type and file extension from the file's first bytes. */
        fun imageType(bytes: ByteArray): Pair<String, String>? = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg" to ".jpg"
            bytes.size > 8 && bytes.copyOfRange(1, 4).contentEquals("PNG".toByteArray()) -> "image/png" to ".png"
            bytes.size > 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" -> "image/heic" to ".heic"
            bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp" to ".webp"
            else -> null
        }
    }
}

/** [RestoreOriginal.Gallery] on MediaStore. */
class MediaStoreGallery(private val resolver: ContentResolver) : RestoreOriginal.Gallery {
    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    override fun locate(id: Long): Pair<String?, String>? = resolver.query(
        uri(id),
        arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) to (c.getString(1) ?: "image") else null }

    override fun exists(id: Long) = locate(id) != null

    override fun insert(relativePath: String?, displayName: String, mimeType: String, bytes: ByteArray): Long? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (relativePath != null) put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        return try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            ContentUris.parseId(uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    override fun read(id: Long): ByteArray? = try {
        resolver.openInputStream(uri(id))?.use { it.readBytes() }
    } catch (_: java.io.FileNotFoundException) {
        null
    }

    override fun delete(id: Long) {
        resolver.delete(uri(id), null, null)
    }
}
