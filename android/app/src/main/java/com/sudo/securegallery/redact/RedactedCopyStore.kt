package com.sudo.securegallery.redact

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.sudo.securegallery.scan.ImageQuery

/** Where redacted copies go: MediaStore in the app, a fake in tests. */
interface RedactedCopyStore {
    /**
     * Saves [jpeg] as the redacted copy of the original [originalId] and returns the copy's
     * MediaStore ID, or null if the original no longer exists.
     */
    fun save(originalId: Long, jpeg: ByteArray): Long?

    /** The saved copy's bytes as they are on disk, for the recheck (§8.5). */
    fun read(copyId: Long): ByteArray?

    /** Replaces the copy's bytes, for a retry with larger boxes (#19). */
    fun overwrite(copyId: Long, jpeg: ByteArray)
}

/**
 * Inserts the copy next to the original (§8.2 step 5): same volume and `RELATIVE_PATH`, named
 * `<original name>.sg-redacted.jpg` so the scan never picks it up again ([ImageQuery]). The
 * copy is written while pending, so other apps never see a half-written file. The original
 * is only read, never written.
 */
class MediaStoreCopyStore(private val resolver: ContentResolver) : RedactedCopyStore {

    override fun save(originalId: Long, jpeg: ByteArray): Long? {
        val original = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, originalId)
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.VOLUME_NAME,
        )
        val (name, path, volume) = resolver.query(original, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            Triple(c.getString(0) ?: "image", c.getString(1), c.getString(2) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } ?: return null
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val displayName = copyName(name)
        val uri = try {
            insertPending(collection, displayName, path)
        } catch (_: IllegalArgumentException) {
            // MediaStore keeps images under DCIM/ or Pictures/ only; an original elsewhere
            // (for example Download/) gets its copy in the app's own album instead.
            insertPending(collection, displayName, FALLBACK_PATH)
        } ?: error("MediaStore refused the insert")
        try {
            resolver.openOutputStream(uri, "w").use { out -> checkNotNull(out).write(jpeg) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return ContentUris.parseId(uri)
    }

    override fun read(copyId: Long): ByteArray? = try {
        resolver.openInputStream(copyUri(copyId))?.use { it.readBytes() }
    } catch (_: java.io.FileNotFoundException) {
        null
    }

    override fun overwrite(copyId: Long, jpeg: ByteArray) {
        // "wt" truncates, so no bytes of the earlier attempt remain past the new end.
        resolver.openOutputStream(copyUri(copyId), "wt").use { out -> checkNotNull(out).write(jpeg) }
    }

    private fun copyUri(copyId: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, copyId)

    private fun insertPending(collection: Uri, displayName: String, relativePath: String?): Uri? =
        resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (relativePath != null) put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        )

    companion object {
        const val FALLBACK_PATH = "Pictures/Secure Gallery/"

        /** `IMG_0001.heic` becomes `IMG_0001.sg-redacted.jpg`. */
        fun copyName(originalName: String): String =
            originalName.substringBeforeLast('.') + ImageQuery.REDACTED_NAME_MARKER + "jpg"
    }
}
