package com.sudo.securegallery.redact

import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.scan.ImageSource

/**
 * Makes the redacted copy of one localized item (§8.2 steps 3 and 5, #18): decode the original
 * at full resolution, fill every box, encode JPEG q95 with only the kept EXIF, and save it next
 * to the original. One full-size bitmap exists at a time, and it is recycled before returning.
 *
 * The original is only read. If a step fails the item keeps no copy and is tried again on the
 * next scan; after [MAX_ATTEMPTS] failures it becomes FAILED, with the original untouched.
 */
class Redactor(private val source: ImageSource, private val store: RedactedCopyStore) {

    fun redact(item: Item): Item {
        val jpeg = try {
            val exif = source.open(item.mediaStoreId)?.use(KeptExif::read) ?: return gone(item)
            val bitmap = source.loadFullResolution(item.mediaStoreId) ?: return failedAttempt(item)
            try {
                RedactedJpeg.fill(bitmap, item.boxes)
                RedactedJpeg.encode(bitmap, exif)
            } finally {
                bitmap.recycle()
            }
        } catch (_: RuntimeException) {
            return failedAttempt(item)
        } catch (_: OutOfMemoryError) {
            return failedAttempt(item)
        }
        val copyId = try {
            store.save(item.mediaStoreId, jpeg) ?: return gone(item)
        } catch (_: RuntimeException) {
            return failedAttempt(item)
        }
        return item.copy(redactedMediaStoreId = copyId, retryCount = 0)
    }

    /** The original was deleted since the scan: there is nothing left to protect. */
    private fun gone(item: Item) = item.moveTo(ItemState.FAILED)

    private fun failedAttempt(item: Item): Item {
        val attempts = item.retryCount + 1
        return if (attempts >= MAX_ATTEMPTS) item.moveTo(ItemState.FAILED).copy(retryCount = attempts) else item.copy(retryCount = attempts)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
