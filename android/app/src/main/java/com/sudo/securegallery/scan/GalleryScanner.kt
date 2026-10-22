package com.sudo.securegallery.scan

import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import java.io.InputStream
import java.security.MessageDigest

/**
 * The first full scan (§8.2): index every gallery image, then process them one at a time.
 *
 * Processing is only hashing for now; detection (#16) will plug in after the hash. Bytes are
 * streamed through the digest, never decoded into a bitmap.
 */
class GalleryScanner(
    private val source: ImageSource,
    private val dao: ItemDao,
) {
    data class Result(val found: Int, val processed: Int, val missing: Int)

    suspend fun scan(): Result {
        val images = source.listImages()
        index(images)
        var processed = 0
        var missing = 0
        while (true) {
            val item = dao.nextUnhashed() ?: break
            val hash = source.open(item.mediaStoreId)?.use(::sha256)
            if (hash == null) {
                // Deleted or unreadable since the query: nothing to protect, stop retrying it.
                dao.update(item.moveTo(ItemState.FAILED))
                missing++
            } else {
                dao.update(item.copy(contentHash = hash))
                processed++
            }
        }
        return Result(found = images.size, processed = processed, missing = missing)
    }

    /** Adds new images; a changed file that was not flagged is indexed again. */
    private suspend fun index(images: List<GalleryImage>) {
        dao.insertNew(
            images.map { Item(mediaStoreId = it.mediaStoreId, dateModified = it.dateModified, sizeBytes = it.sizeBytes) },
        )
        for (image in images) {
            val item = dao.byMediaStoreId(image.mediaStoreId) ?: continue
            val changed = item.dateModified != image.dateModified || item.sizeBytes != image.sizeBytes
            if (changed && item.state.canMoveTo(ItemState.PENDING)) {
                dao.update(
                    item.moveTo(ItemState.PENDING).copy(
                        dateModified = image.dateModified,
                        sizeBytes = image.sizeBytes,
                        contentHash = null,
                    ),
                )
            }
        }
    }

    companion object {
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
